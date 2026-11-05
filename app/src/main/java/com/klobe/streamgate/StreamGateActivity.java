package com.klobe.streamgate;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class StreamGateActivity extends Activity {
    private static final String HOME = "https://ppv.tj/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> blockedHosts = new HashSet<>();

    private final GateChrome chrome = new GateChrome();
    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String shieldJs = "";
    private boolean documentStartScript;
    private long lastToast;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadBlockedHosts();
        // The stylesheet is embedded as a JSON-safe string for tv.js to apply.
        shieldJs = readAsset("shield.js") + "\nwindow.__sgCss=" + JSONObject.quote(readAsset("tv.css"))
                + ";\n" + readAsset("tv.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        createWebView();
        web.loadUrl(HOME);

    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(true);   // Routes every popup through onCreateWindow.
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Players can refuse to run when the browser identifies itself as an app.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "")
                .replaceAll("Version/[0-9.]+ ", ""));

        web.addJavascriptInterface(new NativeBridge(), "SGNative");
        web.setWebViewClient(new GateClient());
        web.setWebChromeClient(chrome);
        // No DownloadListener is set, so forced downloads are ignored.

        documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (documentStartScript) {
            WebViewCompat.addDocumentStartJavaScript(web, shieldJs, Collections.singleton("*"));
        }
        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestFocus();
    }

    /** Stays on the site, and returns nothing for requests to ad servers. */
    private class GateClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            boolean allow = req.isForMainFrame()
                    ? Policy.allowTopLevel(u.getScheme(), u.getHost())
                    : Policy.allowSubframe(u.getScheme(), u.getHost(), blockedHosts);
            if (!allow && req.hasGesture()) notifyBlocked(u);
            return !allow;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            if (!Policy.isBlocked(req.getUrl().getHost(), blockedHosts)) return null;
            return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                    Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                    new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (!documentStartScript) view.evaluateJavascript(shieldJs, null);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Recover from a crashed page instead of closing the app.
            root.removeView(web);
            web.destroy();
            createWebView();
            web.loadUrl(HOME);
            return true;
        }
    }

    private class GateChrome extends WebChromeClient {
        /**
         * Popups load into a throwaway WebView just long enough to learn their
         * URL. Links to the site open in the main view; everything else is dropped.
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message msg) {
            final WebView probe = new WebView(StreamGateActivity.this);
            final boolean[] done = {false};
            final Runnable finish = () -> {
                if (!done[0]) { done[0] = true; probe.destroy(); }
            };
            probe.setWebViewClient(new WebViewClient() {
                private void resolve(Uri u) {
                    if (done[0]) return;
                    if (Policy.allowTopLevel(u.getScheme(), u.getHost())) web.loadUrl(u.toString());
                    else if (gesture) notifyBlocked(u);
                    handler.post(finish);
                }
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    resolve(r.getUrl());
                    return true;
                }
                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                    if (url != null && !url.startsWith("about:")) resolve(Uri.parse(url));
                }
            });
            ((WebView.WebViewTransport) msg.obj).setWebView(probe);
            msg.sendToTarget();
            handler.postDelayed(finish, 5000);
            return true;
        }

        @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
        @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
        @Override public boolean onJsPrompt(WebView v, String u, String m, String d, JsPromptResult r) { r.cancel(); return true; }
        @Override public boolean onJsBeforeUnload(WebView v, String u, String m, JsResult r) { r.confirm(); return true; }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            root.addView(view, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
        }
    }

    /** Remote presses go straight to the TV interface, whichever frame has focus. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    return sendKey(down, "up");
            case KeyEvent.KEYCODE_DPAD_DOWN:  return sendKey(down, "down");
            case KeyEvent.KEYCODE_DPAD_LEFT:  return sendKey(down, "left");
            case KeyEvent.KEYCODE_DPAD_RIGHT: return sendKey(down, "right");
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return sendKey(down && e.getRepeatCount() == 0, "ok");
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) tapCenter();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) goBack();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private boolean sendKey(boolean send, String key) {
        if (send) web.evaluateJavascript("window.__sgKey&&__sgKey('" + key + "')", null);
        return true;
    }

    private void goBack() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        web.evaluateJavascript("window.__sgBack?__sgBack():'history'", result -> {
            if ("\"handled\"".equals(result)) return;
            if (!"\"exit\"".equals(result) && web.canGoBack()) web.goBack();
            else finish();
        });
    }

    /** Some players need one real tap before they start. */
    private void tapCenter() {
        float x = root.getWidth() / 2f;
        float y = root.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent ev = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            root.dispatchTouchEvent(ev);
            ev.recycle();
        }
    }

    /** Exposed to page scripts, so it offers nothing beyond a tap on the player. */
    private class NativeBridge {
        @JavascriptInterface
        public void tap() {
            handler.post(StreamGateActivity.this::tapCenter);
        }
    }

    private void notifyBlocked(Uri u) {
        long now = SystemClock.uptimeMillis();
        if (now - lastToast < 3000) return;
        lastToast = now;
        handler.post(() -> Toast.makeText(this, "Blocked: " + u.getHost(), Toast.LENGTH_SHORT).show());
    }

    private void loadBlockedHosts() {
        for (String line : readAsset("blocked_hosts.txt").split("\n")) {
            String h = line.trim().toLowerCase();
            if (!h.isEmpty() && !h.startsWith("#")) blockedHosts.add(h);
        }
    }

    private String readAsset(String name) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
        return out.toString();
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
