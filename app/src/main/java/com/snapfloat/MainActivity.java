package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private MediaPlayer testPlayer;

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
                "صدای زن فارسی داخل برنامه ذخیره شده و آفلاین پخش می‌شود.\\n\\n" +
                "برای کارکرد برنامه، دسترسی‌پذیری را فعال کنید."
        );
        rules.setTextSize(17);
        root.addView(rules);

        Button accessibility = new Button(this);
        accessibility.setText("فعال کردن دسترسی‌پذیری");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button goodTest = new Button(this);
        goodTest.setText("تست صدای خانم: خوبه");
        goodTest.setOnClickListener(v -> playTest(R.raw.voice_good));
        root.addView(goodTest);

        Button badTest = new Button(this);
        badTest.setText("تست صدای خانم: بده");
        badTest.setOnClickListener(v -> playTest(R.raw.voice_bad));
        root.addView(badTest);

        setContentView(root);
    }

    private void playTest(int resourceId) {
        try {
            releaseTestPlayer();
            testPlayer = MediaPlayer.create(this, resourceId);
            if (testPlayer == null) {
                Toast.makeText(this, "فایل صوتی آماده نیست", Toast.LENGTH_LONG).show();
                return;
            }
            testPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            testPlayer.setOnCompletionListener(mp -> releaseTestPlayer());
            testPlayer.start();
        } catch (Exception e) {
            Toast.makeText(this, "پخش صدا انجام نشد", Toast.LENGTH_LONG).show();
            releaseTestPlayer();
        }
    }

    private void releaseTestPlayer() {
        if (testPlayer != null) {
            try { if (testPlayer.isPlaying()) testPlayer.stop(); } catch (Exception ignored) { }
            try { testPlayer.release(); } catch (Exception ignored) { }
            testPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        releaseTestPlayer();
        super.onDestroy();
    }
}
