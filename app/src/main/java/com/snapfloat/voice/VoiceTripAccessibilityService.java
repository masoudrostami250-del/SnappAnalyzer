package com.snapfloat.voice;

import android.accessibilityservice.AccessibilityService;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Rect;
import java.util.Collections;
import java.util.Comparator;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.snapfloat.R;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceTripAccessibilityService extends AccessibilityService {
    private static final String TAG = "SnappVoice17000Pickup2km";
    private static final String SNAPP_PACKAGE = "cab.snapp.driver";
    private static final double MIN_TOMAN_PER_KM = 17000.0;
    private static final double MAX_PICKUP_KM = 2.0;
    private static final long ANALYZE_DELAY_MS = 900L;
    private static final long RESET_DELAY_MS = 7000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingEventText = new StringBuilder();
    private String lastSpokenKey = "";
    private String lastWindowSignature = "";
    private MediaPlayer player;

    private final Runnable analyzePending = () -> {
        String snapshot = pendingEventText.toString();
        // Keep partial accessibility text briefly: Snapp may publish fare and
        // route distances in separate events, not in one complete event.
        if (!snapshot.trim().isEmpty()) analyzeOffer(snapshot);
    };
    private final Runnable resetOffer = () -> {
        lastSpokenKey = "";
        pendingEventText.setLength(0);
        lastWindowSignature = "";
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Female Persian offline audio service connected");
        saveDiagnostic("سرویس دسترسی‌پذیری متصل شد؛ منتظر صفحه اسنپ");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            String eventPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
            String rootPackage = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString() : "";
            saveDiagnostic("رویداد دریافت شد | eventPackage=" + eventPackage + " | rootPackage=" + rootPackage + " | root=" + (root != null));

            boolean snappWindow = SNAPP_PACKAGE.equals(eventPackage) || SNAPP_PACKAGE.equals(rootPackage);
            if (!snappWindow) {
                handler.removeCallbacks(analyzePending);
                scheduleOfferReset();
                return;
            }

            handler.removeCallbacks(resetOffer);
            StringBuilder collected = new StringBuilder();

            // Read the current visible window and the event payload together.
            // Different Snapp releases expose some labels in only one of these sources.
            if (root != null) collectText(root, collected);

            if (event.getText() != null) {
                for (CharSequence item : event.getText()) {
                    if (item != null && item.length() > 0) collected.append(' ').append(item);
                }
            }
            if (event.getContentDescription() != null) {
                collected.append(' ').append(event.getContentDescription());
            }

            String current = collected.toString().trim();
            if (current.isEmpty()) {
                saveDiagnostic("پنجره اسنپ شناسایی شد اما متن قابل‌خواندن خالی است.");
                scheduleOfferReset();
                return;
            }

            // Window changes separate old offer text from the next offer.
            String signature = current.length() > 180 ? current.substring(0, 180) : current;
            if (!lastWindowSignature.isEmpty() && !signature.contains(lastWindowSignature)
                    && !lastWindowSignature.contains(signature)) {
                pendingEventText.setLength(0);
            }
            lastWindowSignature = signature;

            saveDiagnostic("متن صفحه اسنپ دریافت شد:\n" + current.substring(0, Math.min(current.length(), 3500)));
            String normalizedCurrent = normalizeDigits(current);
            boolean completeSnapshot = findFare(normalizedCurrent) != null
                    && findDistances(normalizedCurrent).size() >= 2;
            if (completeSnapshot) {
                // A complete window snapshot replaces older partial events, avoiding
                // duplicate distance pairs when Android emits repeated content events.
                pendingEventText.setLength(0);
                pendingEventText.append(current);
            } else if (!pendingEventText.toString().contains(current)) {
                // Keep unique partial event payloads until a complete snapshot arrives.
                pendingEventText.append(' ').append(current);
                // Bound memory if a screen continuously emits changing accessibility text.
                if (pendingEventText.length() > 24000) {
                    pendingEventText.delete(0, pendingEventText.length() - 16000);
                }
            }
            handler.removeCallbacks(analyzePending);
            handler.postDelayed(analyzePending, ANALYZE_DELAY_MS);
        } catch (Exception e) {
            Log.e(TAG, "Could not read Snapp accessibility content", e);
            scheduleOfferReset();
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) { }
            }
        }
    }


    private static class PositionedText {
        final int top;
        final String value;
        PositionedText(int top, String value) { this.top = top; this.value = value; }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        ArrayList<PositionedText> items = new ArrayList<>();
        collectPositioned(node, items);
        Collections.sort(items, Comparator.comparingInt(a -> a.top));
        for (PositionedText item : items) out.append(' ').append(item.value);
    }

    private void collectPositioned(AccessibilityNodeInfo node, ArrayList<PositionedText> out) {
        if (node == null) return;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int top = bounds.top;
            CharSequence value = node.getText();
            if (value != null && value.length() > 0) out.add(new PositionedText(top, value.toString()));
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) out.add(new PositionedText(top, desc.toString()));
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try { collectPositioned(child, out); }
                    finally { try { child.recycle(); } catch (Exception ignored) { } }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "A view changed while its text was being read", e);
        }
    }

    private void saveDiagnostic(String report) {
        try {
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putString("last_accessibility_report", report).apply();
        } catch (Exception ignored) { }
    }

    private void analyzeOffer(String raw) {
        String text = normalizeDigits(raw).toLowerCase(Locale.ROOT);
        Long fare = findFare(text);
        if (fare == null || fare <= 0) {
            saveDiagnostic("متن دریافت شد اما مبلغ کرایه تشخیص داده نشد. متن:\n" + raw.substring(0, Math.min(raw.length(), 3500)));
            scheduleOfferReset();
            return;
        }

        ArrayList<Double> distances = findDistances(text);
        if (distances.size() < 2) {
            saveDiagnostic("مبلغ پیدا شد (" + fare + ") اما دو فاصله کیلومتری پیدا نشد. فاصله‌های پیدا‌شده=" + distances + "\nمتن:\n" + raw.substring(0, Math.min(raw.length(), 3500)));
            // Do not guess when the pickup and destination cannot both be identified.
            scheduleOfferReset();
            return;
        }

        // Snapp may rename or remove the pickup/destination labels.
        // Always interpret the first (upper) displayed distance as pickup
        // and the second (lower) displayed distance as destination.
        double pickupKm = distances.get(0);
        double destinationKm = distances.get(1);

        if (pickupKm <= 0 || destinationKm <= 0) {
            scheduleOfferReset();
            return;
        }

        double tomanPerKm = fare / destinationKm;
        boolean pickupAllowed = pickupKm <= MAX_PICKUP_KM;
        boolean fareAllowed = tomanPerKm >= MIN_TOMAN_PER_KM;
        String phrase = pickupAllowed && fareAllowed ? "برو" : "نرو";

        String key = fare + "|" + Math.round(pickupKm * 1000.0)
                + "|" + Math.round(destinationKm * 1000.0);
        if (key.equals(lastSpokenKey)) return;

        lastSpokenKey = key;
        handler.removeCallbacks(resetOffer);
        saveDiagnostic("تحلیل کامل شد: " + phrase + " | کرایه=" + fare + " | فاصله بالایی/مبدأ=" + pickupKm + " | فاصله پایینی/مقصد=" + destinationKm + " | نرخ مقصد=" + tomanPerKm + " تومان/کیلومتر");
        Log.i(TAG, "VOICE_RESULT=" + phrase + " pickupKm=" + pickupKm
                + " destinationKm=" + destinationKm + " fare=" + fare
                + " tomanPerKm=" + tomanPerKm + " pickupAllowed=" + pickupAllowed
                + " fareAllowed=" + fareAllowed);
        playPhrase(phrase);
    }

    private Double findDistanceNearLabel(String text, String labelExpression) {
        Pattern p = Pattern.compile(labelExpression
                + "[^0-9]{0,70}([0-9]+(?:[.,][0-9]+)?)\\s*(km|کیلومتر|کيلومتر|متر|m)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
            return value > 0 && value <= 100 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArrayList<Double> findDistances(String text) {
        ArrayList<Double> distances = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "([0-9]+(?:[.,][0-9]+)?)\\s*(km|کیلومتر|کيلومتر|متر|m)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
                if (value > 0 && value <= 100) distances.add(value);
            } catch (Exception ignored) { }
        }
        return distances;
    }

    private Long findFare(String text) {
        ArrayList<Long> fares = new ArrayList<>();
        collectFares(Pattern.compile("(?:تومان|تومن|ریال)\\s*([0-9][0-9,\\.\\s]*)").matcher(text), fares);
        collectFares(Pattern.compile("([0-9][0-9,\\.\\s]*)\\s*(?:تومان|تومن|ریال)").matcher(text), fares);
        if (fares.isEmpty()) {
            // Some layouts put the amount next to a fare label without repeating the currency.
            Matcher labelled = Pattern.compile("(?:مبلغ|کرایه|هزینه)[^0-9]{0,25}([0-9][0-9,\\.\\s]{2,})")
                    .matcher(text);
            while (labelled.find()) addFare(labelled.group(1), fares);
        }
        if (fares.isEmpty()) return null;
        long max = 0;
        for (Long value : fares) if (value != null && value > max) max = value;
        return max > 0 ? max : null;
    }

    private void collectFares(Matcher matcher, ArrayList<Long> fares) {
        while (matcher.find()) addFare(matcher.group(1), fares);
    }

    private void addFare(String raw, ArrayList<Long> fares) {
        try {
            String number = raw.replace(",", "").replace(".", "").replace(" ", "");
            if (!number.isEmpty()) {
                long value = Long.parseLong(number);
                if (value > 0 && value <= 1000000000L) fares.add(value);
            }
        } catch (Exception ignored) { }
    }

    private String normalizeDigits(String value) {
        return value.replace('۰', '0').replace('۱', '1').replace('۲', '2')
                .replace('۳', '3').replace('۴', '4').replace('۵', '5')
                .replace('۶', '6').replace('۷', '7').replace('۸', '8')
                .replace('۹', '9').replace('٠', '0').replace('١', '1')
                .replace('٢', '2').replace('٣', '3').replace('٤', '4')
                .replace('٥', '5').replace('٦', '6').replace('٧', '7')
                .replace('٨', '8').replace('٩', '9')
                .replace('٫', '.').replace('٬', ',');
    }

    private void scheduleOfferReset() {
        handler.removeCallbacks(resetOffer);
        handler.postDelayed(resetOffer, RESET_DELAY_MS);
    }

    private void playPhrase(String phrase) {
        int sound = getVoiceResource("برو".equals(phrase));
        try {
            releasePlayer();
            player = MediaPlayer.create(this, sound);
            if (player == null) {
                Log.e(TAG, "Could not create female audio player for " + phrase);
                return;
            }
            // MediaPlayer.create() returns a prepared player. Do not change
            // its audio attributes after preparation; use the normal media route.
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(mp -> releasePlayer());
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Audio playback error what=" + what + " extra=" + extra);
                releasePlayer();
                return true;
            });
            player.start();
        } catch (Exception e) {
            Log.e(TAG, "Female audio playback failed", e);
            releasePlayer();
        }
    }

    private int getVoiceResource(boolean go) {
        int choice = getSharedPreferences("voice_settings", MODE_PRIVATE).getInt("selected_voice", 0);
        switch (choice) {
            case 1: return go ? R.raw.voice1_go : R.raw.voice1_stop;
            case 2: return go ? R.raw.voice2_go : R.raw.voice2_stop;
            case 3: return go ? R.raw.voice3_go : R.raw.voice3_stop;
            default: return go ? R.raw.voice_go : R.raw.voice_stop;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
