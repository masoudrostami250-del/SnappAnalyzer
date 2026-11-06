package com.snapincomelights;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IncomeAccessibilityService extends AccessibilityService {
    private static final String TAG = "IncomeLights";
    private static final String SNAPP = "cab.snapp.driver";
    private WindowManager wm;
    private LinearLayout lights;
    private final ArrayList<View> bulbs = new ArrayList<>();
    private ObjectAnimator whiteAnimator;
    private int activeStatus = 0; // 1 black, 2 green, 3 blue

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        boolean inSnapp = pkg != null && SNAPP.contentEquals(pkg);
        if (!inSnapp) {
            removeOverlay();
            return;
        }
        showOverlay();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder all = new StringBuilder();
        collectText(root, all);
        Long income = readNetIncome(all.toString());
        if (income != null && income >= 0) {
            if (income < 1_000_000L) activeStatus = 1;
            else if (income < 3_000_000L) activeStatus = 2;
            else activeStatus = 3;
            updateBulbs();
            Log.d(TAG, "NET_INCOME_TOMAN=" + income + " STATUS=" + activeStatus);
        }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null) out.append(' ').append(t);
        CharSequence d = node.getContentDescription();
        if (d != null) out.append(' ').append(d);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectText(child, out);
        }
    }

    private String digits(String s) {
        return s.replace('۰','0').replace('۱','1').replace('۲','2')
                .replace('۳','3').replace('۴','4').replace('۵','5')
                .replace('۶','6').replace('۷','7').replace('۸','8')
                .replace('۹','9').replace('٬',',').replace('٫','.');
    }

    private Long readNetIncome(String raw) {
        if (raw == null) return null;
        String text = digits(raw);
        int label = text.indexOf("درآمد خالص");
        if (label < 0) return null;
        String nearby = text.substring(label, Math.min(text.length(), label + 220));
        Pattern money = Pattern.compile("([0-9][0-9,\\.\\s]{2,})\\s*(تومان|تومن|ریال)?");
        Matcher m = money.matcher(nearby);
        while (m.find()) {
            String number = m.group(1).replace(",", "").replace(".", "").replace(" ", "");
            if (number.length() < 4) continue;
            try {
                long value = Long.parseLong(number);
                if ("ریال".equals(m.group(2))) value /= 10L;
                if (value >= 0 && value <= 10_000_000_000L) return value;
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private void showOverlay() {
        if (wm == null || (lights != null && lights.getWindowToken() != null)) return;
        try {
            lights = new LinearLayout(this);
            lights.setOrientation(LinearLayout.VERTICAL);
            lights.setGravity(Gravity.CENTER);
            int size = dp(52);
            int gap = dp(9);
            addBulb(Color.WHITE, size, gap);
            addBulb(Color.BLACK, size, gap);
            addBulb(Color.GREEN, size, gap);
            addBulb(Color.BLUE, size, gap);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    dp(66), dp(4 * 52 + 3 * 9 + 18),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.END;
            p.x = dp(8);
            p.y = dp(190);
            wm.addView(lights, p);
            whiteAnimator = ObjectAnimator.ofFloat(bulbs.get(0), View.ALPHA, 0.25f, 1f);
            whiteAnimator.setDuration(650);
            whiteAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            whiteAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            whiteAnimator.start();
            updateBulbs();
        } catch (Exception e) {
            Log.e(TAG, "overlay create failed", e);
            stopWhiteAnimation();
            lights = null;
            bulbs.clear();
        }
    }

    private void addBulb(int color, int size, int gap) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.bottomMargin = gap;
        v.setLayoutParams(lp);
        v.setBackground(circle(color));
        lights.addView(v);
        bulbs.add(v);
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), Color.GRAY);
        return d;
    }

    private void updateBulbs() {
        if (bulbs.size() != 4) return;
        bulbs.get(0).setVisibility(View.VISIBLE);
        int[] colors = {Color.BLACK, Color.GREEN, Color.BLUE};
        for (int i = 0; i < 3; i++) {
            View bulb = bulbs.get(i + 1);
            boolean selected = activeStatus == i + 1;
            bulb.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) bulb.setBackground(circle(colors[i]));
        }
    }

    private void stopWhiteAnimation() {
        if (whiteAnimator != null) {
            whiteAnimator.cancel();
            whiteAnimator = null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void removeOverlay() {
        stopWhiteAnimation();
        if (lights != null && wm != null) {
            try { wm.removeView(lights); } catch (Exception ignored) { }
        }
        lights = null;
        bulbs.clear();
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
