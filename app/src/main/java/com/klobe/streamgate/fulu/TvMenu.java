package com.klobe.streamgate.fulu;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** A large right-hand list operated with the remote. */
final class TvMenu {
    private static final String TAG = "tv-menu";
    static final int ACCENT = 0xFF95FF50;
    static final int PANEL = 0xF2101418;

    static final class Item {
        final String label;
        final Runnable action;

        Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private TvMenu() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Styles a focusable text control: green with dark text when selected. */
    static TextView control(Context c, String label, Runnable action) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(22);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, focused) -> {
            t.setBackgroundColor(focused ? ACCENT : 0);
            t.setTextColor(focused ? 0xFF101418 : 0xFFFFFFFF);
        });
        return t;
    }

    static void show(FrameLayout root, String title, List<Item> items) {
        close(root);
        Context c = root.getContext();
        ScrollView scroll = new ScrollView(c);
        scroll.setTag(TAG);
        scroll.setBackgroundColor(PANEL);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(c, 28), 0, dp(c, 28));
        TextView head = new TextView(c);
        head.setText(title);
        head.setTextSize(28);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(ACCENT);
        head.setPadding(dp(c, 22), 0, dp(c, 22), dp(c, 14));
        list.addView(head);
        View first = null;
        for (Item item : items) {
            TextView row = control(c, item.label, () -> {
                close(root);
                item.action.run();
            });
            list.addView(row);
            if (first == null) first = row;
        }
        scroll.addView(list);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, 380), -1, Gravity.END);
        root.addView(scroll, lp);
        if (first != null) first.requestFocus();
    }

    static boolean isOpen(FrameLayout root) {
        return root.findViewWithTag(TAG) != null;
    }

    static void close(FrameLayout root) {
        View v = root.findViewWithTag(TAG);
        if (v != null) root.removeView(v);
    }
}
