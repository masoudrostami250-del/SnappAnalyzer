package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "voice_settings";
    private static final String VOICE_KEY = "selected_voice";
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
                "قوانین این نسخه:\n" +
                "• مبدأ حداکثر ۲ کیلومتر باشد.\n" +
                "• کرایه حداقل ۷۵ هزار تومان باشد.\n" +
                "• نرخ مقصد حداقل ۱۷ هزار تومان برای هر کیلومتر باشد.\n" +
                "• نرخ فقط از تقسیم کرایه بر کیلومتر مقصد محاسبه می‌شود.\n\n" +
                "اگر هر سه شرط برقرار باشد «برو» و در غیر این صورت «نرو» گفته می‌شود.\n" +
                "صداهای ضبط‌شده نسخه ۹۰ هزار تومانی، آفلاین و با سرعت ۱٫۳۰ برابر استفاده می‌شوند.\n\n" +
                "صدای موردنظر را انتخاب کنید:"
        );
        rules.setTextSize(16);
        root.addView(rules);

        RadioGroup voiceChoices = new RadioGroup(this);
        voiceChoices.setOrientation(RadioGroup.VERTICAL);
        String[] labels = {
                "صدای زن پیش‌فرض",
                "صدای ویدئو - گزینه ۱",
                "صدای ویدئو - گزینه ۲",
                "صدای ویدئو - گزینه ۳",
                "صدای پنجعلی و بهبود"
        };
        int selected = getPreferences().getInt(VOICE_KEY, 0);
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(100 + i);
            option.setText(labels[i]);
            voiceChoices.addView(option);
            if (i == selected) option.setChecked(true);
        }
        root.addView(voiceChoices);
        voiceChoices.setOnCheckedChangeListener((group, checkedId) -> {
            int choice = checkedId - 100;
            if (choice >= 0 && choice <= 4) {
                getPreferences().edit().putInt(VOICE_KEY, choice).apply();
                Toast.makeText(this, "صدای انتخاب‌شده ذخیره شد", Toast.LENGTH_SHORT).show();
            }
        });

        Button accessibility = new Button(this);
        accessibility.setText("فعال کردن دسترسی‌پذیری");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button goodTest = new Button(this);
        goodTest.setText("تست صدای انتخاب‌شده: برو");
        goodTest.setOnClickListener(v -> playTest(getVoiceResource(true)));
        root.addView(goodTest);

        Button badTest = new Button(this);
        badTest.setText("تست صدای انتخاب‌شده: نرو");
        badTest.setOnClickListener(v -> playTest(getVoiceResource(false)));
        root.addView(badTest);
        setContentView(root);
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int getVoiceResource(boolean go) {
        int choice = getPreferences().getInt(VOICE_KEY, 0);
        switch (choice) {
            case 1: return go ? R.raw.voice1_go : R.raw.voice1_stop;
            case 2: return go ? R.raw.voice2_go : R.raw.voice2_stop;
            case 3: return go ? R.raw.voice3_go : R.raw.voice3_stop;
            case 4: return go ? R.raw.voice4_go : R.raw.voice4_stop;
            default: return go ? R.raw.voice1_go : R.raw.voice1_stop;
        }
    }

    private void playTest(int resourceId) {
        try {
            releaseTestPlayer();
            testPlayer = MediaPlayer.create(this, resourceId);
            if (testPlayer == null) {
                Toast.makeText(this, "فایل صوتی آماده نیست", Toast.LENGTH_LONG).show();
                return;
            }
            testPlayer.setVolume(1.0f, 1.0f);
            if (Build.VERSION.SDK_INT >= 23) {
                testPlayer.setPlaybackParams(new PlaybackParams().setSpeed(1.30f).setPitch(1.0f));
            }
            testPlayer.setOnCompletionListener(mp -> releaseTestPlayer());
            testPlayer.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "خطای پخش صدا: " + what, Toast.LENGTH_LONG).show();
                releaseTestPlayer();
                return true;
            });
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
