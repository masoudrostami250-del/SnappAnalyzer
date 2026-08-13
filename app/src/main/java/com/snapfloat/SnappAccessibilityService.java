package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
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
    private final ArrayList<Button> tripButtons = new ArrayList<>();
    private final ArrayList<WindowManager.LayoutParams> tripParams = new ArrayList<>();
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


private void clearTripButtons() {
    for (Button button : tripButtons) {
        try {
            if (windowManager != null) {
                windowManager.removeView(button);
            }
        } catch (Exception ignored) {
        }
    }

    tripButtons.clear();
    tripParams.clear();
}


private static class TripCandidate {
    String text;
    Rect bounds;

    TripCandidate(String text, Rect bounds) {
        this.text = text;
        this.bounds = bounds;
    }
}

private void analyzeTripCards(AccessibilityNodeInfo root) {

    if (root == null || windowManager == null || !analyzerEnabled) {
        return;
    }

    clearTripButtons();

    ArrayList<TripCandidate> cards = new ArrayList<>();

    collectTripCandidates(root, cards);

    int index = 0;

    for (TripCandidate candidate : cards) {

        if (candidate == null || candidate.text == null) {
            continue;
        }

        String normalized =
                normalizeDigits(candidate.text)
                        .toLowerCase(Locale.ROOT)
                        .trim();

        if (normalized.length() < 5) {
            continue;
        }

        int color = getTripColor(normalized);

        if (color == Color.TRANSPARENT) {
            continue;
        }

        addTripIndicator(
                color,
                candidate.bounds,
                index
        );

        index++;

        if (index >= 6) {
            break;
        }
    }
}

private void collectTripCandidates(
        AccessibilityNodeInfo node,
        ArrayList<TripCandidate> cards) {

    if (node == null) {
        return;
    }

    /*
     * ابتدا فرزندان را بررسی می‌کنیم تا پایین‌ترین Node مناسب
     * به عنوان کارت انتخاب شود و اطلاعات چند کارت با هم ترکیب نشود.
     */
    boolean childHasCard = false;

    for (int i = 0; i < node.getChildCount(); i++) {

        int before = cards.size();

        collectTripCandidates(
                node.getChild(i),
                cards
        );

        if (cards.size() > before) {
            childHasCard = true;
        }
    }

    if (childHasCard) {
        return;
    }

    StringBuilder local = new StringBuilder();

    collect(node, local);

    String text = local.toString();

    if (text.length() < 5 || text.length() > 1200) {
        return;
    }

    String normalized =
            normalizeDigits(text)
                    .toLowerCase(Locale.ROOT)
                    .trim();

    boolean hasFare =
            normalized.contains("تومان") ||
            normalized.contains("تومن") ||
            normalized.contains("ریال");

    boolean hasDistance =
            normalized.matches(
                    "(?s).*\\d+(?:[\\.,]\\d+)?\\s*" +
                    "(?:km|کیلومتر|کيلومتر|متر|m).*"
            );

    if (!hasFare || !hasDistance) {
        return;
    }

    Rect bounds = new Rect();

    try {
        node.getBoundsInScreen(bounds);
    } catch (Exception ignored) {
    }

    /*
     * کارت‌های بدون موقعیت معتبر را وارد تحلیل نمی‌کنیم.
     */
    if (bounds.width() <= 0 || bounds.height() <= 0) {
        return;
    }

    cards.add(
            new TripCandidate(
                    text,
                    new Rect(bounds)
            )
    );

    Log.d(
            "SnapFloatDebug",
            "CARD_CANDIDATE_BOUNDS=" +
            bounds.left + "," +
            bounds.top + "," +
            bounds.right + "," +
            bounds.bottom +
            " TEXT=" + normalized
    );
}

private int getTripColor(String rawText) {

    if (rawText == null || rawText.trim().isEmpty()) {
        return Color.TRANSPARENT;
    }

    String text = normalizeDigits(rawText)
            .toLowerCase(Locale.ROOT);

    Long fare = findFare(text);

    if (fare == null || fare <= 0) {
        Log.d(
                "SnapFloatDebug",
                "COLOR_SKIP_NO_FARE TEXT=" + text
        );
        return Color.TRANSPARENT;
    }

    ArrayList<Double> distances = new ArrayList<>();

    Matcher distanceMatcher = Pattern.compile(
            "(\\d+(?:[\\.,]\\d+)?)\\s*" +
            "(km|کیلومتر|کيلومتر|متر|m)"
    ).matcher(text);

    while (distanceMatcher.find()) {

        try {

            double value = Double.parseDouble(
                    distanceMatcher.group(1)
                            .replace(',', '.')
            );

            String unit = distanceMatcher.group(2);

            if (unit.equals("متر") || unit.equals("m")) {
                value = value / 1000.0;
            }

            if (value > 0 && value <= 100) {
                distances.add(value);
            }

        } catch (Exception ignored) {
        }
    }

    if (distances.isEmpty()) {

        Log.d(
                "SnapFloatDebug",
                "COLOR_SKIP_NO_DISTANCE FARE=" + fare +
                " TEXT=" + text
        );

        return Color.TRANSPARENT;
    }

    /*
     * فاصله‌های تکراری را حذف می‌کنیم.
     */
    ArrayList<Double> uniqueDistances = new ArrayList<>();

    for (Double d : distances) {

        boolean exists = false;

        for (Double u : uniqueDistances) {

            if (Math.abs(u - d) < 0.0005) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            uniqueDistances.add(d);
        }
    }

    if (uniqueDistances.isEmpty()) {
        return Color.TRANSPARENT;
    }

    /*
     * در کارت اسنپ:
     *
     * فاصله اول = فاصله تا مبدأ
     * فاصله دوم = مسافت سفر
     *
     * اگر دو فاصله داریم، فاصله دوم را استفاده می‌کنیم.
     * اگر فقط یک فاصله داریم، همان فاصله استفاده می‌شود.
     */
    double tripKm;

    if (uniqueDistances.size() >= 2) {
        tripKm = uniqueDistances.get(1);
    } else {
        tripKm = uniqueDistances.get(0);
    }

    if (tripKm <= 0) {
        return Color.TRANSPARENT;
    }

    double farePerKm = (double) fare / tripKm;

    boolean blue = farePerKm >= 12000.0;

    Log.d(
            "SnapFloatDebug",
            "FINAL_CARD" +
            " FARE=" + fare +
            " DISTANCES=" + uniqueDistances +
            " TRIP_KM=" + tripKm +
            " FARE_PER_KM=" + farePerKm +
            " COLOR=" + (blue ? "BLUE" : "BLACK") +
            " TEXT=" + text
    );

    if (blue) {
        return Color.BLUE;
    }

    return Color.BLACK;
}


private void addTripIndicator(
        int color,
        Rect cardBounds,
        int index) {

    try {

        Button button = new Button(this);

        button.setText("");

        GradientDrawable background =
                new GradientDrawable();

        background.setShape(
                GradientDrawable.OVAL
        );

        background.setColor(color);

        button.setBackground(background);

        WindowManager.LayoutParams p =
                new WindowManager.LayoutParams(
                        55,
                        55,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                );

        /*
         * نشانگر مستقیماً بر اساس موقعیت همان کارت قرار می‌گیرد.
         * دیگر از index برای تعیین محل کارت استفاده نمی‌شود.
         */
        p.gravity = Gravity.TOP | Gravity.START;

        if (cardBounds != null &&
                cardBounds.width() > 0 &&
                cardBounds.height() > 0) {

            p.x = Math.max(
                    5,
                    cardBounds.left - 65
            );

            p.y = Math.max(
                    5,
                    cardBounds.top +
                    (cardBounds.height() / 2) -
                    27
            );

        } else {

            /*
             * فقط در صورت نبودن Bounds معتبر،
             * موقعیت قدیمی به عنوان fallback استفاده می‌شود.
             */
            p.x = 25;
            p.y = 410 + (index * 70);
        }

        windowManager.addView(
                button,
                p
        );

        tripButtons.add(button);
        tripParams.add(p);

        Log.d(
                "SnapFloatDebug",
                "INDICATOR color=" +
                (color == Color.BLUE ? "BLUE" : "BLACK") +
                " index=" + index +
                " x=" + p.x +
                " y=" + p.y
        );

    } catch (Exception e) {
        Log.e(
                "SnapFloatDebug",
                "INDICATOR_ADD_FAILED index=" + index +
                " color=" + (color == Color.BLUE ? "BLUE" : "BLACK") +
                " bounds=" + cardBounds,
                e
        );
    }
}

private void analyzeTrip(String rawText) {
    /*
     * تحلیل اصلی اکنون توسط analyzeTripCards انجام می‌شود.
     * این متد فقط برای سازگاری با Runnable قبلی نگه داشته شده است.
     */
    if (floatingButton == null || !analyzerEnabled) {
        return;
    }

    try {
        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root != null) {
            analyzeTripCards(root);
        }
    } catch (Exception ignored) {
    }
}

private Long findFare(
            String text) {

        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        /*
         * Accessibility ممکن است ترتیب متن را به شکل‌های مختلف برگرداند.
         * بنابراین اولین عدد بعد/قبل از «تومان» را کرایه فرض نمی‌کنیم.
         * همه مبالغ را پیدا می‌کنیم و بزرگ‌ترین مبلغ معتبر را انتخاب می‌کنیم.
         */

        ArrayList<Long> fares = new ArrayList<>();

        Pattern beforeCurrency = Pattern.compile(
                "(?:تومان|تومن|ریال)\s*" +
                "([0-9][0-9,\.\s]*)"
        );

        Matcher m1 = beforeCurrency.matcher(text);

        while (m1.find()) {
            try {
                String number = m1.group(1)
                        .replace(",", "")
                        .replace(".", "")
                        .replace(" ", "");

                if (!number.isEmpty()) {
                    long value = Long.parseLong(number);

                    if (value > 0 && value <= 1000000000L) {
                        fares.add(value);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        Pattern afterCurrency = Pattern.compile(
                "([0-9][0-9,\.\s]*)\s*" +
                "(?:تومان|تومن|ریال)"
        );

        Matcher m2 = afterCurrency.matcher(text);

        while (m2.find()) {
            try {
                String number = m2.group(1)
                        .replace(",", "")
                        .replace(".", "")
                        .replace(" ", "");

                if (!number.isEmpty()) {
                    long value = Long.parseLong(number);

                    if (value > 0 && value <= 1000000000L) {
                        fares.add(value);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (fares.isEmpty()) {
            return null;
        }

        long maxFare = 0;

        for (Long fare : fares) {
            if (fare != null && fare > maxFare) {
                maxFare = fare;
            }
        }

        Log.d(
                "SnapFloatDebug",
                "FARE_CANDIDATES=" + fares +
                " SELECTED_FARE=" + maxFare
        );

        return maxFare > 0 ? maxFare : null;
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
