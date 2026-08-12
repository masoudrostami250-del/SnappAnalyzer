package com.snapfloat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class TripOverlay {
    private static View indicator;

    private TripOverlay() {}

    public static void show(Context context) {
        if (indicator != null) return;

        View v = new View(context);
        v.setBackground(makeCircle(Color.BLACK));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            44,
            44,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            -3
        );

        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 12;
        p.y = 180;

        WindowManager wm =
            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        wm.addView(v, p);
        indicator = v;
    }

    public static void good() {
        setColor(Color.rgb(25, 118, 210));
    }

    public static void bad() {
        setColor(Color.BLACK);
    }

    public static void unknown() {
        setColor(Color.DKGRAY);
    }

    private static void setColor(int color) {
        if (indicator != null) {
            indicator.setBackground(makeCircle(color));
        }
    }

    private static GradientDrawable makeCircle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
