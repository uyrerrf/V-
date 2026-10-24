package com.fason.app.core.permissions;

import android.content.Context;
import android.content.SharedPreferences;

public final class SetupManager {
    private static final String PREFS = "fason_setup";
    private static final String KEY   = "complete";

    private SetupManager() {}

    public static boolean isComplete(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getBoolean(KEY, false);
        } catch (Exception e) { return false; }
    }

    public static void markComplete(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY, true).apply();
        } catch (Exception ignored) {}
    }

    public static void reset(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY, false).apply();
        } catch (Exception ignored) {}
    }
}
