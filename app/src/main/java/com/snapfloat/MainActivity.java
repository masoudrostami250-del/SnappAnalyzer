package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private TextToSpeech testTts;

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
                "\nقانون برنامه:\n" +
                "• ۱۶ هزار تومان یا بیشتر برای هر کیلومتر مقصد: «خوبه»\n" +
                "• کمتر از ۱۶ هزار تومان برای هر کیلومتر مقصد: «بده»\n\n" +
                "فاصله تا مبدأ در محاسبه وارد نمی‌شود.\n" +
                "برای هر پیشنهاد سفر فقط یک بار صدا پخش می‌شود.\n\n" +
                "برای کارکرد برنامه، دسترسی‌پذیری این برنامه را فعال کنید."
        );
        rules.setTextSize(17);
        root.addView(rules);

        Button accessibility = new Button(this);
        accessibility.setText("فعال کردن دسترسی‌پذیری");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibility);

        Button test = new Button(this);
        test.setText("تست صدای فارسی");
        test.setOnClickListener(v -> {
            if (testTts == null) {
                testTts = new TextToSpeech(this, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = testTts.setLanguage(new Locale("fa", "IR"));
                        if (result != TextToSpeech.LANG_MISSING_DATA
                                && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                            testTts.speak("صدای برنامه فعال است", TextToSpeech.QUEUE_FLUSH, null, "voice-test");
                        }
                    }
                });
            } else {
                testTts.speak("صدای برنامه فعال است", TextToSpeech.QUEUE_FLUSH, null, "voice-test");
            }
        });
        root.addView(test);

        setContentView(root);
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
