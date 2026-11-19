package com.snapfloat.voice;

import android.accessibilityservice.AccessibilityService;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Rect;
import java.util.Collections;
import java.util.Comparator;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.LinkedHashSet;

import com.snapfloat.R;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// v18: isolate each offer snapshot and reject OCR frames belonging to the previous card.
public class VoiceTripAccessibilityService extends AccessibilityService {
    private static final String TAG = "SnappVoice17000Pickup2km";
    private static final String SNAPP_PACKAGE = "cab.snapp.driver";
    private static final double MIN_TOMAN_PER_KM = 17000.0;
    private static final double MAX_PICKUP_KM = 2.0;
    private static final long ANALYZE_DELAY_MS = 200L;
    private static final long RESET_DELAY_MS = 7000L;
    private static final long MAX_OCR_AGE_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingEventText = new StringBuilder();
    private String lastSpokenKey = "";
    private long lastSpokenAtMs = 0L;
    private static final long SAME_TRIP_DEDUPE_MS = 120000L;
    private String lastOcrOfferSignature = "";
    private long lastOcrOfferCaptureTime = 0L;
    private String lastWindowSignature = "";
    private long currentOfferStartedAtMs = 0L;
    private String lastOfferIdentity = "";
    private String pendingPickupAddress = "";
    private String pendingDestinationAddress = "";
    private boolean routeAnalysisInProgress = false;
    private String lastRouteRequestKey = "";
    private int analysisGeneration = 0;
    private MediaPlayer player;
    // Once a Snapp event is seen, keep its report separately from analyzer-app events.
    private boolean snappContextSeen = false;

    private int analysisRetries = 0;
    private final Runnable analyzePending = () -> {
        // Every analysis uses one current offer snapshot. Never append OCR from a
        // different frame/card to accessibility text from this offer.
        String snapshot = pendingEventText.toString();
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("voice_settings", MODE_PRIVATE);
            boolean activeOffer = prefs.getBoolean("active_offer_visible", false);
            long offerSeenAt = prefs.getLong("active_offer_seen_at", 0L);
            String ocr = prefs.getString("screen_ocr_text", "");
            String positionedOcrLines = prefs.getString("screen_ocr_lines", "");
            long capturedAt = prefs.getLong("screen_ocr_time", 0L);
            long now = System.currentTimeMillis();
            boolean freshOcr = !ocr.trim().isEmpty()
                    && now - capturedAt >= 0L
                    && now - capturedAt <= MAX_OCR_AGE_MS
                    && currentOfferStartedAtMs > 0L
                    && capturedAt >= currentOfferStartedAtMs;
            if (!activeOffer || now - offerSeenAt > 5000L) { analysisRetries = 0; return; }

            Long snapshotFare = findFare(normalizeDigits(snapshot));
            Long ocrFare = freshOcr ? findFare(normalizeDigits(ocr)) : null;
            boolean sameOfferFrame = snapshotFare != null && ocrFare != null
                    && snapshotFare.longValue() == ocrFare.longValue();

            // OCR distances are accepted only when the fare in that exact OCR
            // frame matches the fare in the current accessibility snapshot.
            if (sameOfferFrame && !positionedOcrLines.trim().isEmpty()
                    && findDistances(normalizeDigits(ocr)).size() >= 2) {
                analysisRetries = 0;
                analyzeOffer(ocr, positionedOcrLines);
                return;
            }

            // If OCR is still catching up to a newly displayed card, wait for its
            // next frame instead of speaking a decision using the previous card.
            if (freshOcr && !sameOfferFrame && analysisRetries < 20) {
                analysisRetries++;
                scheduleAnalyzeRetry();
                return;
            }

            // Accessibility-only fallback is safe only from this single current
            // window snapshot; old partial event payloads are never accumulated.
            if (!snapshot.trim().isEmpty()
                    && findDistances(normalizeDigits(snapshot)).size() >= 2) {
                analysisRetries = 0;
                analyzeOffer(snapshot, "");
            } else if (analysisRetries < 20) {
                analysisRetries++;
                scheduleAnalyzeRetry();
            } else {
                analysisRetries = 0;
                saveDiagnostic("کارت سفر شناسایی شد اما اطلاعات همین کارت کامل نبود؛ برای جلوگیری از اعلام نتیجه اشتباه، سکوت شد.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read latest screen OCR text", e);
            analysisRetries = 0;
        }
    };
    private void scheduleAnalyzeRetry() {
        handler.postDelayed(analyzePending, 200L);
    }

    private final Runnable resetOffer = () -> {
        // Keep the last spoken trip key across temporary app/window focus changes.
        pendingEventText.setLength(0);
        lastWindowSignature = "";
        currentOfferStartedAtMs = 0L;
        lastOfferIdentity = "";
        pendingPickupAddress = "";
        pendingDestinationAddress = "";
        routeAnalysisInProgress = false;
        lastRouteRequestKey = "";
        analysisGeneration++;
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Female Persian offline audio service connected");
        saveDiagnostic("سرویس دسترسی‌پذیری متصل شد؛ منتظر صفحه اسنپ");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            String eventPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
            String rootPackage = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString() : "";
            boolean snappWindow = SNAPP_PACKAGE.equals(eventPackage) || SNAPP_PACKAGE.equals(rootPackage);
            if (!snappWindow) {
                getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                        .putString("last_any_accessibility_event", "رویداد غیر اسنپ | eventPackage=" + eventPackage + " | rootPackage=" + rootPackage + " | root=" + (root != null))
                        .apply();
                handler.removeCallbacks(analyzePending);
                scheduleOfferReset();
                return;
            }

            snappContextSeen = true;
            saveDiagnostic("رویداد اسنپ دریافت شد | eventPackage=" + eventPackage + " | rootPackage=" + rootPackage + " | root=" + (root != null));
            handler.removeCallbacks(resetOffer);
            StringBuilder collected = new StringBuilder();

            // Prefer one complete Snapp tree. Reading root + event source + windows
            // together duplicated the same distance labels and could shift the pair.
            boolean rootIsSnapp = root != null && root.getPackageName() != null
                    && SNAPP_PACKAGE.contentEquals(root.getPackageName());
            if (rootIsSnapp) {
                collectText(root, collected);
            } else {
                collectSnappWindows(collected);
            }
            // Use the event source only as a fallback when no Snapp window text exists.
            if (collected.length() == 0) {
                AccessibilityNodeInfo eventSource = null;
                try {
                    eventSource = event.getSource();
                    if (eventSource != null) collectText(eventSource, collected);
                } catch (Exception ignored) { }
                finally {
                    if (eventSource != null) try { eventSource.recycle(); } catch (Exception ignored) { }
                }
            }

            // Diagnostic-only branch: capture the actual accessibility node tree
            // so we can tell whether the distance widgets are exposed to Android.
            StringBuilder nodeDump = new StringBuilder();
            int[] nodeCount = new int[]{0};
            if (root != null) dumpNodeStructure(root, nodeDump, nodeCount);
            dumpSnappWindowStructures(nodeDump, nodeCount);
            if (nodeDump.length() > 0) {
                saveStructureDiagnostic("گزارش ساختار عناصر صفحه اسنپ (حداکثر 250 عنصر):\\n"
                        + nodeDump.substring(0, Math.min(nodeDump.length(), 14000)));
            }

            // Root/window text is the authoritative current screen snapshot.
            // Event payloads can describe a previous card during rapid transitions,
            // so use them only when no current window text could be read.
            if (collected.length() == 0) {
                if (event.getText() != null) {
                    for (CharSequence item : event.getText()) {
                        if (item != null && item.length() > 0) collected.append(' ').append(item);
                    }
                }
                if (event.getContentDescription() != null) {
                    collected.append(' ').append(event.getContentDescription());
                }
            }

            String current = collected.toString().trim();
            String currentPickupAddress = findAddressInSnappWindows(root, "originAddress");
            String currentDestinationAddress = findAddressInSnappWindows(root, "firstDestinationAddress");
            if (current.isEmpty()) {
                saveDiagnostic("پنجره اسنپ شناسایی شد اما متن قابل‌خواندن خالی است.");
                scheduleOfferReset();
                return;
            }

            // Snapp exposes the offer card through this stable resource ID.
            boolean activeOfferButton = hasVisibleNodeId(root, "acceptOfferButton") || hasVisibleAcceptButtonInSnappWindows();
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putBoolean("active_offer_visible", activeOfferButton)
                    .putLong("active_offer_seen_at", activeOfferButton ? System.currentTimeMillis() : 0L)
                    .apply();
            if (!activeOfferButton) {
                pendingEventText.setLength(0);
                handler.removeCallbacks(analyzePending);
                analysisRetries = 0;
                lastOcrOfferSignature = "";
                lastOcrOfferCaptureTime = 0L;
                saveDiagnostic("دکمه قبول سفر وجود ندارد؛ برنامه سکوت کرد.");
                return;
            }

            // Window changes separate old offer text from the next offer.
            String signature = current.length() > 180 ? current.substring(0, 180) : current;
            if (currentOfferStartedAtMs == 0L) {
                currentOfferStartedAtMs = System.currentTimeMillis();
            }
            if (!lastWindowSignature.isEmpty() && !signature.equals(lastWindowSignature)) {
                pendingEventText.setLength(0);
                lastOfferIdentity = "";
                pendingPickupAddress = "";
                pendingDestinationAddress = "";
                routeAnalysisInProgress = false;
                lastRouteRequestKey = "";
                currentOfferStartedAtMs = System.currentTimeMillis();
                analysisGeneration++;
            }
            lastWindowSignature = signature;
            if (!currentPickupAddress.trim().isEmpty()) pendingPickupAddress = currentPickupAddress.trim();
            if (!currentDestinationAddress.trim().isEmpty()) pendingDestinationAddress = currentDestinationAddress.trim();

            Long identityFare = findFare(normalizeDigits(current));
            if (identityFare != null && !currentPickupAddress.trim().isEmpty()
                    && !currentDestinationAddress.trim().isEmpty()) {
                String identity = identityFare + "|" + currentPickupAddress.trim()
                        + "|" + currentDestinationAddress.trim();
                if (!lastOfferIdentity.isEmpty() && !lastOfferIdentity.equals(identity)) {
                    pendingEventText.setLength(0);
                    routeAnalysisInProgress = false;
                    lastRouteRequestKey = "";
                    currentOfferStartedAtMs = System.currentTimeMillis();
                    analysisGeneration++;
                }
                lastOfferIdentity = identity;
            }

            saveDiagnostic("متن صفحه اسنپ دریافت شد:\n" + current.substring(0, Math.min(current.length(), 3500)));
            String normalizedCurrent = normalizeDigits(current);
            boolean completeSnapshot = findFare(normalizedCurrent) != null
                    && findDistances(normalizedCurrent).size() >= 2;
            // Replace the buffer on every event. Accumulating partial payloads can
            // mix fare/distances from adjacent offers when cards arrive back-to-back.
            pendingEventText.setLength(0);
            pendingEventText.append(current);
            handler.removeCallbacks(analyzePending);
            analysisRetries = 0;
            handler.postDelayed(analyzePending, ANALYZE_DELAY_MS);
        } catch (Exception e) {
            Log.e(TAG, "Could not read Snapp accessibility content", e);
            scheduleOfferReset();
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) { }
            }
        }
    }



    private boolean hasVisibleAcceptButtonInSnappWindows() {
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                    if (windowRoot == null) continue;
                    CharSequence pkg = windowRoot.getPackageName();
                    if (pkg != null && SNAPP_PACKAGE.contentEquals(pkg) && hasVisibleNodeId(windowRoot, "acceptOfferButton")) return true;
                } catch (Exception ignored) {
                } finally { if (windowRoot != null) try { windowRoot.recycle(); } catch (Exception ignored) { } }
            }
        } catch (Exception ignored) { }
        return false;
    }
    private boolean hasVisibleNodeId(AccessibilityNodeInfo node, String suffix) {
        if (node == null) return false;
        try {
            String id = node.getViewIdResourceName();
            if (id != null && (id.endsWith("/" + suffix) || id.equals(suffix))
                    && node.isVisibleToUser()) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        if (hasVisibleNodeId(child, suffix)) return true;
                    } finally {
                        try { child.recycle(); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private String findAddressInSnappWindows(AccessibilityNodeInfo root, String idSuffix) {
        String value = findAddressInNode(root, idSuffix);
        if (!value.isEmpty()) return value;
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            if (windows == null) return "";
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                    if (windowRoot == null) continue;
                    CharSequence pkg = windowRoot.getPackageName();
                    if (pkg == null || !SNAPP_PACKAGE.contentEquals(pkg)) continue;
                    value = findAddressInNode(windowRoot, idSuffix);
                    if (!value.isEmpty()) return value;
                } catch (Exception ignored) {
                } finally {
                    if (windowRoot != null) try { windowRoot.recycle(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String findAddressInNode(AccessibilityNodeInfo node, String idSuffix) {
        if (node == null) return "";
        try {
            String id = node.getViewIdResourceName();
            if (id != null && (id.endsWith("/" + idSuffix) || id.equals(idSuffix))
                    && node.isVisibleToUser()) {
                LinkedHashSet<String> parts = new LinkedHashSet<>();
                collectAddressText(node, parts);
                StringBuilder result = new StringBuilder();
                for (String part : parts) {
                    if (result.length() > 0) result.append(" ");
                    result.append(part);
                }
                if (result.length() > 0) return result.toString().trim();
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        String found = findAddressInNode(child, idSuffix);
                        if (!found.isEmpty()) return found;
                    } finally {
                        try { child.recycle(); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract address node " + idSuffix, e);
        }
        return "";
    }

    private void collectAddressText(AccessibilityNodeInfo node, LinkedHashSet<String> parts) {
        if (node == null) return;
        try {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) parts.add(text.toString().trim());
            CharSequence description = node.getContentDescription();
            if (description != null && description.length() > 0) parts.add(description.toString().trim());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try { collectAddressText(child, parts); }
                    finally { try { child.recycle(); } catch (Exception ignored) { } }
                }
            }
        } catch (Exception ignored) { }
    }

    private void collectSnappWindows(StringBuilder out) {
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                    if (windowRoot == null) continue;
                    CharSequence pkg = windowRoot.getPackageName();
                    if (pkg != null && SNAPP_PACKAGE.contentEquals(pkg)) {
                        collectText(windowRoot, out);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not inspect an interactive Snapp window", e);
                } finally {
                    if (windowRoot != null) try { windowRoot.recycle(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate interactive windows", e);
        }
    }


    private void dumpSnappWindowStructures(StringBuilder out, int[] count) {
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || count[0] >= 250) continue;
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                    if (windowRoot == null) continue;
                    CharSequence pkg = windowRoot.getPackageName();
                    if (pkg != null && SNAPP_PACKAGE.contentEquals(pkg)) {
                        out.append("\\n--- SNAPP WINDOW ---\\n");
                        dumpNodeStructure(windowRoot, out, count);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not dump Snapp window nodes", e);
                } finally {
                    if (windowRoot != null) try { windowRoot.recycle(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate windows for node dump", e);
        }
    }

    private void dumpNodeStructure(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0] >= 250 || out.length() >= 14000) return;
        try {
            count[0]++;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            CharSequence cls = node.getClassName();
            CharSequence pkg = node.getPackageName();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String viewId = "";
            try {
                String id = node.getViewIdResourceName();
                if (id != null) viewId = id;
            } catch (Exception ignored) { }
            out.append("#").append(count[0])
                    .append(" class=").append(cls == null ? "" : cls)
                    .append(" id=").append(viewId)
                    .append(" text=").append(text == null ? "" : text)
                    .append(" desc=").append(desc == null ? "" : desc)
                    .append(" bounds=").append(bounds.flattenToString())
                    .append(" children=").append(node.getChildCount())
                    .append(" click=").append(node.isClickable())
                    .append(" focus=").append(node.isFocusable())
                    .append(" visible=").append(node.isVisibleToUser())
                    .append(" pkg=").append(pkg == null ? "" : pkg)
                    .append('\n');
            for (int i = 0; i < node.getChildCount(); i++) {
                if (count[0] >= 250 || out.length() >= 14000) break;
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try { dumpNodeStructure(child, out, count); }
                    finally { try { child.recycle(); } catch (Exception ignored) { } }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "A node changed during diagnostic dump", e);
        }
    }

    private static class PositionedText {
        final int top;
        final String value;
        PositionedText(int top, String value) { this.top = top; this.value = value; }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        ArrayList<PositionedText> items = new ArrayList<>();
        collectPositioned(node, items);
        Collections.sort(items, Comparator.comparingInt(a -> a.top));
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (PositionedText item : items) {
            String unique = item.top + "|" + item.value;
            if (seen.add(unique)) out.append(' ').append(item.value);
        }
    }

    private void collectPositioned(AccessibilityNodeInfo node, ArrayList<PositionedText> out) {
        if (node == null) return;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int top = bounds.top;
            CharSequence value = node.getText();
            if (value != null && value.length() > 0) out.add(new PositionedText(top, value.toString()));
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) out.add(new PositionedText(top, desc.toString()));
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try { collectPositioned(child, out); }
                    finally { try { child.recycle(); } catch (Exception ignored) { } }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "A view changed while its text was being read", e);
        }
    }


    private void saveStructureDiagnostic(String report) {
        try {
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putString("last_snapp_structure", report)
                    .apply();
        } catch (Exception ignored) { }
    }

    private void saveDiagnostic(String report) {
        try {
            android.content.SharedPreferences.Editor editor =
                    getSharedPreferences("voice_settings", MODE_PRIVATE).edit();
            editor.putString("last_accessibility_report", report);
            if (snappContextSeen) editor.putString("last_snapp_report", report);
            editor.apply();
        } catch (Exception ignored) { }
    }

    private boolean hasPickupOfferMarker(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String value = raw.replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک');
        return Pattern.compile("تا\\s*م(?:بدا|بدأ)", Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    private String makeTripKey(long fare, double pickupKm, double destinationKm) {
        return fare + "|" + Math.round(pickupKm * 10.0)
                + "|" + Math.round(destinationKm * 10.0);
    }

    private static class PositionedDistance {
        final int top;
        final double km;
        PositionedDistance(int top, double km) { this.top = top; this.km = km; }
    }

    private ArrayList<Double> findDistancesByScreenPosition(String positionedLines) {
        ArrayList<PositionedDistance> found = new ArrayList<>();
        if (positionedLines == null || positionedLines.trim().isEmpty()) return new ArrayList<>();
        String[] lines = positionedLines.split("\\n");
        for (String line : lines) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) continue;
            try {
                int top = Integer.parseInt(parts[0].trim());
                ArrayList<Double> values = findDistances(normalizeDigits(parts[1]));
                if (!values.isEmpty()) found.add(new PositionedDistance(top, values.get(0)));
            } catch (Exception ignored) { }
        }
        Collections.sort(found, Comparator.comparingInt(item -> item.top));
        ArrayList<Double> result = new ArrayList<>();
        for (PositionedDistance item : found) {
            if (result.size() >= 2) break;
            result.add(item.km);
        }
        return result;
    }

    private void analyzeOffer(String raw, String positionedOcrLines) {
        String text = normalizeDigits(raw).toLowerCase(Locale.ROOT);
        Long fare = findFare(text);
        if (fare == null || fare <= 0) {
            saveDiagnostic("متن دریافت شد اما مبلغ کرایه تشخیص داده نشد. متن:\n" + raw.substring(0, Math.min(raw.length(), 3500)));
            scheduleOfferReset();
            return;
        }

        // Use actual vertical OCR coordinates: upper distance is pickup,
        // lower distance is the trip length to destination.
        ArrayList<Double> distances = findDistancesByScreenPosition(positionedOcrLines);
        String distanceSource = "OCR با ترتیب عمودی صفحه";
        if (distances.size() < 2) {
            distances = findDistances(text);
            distanceSource = "متن صفحه اسنپ";
        }
        if (distances.size() < 2) {
            saveDiagnostic("مبلغ سفر پیدا شد (" + fare + ") اما دو عدد کیلومتر از صفحه اسنپ خوانده نشد. "
                    + "این نسخه فقط از دو فاصله نمایش‌داده‌شده در خود اسنپ استفاده می‌کند و GPS یا نقشه ندارد. "
                    + "فاصله‌های خوانده‌شده=" + distances + "\nمتن صفحه:\n"
                    + raw.substring(0, Math.min(raw.length(), 3500)));
            scheduleOfferReset();
            return;
        }

        // The first (upper) visible distance is driver-to-pickup;
        // the second (lower) visible distance is pickup-to-destination.
        double pickupKm = distances.get(0);
        double destinationKm = distances.get(1);
        completeAnalysis(fare, pickupKm, destinationKm, distanceSource);
    }

    private void completeAnalysis(long fare, double pickupKm, double destinationKm, String source) {
        if (pickupKm <= 0 || destinationKm <= 0) {
            saveDiagnostic("فاصله مسیر معتبر نیست؛ هیچ تصمیمی اعلام نشد.");
            scheduleOfferReset();
            return;
        }
        double tomanPerKm = fare / destinationKm;
        boolean pickupAllowed = pickupKm <= MAX_PICKUP_KM;
        boolean fareAllowed = tomanPerKm >= MIN_TOMAN_PER_KM;
        String phrase = pickupAllowed && fareAllowed ? "برو" : "نرو";

        // Round distances to 0.1 km so minor OCR fluctuations do not re-announce one offer.
        String key = makeTripKey(fare, pickupKm, destinationKm);
        long now = System.currentTimeMillis();
        if (key.equals(lastSpokenKey) && now - lastSpokenAtMs < SAME_TRIP_DEDUPE_MS) {
            Log.i(TAG, "DUPLICATE_TRIP_SUPPRESSED key=" + key);
            return;
        }

        lastSpokenKey = key;
        lastSpokenAtMs = now;
        handler.removeCallbacks(resetOffer);
        saveDiagnostic("تحلیل کامل شد: " + phrase + " | روش=" + source + " | کرایه=" + fare
                + " | فاصله مبدأ/راننده=" + pickupKm + " کیلومتر"
                + " | فاصله مقصد=" + destinationKm + " کیلومتر"
                + " | نرخ مقصد=" + tomanPerKm + " تومان/کیلومتر");
        Log.i(TAG, "VOICE_RESULT=" + phrase + " source=" + source + " pickupKm=" + pickupKm
                + " destinationKm=" + destinationKm + " fare=" + fare
                + " tomanPerKm=" + tomanPerKm + " pickupAllowed=" + pickupAllowed
                + " fareAllowed=" + fareAllowed);
        playPhrase(phrase);
    }

    private Double findDistanceNearLabel(String text, String labelExpression) {
        Pattern p = Pattern.compile(labelExpression
                + "[^0-9]{0,70}([0-9]+(?:[.,][0-9]+)?)\\s*(km|کیلومتر|کيلومتر|متر(?!ی)|m)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
            return value > 0 && value <= 100 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArrayList<Double> findDistances(String text) {
        ArrayList<Double> distances = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "([0-9]+(?:[.,][0-9]+)?)\\s*(km|کیلومتر|کيلومتر|متر(?!ی)|m)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.equals("متر") || unit.equals("m")) value /= 1000.0;
                if (value > 0 && value <= 100) distances.add(value);
            } catch (Exception ignored) { }
        }
        return distances;
    }

    private Long findFare(String text) {
        // Snapp can leave a previous card's fare in the accessibility tree.
        // If the active card is explicitly marked as a suggested trip, read
        // the amount immediately after that marker instead of taking the
        // numerically largest amount from the whole screen.
        Matcher suggested = Pattern.compile("سفر\\s*پیشنهادی\\s*اسنپ").matcher(text);
        int markerEnd = -1;
        while (suggested.find()) markerEnd = suggested.end();
        if (markerEnd >= 0) {
            int end = Math.min(text.length(), markerEnd + 140);
            String activeSection = text.substring(markerEnd, end);
            ArrayList<Long> activeFares = new ArrayList<>();
            collectFares(Pattern.compile("([0-9][0-9,\\.\\s]*)\\s*(?:تومان|تومن|ریال)").matcher(activeSection), activeFares);
            if (activeFares.isEmpty()) {
                collectFares(Pattern.compile("(?:تومان|تومن|ریال)\\s*([0-9][0-9,\\.\\s]*)").matcher(activeSection), activeFares);
            }
            if (!activeFares.isEmpty()) return activeFares.get(0);
        }

        ArrayList<Long> fares = new ArrayList<>();
        collectFares(Pattern.compile("(?:تومان|تومن|ریال)\\s*([0-9][0-9,\\.\\s]*)").matcher(text), fares);
        collectFares(Pattern.compile("([0-9][0-9,\\.\\s]*)\\s*(?:تومان|تومن|ریال)").matcher(text), fares);
        if (fares.isEmpty()) {
            // Some layouts put the amount next to a fare label without repeating the currency.
            Matcher labelled = Pattern.compile("(?:مبلغ|کرایه|هزینه)[^0-9]{0,25}([0-9][0-9,\\.\\s]{2,})")
                    .matcher(text);
            while (labelled.find()) addFare(labelled.group(1), fares);
        }
        // When several amounts are present without an explicit active-card
        // marker, the last visible fare is more likely to belong to the
        // currently displayed offer than an older amount retained in the tree.
        return fares.isEmpty() ? null : fares.get(fares.size() - 1);
    }

    private void collectFares(Matcher matcher, ArrayList<Long> fares) {
        while (matcher.find()) addFare(matcher.group(1), fares);
    }

    private void addFare(String raw, ArrayList<Long> fares) {
        try {
            String number = raw.replace(",", "").replace(".", "").replace(" ", "");
            if (!number.isEmpty()) {
                long value = Long.parseLong(number);
                if (value > 0 && value <= 1000000000L) fares.add(value);
            }
        } catch (Exception ignored) { }
    }

    private String normalizeDigits(String value) {
        return value.replace('۰', '0').replace('۱', '1').replace('۲', '2')
                .replace('۳', '3').replace('۴', '4').replace('۵', '5')
                .replace('۶', '6').replace('۷', '7').replace('۸', '8')
                .replace('۹', '9').replace('٠', '0').replace('١', '1')
                .replace('٢', '2').replace('٣', '3').replace('٤', '4')
                .replace('٥', '5').replace('٦', '6').replace('٧', '7')
                .replace('٨', '8').replace('٩', '9')
                .replace('٫', '.').replace('٬', ',');
    }

    private void scheduleOfferReset() {
        handler.removeCallbacks(resetOffer);
        handler.postDelayed(resetOffer, RESET_DELAY_MS);
    }

    private void playPhrase(String phrase) {
        int sound = getVoiceResource("برو".equals(phrase));
        try {
            releasePlayer();
            player = MediaPlayer.create(this, sound);
            if (player == null) {
                Log.e(TAG, "Could not create female audio player for " + phrase);
                return;
            }
            // MediaPlayer.create() returns a prepared player. Do not change
            // its audio attributes after preparation; use the normal media route.
            player.setVolume(1.0f, 1.0f);
            // Play the existing Persian voice recording 30% faster without changing pitch.
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                player.setPlaybackParams(new PlaybackParams().setSpeed(1.30f).setPitch(1.0f));
            }
            player.setOnCompletionListener(mp -> releasePlayer());
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Audio playback error what=" + what + " extra=" + extra);
                releasePlayer();
                return true;
            });
            player.start();
        } catch (Exception e) {
            Log.e(TAG, "Female audio playback failed", e);
            releasePlayer();
        }
    }

    private int getVoiceResource(boolean go) {
        int choice = getSharedPreferences("voice_settings", MODE_PRIVATE).getInt("selected_voice", 0);
        switch (choice) {
            case 1: return go ? R.raw.voice1_go : R.raw.voice1_stop;
            case 2: return go ? R.raw.voice2_go : R.raw.voice2_stop;
            case 3: return go ? R.raw.voice3_go : R.raw.voice3_stop;
            default: return go ? R.raw.voice_go : R.raw.voice_stop;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
