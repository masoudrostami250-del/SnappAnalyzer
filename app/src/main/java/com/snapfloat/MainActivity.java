package com.snapfloat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 30);

        TextView title = new TextView(this);
        title.setText("Snapp Analyzer 2.0");
        title.setTextSize(24);
        root.addView(title);

        TextView rules = new TextView(this);
        rules.setText(
            "\nقانون تحلیل:\n" +
            "• ۱۲٬۰۰۰ تومان یا بیشتر در هر کیلومتر = آبی / خوب\n" +
            "• کمتر از ۱۲٬۰۰۰ تومان در هر کیلومتر = مشکی / بد\n\n" +
            "مسافت کمتر از ۱ کیلومتر برای محاسبه، ۱ کیلومتر محسوب می‌شود.\n" +
            "مثال: ۷۰۰ متر = ۱ کیلومتر محاسباتی.\n\n" +
            "هر سفر کاملاً مستقل تحلیل می‌شود و مبلغ یک سفر با فاصله سفر دیگری ترکیب نمی‌شود."
        );
        rules.setTextSize(16);
        root.addView(rules);

        Button overlay = new Button(this);
        overlay.setText("فعال کردن دکمه شناور");
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
                );
                startActivity(i);
            } else {
                TripOverlay.show(this);
            }
        });
        root.addView(overlay);

        Button accessibility = new Button(this);
        accessibility.setText("فعال کردن Accessibility");
        accessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibility);

        setContentView(root);
    }
}
