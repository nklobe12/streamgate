package com.klobe.streamgate.fulu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class FuluActivity extends Activity {
    private static final String HOME = "https://cinejoy.pk/";
    private static final long CURSOR_HIDE_MS = 4000;

    private final AdBlocker blocker = new AdBlocker();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private PlayerUi player;
    private SharedPreferences prefs;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String guardJs;
    private boolean guardRunsEarly;
    private long lastBackAt;

    private long lastMoveAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        blocker.start(this);
        guardJs = readAsset("guard.js");

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        prefs = getSharedPreferences("cineshield", MODE_PRIVATE);
        player = new PlayerUi(this, root, new PlayerUi.Host() {
            @Override
            public void command(String name, double value) {
                sendPlayerCommand(name, value);
            }

            @Override
            public String title() {
                return web.getTitle();
            }

            @Override
            public void closed() {
                sendPlayerCommand("pause", 0);
                sendPlayerCommand("unfill", 0);
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Popups: nothing opens a window without passing onCreateWindow below.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(115);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "")
                .replaceAll("Version/[0-9.]+ ", ""));

        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Bridge(), "CineShieldBridge");
        web.setWebViewClient(new ShieldClient());
        web.setWebChromeClient(new ShieldChrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> notice("Blocked a download"));

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, guardJs, Collections.singleton("*"));
            guardRunsEarly = true;
        }

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notice(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static boolean isWeb(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                new ByteArrayInputStream(new byte[0]));
    }

    private final class ShieldClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();
            if (request.isForMainFrame()) {
                if (isWeb(uri) && AdBlocker.isSite(host)) return false;
                notice("Blocked redirect" + (host == null ? "" : ": " + host));
                return true;
            }
            // Video players live in third-party frames, so those may load unless they
            // are ad hosts. Their attempts to take over the whole page hit the rule above.
            if (isWeb(uri)) return blocker.isAd(host);
            String scheme = uri.getScheme();
            return !("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!isWeb(uri)) return null;
            String host = uri.getHost();
            String path = uri.getPath();
            if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) {
                return emptyResponse();
            }
            if (request.isForMainFrame() && !AdBlocker.isSite(host)) return emptyResponse();
            return blocker.isAd(host) ? emptyResponse() : null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Uri uri = Uri.parse(url);
            if (isWeb(uri) && !AdBlocker.isSite(uri.getHost())) {
                view.stopLoading();
                if (view.canGoBack()) view.goBack();
                else view.loadUrl(HOME);
                return;
            }
            if (!guardRunsEarly) view.evaluateJavascript(guardJs, null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ui.postDelayed(() -> web.evaluateJavascript("window.__cs&&__cs.init()", null), 800);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && AdBlocker.isSite(request.getUrl().getHost())) {
                notice("Can't reach the site. Open Menu to reload.");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Rebuild the screen rather than letting a dead renderer close the app.
            recreate();
            return true;
        }
    }

    private final class ShieldChrome extends WebChromeClient {
        /**
         * A new-window request is only honoured when it came from a real click and leads
         * to the site. A throwaway WebView reveals where it was headed.
         */
        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message result) {
            if (!userGesture) {
                notice("Blocked popup");
                return false;
            }
            final WebView probe = new WebView(FuluActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                private boolean handled;

                private void decide(Uri uri) {
                    if (handled) return;
                    handled = true;
                    if (isWeb(uri) && AdBlocker.isSite(uri.getHost())) web.loadUrl(uri.toString());
                    else notice("Blocked popup");
                    ui.post(probe::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    decide(r.getUrl());
                    return true;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return emptyResponse();
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
            transport.setWebView(probe);
            result.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String message, JsResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String message, JsResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView v, String url, String message, String def, JsPromptResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView v, String url, String message, JsResult r) {
            r.confirm();
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreenView = view;
            fullscreenCallback = callback;
            web.setVisibility(View.GONE);
            root.addView(view, 1, new FrameLayout.LayoutParams(-1, -1));
        }

        @Override
        public void onHideCustomView() {
            if (fullscreenView == null) return;
            root.removeView(fullscreenView);
            fullscreenView = null;
            fullscreenCallback = null;
            web.setVisibility(View.VISIBLE);
            web.requestFocus();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    /** Only reports video status. Every frame can reach this, so it does nothing else. */
    private final class Bridge {
        @JavascriptInterface
        public void state(String json) {
            ui.post(() -> player.onState(json));
        }

        @JavascriptInterface
        public boolean lite() {
            return prefs.getBoolean("lite", true);
        }
    }

    private void pressAt(float fx, float fy) {
        float x = fx * web.getWidth();
        float y = fy * web.getHeight();
        long down = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(e);
        e.recycle();
        ui.postDelayed(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent u = MotionEvent.obtain(down, now, MotionEvent.ACTION_UP, x, y, 0);
            u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(u);
            u.recycle();
        }, 50);
    }

    private void pressFocused() {
        web.evaluateJavascript("window.__cs?__cs.press():null", value -> {
            if (value == null || !value.startsWith("[")) return;
            try {
                JSONArray at = new JSONArray(value);
                pressAt((float) at.getDouble(0), (float) at.getDouble(1));
            } catch (JSONException ignored) {
            }
        });
    }

    private void moveFocus(String direction) {
        long now = SystemClock.uptimeMillis();
        if (now - lastMoveAt < 110) return;
        lastMoveAt = now;
        web.evaluateJavascript("window.__cs&&__cs.move('" + direction + "')", null);
    }

    private void openAppSettings() {
        boolean lite = prefs.getBoolean("lite", true);
        TvMenu.show(root, "Settings", java.util.Arrays.asList(
                new TvMenu.Item("Home", () -> web.loadUrl(HOME)),
                new TvMenu.Item("Reload page", web::reload),
                new TvMenu.Item("Simple menus: " + (lite ? "On" : "Off"), () -> {
                    prefs.edit().putBoolean("lite", !lite).apply();
                    web.reload();
                }),
                new TvMenu.Item("Clear site data", () -> {
                    web.clearCache(true);
                    android.webkit.WebStorage.getInstance().deleteAllData();
                    android.webkit.CookieManager.getInstance().removeAllCookies(null);
                    web.loadUrl(HOME);
                }),
                new TvMenu.Item("Back to menu", this::finish)));
    }

    /** Reaches the player even inside a cross-origin frame. See guard.js. */
    private void sendPlayerCommand(String command, double value) {
        web.evaluateJavascript("(function f(w){try{w.postMessage({cineshield:'" + command + "',s:"
                + value + "},'*')}catch(e){}for(var i=0;i<w.frames.length;i++)f(w.frames[i])})(window)",
                null);
    }

    private void onBack() {
        if (fullscreenView != null) {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        } else if (web.canGoBack()) {
            web.goBack();
        } else if (SystemClock.uptimeMillis() - lastBackAt < 2500) {
            finish();
        } else {
            lastBackAt = SystemClock.uptimeMillis();
            notice("Press Back again for the menu");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (TvMenu.isOpen(root)) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) TvMenu.close(root);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (player.isActive()) {
            return player.handleKey(e) || super.dispatchKeyEvent(e);
        }
        int key = e.getKeyCode();
        boolean isDown = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean isUp = e.getAction() == KeyEvent.ACTION_UP;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isDown) moveFocus("left");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isDown) moveFocus("right");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (isDown) moveFocus("up");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (isDown) moveFocus("down");
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (isUp) pressFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (isUp) onBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (isUp) openAppSettings();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (isDown && e.getRepeatCount() == 0) sendPlayerCommand("toggle", 0);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
