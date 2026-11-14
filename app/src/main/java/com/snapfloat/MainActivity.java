package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "voice_settings";
    private static final String VOICE_KEY = "selected_voice";
    private MediaPlayer testPlayer;
    private TextView diagnosticReport;
    private static final int REQUEST_SCREEN_CAPTURE = 7401;

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
                "قانون برنامه:\n" +
                "• مبدأ تا ۲ کیلومتر و مقصد حداقل ۱۷ هزار تومان برای هر کیلومتر: «برو»\n" +
                "• مبدأ بیشتر از ۲ کیلومتر: «نرو»\n" +
                "• مقصد کمتر از ۱۷ هزار تومان برای هر کیلومتر: «نرو»\n\n" +
                "محاسبه نرخ فقط با مسافت مقصد انجام می‌شود؛ مسافت مبدأ به نرخ مقصد اضافه نمی‌شود.\n" +
                "اگر فاصله مبدأ و مقصد هر دو قابل تشخیص نباشند، برنامه سفر را تأیید نمی‌کند.\n" +
                "برای هر پیشنهاد سفر فقط یک بار صدا پخش می‌شود.\n" +
                "صداها داخل برنامه ذخیره شده‌اند و برای پخش به اینترنت نیاز ندارند.\n\n" +
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
                "صدای ویدئو - گزینه ۳"
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
            if (choice >= 0 && choice <= 3) {
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

        Button startOcr = new Button(this);
        startOcr.setText("فعال‌سازی خواندن تصویری اسنپ (OCR)");
        startOcr.setOnClickListener(v -> {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        });
        root.addView(startOcr);

        Button stopOcr = new Button(this);
        stopOcr.setText("توقف خواندن تصویری");
        stopOcr.setOnClickListener(v -> stopService(new Intent(this, com.snapfloat.voice.SnappScreenOcrService.class)));
        root.addView(stopOcr);

        TextView diagnosticTitle = new TextView(this);
        diagnosticTitle.setText("\n\nبخش عیب‌یابی دریافت اطلاعات اسنپ");
        diagnosticTitle.setTextSize(18);
        root.addView(diagnosticTitle);

        TextView diagnosticHelp = new TextView(this);
        diagnosticHelp.setText("برای بررسی مشکل، پایش را شروع کنید؛ سپس وارد اسنپ شوید، جزئیات یک پیشنهاد را باز کنید و به این صفحه برگردید. گزارش فقط برای بررسی همین مشکل در گوشی ذخیره می‌شود.");
        diagnosticHelp.setTextSize(14);
        root.addView(diagnosticHelp);

        Button startDiagnostic = new Button(this);
        startDiagnostic.setText("شروع پایش عیب‌یابی");
        startDiagnostic.setOnClickListener(v -> {
            getSharedPreferences("voice_diagnostics", MODE_PRIVATE).edit()
                    .putBoolean("enabled", true).apply();
            Toast.makeText(this, "پایش فعال شد؛ وارد اسنپ شوید", Toast.LENGTH_LONG).show();
            refreshDiagnosticReport();
        });
        root.addView(startDiagnostic);

        Button stopDiagnostic = new Button(this);
        stopDiagnostic.setText("توقف پایش");
        stopDiagnostic.setOnClickListener(v -> {
            getSharedPreferences("voice_diagnostics", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).apply();
            refreshDiagnosticReport();
        });
        root.addView(stopDiagnostic);

        Button refreshDiagnostic = new Button(this);
        refreshDiagnostic.setText("نمایش گزارش ثبت‌شده");
        refreshDiagnostic.setOnClickListener(v -> refreshDiagnosticReport());
        root.addView(refreshDiagnostic);

        diagnosticReport = new TextView(this);
        diagnosticReport.setTextSize(13);
        root.addView(diagnosticReport);
        refreshDiagnosticReport();

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiagnosticReport();
    }

    private void refreshDiagnosticReport() {
        if (diagnosticReport == null) return;
        SharedPreferences prefs = getSharedPreferences("voice_diagnostics", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled", false);
        String capture = prefs.getString("last_snapp_capture", "");
        String lastEvent = prefs.getString("last_event", "");
        String status = enabled ? "وضعیت پایش: روشن" : "وضعیت پایش: خاموش";
        if (capture.isEmpty()) {
            diagnosticReport.setText(status + "\n\nهنوز متن پنجره اسنپ ثبت نشده است. ابتدا پایش را روشن کنید و یک پیشنهاد سفر را باز کنید.\n\nآخرین رویداد:\n" + lastEvent);
        } else {
            diagnosticReport.setText(status + "\n\n" + capture);
        }
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
            default: return go ? R.raw.voice_go : R.raw.voice_stop;
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
            // MediaPlayer.create() returns an already-prepared player.
            // Keep its default media audio route and set an explicit audible volume.
            testPlayer.setVolume(1.0f, 1.0f);
            testPlayer.setOnCompletionListener(mp -> releaseTestPlayer());
            testPlayer.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "خطای پخش صدا: " + what, Toast.LENGTH_LONG).show();
                releaseTestPlayer();
                return true;
            });
            testPlayer.start();
        } catch (Exception e) {
            Toast.makeText(this, "پخش صدا انجام نشد: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
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
