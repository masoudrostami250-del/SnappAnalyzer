package com.snapfloat.voice;

import android.accessibilityservice.AccessibilityService;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
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
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable resetOffer = () -> lastSpokenKey = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialization failed: " + status);
                return;
            }
            ttsReady = configurePersianFemaleVoice(tts);
            Log.i(TAG, "Persian female voice ready=" + ttsReady);
            if (ttsReady && !pendingPhrase.isEmpty()) {
                speak(pendingPhrase);
                pendingPhrase = "";
            }
        });
    }

    private boolean configurePersianFemaleVoice(TextToSpeech engine) {
        Locale fa = new Locale("fa", "IR");
        int result = engine.setLanguage(fa);
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false;
        }

        engine.setSpeechRate(0.92f);
        engine.setPitch(1.12f);

        Set<Voice> voices = engine.getVoices();
        if (voices == null) return true;

        Voice persianVoice = null;
        Voice femalePersianVoice = null;
        for (Voice voice : voices) {
            Locale locale = voice.getLocale();
            if (locale == null || !"fa".equalsIgnoreCase(locale.getLanguage())) continue;

            if (persianVoice == null) persianVoice = voice;

            String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
            Set<String> features = voice.getFeatures();
            boolean female = name.contains("female") || name.contains("woman")
                    || name.contains("feminine")
                    || (features != null && (features.contains("female")
                    || features.contains("gender= female") || features.contains("gender=female")));
            if (female) {
                femalePersianVoice = voice;
                break;
            }
        }

        // Prefer an explicitly female Persian voice. If the installed engine does not
        // expose gender metadata, retain its Persian default and raise pitch slightly.
        Voice selected = femalePersianVoice != null ? femalePersianVoice : persianVoice;
        if (selected != null) {
            try {
                engine.setVoice(selected);
                Log.i(TAG, "Selected Persian voice: " + selected.getName()
                        + " femaleMatch=" + (femalePersianVoice != null));
            } catch (Exception e) {
                Log.e(TAG, "Could not select Persian voice", e);
            }
        }
        return true;
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
        String phrase = tomanPerKm >= MIN_TOMAN_PER_KM ? "خوبه" : "بده";
        String key = fare + "|" + Math.round(destinationKm * 1000.0);
        if (key.equals(lastSpokenKey)) return;

        lastSpokenKey = key;
        Log.i(TAG, "VOICE_RESULT=" + phrase + " fare=" + fare
                + " destinationKm=" + destinationKm + " tomanPerKm=" + tomanPerKm);
        if (ttsReady) speak(phrase);
        else pendingPhrase = phrase;
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

    private void speak(String phrase) {
        if (tts != null && ttsReady) {
            tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null,
                    "snapp-" + System.currentTimeMillis());
        }
    }

    @Override public void onInterrupt() { }

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
