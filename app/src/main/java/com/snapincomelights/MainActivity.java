package com.snapincomelights;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(28, 36, 28, 28);

        TextView title = new TextView(this);
        title.setText("چراغ درآمد اسنپ");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView rules = new TextView(this);
        rules.setText("\nچراغ سفید: همیشه روشن\n\nکمتر از ۱٬۰۰۰٬۰۰۰ تومان: مشکی\n۱٬۰۰۰٬۰۰۰ تا کمتر از ۳٬۰۰۰٬۰۰۰ تومان: سبز\n۳٬۰۰۰٬۰۰۰ تومان به بالا: آبی\n\nمبنای محاسبه فقط «درآمد خالص» در بخش «گزارش عملکرد» اسنپ است. برای به‌روزرسانی عدد، صفحه گزارش عملکرد را در اسنپ باز کنید.");
        rules.setTextSize(18);
        root.addView(rules);

        Button enable = new Button(this);
        enable.setText("فعال‌سازی دسترسی خواندن اسنپ");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable);

        TextView note = new TextView(this);
        note.setText("\nپس از فعال‌کردن «چراغ درآمد اسنپ» در تنظیمات دسترس‌پذیری، به اسنپ برگردید. چراغ‌ها فقط هنگام باز بودن اسنپ روی صفحه دیده می‌شوند.");
        note.setTextSize(15);
        root.addView(note);
        setContentView(root);
    }
}
