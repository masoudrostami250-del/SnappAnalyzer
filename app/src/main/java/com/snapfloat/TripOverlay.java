package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class TripOverlay {

    private static View indicator;
    private static WindowManager windowManager;

    private TripOverlay() {}

    public static void show(AccessibilityService service) {
        if (indicator != null) return;

        indicator = new View(service);
        indicator.setBackground(makeCircle(Color.BLACK));
        indicator.setContentDescription("Snapp Analyzer");

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        46,
                        46,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        -3
                );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 10;
        params.y = 180;

        windowManager =
                (WindowManager) service.getSystemService(
                        AccessibilityService.WINDOW_SERVICE
                );

        windowManager.addView(indicator, params);
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
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    public static void hide() {
        if (indicator != null && windowManager != null) {
            try {
                windowManager.removeView(indicator);
            } catch (Exception ignored) {}
        }

        indicator = null;
        windowManager = null;
    }
}
