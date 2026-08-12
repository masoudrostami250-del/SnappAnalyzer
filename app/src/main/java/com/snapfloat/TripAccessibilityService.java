package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;


import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TripAccessibilityService extends AccessibilityService {

    private static final String CHANNEL_ID = "snapp_analyzer_result";
    private static final int NOTIFICATION_ID = 12000;

    private static final Pattern MONEY = Pattern.compile(
            "(\\d[\\d,٬\\s]{2,})\\s*(?:تومان|ت|ریال)?"
    );

    private static final Pattern KM = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:کیلومتر|km|ک\\.م)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern METER = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:متر|m)",
            Pattern.CASE_INSENSITIVE
    );

    private long lastFare = -1;
    private long lastMeters = -1;
    private long lastNotificationTime = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        createNotificationChannel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if (event == null) return;

        int type = event.getEventType();

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) return;

        List<TripData> trips = new ArrayList<>();

        findTripCards(root, trips);

        if (trips.size() != 1) {
            /*
             * اگر یک کارت سفر مشخص پیدا نشد، هیچ اطلاعاتی
             * از سفرهای دیگر با هم ترکیب نمی‌شود.
             */
            return;
        }

        TripData trip = trips.get(0);

        if (trip.fare <= 0 || trip.meters <= 0) return;

        /*
         * جلوگیری از Notification تکراری برای همان سفر
         */
        if (trip.fare == lastFare
                && trip.meters == lastMeters
                && System.currentTimeMillis() - lastNotificationTime < 5000) {
            return;
        }

        lastFare = trip.fare;
        lastMeters = trip.meters;
        lastNotificationTime = System.currentTimeMillis();

        analyzeAndNotify(trip.fare, trip.meters);
    }

    /*
     * پیدا کردن کوچک‌ترین/مناسب‌ترین container قابل کلیک
     * که هم مبلغ و هم مسافت یک سفر را در خودش دارد.
     */
    private void findTripCards(
            AccessibilityNodeInfo node,
            List<TripData> results) {

        if (node == null) return;

        if (node.isClickable() || node.isFocusable()) {

            ArrayList<String> texts = new ArrayList<>();
            collectAllText(node, texts);

            long fare = findFare(texts);
            long meters = findDistanceMeters(texts);

            if (fare > 0 && meters > 0) {
                results.add(new TripData(fare, meters));
                return;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null) {
                findTripCards(child, results);
                child.recycle();
            }
        }
    }

    private void collectAllText(
            AccessibilityNodeInfo node,
            ArrayList<String> out) {

        if (node == null) return;

        if (node.getText() != null) {
            String text = node.getText().toString().trim();

            if (!text.isEmpty()) {
                out.add(text);
            }
        }

        if (node.getContentDescription() != null) {
            String text = node.getContentDescription().toString().trim();

            if (!text.isEmpty()) {
                out.add(text);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null) {
                collectAllText(child, out);
                child.recycle();
            }
        }
    }

    private long findFare(List<String> texts) {

        for (String text : texts) {

            Matcher matcher = MONEY.matcher(text);

            while (matcher.find()) {

                long value = parseLong(matcher.group(1));

                /*
                 * مبلغ‌های خیلی کوچک مثل شماره، درصد یا فاصله
                 * به‌عنوان کرایه قبول نمی‌شوند.
                 */
                if (value >= 10000) {
                    return value;
                }
            }
        }

        return 0;
    }

    private long findDistanceMeters(List<String> texts) {

        for (String text : texts) {

            Matcher km = KM.matcher(text);

            if (km.find()) {

                double value = parseDouble(km.group(1));

                if (value > 0) {
                    return Math.round(value * 1000.0);
                }
            }

            Matcher meter = METER.matcher(text);

            if (meter.find()) {

                double value = parseDouble(meter.group(1));

                if (value > 0) {
                    return Math.round(value);
                }
            }
        }

        return 0;
    }

    private long parseLong(String value) {

        try {

            return Long.parseLong(
                    value
                            .replace(",", "")
                            .replace("٬", "")
                            .replace(" ", "")
            );

        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDouble(String value) {

        try {

            return Double.parseDouble(
                    value.replace(',', '.')
            );

        } catch (Exception e) {
            return 0;
        }
    }

    private void analyzeAndNotify(long fare, long meters) {

        /*
         * قانون کاربر:
         *
         * هر فاصله کمتر از 1000 متر = 1 کیلومتر
         *
         * بنابراین:
         * 500m = 1km
         * 700m = 1km
         * 900m = 1km
         *
         * فاصله 1200m = 1.2km
         */

        double calculationKm;

        if (meters < 1000) {
            calculationKm = 1.0;
        } else {
            calculationKm = meters / 1000.0;
        }

        double perKm = fare / calculationKm;

        boolean good = perKm >= 12000;

        String title;
        String text;

        if (good) {

            title = "🟦 سفر خوب";

            text = String.format(
                    "مبلغ: %,d تومان | مسافت: %s | %.0f تومان/کیلومتر",
                    fare,
                    formatDistance(meters),
                    perKm
            );

        } else {

            title = "⬛ سفر بد";

            text = String.format(
                    "مبلغ: %,d تومان | مسافت: %s | %.0f تومان/کیلومتر",
                    fare,
                    formatDistance(meters),
                    perKm
            );
        }

        showNotification(title, text);
    }

    private String formatDistance(long meters) {

        if (meters < 1000) {
            return "1 کیلومتر (مسافت کمتر از 1km)";
        }

        return String.format(
                "%.2f کیلومتر",
                meters / 1000.0
        );
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);

            if (manager == null) return;

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "نتیجه تحلیل سفر",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "نتیجه تحلیل سفرهای اسنپ"
            );

            manager.createNotificationChannel(channel);
        }
    }

    private void showNotification(
            String title,
            String text) {

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        if (manager == null) return;

        Intent intent =
                new Intent(this, MainActivity.class);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(
                        new Notification.BigTextStyle()
                                .bigText(text)
                )
                .setAutoCancel(false)
                .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        manager.notify(
                NOTIFICATION_ID,
                builder.build()
        );
    }

    private static class TripData {

        final long fare;
        final long meters;

        TripData(long fare, long meters) {
            this.fare = fare;
            this.meters = meters;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
