package com.klobe.streamgate;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String HOME = "https://mybuffstreams.plus/home7";
    private static final int CURSOR_SIZE = 34;
    private static final long CURSOR_HIDE_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideCursor = () -> { if (this.cursor != null) this.cursor.setAlpha(0f); };
    private final Set<String> blockedHosts = new HashSet<>();

    private FrameLayout root;
    private WebView web;
    private View cursor;
    private float cursorX;
    private float cursorY;
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
        shieldJs = readAsset("shield.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(CURSOR_SIZE, CURSOR_SIZE));
        setContentView(root);
        createWebView();
        web.loadUrl(HOME);

        root.post(() -> {
            cursorX = root.getWidth() / 2f;
            cursorY = root.getHeight() / 2f;
            placeCursor();
        });
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
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        web.setWebViewClient(new GateClient());
        web.setWebChromeClient(new GateChrome());
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
            if (!allow) notifyBlocked(u);
            return !allow;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            if (!Policy.isBlocked(req.getUrl().getHost(), blockedHosts)) return null;
            return new WebResourceResponse("text/plain", "utf-8",
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
            final WebView probe = new WebView(MainActivity.this);
            final boolean[] done = {false};
            final Runnable finish = () -> {
                if (!done[0]) { done[0] = true; probe.destroy(); }
            };
            probe.setWebViewClient(new WebViewClient() {
                private void resolve(Uri u) {
                    if (done[0]) return;
                    if (Policy.allowTopLevel(u.getScheme(), u.getHost())) web.loadUrl(u.toString());
                    else notifyBlocked(u);
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

    /** The site isn't built for a remote, so the D-pad drives an on-screen pointer. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int step = 12 + Math.min(e.getRepeatCount() * 5, 48);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    if (down) moveCursor(0, -step); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  if (down) moveCursor(0, step);  return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (down) moveCursor(-step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) moveCursor(step, 0);  return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) touch(MotionEvent.ACTION_DOWN);
                else if (!down) touch(MotionEvent.ACTION_UP);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) goBack();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void moveCursor(int dx, int dy) {
        float nx = cursorX + dx;
        float ny = cursorY + dy;
        int maxX = root.getWidth() - 1;
        int maxY = root.getHeight() - 1;
        // Pushing past the top or bottom edge scrolls the page.
        if (customView == null && (ny < 0 || ny > maxY)) {
            web.evaluateJavascript("window.scrollBy(0," + (dy * 3) + ")", null);
        }
        cursorX = Math.max(0, Math.min(nx, maxX));
        cursorY = Math.max(0, Math.min(ny, maxY));
        placeCursor();
    }

    private void placeCursor() {
        cursor.setTranslationX(cursorX - CURSOR_SIZE / 2f);
        cursor.setTranslationY(cursorY - CURSOR_SIZE / 2f);
        cursor.setAlpha(1f);
        cursor.bringToFront();
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, CURSOR_HIDE_MS);
    }

    private void touch(int action) {
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, action, cursorX, cursorY, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private void goBack() {
        if (customView != null) web.getWebChromeClient().onHideCustomView();
        else if (web.canGoBack()) web.goBack();
        else finish();
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

    private static class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas canvas) {
            float mid = getWidth() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCCFFFFFF);
            canvas.drawCircle(mid, mid, mid - 5, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(0xFF000000);
            canvas.drawCircle(mid, mid, mid - 4, paint);
        }
    }
}
