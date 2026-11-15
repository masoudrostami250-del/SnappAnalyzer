package com.snapfloat.voice;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RouteDistanceClient {
    private static final String TAG = "SnappRouteDistance";
    private static final String DEFAULT_GEOCODER = "https://nominatim.openstreetmap.org";
    private static final String DEFAULT_ROUTER = "https://router.project-osrm.org";
    private static final String USER_AGENT =
            "SnappVoiceRoute/9.0 (Android; package=com.snapfloat.voice17000pickup2kmroute)";
    private static final long MIN_REQUEST_GAP_MS = 1100L;
    private static final long GEOCODE_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastGeocodeRequestAt;
    private static long lastRouteRequestAt;

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean success;
        public final double pickupKm;
        public final double destinationKm;
        public final String message;

        private Result(boolean success, double pickupKm, double destinationKm, String message) {
            this.success = success;
            this.pickupKm = pickupKm;
            this.destinationKm = destinationKm;
            this.message = message;
        }

        static Result ok(double pickupKm, double destinationKm) {
            return new Result(true, pickupKm, destinationKm,
                    "فاصله‌ها با GPS و مسیر جاده‌ای نقشه محاسبه شدند.");
        }

        static Result error(String message) {
            return new Result(false, 0, 0, message);
        }
    }

    private static final class Point {
        final double lat;
        final double lon;
        Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private RouteDistanceClient() { }

    public static void calculate(Context context, String pickupAddress,
                                 String destinationAddress, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Result result;
            try {
                if (pickupAddress == null || pickupAddress.trim().isEmpty()
                        || destinationAddress == null || destinationAddress.trim().isEmpty()) {
                    throw new Exception("آدرس مبدأ یا مقصد از صفحه اسنپ خوانده نشد.");
                }

                Location driver = getDriverLocation(app);
                Point pickup = geocode(app, pickupAddress);
                Point destination = geocode(app, destinationAddress);

                SharedPreferences settings = app.getSharedPreferences("route_settings", Context.MODE_PRIVATE);
                String routerBase = cleanBase(settings.getString("router_base", DEFAULT_ROUTER), DEFAULT_ROUTER);
                double[] legs = getRouteLegs(routerBase, driver, pickup, destination);

                if (legs[0] <= 0 || legs[1] <= 0 || legs[0] > 150000 || legs[1] > 300000) {
                    throw new Exception("فاصله مسیر غیرعادی بود؛ برای جلوگیری از اعلام نتیجه اشتباه، سفر تحلیل نشد.");
                }

                result = Result.ok(legs[0] / 1000.0, legs[1] / 1000.0);
            } catch (SecurityException e) {
                result = Result.error("مجوز موقعیت مکانی دقیق و «همیشه فعال» داده نشده است. از تنظیمات گوشی، دسترسی موقعیت مکانی این برنامه را روی Allow all the time و Precise قرار دهید.");
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = "ارتباط با سرویس نقشه برقرار نشد.";
                result = Result.error(message);
            }

            Result finalResult = result;
            if (callback != null) MAIN.post(() -> callback.onResult(finalResult));
        });
    }

    private static Location getDriverLocation(Context context) throws Exception {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("مجوز موقعیت مکانی دقیق لازم است.");
        }
        if (Build.VERSION.SDK_INT >= 29
                && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("مجوز موقعیت مکانی همیشه فعال لازم است.");
        }

        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) throw new Exception("مدیر موقعیت مکانی گوشی در دسترس نیست.");

        Location cached = bestLastKnown(manager);
        if (cached != null && ageMs(cached) <= 15000L && accuracy(cached) <= 100f) return cached;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> received = new AtomicReference<>();
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;
                Location old = received.get();
                if (old == null || accuracy(location) < accuracy(old)
                        || location.getTime() > old.getTime()) {
                    received.set(location);
                }
                if (accuracy(location) <= 120f) latch.countDown();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };

        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            boolean requested = false;
            try {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f,
                            listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) { }
            try {
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0f,
                            listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) { }
            if (!requested) latch.countDown();
        });

        boolean gotFix = latch.await(6500L, TimeUnit.MILLISECONDS);
        main.post(() -> {
            try { manager.removeUpdates(listener); } catch (Exception ignored) { }
        });

        Location fresh = received.get();
        if (fresh != null && ageMs(fresh) <= 30000L && accuracy(fresh) <= 150f) return fresh;

        Location afterWait = bestLastKnown(manager);
        if (afterWait != null && ageMs(afterWait) <= 30000L && accuracy(afterWait) <= 150f) return afterWait;

        if (!gotFix) {
            throw new Exception("موقعیت GPS دقیق دریافت نشد. GPS گوشی را روشن کنید و مجوز موقعیت مکانی همیشه فعال و دقیق را بدهید.");
        }
        throw new Exception("دقت GPS برای تشخیص فاصله ۲ کیلومتر کافی نیست؛ نتیجه‌ای اعلام نشد.");
    }

    private static Location bestLastKnown(LocationManager manager) {
        Location best = null;
        String[] providers = new String[] {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null || ageMs(candidate) > 120000L || accuracy(candidate) > 200f) continue;
                if (best == null || candidate.getTime() > best.getTime()
                        || (candidate.getTime() == best.getTime()
                        && accuracy(candidate) < accuracy(best))) {
                    best = candidate;
                }
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static long ageMs(Location location) {
        return Math.max(0L, System.currentTimeMillis() - location.getTime());
    }

    private static float accuracy(Location location) {
        return location.hasAccuracy() ? location.getAccuracy() : 10000f;
    }

    private static Point geocode(Context context, String address) throws Exception {
        String normalized = normalize(address);
        SharedPreferences cache = context.getSharedPreferences("route_geocode_cache", Context.MODE_PRIVATE);
        String cacheKey = "geo_" + sha256(normalized);
        String cachedValue = cache.getString(cacheKey, null);
        if (cachedValue != null) {
            String[] parts = cachedValue.split(",");
            if (parts.length == 3) {
                try {
                    long savedAt = Long.parseLong(parts[2]);
                    if (System.currentTimeMillis() - savedAt <= GEOCODE_CACHE_TTL_MS) {
                        return new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
                    }
                } catch (Exception ignored) { }
            }
        }

        SharedPreferences settings = context.getSharedPreferences("route_settings", Context.MODE_PRIVATE);
        String base = cleanBase(settings.getString("geocoder_base", DEFAULT_GEOCODER), DEFAULT_GEOCODER);
        String query = address.trim() + ", ایران";
        String url = base + "/search?format=jsonv2&addressdetails=1&limit=5"
                + "&countrycodes=ir&accept-language=fa&q="
                + URLEncoder.encode(query, "UTF-8");

        throttle(false);
        String body = httpGet(url);
        JSONArray results = new JSONArray(body);
        int bestScore = Integer.MIN_VALUE;
        Point bestPoint = null;

        Set<String> queryTokens = tokens(normalized);
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            JSONObject addressDetails = item.optJSONObject("address");
            String countryCode = addressDetails == null ? "" : addressDetails.optString("country_code", "");
            if (!"ir".equalsIgnoreCase(countryCode)) continue;

            String display = item.optString("display_name", "");
            String searchable = normalize(display + " " + (addressDetails == null ? "" : addressDetails.toString()));
            int matches = 0;
            for (String token : queryTokens) {
                if (searchable.contains(token)) matches++;
            }

            String addressType = item.optString("addresstype", item.optString("type", ""));
            boolean detailed = hasDetailedAddress(addressDetails)
                    || addressType.equals("road") || addressType.equals("neighbourhood")
                    || addressType.equals("suburb") || addressType.equals("village")
                    || addressType.equals("hamlet") || addressType.equals("quarter")
                    || addressType.equals("house") || addressType.equals("building");
            int requiredMatches = queryTokens.size() >= 2 ? 2 : 1;
            if (!detailed || matches < requiredMatches) continue;

            double lat = item.optDouble("lat", Double.NaN);
            double lon = item.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < 24 || lat > 40 || lon < 43 || lon > 64) continue;

            int score = matches * 100 + (detailed ? 40 : 0)
                    + (int) Math.round(item.optDouble("importance", 0.0) * 10);
            if (score > bestScore) {
                bestScore = score;
                bestPoint = new Point(lat, lon);
            }
        }

        if (bestPoint == null) {
            throw new Exception("آدرس «" + address + "» در نقشه با دقت کافی پیدا نشد. برای جلوگیری از محاسبه اشتباه، این سفر تأیید نشد.");
        }

        cache.edit().putString(cacheKey, bestPoint.lat + "," + bestPoint.lon + ","
                + System.currentTimeMillis()).apply();
        return bestPoint;
    }

    private static boolean hasDetailedAddress(JSONObject address) {
        if (address == null) return false;
        String[] fields = {
                "road", "residential", "pedestrian", "neighbourhood", "suburb",
                "quarter", "village", "hamlet", "house_number", "building",
                "amenity", "hospital", "industrial"
        };
        for (String field : fields) {
            if (!address.optString(field, "").trim().isEmpty()) return true;
        }
        return false;
    }

    private static double[] getRouteLegs(String routerBase, Location driver,
                                         Point pickup, Point destination) throws Exception {
        String coordinates = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f;%.6f,%.6f",
                driver.getLongitude(), driver.getLatitude(),
                pickup.lon, pickup.lat, destination.lon, destination.lat);
        String url = routerBase + "/route/v1/driving/" + coordinates
                + "?overview=false&steps=false&alternatives=false";
        throttle(true);
        String body = httpGet(url);
        JSONObject root = new JSONObject(body);
        if (!"Ok".equalsIgnoreCase(root.optString("code", ""))) {
            throw new Exception("سرویس مسیریابی نتوانست مسیر جاده‌ای پیدا کند.");
        }
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new Exception("مسیر جاده‌ای برای این پیشنهاد پیدا نشد.");
        }
        JSONArray legs = routes.getJSONObject(0).optJSONArray("legs");
        if (legs == null || legs.length() < 2) {
            throw new Exception("پاسخ نقشه کامل نبود؛ فاصله‌ها محاسبه نشدند.");
        }
        double pickupMeters = legs.getJSONObject(0).optDouble("distance", -1);
        double destinationMeters = legs.getJSONObject(1).optDouble("distance", -1);
        if (pickupMeters <= 0 || destinationMeters <= 0) {
            throw new Exception("فاصله یکی از بخش‌های مسیر معتبر نبود.");
        }
        return new double[] { pickupMeters, destinationMeters };
    }

    private static String httpGet(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "fa");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Exception("پاسخ سرویس نقشه خالی بود (HTTP " + status + ").");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            if (status < 200 || status >= 300) {
                if (status == 429) throw new Exception("سرویس نقشه موقتاً درخواست‌های زیاد را محدود کرده است؛ کمی بعد دوباره امتحان کنید.");
                throw new Exception("سرویس نقشه پاسخ خطا داد (HTTP " + status + ").");
            }
            return body.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void throttle(boolean router) throws InterruptedException {
        synchronized (RouteDistanceClient.class) {
            long now = System.currentTimeMillis();
            long previous = router ? lastRouteRequestAt : lastGeocodeRequestAt;
            long wait = MIN_REQUEST_GAP_MS - (now - previous);
            if (wait > 0) Thread.sleep(wait);
            long started = System.currentTimeMillis();
            if (router) lastRouteRequestAt = started;
            else lastGeocodeRequestAt = started;
        }
    }

    private static String cleanBase(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty()) return fallback;
        return result;
    }

    private static Set<String> tokens(String value) {
        Set<String> output = new HashSet<>();
        String normalized = normalize(value);
        String[] parts = normalized.split("\\s+");
        String[] ignored = {
                "ایران", "شهر", "محله", "روستا", "روستای", "استان", "شهرستان",
                "خیابان", "کوچه", "بلوار", "تقاطع", "جاده", "میدان", "ایستگاه",
                "تهران", "تهران،", "iran"
        };
        for (String part : parts) {
            String token = part.trim();
            if (token.length() < 3) continue;
            boolean skip = false;
            for (String word : ignored) {
                if (token.equals(word)) { skip = true; break; }
            }
            if (!skip) output.add(token);
        }
        return output;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
                .replace('أ', 'ا').replace('إ', 'ا')
                .replaceAll("[،,:؛؛()\\[\\]{}|]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }
}
