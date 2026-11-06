package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private TextToSpeech testTts;
    private boolean testReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("تحلیل صوتی سفر اسنپ");
        title.setTextSize(23);
        root.addView(title);

        TextView rules = new TextView(this);
        rules.setText(
                "\\nقانون برنامه:\\n" +
                "• ۱۶ هزار تومان یا بیشتر برای هر کیلومتر مقصد: «خوبه»\\n" +
                "• کمتر از ۱۶ هزار تومان برای هر کیلومتر مقصد: «بده»\\n\\n" +
                "فاصله تا مبدأ در محاسبه وارد نمی‌شود.\\n" +
                "برای هر پیشنهاد سفر فقط یک بار صدا پخش می‌شود.\\n" +
                "برنامه صدای زن فارسی را در صورت موجود بودن انتخاب می‌کند.\\n\\n" +
                "برای کارکرد برنامه، دسترسی‌پذیری را فعال کنید."
        );
        rules.setTextSize(17);
        root.addView(rules);

        Button accessibility = new Button(this);
        accessibility.setText("فعال کردن دسترسی‌پذیری");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button ttsSettings = new Button(this);
        ttsSettings.setText("تنظیمات صدای گوشی و نصب صدای فارسی");
        ttsSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            } catch (Exception e) {
                Toast.makeText(this, "تنظیمات تبدیل متن به گفتار را از تنظیمات گوشی باز کنید", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(ttsSettings);

        Button test = new Button(this);
        test.setText("تست صدای گوینده زن");
        test.setOnClickListener(v -> {
            if (testTts == null) {
                testTts = new TextToSpeech(this, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        testReady = configurePersianVoice(testTts);
                        if (testReady) {
                            testTts.speak("سلام، صدای فارسی برنامه فعال است",
                                    TextToSpeech.QUEUE_FLUSH, null, "female-voice-test");
                        } else {
                            Toast.makeText(this,
                                    "صدای فارسی نصب نیست. از تنظیمات صدا، موتور گفتار و صدای فارسی را نصب کنید.",
                                    Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(this, "موتور تبدیل متن به گفتار فعال نیست", Toast.LENGTH_LONG).show();
                    }
                });
            } else if (testReady) {
                testTts.speak("سلام، صدای فارسی برنامه فعال است",
                        TextToSpeech.QUEUE_FLUSH, null, "female-voice-test");
            } else {
                Toast.makeText(this, "ابتدا صدای فارسی را در تنظیمات گوشی نصب کنید", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(test);

        setContentView(root);
    }

    private boolean configurePersianVoice(TextToSpeech engine) {
        int result = engine.setLanguage(new Locale("fa", "IR"));
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) return false;

        engine.setSpeechRate(0.92f);
        engine.setPitch(1.12f);

        Set<Voice> voices = engine.getVoices();
        if (voices != null) {
            Voice anyPersian = null;
            Voice femalePersian = null;
            for (Voice voice : voices) {
                Locale locale = voice.getLocale();
                if (locale == null || !"fa".equalsIgnoreCase(locale.getLanguage())) continue;
                if (anyPersian == null) anyPersian = voice;

                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                Set<String> features = voice.getFeatures();
                boolean female = name.contains("female") || name.contains("woman")
                        || name.contains("feminine")
                        || (features != null && (features.contains("female")
                        || features.contains("gender=female") || features.contains("gender= female")));
                if (female) { femalePersian = voice; break; }
            }
            Voice selected = femalePersian != null ? femalePersian : anyPersian;
            if (selected != null) engine.setVoice(selected);
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        if (testTts != null) {
            testTts.stop();
            testTts.shutdown();
        }
        super.onDestroy();
    }
}
