package com.fason.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import com.fason.app.R;
import com.fason.app.core.permissions.OemAutoStartHelper;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.core.permissions.RestrictedPermissionHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class PermissionSetupController {
    private static final String TAG = "PermSetup";
    public static final int PERM_REQ = 1001;
    private static final int S_NA     = 0;
    private static final int S_DONE   = 1;
    private static final int S_DENIED = 2;
    private static final int S_NEED   = 3;
    private int[] dotColor;
    private int[] labelColor;

    private static final char[] DOT_CHAR = {
        '\u2014', '\u2713', '\u2717', '\u25CF'
    };
    private void resolveThemeColors() {
        int textColorPrimary = resolveColor(
            android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int textColorSecondary = resolveColor(
            android.R.attr.textColorSecondary, 0xFFAAAAAA);
        int accentColor = resolveColor(
            android.R.attr.colorAccent, 0xFF4FC3F7);
        dotColor = new int[] {
            textColorSecondary,
            0xFF4CAF50,
            0xFFFF5252,
            accentColor,
        };
        labelColor = new int[] {
            textColorSecondary,
            textColorSecondary,
            0xFFFF6B6B,
            textColorPrimary,
        };
    }

    private int resolveColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(attr, tv, true)) {
            try {
                if (tv.resourceId != 0) {
                    return androidx.core.content.ContextCompat
                        .getColor(activity, tv.resourceId);
                }
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static final class Gate {
        final String label;
        final Predicate<Activity> isGranted;
        final Predicate<Activity> isApplicable;
        final GateOpener open;
        Gate(String label,
             Predicate<Activity> isGranted,
             Predicate<Activity> isApplicable,
             GateOpener open) {
            this.label = label;
            this.isGranted = isGranted;
            this.isApplicable = isApplicable;
            this.open = open;
        }
    }

    @FunctionalInterface
    private interface GateOpener {
        boolean open(Activity act, PermissionSetupController ctrl, int gateIndex);
    }

    private final List<Gate> gates;

    {
        List<Gate> list = new ArrayList<>();
        list.add(new Gate(
            "Permissions",
            PermissionManager::hasAllPerms,
            act -> true,
            (act, ctrl, idx) -> ctrl.openRuntimeGate(idx)));
        list.add(new Gate(
            "Storage",
            ctx -> PermissionManager.hasStorageManager(),
            act -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasStorageManager()) return false;
                try {
                    if (PermissionManager.requestStorageManager(act)) {
                        ctrl.gatePrompted[idx] = true;
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "Storage settings failed", e);
                    return false;
                }
            }));
        list.add(new Gate(
            "Battery",
            PermissionManager::hasBatteryExemption,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasBatteryExemption(act)) return false;
                try {
                    if (PermissionManager.requestBatteryExemption(act)) {
                        ctrl.gatePrompted[idx] = true;
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "Battery settings failed", e);
                    return false;
                }
            }));
        list.add(new Gate(
            "Auto-Start",
            PermissionManager::hasAutoStartAccess,
            PermissionManager::needsAutoStart,
            (act, ctrl, idx) -> ctrl.openAutoStartGate(idx)));
        list.add(new Gate(
            "Notifications",
            PermissionManager::hasNotifAccess,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasNotifAccess(act)) return false;
                try {
                    if (PermissionManager.requestNotifAccess(act)) {
                        ctrl.gatePrompted[idx] = true;
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "Notification settings failed", e);
                    return false;
                }
            }));
        list.add(new Gate(
            "Accessibility",
            PermissionManager::hasAccessibilityAccess,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasAccessibilityAccess(act)) return false;
                try {
                    if (PermissionManager.requestAccessibilityAccess(act)) {
                        ctrl.gatePrompted[idx] = true;
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "Accessibility settings failed", e);
                    return false;
                }
            }));
        gates = list;
    }

    private final Activity activity;
    private View          permOverlay;
    private TextView      permTitle;
    private TextView      permSubtitle;
    private ProgressBar   permProgress;
    private FrameLayout   permBtnContainer;
    private Button        grantButton;
    private TextView[]    rowDots;
    private TextView[]    rowLabels;
    private TextView[]    rowDetails;
    private boolean[] gatePrompted;
    private int runtimeGateIndex = -1;
    private boolean waitingForRuntimeResult = false;
    private boolean justOpenedRestrictedSettings = false;
    private boolean isFirstLaunch = true;

    public PermissionSetupController(@NonNull Activity activity) {
        this.activity = activity;
        this.gatePrompted = new boolean[gates.size()];
        this.rowDots    = new TextView[gates.size()];
        this.rowLabels  = new TextView[gates.size()];
        this.rowDetails = new TextView[gates.size()];
        for (int i = 0; i < gates.size(); i++) {
            if ("Permissions".equals(gates.get(i).label)) {
                runtimeGateIndex = i;
                break;
            }
        }
    }

    public void onCreate(Bundle savedInstanceState) {
        initOverlay();
        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
            isFirstLaunch = false;
            updateOverlay();
        } else {
            isFirstLaunch = true;
        }
    }

    public void onResume() {
        if (waitingForRuntimeResult && runtimeGateIndex >= 0) {
            waitingForRuntimeResult = false;
        }
        updateOverlay();
    }

    public void onRequestPermissionsResult(int requestCode) {
        if (requestCode == PERM_REQ && runtimeGateIndex >= 0) {
            waitingForRuntimeResult = false;
            gatePrompted[runtimeGateIndex] = true;
        }
    }

    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putBooleanArray("psc_gatePrompted", gatePrompted);
        out.putBoolean("psc_waitingRuntime", waitingForRuntimeResult);
    }

    public void autoStartFirstMissing() {
        if (isFirstLaunch) {
            isFirstLaunch = false;
            if (hasMissingGate()) {
                showOverlay();
                requestNextMissing();
            }
        }
    }

    private boolean hasMissingGate() {
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) continue;
            if (!g.isGranted.test(activity)) return true;
        }
        return false;
    }

    private void initOverlay() {
        resolveThemeColors();
        permOverlay      = activity.findViewById(R.id.permOverlay);
        permTitle        = activity.findViewById(R.id.permTitle);
        permSubtitle     = activity.findViewById(R.id.permSubtitle);
        permProgress     = activity.findViewById(R.id.permProgress);
        permBtnContainer = activity.findViewById(R.id.permBtnContainer);
        LinearLayout list = activity.findViewById(R.id.permList);
        int dp8  = dp(8);
        int dp12 = dp(12);
        for (int i = 0; i < gates.size(); i++) {
            LinearLayout rowContainer = new LinearLayout(activity);
            rowContainer.setOrientation(LinearLayout.VERTICAL);
            rowContainer.setPadding(0, dp8, 0, dp8);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dot = new TextView(activity);
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dot.setTextColor(dotColor[S_NEED]);
            dot.setText(String.valueOf(DOT_CHAR[S_NEED]));
            row.addView(dot);
            TextView label = new TextView(activity);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(labelColor[S_NEED]);
            label.setText(gates.get(i).label);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp12;
            label.setLayoutParams(lp);
            row.addView(label);
            TextView detail = new TextView(activity);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            detail.setTextColor(labelColor[S_NA]);
            detail.setVisibility(View.GONE);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            detailLp.leftMargin = dp12 + (int) (16 * activity.getResources().getDisplayMetrics().density);
            detailLp.topMargin = dp(2);
            detail.setLayoutParams(detailLp);
            rowContainer.addView(row);
            rowContainer.addView(detail);
            list.addView(rowContainer);
            rowDots[i]    = dot;
            rowLabels[i]  = label;
            rowDetails[i] = detail;
        }
        grantButton = new Button(activity);
        grantButton.setText("Grant Permissions");
        grantButton.setTextColor(Color.WHITE);
        grantButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        grantButton.setAllCaps(false);
        grantButton.setBackground(makeButtonBg());
        grantButton.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        grantButton.setLayoutParams(btnLp);
        grantButton.setOnClickListener(v -> requestNextMissing());
        permBtnContainer.addView(grantButton);
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7));
        return gd;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value,
            activity.getResources().getDisplayMetrics());
    }

    private void updateOverlay() {
        if (permOverlay == null) return;
        int applicableCount = 0;
        int grantedCount   = 0;
        int deniedCount    = 0;
        int firstMissingIdx = -1;
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) {
                setRowState(i, S_NA);
                setRowDetail(i, null);
                continue;
            }
            applicableCount++;
            if (g.isGranted.test(activity)) {
                setRowState(i, S_DONE);
                setRowDetail(i, null);
                grantedCount++;
            } else if (gatePrompted[i]) {
                setRowState(i, S_DENIED);
                setRowDetail(i, getGateDetail(i));
                deniedCount++;
                if (firstMissingIdx < 0) firstMissingIdx = i;
            } else {
                setRowState(i, S_NEED);
                setRowDetail(i, getGateDetail(i));
                if (firstMissingIdx < 0) firstMissingIdx = i;
            }
        }
        permProgress.setMax(applicableCount > 0 ? applicableCount : 1);
        permProgress.setProgress(grantedCount);
        if (grantedCount >= applicableCount) {
            permTitle.setText("All Set");
            permSubtitle.setText("All permissions granted");
            permOverlay.removeCallbacks(fadeOutRunnable);
            permOverlay.postDelayed(fadeOutRunnable, 800);
            return;
        }
        showOverlay();
        permTitle.setText("Permissions Required");
        permSubtitle.setText(deniedCount > 0
            ? grantedCount + " of " + applicableCount + " granted \u2014 tap to retry"
            : "Tap below to grant each permission");
        if (firstMissingIdx >= 0) {
            grantButton.setText("Grant " + gates.get(firstMissingIdx).label);
            grantButton.setVisibility(View.VISIBLE);
            permBtnContainer.setVisibility(View.VISIBLE);
        } else {
            grantButton.setVisibility(View.GONE);
            permBtnContainer.setVisibility(View.GONE);
        }
    }

    private void setRowState(int idx, int state) {
        rowDots[idx].setTextColor(dotColor[state]);
        rowDots[idx].setText(String.valueOf(DOT_CHAR[state]));
        rowLabels[idx].setTextColor(labelColor[state]);
    }

    private void setRowDetail(int idx, String text) {
        if (rowDetails[idx] == null) return;
        if (text == null || text.isEmpty()) {
            rowDetails[idx].setVisibility(View.GONE);
        } else {
            rowDetails[idx].setText(text);
            rowDetails[idx].setVisibility(View.VISIBLE);
        }
    }

    private String getGateDetail(int gateIndex) {
        String label = gates.get(gateIndex).label;
        if ("Permissions".equals(label)) {
            List<String> denied = PermissionManager.getDeniedPerms(activity);
            if (denied.isEmpty()) return null;
            List<String> restricted = RestrictedPermissionHelper.getRestrictedPerms(activity);
            if (!restricted.isEmpty()) {
                StringBuilder sb = new StringBuilder("Restricted: ");
                for (int i = 0; i < restricted.size() && i < 3; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(restricted.get(i));
                }
                if (restricted.size() > 3) sb.append(" +").append(restricted.size() - 3);
                sb.append(" \u2014 open Settings to allow");
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder("Missing: ");
            for (int i = 0; i < denied.size() && i < 3; i++) {
                if (i > 0) sb.append(", ");
                String p = denied.get(i);
                int dot = p.lastIndexOf('.');
                sb.append(dot >= 0 ? p.substring(dot + 1) : p);
            }
            if (denied.size() > 3) sb.append(" +").append(denied.size() - 3);
            return sb.toString();
        }
        return null;
    }

    private void showOverlay() {
        permOverlay.removeCallbacks(fadeOutRunnable);
        if (permOverlay.getVisibility() != View.VISIBLE) {
            permOverlay.setAlpha(1f);
            permOverlay.setVisibility(View.VISIBLE);
        }
    }

    private final Runnable fadeOutRunnable = () -> {
        if (permOverlay != null) {
            permOverlay.animate().cancel();
            permOverlay.animate().alpha(0f).setDuration(400)
                .withEndAction(() -> {
                    if (permOverlay != null) permOverlay.setVisibility(View.GONE);
                });
        }
    };
    private void requestNextMissing() {
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) continue;
            if (g.isGranted.test(activity))      continue;
            boolean opened = g.open.open(activity, this, i);
            if (opened) return;
        }
        updateOverlay();
    }

    private boolean openRuntimeGate(int gateIndex) {
        if (PermissionManager.hasAllPerms(activity)) return false;
        if (justOpenedRestrictedSettings) {
            justOpenedRestrictedSettings = false;
            Log.d(TAG, "Returned from settings, retrying perms");
            try {
                PermissionManager.requestPerms(activity, PERM_REQ);
                gatePrompted[gateIndex] = true;
                waitingForRuntimeResult = true;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "requestPerms failed after settings", e);
            }
        }
        if (!gatePrompted[gateIndex]) {
            try {
                PermissionManager.requestPerms(activity, PERM_REQ);
                gatePrompted[gateIndex] = true;
                waitingForRuntimeResult = true;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "requestPerms failed, opening app settings", e);
                PermissionManager.openAppSettings(activity);
                return true;
            }
        }
        if (RestrictedPermissionHelper.hasRestrictedPerms(activity)) {
            Log.w(TAG, "Restricted perms, opening settings");
            justOpenedRestrictedSettings = true;
            RestrictedPermissionHelper.openAppSettingsForRestricted(activity);
            return true;
        }
        List<String> rationale = PermissionManager.getRationalePerms(activity);
        if (!rationale.isEmpty()) {
            try {
                ActivityCompat.requestPermissions(
                    activity, rationale.toArray(new String[0]), PERM_REQ);
                waitingForRuntimeResult = true;
            } catch (Exception e) {
                Log.w(TAG, "Retry failed, opening settings", e);
                PermissionManager.openAppSettings(activity);
            }
        } else {
            PermissionManager.openAppSettings(activity);
        }
        return true;
    }

    private boolean openAutoStartGate(int gateIndex) {
        OemAutoStartHelper.AutoStartResult result =
            PermissionManager.requestAutoStart(activity);
        switch (result) {
            case OPENED_AUTOSTART:
                gatePrompted[gateIndex] = true;
                PermissionManager.markAutoStartVisited(activity);
                return true;
            case OPENED_APP_DETAILS:
                Log.w(TAG, "Auto-start fell back to app-details");
                return true;
            case FAILED:
                Log.w(TAG, "Auto-start failed");
                return false;
        }
        return false;
    }

    private void restoreState(@NonNull Bundle in) {
        boolean[] saved = in.getBooleanArray("psc_gatePrompted");
        if (saved != null) {
            System.arraycopy(saved, 0, gatePrompted, 0,
                Math.min(saved.length, gatePrompted.length));
        }
        waitingForRuntimeResult = in.getBoolean("psc_waitingRuntime", false);
    }
}
