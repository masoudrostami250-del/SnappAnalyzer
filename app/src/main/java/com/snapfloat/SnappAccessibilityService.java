package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SnappAccessibilityService extends AccessibilityService {

    private static final String SNAPP_PACKAGE = "cab.snapp.driver";

    private WindowManager windowManager;
    private Button floatingButton;
    private WindowManager.LayoutParams params;

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

        setButtonColor(Color.YELLOW);

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

    private void analyzeTrip(
            String rawText) {

        String text =
                normalizeDigits(rawText)
                        .toLowerCase(Locale.ROOT);

        double distance =
                findTotalDistance(text);

        int minutes =
                findTotalMinutes(text);

        Long fare =
                findFare(text);

        if (distance <= 0 ||
                minutes <= 0 ||
                fare == null) {
            return;
        }

        double farePerKm =
                (double) fare / distance;

        boolean good =
                distance <= 10 &&
                minutes <= 20 &&
                farePerKm >= 15000;

        boolean bad =
                farePerKm < 10000 ||
                distance > 15 ||
                minutes > 35;

        if (bad) {

            // سفر بد همیشه قرمز
            setButtonColor(Color.RED);

        } else if (good) {

            // سفر خوب + مبدأ زیر ۳ دقیقه = آبی
            if (hasPickupUnderThreeMinutes(text)) {
                setButtonColor(Color.BLUE);
            } else {
                setButtonColor(Color.GREEN);
            }

        } else {

            setButtonColor(Color.YELLOW);
        }
    }

    private boolean hasPickupUnderThreeMinutes(
            String text) {

        Pattern pattern =
                Pattern.compile(
                        "(\\d+)\\s*" +
                        "(?:min|mins|minute|minutes|دقیقه)"
                );

        Matcher matcher =
                pattern.matcher(text);

        while (matcher.find()) {

            try {

                int value =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                if (value >= 0 && value < 3) {
                    return true;
                }

            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private double findTotalDistance(
            String text) {

        double totalKm = 0;

        Pattern kmPattern =
                Pattern.compile(
                        "(\\d+(?:[\\.,]\\d+)?)\\s*" +
                        "(?:km|کیلومتر|کيلومتر)"
                );

        Matcher kmMatcher =
                kmPattern.matcher(text);

        while (kmMatcher.find()) {

            try {

                String value =
                        kmMatcher.group(1)
                                .replace(',', '.');

                totalKm +=
                        Double.parseDouble(value);

            } catch (Exception ignored) {
            }
        }

        Pattern meterPattern =
                Pattern.compile(
                        "(\\d+)\\s*" +
                        "(?:m|متر)"
                );

        Matcher meterMatcher =
                meterPattern.matcher(text);

        while (meterMatcher.find()) {

            try {

                double meters =
                        Double.parseDouble(
                                meterMatcher.group(1)
                        );

                totalKm +=
                        meters / 1000.0;

            } catch (Exception ignored) {
            }
        }

        return totalKm;
    }

    private int findTotalMinutes(
            String text) {

        Pattern pattern =
                Pattern.compile(
                        "(\\d+)\\s*" +
                        "(?:min|mins|minute|minutes|دقیقه)"
                );

        Matcher matcher =
                pattern.matcher(text);

        int total = 0;

        while (matcher.find()) {

            try {

                total +=
                        Integer.parseInt(
                                matcher.group(1)
                        );

            } catch (Exception ignored) {
            }
        }

        return total;
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
