package com.snapfloat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "voice_settings";
    private static final String VOICE_KEY = "selected_voice";
    private static final int REQUEST_FOREGROUND_LOCATION = 701;
    private static final int REQUEST_BACKGROUND_LOCATION = 702;
    private MediaPlayer testPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("تحلیل صوتی سفر اسنپ - محاسبه مسیر");
        title.setTextSize(22);
        root.addView(title);

        TextView rules = new TextView(this);
        rules.setText(
                "قانون برنامه:\n" +
                "• فاصله گوشی تا مبدأ سفر حداکثر ۲ کیلومتر باشد.\n" +
                "• کرایه تقسیم بر مسافت جاده‌ای مبدأ تا مقصد، حداقل ۱۷ هزار تومان برای هر کیلومتر باشد.\n" +
                "• اگر هر دو شرط برقرار باشد «برو» و در غیر این صورت «نرو» گفته می‌شود.\n\n" +
                "در این نسخه، فاصله مبدأ از GPS گوشی و فاصله مقصد با آدرس‌های روی کارت سفر و نقشه محاسبه می‌شود.\n" +
                "برای کار درحالی‌که اسنپ باز است، موقعیت مکانی دقیق باید روی «همیشه فعال / Allow all the time» باشد.\n" +
                "اگر آدرس در نقشه با دقت کافی پیدا نشود یا GPS معتبر نباشد، برنامه نتیجه حدسی اعلام نمی‌کند.\n\n" +
                "صداها داخل برنامه هستند و برای پخش به اینترنت نیاز ندارند. برای محاسبه مسیر اینترنت لازم است.\n\n" +
                "صدای موردنظر را انتخاب کنید:"
        );
        rules.setTextSize(15);
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

        Button locationPermission = new Button(this);
        locationPermission.setText("فعال‌سازی موقعیت مکانی دقیق و همیشه فعال");
        locationPermission.setOnClickListener(v -> requestLocationAccess());
        root.addView(locationPermission);

        TextView permissionStatus = new TextView(this);
        permissionStatus.setText(getLocationPermissionStatus());
        root.addView(permissionStatus);

        Button mapSettings = new Button(this);
        mapSettings.setText("تنظیم نشانی سرویس‌های نقشه");
        mapSettings.setOnClickListener(v -> showMapServiceSettings());
        root.addView(mapSettings);

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

        Button diagnostic = new Button(this);
        diagnostic.setText("گزارش آخرین دریافت و تحلیل اسنپ");
        diagnostic.setOnClickListener(v -> {
            String report = getPreferences().getString("last_snapp_report",
                    "هنوز رویدادی از اسنپ ثبت نشده است. اسنپ را باز کنید و یک پیشنهاد سفر را روی صفحه بیاورید.");
            showReport("گزارش عیب‌یابی", report);
        });
        root.addView(diagnostic);

        Button structureDiagnostic = new Button(this);
        structureDiagnostic.setText("گزارش ساختار عناصر صفحه اسنپ");
        structureDiagnostic.setOnClickListener(v -> {
            String report = getPreferences().getString("last_snapp_structure",
                    "هنوز گزارش ساختار ثبت نشده است. اسنپ را باز کنید، یک پیشنهاد سفر را روی صفحه بیاورید و چند ثانیه صبر کنید.");
            showReport("گزارش ساختار عناصر اسنپ", report);
        });
        root.addView(structureDiagnostic);

        TextView attribution = new TextView(this);
        attribution.setText(
                "\nمنابع نقشه: داده‌های OpenStreetMap، جست‌وجوی Nominatim و مسیریابی OSRM. " +
                "مسافت‌ها تخمینی هستند و سرویس‌های عمومی ممکن است موقتاً در دسترس نباشند.\n" +
                "برای فاصله‌ها، موقعیت گوشی و آدرس‌های کارت سفر به سرویس‌های نقشه فرستاده می‌شوند."
        );
        attribution.setTextSize(12);
        root.addView(attribution);

        setContentView(root);
    }

    private void requestLocationAccess() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_FOREGROUND_LOCATION);
            return;
        }
        continueBackgroundLocationPermission();
    }

    private void continueBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "مجوز موقعیت مکانی فعال است.", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "موقعیت مکانی همیشه فعال است.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, REQUEST_BACKGROUND_LOCATION);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("اجازه موقعیت مکانی همیشه فعال")
                    .setMessage("برای خواندن فاصله هنگام باز بودن اسنپ، در صفحه تنظیمات برنامه وارد بخش Location شوید و گزینه Allow all the time را انتخاب کنید. گزینه Precise location نیز باید روشن باشد.")
                    .setPositiveButton("باز کردن تنظیمات", (dialog, which) -> openAppSettings())
                    .setNegativeButton("بعداً", null)
                    .show();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                continueBackgroundLocationPermission();
            } else {
                Toast.makeText(this, "بدون موقعیت مکانی دقیق، فاصله تا مبدأ محاسبه نمی‌شود.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "موقعیت مکانی همیشه فعال شد.", Toast.LENGTH_LONG).show();
            } else {
                new AlertDialog.Builder(this)
                        .setMessage("دسترسی همیشه فعال داده نشد. در تنظیمات برنامه، Location را روی Allow all the time قرار دهید.")
                        .setPositiveButton("تنظیمات", (dialog, which) -> openAppSettings())
                        .setNegativeButton("بستن", null)
                        .show();
            }
        }
    }

    private String getLocationPermissionStatus() {
        if (Build.VERSION.SDK_INT < 23) return "موقعیت مکانی: فعال";
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine && background) return "وضعیت موقعیت مکانی: مجوز دقیق و همیشه فعال داده شده است.";
        if (!fine) return "وضعیت موقعیت مکانی: مجوز دقیق هنوز داده نشده است.";
        return "وضعیت موقعیت مکانی: دسترسی همیشه فعال هنوز داده نشده است.";
    }

    private void showMapServiceSettings() {
        SharedPreferences prefs = getSharedPreferences("route_settings", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 8, 24, 8);

        TextView geoLabel = new TextView(this);
        geoLabel.setText("نشانی سرویس جست‌وجوی آدرس (Nominatim)");
        layout.addView(geoLabel);
        EditText geocoder = new EditText(this);
        geocoder.setSingleLine(true);
        geocoder.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        geocoder.setText(prefs.getString("geocoder_base", "https://nominatim.openstreetmap.org"));
        layout.addView(geocoder);

        TextView routeLabel = new TextView(this);
        routeLabel.setText("نشانی سرویس مسیریابی (OSRM)");
        layout.addView(routeLabel);
        EditText router = new EditText(this);
        router.setSingleLine(true);
        router.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        router.setText(prefs.getString("router_base", "https://router.project-osrm.org"));
        layout.addView(router);

        new AlertDialog.Builder(this)
                .setTitle("تنظیم سرویس‌های نقشه")
                .setView(layout)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String geo = trimSlashes(geocoder.getText().toString());
                    String route = trimSlashes(router.getText().toString());
                    if (!geo.startsWith("https://") || !route.startsWith("https://")) {
                        Toast.makeText(this, "نشانی هر دو سرویس باید با https:// شروع شود.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putString("geocoder_base", geo)
                            .putString("router_base", route).apply();
                    Toast.makeText(this, "تنظیمات نقشه ذخیره شد.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private String trimSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private void showReport(String title, String report) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(report)
                .setPositiveButton("بستن", null)
                .setNeutralButton("کپی گزارش", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, report));
                    Toast.makeText(this, "گزارش کپی شد", Toast.LENGTH_SHORT).show();
                })
                .show();
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
