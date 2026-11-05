package com.klobe.streamgate.fulu;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen TV controls drawn by the app, on top of any site's player. */
final class PlayerUi {
    interface Host {
        void command(String name, double value);

        String title();

        void closed();
    }

    private static final long HIDE_MS = 5000;
    private static final long SEEK_GAP_MS = 220;
    private static final double[] SPEEDS = {0.75, 1, 1.25, 1.5, 2};

    private final Host host;
    private final FrameLayout root;
    private final LinearLayout bar;
    private final TextView titleView;
    private final TextView timeView;
    private final ProgressBar progress;
    private final TextView playButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideControls;

    private boolean active;
    private boolean playing;
    private boolean suppressed;
    private JSONObject last;
    private long lastSeekAt;

    PlayerUi(Context c, FrameLayout root, Host host) {
        this.root = root;
        this.host = host;
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(TvMenu.PANEL);
        int pad = TvMenu.dp(c, 32);
        bar.setPadding(pad, TvMenu.dp(c, 20), pad, TvMenu.dp(c, 20));

        titleView = new TextView(c);
        titleView.setTextSize(26);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setSingleLine(true);
        bar.addView(titleView);

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TvMenu.ACCENT));
        bar.addView(progress, new LinearLayout.LayoutParams(-1, TvMenu.dp(c, 12)));

        timeView = new TextView(c);
        timeView.setTextSize(20);
        timeView.setTextColor(0xFFD0D4D8);
        bar.addView(timeView);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, TvMenu.dp(c, 10), 0, 0);
        row.addView(TvMenu.control(c, "-30s", () -> seek(-30)));
        row.addView(TvMenu.control(c, "-10s", () -> seek(-10)));
        playButton = TvMenu.control(c, "Pause", () -> host.command("toggle", 0));
        row.addView(playButton);
        row.addView(TvMenu.control(c, "+10s", () -> seek(10)));
        row.addView(TvMenu.control(c, "+30s", () -> seek(30)));
        row.addView(TvMenu.control(c, "Settings", this::openSettings));
        bar.addView(row);

        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    boolean isActive() {
        return active;
    }

    void onState(String json) {
        JSONObject s;
        try {
            s = new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        double duration = s.optDouble("d", 0);
        boolean nowPlaying = s.optBoolean("playing");
        // Real programmes only: long, or a live stream with no fixed length.
        if (duration >= 0 && duration < 60) return;
        if (!nowPlaying) suppressed = false;
        if (!active && nowPlaying && !suppressed) {
            active = true;
            host.command("fill", 0);
            showControls();
        }
        if (!active) return;
        last = s;
        playing = nowPlaying;
        playButton.setText(playing ? "Pause" : "Play");
        double t = s.optDouble("t", 0);
        progress.setProgress(duration > 0 ? (int) (1000 * t / duration) : 0);
        timeView.setText(duration > 0 ? clock(t) + " / " + clock(duration) : "Live");
        titleView.setText(host.title());
        if (!playing) showControls();
    }

    private static String clock(double seconds) {
        int s = (int) seconds;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void seek(int seconds) {
        host.command("seek", seconds);
        showControls();
    }

    private void showControls() {
        boolean wasHidden = bar.getVisibility() != View.VISIBLE;
        bar.setVisibility(View.VISIBLE);
        if (wasHidden) playButton.requestFocus();
        ui.removeCallbacks(hide);
        if (playing) ui.postDelayed(hide, HIDE_MS);
    }

    private void hideControls() {
        if (TvMenu.isOpen(root)) {
            ui.postDelayed(hide, HIDE_MS);
            return;
        }
        bar.setVisibility(View.GONE);
    }

    private void close() {
        active = false;
        // Stay out until this video stops, so leaving does not bounce straight back.
        suppressed = true;
        ui.removeCallbacks(hide);
        bar.setVisibility(View.GONE);
        host.closed();
    }

    private void openSettings() {
        ui.removeCallbacks(hide);
        List<TvMenu.Item> items = new ArrayList<>();
        items.add(new TvMenu.Item("Subtitles", this::openSubtitles));
        items.add(new TvMenu.Item("Quality", this::openQuality));
        items.add(new TvMenu.Item("Speed", this::openSpeed));
        boolean fill = last != null && last.optInt("fit") == 1;
        items.add(new TvMenu.Item("Picture: " + (fill ? "Fill screen" : "Fit screen"),
                () -> host.command("fit", fill ? 0 : 1)));
        TvMenu.show(root, "Video settings", items);
    }

    private void addChoices(List<TvMenu.Item> items, String key, String command) {
        JSONArray list = last == null ? null : last.optJSONArray(key);
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            int index = o.optInt("i");
            items.add(new TvMenu.Item((o.optBoolean("on") ? "\u2713 " : "") + o.optString("label"),
                    () -> host.command(command, index)));
        }
    }

    private void openSubtitles() {
        List<TvMenu.Item> items = new ArrayList<>();
        items.add(new TvMenu.Item("Off", () -> host.command("sub", -1)));
        addChoices(items, "subs", "sub");
        if (items.size() == 1) items.set(0, new TvMenu.Item("None available", () -> { }));
        TvMenu.show(root, "Subtitles", items);
    }

    private void openQuality() {
        List<TvMenu.Item> items = new ArrayList<>();
        addChoices(items, "q", "quality");
        if (items.isEmpty()) items.add(new TvMenu.Item("Automatic", () -> { }));
        else items.add(0, new TvMenu.Item("Auto", () -> host.command("quality", -1)));
        TvMenu.show(root, "Quality", items);
    }

    private void openSpeed() {
        List<TvMenu.Item> items = new ArrayList<>();
        double current = last == null ? 1 : last.optDouble("rate", 1);
        for (double speed : SPEEDS) {
            String label = (speed == current ? "\u2713 " : "") + speed + "x";
            items.add(new TvMenu.Item(label, () -> host.command("rate", speed)));
        }
        TvMenu.show(root, "Speed", items);
    }

    /** Returns true when the key was used here. */
    boolean handleKey(KeyEvent e) {
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        boolean first = down && e.getRepeatCount() == 0;
        boolean visible = bar.getVisibility() == View.VISIBLE;
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
                if (up) {
                    if (visible && playing) hideControls();
                    else close();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up) {
                    showControls();
                    openSettings();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) host.command("toggle", 0);
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) timedSeek(30);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) timedSeek(-30);
                return true;
            default:
                break;
        }
        if (visible) {
            showControls();
            return false;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) timedSeek(-10);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) timedSeek(10);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {
                    host.command("toggle", 0);
                    showControls();
                }
                return true;
            default:
                return false;
        }
    }

    private void timedSeek(int seconds) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSeekAt < SEEK_GAP_MS) return;
        lastSeekAt = now;
        seek(seconds);
    }
}
