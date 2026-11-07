package com.snapfloat.voice;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Handler;
import android.os.Looper;

import com.snapfloat.R;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceTripAccessibilityService extends AccessibilityService {
    private static final String TAG = "SnappVoice16000";
    private static final String SNAPP_PACKAGE = "cab.snapp.driver";
    private static final double MIN_TOMAN_PER_KM = 17000.0;

    private String lastSpokenKey = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private final Runnable resetOffer = () -> lastSpokenKey = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Female Persian offline audio service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !SNAPP_PACKAGE.equals(packageName.toString())) {
            scheduleOfferReset();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleOfferReset();
            return;
        }
        StringBuilder allText = new StringBuilder();
        collectText(root, allText);
        analyzeOffer(allText.toString());
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null) out.append(' ').append(text);
        CharSequence description = node.getContentDescription();
        if (description != null) out.append(' ').append(description);
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out);
    }

    private void analyzeOffer(String raw) {
        String text = normalizeDigits(raw).toLowerCase(Locale.ROOT);
        Long fare = findFare(text);
        ArrayList<Double> distances = findDistances(text);
        if (fare == null || fare <= 0 || distances.isEmpty()) {
            scheduleOfferReset();
            return;
        }

        // First distinct distance is pickup; second is destination. If only one appears, use it.
        double destinationKm = distances.size() >= 2 ? distances.get(1) : distances.get(0);
        if (destinationKm <= 0) {
            scheduleOfferReset();
            return;
        }

        handler.removeCallbacks(resetOffer);
        double tomanPerKm = fare / destinationKm;
        String phrase = tomanPerKm >= MIN_TOMAN_PER_KM ? "برو" : "نرو";
        String key = fare + "|" + Math.round(destinationKm * 1000.0);
        if (key.equals(lastSpokenKey)) return;

        lastSpokenKey = key;
        Log.i(TAG, "VOICE_RESULT=" + phrase + " fare=" + fare
                + " destinationKm=" + destinationKm + " tomanPerKm=" + tomanPerKm);
        playPhrase(phrase);
    }

    private ArrayList<Double> findDistances(String text) {
        ArrayList<Double> distances = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "(\\d+(?:[\\.,]\\d+)?)\\s*(km|کیلومتر|کيلومتر|متر|m)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
                if (value <= 0 || value > 100) continue;
                boolean duplicate = false;
                for (Double prior : distances) {
                    if (Math.abs(prior - value) < 0.0005) { duplicate = true; break; }
                }
                if (!duplicate) distances.add(value);
            } catch (Exception ignored) { }
        }
        return distances;
    }

    private Long findFare(String text) {
        ArrayList<Long> fares = new ArrayList<>();
        collectFares(Pattern.compile("(?:تومان|تومن|ریال)\\s*([0-9][0-9,\\.\\s]*)").matcher(text), fares);
        collectFares(Pattern.compile("([0-9][0-9,\\.\\s]*)\\s*(?:تومان|تومن|ریال)").matcher(text), fares);
        if (fares.isEmpty()) return null;
        long max = 0;
        for (Long value : fares) if (value != null && value > max) max = value;
        return max > 0 ? max : null;
    }

    private void collectFares(Matcher matcher, ArrayList<Long> fares) {
        while (matcher.find()) {
            try {
                String number = matcher.group(1).replace(",", "").replace(".", "").replace(" ", "");
                if (!number.isEmpty()) {
                    long value = Long.parseLong(number);
                    if (value > 0 && value <= 1000000000L) fares.add(value);
                }
            } catch (Exception ignored) { }
        }
    }

    private String normalizeDigits(String value) {
        return value.replace('۰', '0').replace('۱', '1').replace('۲', '2')
                .replace('۳', '3').replace('۴', '4').replace('۵', '5')
                .replace('۶', '6').replace('۷', '7').replace('۸', '8')
                .replace('۹', '9').replace('٫', '.').replace('٬', ',');
    }

    private void scheduleOfferReset() {
        handler.removeCallbacks(resetOffer);
        handler.postDelayed(resetOffer, 1500);
    }

    private void playPhrase(String phrase) {
        int sound = "برو".equals(phrase) ? R.raw.voice_go : R.raw.voice_stop;
        try {
            releasePlayer();
            player = MediaPlayer.create(this, sound);
            if (player == null) {
                Log.e(TAG, "Could not create female audio player for " + phrase);
                return;
            }
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
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
