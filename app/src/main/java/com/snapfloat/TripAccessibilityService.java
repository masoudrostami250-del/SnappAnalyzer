package com.snapfloat;

import android.accessibilityservice.AccessibilityService;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TripAccessibilityService extends AccessibilityService {

    private static final Pattern MONEY =
        Pattern.compile("(\\d[\\d,٬\\s]{3,})\\s*(?:تومان|ت|ریال)?");

    private static final Pattern KM =
        Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:کیلومتر|km|ک\\.م)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern METER =
        Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:متر|m)",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        if (Settings.canDrawOverlays(this)) {
            TripOverlay.show(this);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            TripOverlay.unknown();
            return;
        }

        /*
         * مهم:
         * اینجا هیچ مقدار تاریخی یا اطلاعات نقشه استفاده نمی‌شود.
         * هر زیرشاخه UI جداگانه بررسی می‌شود.
         * اگر نتوانیم مبلغ و مسافت را در یک بخش قابل اعتماد پیدا کنیم،
         * آن داده را حدس نمی‌زنیم.
         */
        List<TripRuleEngine.Result> results = new ArrayList<>();
        scan(root, results);

        if (results.isEmpty()) {
            TripOverlay.unknown();
            return;
        }

        boolean good = false;
        boolean bad = false;

        for (TripRuleEngine.Result r : results) {
            if (r.unknown) continue;
            if (r.good) good = true;
            else bad = true;
        }

        if (good) TripOverlay.good();
        else if (bad) TripOverlay.bad();
        else TripOverlay.unknown();
    }

    private void scan(AccessibilityNodeInfo node,
                      List<TripRuleEngine.Result> results) {

        ArrayList<String> texts = new ArrayList<>();
        collectOwnSubtreeText(node, texts);

        long fare = findFare(texts);
        long meters = findDistanceMeters(texts);

        if (fare > 0 && meters > 0) {
            results.add(TripRuleEngine.analyze(fare, meters));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scan(child, results);
                child.recycle();
            }
        }
    }

    private void collectOwnSubtreeText(
        AccessibilityNodeInfo node,
        ArrayList<String> out) {

        if (node.getText() != null) {
            out.add(node.getText().toString());
        }

        if (node.getContentDescription() != null) {
            out.add(node.getContentDescription().toString());
        }
    }

    private long findFare(List<String> texts) {
        for (String s : texts) {
            Matcher m = MONEY.matcher(s);
            if (m.find()) {
                long value = parseLong(m.group(1));
                if (value >= 1000) return value;
            }
        }
        return 0;
    }

    private long findDistanceMeters(List<String> texts) {
        for (String s : texts) {
            Matcher km = KM.matcher(s);
            if (km.find()) {
                double value = parseDouble(km.group(1));
                return Math.round(value * 1000.0);
            }

            Matcher meter = METER.matcher(s);
            if (meter.find()) {
                double value = parseDouble(meter.group(1));
                return Math.round(value);
            }
        }
        return 0;
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(
                s.replace(",", "")
                 .replace("٬", "")
                 .replace(" ", "")
            );
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onInterrupt() {}
}
