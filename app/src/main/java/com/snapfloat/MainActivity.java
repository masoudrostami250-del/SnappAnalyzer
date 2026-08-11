package com.snapfloat;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("SnapFloat 11");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);

        TextView info = new TextView(this);
        info.setText(
            "\nتحلیل سفر اسنپ فعال است.\n\n" +
            "سبز = سفر خوب\n" +
            "آبی = سفر خوب با مبدأ زیر ۳ دقیقه\n" +
            "قرمز = سفر نامناسب\n" +
            "زرد = سفر متوسط\n\n" +
            "برای فعال‌سازی سرویس، دسترسی Accessibility را فعال کنید."
        );
        info.setTextSize(17);
        info.setTextColor(Color.DKGRAY);

        layout.addView(title);
        layout.addView(info);

        setContentView(layout);
    }
}
