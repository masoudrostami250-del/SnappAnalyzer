package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class TripOverlay {

    private static View indicator;
    private static WindowManager windowManager;
    private static AccessibilityService service;
    private static final Handler handler =
            new Handler(Looper.getMainLooper());

    private static int currentColor = Color.BLACK;

    private TripOverlay() {}

    public static void show(AccessibilityService accessibilityService) {

        service = accessibilityService;

        handler.post(new Runnable() {
            @Override
            public void run() {
                ensureVisible();
            }
        });
    }

    /*
     * مهم‌ترین بخش:
     * اگر View به هر دلیلی از WindowManager خارج شده باشد،
     * reference قدیمی را دور می‌اندازیم و دوباره می‌سازیم.
     */
    public static void ensureVisible() {

        if (service == null) return;

        if (indicator != null && indicator.getWindowToken() != null) {
            return;
        }

        removeBrokenView();

        try {

            indicator = new View(service);
            indicator.setBackground(makeCircle(currentColor));
            indicator.setContentDescription("Snapp Analyzer");

            WindowManager.LayoutParams params =
                    new WindowManager.LayoutParams(
                            46,
                            46,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            -3
                    );

            params.gravity =
                    Gravity.TOP | Gravity.END;

            params.x = 10;
            params.y = 180;

            windowManager =
                    (WindowManager)
                            service.getSystemService(
                                    AccessibilityService.WINDOW_SERVICE
                            );

            if (windowManager == null) {
                indicator = null;
                return;
            }

            windowManager.addView(
                    indicator,
                    params
            );

        } catch (Exception e) {

            indicator = null;
            windowManager = null;
        }
    }

    /*
     * این متد باید هنگام Accessibility Event نیز فراخوانی شود.
     */
    public static void keepAlive() {

        handler.post(new Runnable() {
            @Override
            public void run() {
                ensureVisible();
            }
        });
    }

    public static void good() {

        currentColor =
                Color.rgb(25, 118, 210);

        setColor(currentColor);
    }

    public static void bad() {

        currentColor = Color.BLACK;

        setColor(currentColor);
    }

    public static void unknown() {

        currentColor =
                Color.DKGRAY;

        setColor(currentColor);
    }

    private static void setColor(int color) {

        handler.post(new Runnable() {
            @Override
            public void run() {

                ensureVisible();

                if (indicator != null) {
                    indicator.setBackground(
                            makeCircle(color)
                    );
                }
            }
        });
    }

    private static GradientDrawable makeCircle(
            int color) {

        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setShape(
                GradientDrawable.OVAL
        );

        drawable.setColor(color);

        return drawable;
    }

    private static void removeBrokenView() {

        if (indicator != null
                && windowManager != null) {

            try {
                windowManager.removeView(indicator);
            } catch (Exception ignored) {
            }
        }

        indicator = null;
        windowManager = null;
    }

    public static void hide() {

        handler.post(new Runnable() {
            @Override
            public void run() {
                removeBrokenView();
            }
        });
    }
}
