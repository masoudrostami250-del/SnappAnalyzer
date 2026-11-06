package com.snapfloat.voice;

import android.accessibilityservice.AccessibilityService;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceTripAccessibilityService extends AccessibilityService {
    private static final String TAG = "SnappVoice16000";
    private static final String SNAPP_PACKAGE = "cab.snapp.driver";
    private static final double MIN_TOMAN_PER_KM = 16000.0;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastSpokenKey = "";
    private String pendingPhrase = "";
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable resetOffer = new Runnable() {
        @Override public void run() {
            lastSpokenKey = "";
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageResult = tts.setLanguage(new Locale("fa", "IR"));
                tts.setSpeechRate(1.0f);
                tts.setPitch(1.0f);
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                        && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ttsReady && !pendingPhrase.isEmpty()) {
                    speak(pendingPhrase);
                    pendingPhrase = "";
                }
                Log.i(TAG, "TTS ready=" + ttsReady + " languageResult=" + languageResult);
            } else {
                Log.e(TAG, "TextToSpeech initialization failed: " + status);
            }
        });
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
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), out);
        }
    }

    private void analyzeOffer(String raw) {
        String text = normalizeDigits(raw).toLowerCase(Locale.ROOT);
        Long fare = findFare(text);
        ArrayList<Double> distances = findDistances(text);

        if (fare == null || fare <= 0 || distances.isEmpty()) {
            scheduleOfferReset();
            return;
        }

        // همان قاعده نسخه ۱۶هزار: فاصله اول تا مبدأ است؛ فاصله دوم مسافت مقصد/سفر.
        // اگر فقط یک فاصله نمایش داده شود، همان فاصله استفاده می‌شود.
        double destinationKm = distances.size() >= 2 ? distances.get(1) : distances.get(0);
        if (destinationKm <= 0) {
            scheduleOfferReset();
            return;
        }

        handler.removeCallbacks(resetOffer);
        double tomanPerKm = fare / destinationKm;
        boolean good = tomanPerKm >= MIN_TOMAN_PER_KM;

        String key = fare + "|" + Math.round(destinationKm * 1000.0);
        if (key.equals(lastSpokenKey)) return;

        lastSpokenKey = key;
        String phrase = good ? "خوبه" : "بده";
        Log.i(TAG, "VOICE_RESULT=" + phrase + " fare=" + fare
                + " destinationKm=" + destinationKm + " tomanPerKm=" + tomanPerKm);

        if (ttsReady) speak(phrase);
        else pendingPhrase = phrase;
    }

    private ArrayList<Double> findDistances(String text) {
        ArrayList<Double> distances = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "(\\d+(?:[\\.,]\\d+)?)\\s*(km|کیلومتر|کيلومتر|متر|m)",
                Pattern.CASE_INSENSITIVE
        ).matcher(text);

        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
                if (value <= 0 || value > 100) continue;

                boolean duplicate = false;
                for (Double prior : distances) {
                    if (Math.abs(prior - value) < 0.0005) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) distances.add(value);
            } catch (Exception ignored) { }
        }
        return distances;
    }

    private Long findFare(String text) {
        ArrayList<Long> fares = new ArrayList<>();
        Pattern beforeCurrency = Pattern.compile(
                "(?:تومان|تومن|ریال)\\s*([0-9][0-9,\\.\\s]*)"
        );
        Pattern afterCurrency = Pattern.compile(
                "([0-9][0-9,\\.\\s]*)\\s*(?:تومان|تومن|ریال)"
        );
        collectFares(beforeCurrency.matcher(text), fares);
        collectFares(afterCurrency.matcher(text), fares);
        if (fares.isEmpty()) return null;

        long max = 0;
        for (Long value : fares) if (value != null && value > max) max = value;
        return max > 0 ? max : null;
    }

    private void collectFares(Matcher matcher, ArrayList<Long> fares) {
        while (matcher.find()) {
            try {
                String number = matcher.group(1)
                        .replace(",", "").replace(".", "").replace(" ", "");
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

    private void speak(String phrase) {
        if (tts != null && ttsReady) {
            tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "snapp-" + System.currentTimeMillis());
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
