package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SnappAccessibilityService extends AccessibilityService {

    private static final String SNAPP_PACKAGE = "cab.snapp.driver";

    private WindowManager windowManager;
    private Button floatingButton;
    private WindowManager.LayoutParams params;
private boolean analyzerEnabled = true;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private String lastText = "";

    private final Runnable analyzeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!lastText.isEmpty()) {
                analyzeTrip(lastText);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        createFloatingButton();
    }

    private void createFloatingButton() {

        if (floatingButton != null) {
            return;
        }

        floatingButton = new Button(this);
        floatingButton.setText("");

        setButtonColor(Color.WHITE);

        params = new WindowManager.LayoutParams(
                90,
                90,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 25;
        params.y = 300;

        floatingButton.setOnTouchListener(
                new View.OnTouchListener() {

                    int startX;
                    int startY;
                    float touchX;
                    float touchY;

                    @Override
                    public boolean onTouch(
                            View v,
                            MotionEvent event) {

                        switch (event.getAction()) {

                            case MotionEvent.ACTION_DOWN:

                                startX = params.x;
                                startY = params.y;

                                touchX =
                                        event.getRawX();

                                touchY =
                                        event.getRawY();

                                return true;

                            case MotionEvent.ACTION_MOVE:

                                params.x =
                                        startX +
                                        (int) (
                                                event.getRawX()
                                                        - touchX
                                        );

                                params.y =
                                        startY +
                                        (int) (
                                                event.getRawY()
                                                        - touchY
                                        );

                                try {
                                    windowManager.updateViewLayout(
                                            floatingButton,
                                            params
                                    );
                                } catch (Exception ignored) {
                                }

                                return true;
                        }

                        return true;
                    }
                }
        );

        try {
            windowManager.addView(
                    floatingButton,
                    params
            );
        } catch (Exception ignored) {
            floatingButton = null;
        }
    }

    private void setButtonColor(int color) {

        if (floatingButton == null) {
            return;
        }

        GradientDrawable background =
                new GradientDrawable();

        background.setShape(
                GradientDrawable.OVAL
        );

        background.setColor(color);

        floatingButton.setBackground(background);
    }

    @Override
    public void onAccessibilityEvent(
            AccessibilityEvent event) {

        if (event == null) {
            return;
        }

        CharSequence packageName =
                event.getPackageName();

        if (packageName == null ||
                !SNAPP_PACKAGE.equals(
                        packageName.toString()
                )) {
            return;
        }

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root == null) {
            return;
        }

        String text =
                collectText(root);

        // DIAGNOSTIC: dump Accessibility tree for Snapp trip card
        try {
            Log.d("SnapFloatTree", "===== SNAPFLOAT EVENT =====");
            dumpNode(root, 0);
            Log.d("SnapFloatTree", "===== END SNAPFLOAT EVENT =====");
        } catch (Exception ignored) {
        }

        if (text.isEmpty() ||
                text.equals(lastText)) {
            return;
        }

        lastText = text;

        handler.removeCallbacks(
                analyzeRunnable
        );

        handler.postDelayed(
                analyzeRunnable,
                80
        );
    }

    private String collectText(
            AccessibilityNodeInfo node) {

        StringBuilder result =
                new StringBuilder();

        collect(node, result);

        return result.toString();
    }

    private void collect(
            AccessibilityNodeInfo node,
            StringBuilder result) {

        if (node == null) {
            return;
        }

        CharSequence text =
                node.getText();

        if (text != null) {
            result.append(" ");
            result.append(text);
        }

        CharSequence description =
                node.getContentDescription();

        if (description != null) {
            result.append(" ");
            result.append(description);
        }

        for (int i = 0;
             i < node.getChildCount();
             i++) {

            collect(
                    node.getChild(i),
                    result
            );
        }
    }

    
private void dumpNode(
        AccessibilityNodeInfo node,
        int depth) {

    if (node == null || depth > 12) {
        return;
    }

    StringBuilder indent = new StringBuilder();
    for (int i = 0; i < depth; i++) {
        indent.append("  ");
    }

    CharSequence nodeText = node.getText();
    CharSequence desc = node.getContentDescription();
    CharSequence className = node.getClassName();

    String line =
            indent +
            "CLASS=" + className +
            " TEXT=" + nodeText +
            " DESC=" + desc +
            " CLICK=" + node.isClickable() +
            " ENABLED=" + node.isEnabled();

    Log.d("SnapFloatTree", line);

    for (int i = 0; i < node.getChildCount(); i++) {
        dumpNode(node.getChild(i), depth + 1);
    }
}

private void analyzeTrip(String rawText) {
    if (floatingButton == null || rawText == null || !analyzerEnabled) {
        return;
    }

    String text = normalizeDigits(rawText)
            .toLowerCase(Locale.ROOT);

    ArrayList<Double> distances = new ArrayList<>();

    Matcher kmMatcher = Pattern.compile(
            "(\\d+(?:[\\.,]\\d+)?)\\s*(?:km|کیلومتر|کيلومتر)"
    ).matcher(text);

    while (kmMatcher.find()) {
        try {
            double value = Double.parseDouble(
                    kmMatcher.group(1).replace(',', '.')
            );

            if (value > 0 && value <= 100) {
                distances.add(value);
            }
        } catch (Exception ignored) {
        }
    }

    if (distances.isEmpty()) {
        setButtonColor(Color.WHITE);
        return;
    }

    // مقدار دوم = مسافت واقعی سفر
    // اگر فقط یک مسافت وجود داشت، همان را مسافت سفر در نظر می‌گیریم.
    double tripKm = distances.size() >= 2
            ? distances.get(1)
            : distances.get(0);

    if (tripKm <= 0) {
        setButtonColor(Color.WHITE);
        return;
    }

    Long fare = findFare(text);

    if (fare == null || fare <= 0) {
        setButtonColor(Color.WHITE);
        return;
    }

    // فقط مسافت واقعی سفر در محاسبه استفاده می‌شود.
    double farePerKm = (double) fare / tripKm;

    // ۱۵ هزار تومان به ازای هر کیلومتر = آبی
    // کمتر از ۱۵ هزار تومان = مشکی
    if (farePerKm >= 15000.0) {
        setButtonColor(Color.BLUE);
    } else {
        setButtonColor(Color.BLACK);
    }
}

private Long findFare(
            String text) {

        Pattern before =
                Pattern.compile(
                        "(?:تومان|تومن|ریال)\\s*" +
                        "([0-9][0-9,\\.\\s]*)"
                );

        Matcher m1 =
                before.matcher(text);

        if (m1.find()) {

            try {

                String number =
                        m1.group(1)
                                .replace(",", "")
                                .replace(".", "")
                                .replace(" ", "");

                return Long.parseLong(number);

            } catch (Exception ignored) {
            }
        }

        Pattern after =
                Pattern.compile(
                        "([0-9][0-9,\\.\\s]*)\\s*" +
                        "(?:تومان|تومن|ریال)"
                );

        Matcher m2 =
                after.matcher(text);

        if (m2.find()) {

            try {

                String number =
                        m2.group(1)
                                .replace(",", "")
                                .replace(".", "")
                                .replace(" ", "");

                return Long.parseLong(number);

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String normalizeDigits(
            String text) {

        return text
                .replace('۰', '0')
                .replace('۱', '1')
                .replace('۲', '2')
                .replace('۳', '3')
                .replace('۴', '4')
                .replace('۵', '5')
                .replace('۶', '6')
                .replace('۷', '7')
                .replace('۸', '8')
                .replace('۹', '9')
                .replace('٫', '.')
                .replace('٬', ',');
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {

        if (floatingButton != null) {

            try {
                windowManager.removeView(
                        floatingButton
                );
            } catch (Exception ignored) {
            }

            floatingButton = null;
        }

        super.onDestroy();
    }
}
