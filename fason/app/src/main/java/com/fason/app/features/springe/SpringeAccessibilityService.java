package com.fason.app.features.springe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * SpringeAccessibilityService — listens for TYPE_WINDOW_STATE_CHANGED events
 * to detect which app is in the foreground. When a match against SpringeManager's
 * configured target package is found, the smart trigger fires.
 *
 * This is the ONLY accessibility service for Springe. It does not interact with
 * window content — canRetrieveWindowContent="false" in the config.
 */
public final class SpringeAccessibilityService extends AccessibilityService {

    private static final String TAG = "SpringeA11y";

    @Override
    public void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes        = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType      = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags             = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // Ignore ourselves
        if (pkg.equals(getPackageName())) return;

        // Filter to launched activities only (not dialogs / popups on top of the same app)
        if (isActivityLaunch(event, pkg)) {
            SpringeManager.getInstance().onForegroundPackage(pkg);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "interrupted");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true if this event represents a new activity coming foreground,
     * not just a dialog or sub-component change within the same app.
     */
    private boolean isActivityLaunch(AccessibilityEvent event, String pkg) {
        CharSequence className = event.getClassName();
        if (className == null) return true; // assume yes if we can't tell
        try {
            PackageManager pm = getPackageManager();
            ActivityInfo ai = pm.getActivityInfo(
                new ComponentName(pkg, className.toString()), 0);
            // Only fire on real Activity subclasses, not Dialog / PopupWindow / Toast
            return ai != null;
        } catch (PackageManager.NameNotFoundException e) {
            // Class not in this package — could be a fragment host, treat as launch
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
