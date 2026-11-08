package com.klobe.streamgate;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.klobe.streamgate.fulu.FuluActivity;

/** The opening menu: pick Fulu or StreamGate with the remote. */
public class LauncherActivity extends Activity {
    private static final int RING_COLOR = Color.WHITE;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.round(dm.widthPixels * 0.39f);
        int height = width * 9 / 16;
        int gap = Math.round(dm.widthPixels * 0.04f);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(0xFF07090D);

        TextView title = new TextView(this);
        title.setText("Choose an app");
        title.setTextColor(0xFFAAB3C0);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, gap);
        page.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        FrameLayout fulu = tile(R.drawable.logo_fulu, "Fulu", FuluActivity.SITE_FULU, width, height);
        FrameLayout gate = tile(R.drawable.logo_streamgate, "StreamGate", FuluActivity.SITE_STREAMGATE, width, height);
        LinearLayout.LayoutParams gapped = new LinearLayout.LayoutParams(width, height);
        gapped.setMarginEnd(gap);
        row.addView(fulu, gapped);
        row.addView(gate, new LinearLayout.LayoutParams(width, height));
        page.addView(row);
        setContentView(page);

        ("gate".equals(prefs.getString("last", "fulu")) ? gate : fulu).requestFocus();
    }

    private FrameLayout tile(int image, String name, String site, int w, int h) {
        float radius = h * 0.05f;
        int ring = Math.max(4, Math.round(h * 0.012f));

        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(radius);
        ImageView logo = new ImageView(this);
        logo.setImageResource(image);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(clip);
        logo.setClipToOutline(true);
        logo.setContentDescription(name);

        GradientDrawable focusRing = new GradientDrawable();
        focusRing.setCornerRadius(radius);
        focusRing.setColor(Color.TRANSPARENT);
        focusRing.setStroke(ring, Color.TRANSPARENT);

        FrameLayout tile = new FrameLayout(this);
        tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        tile.setForeground(focusRing);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnFocusChangeListener((v, focused) -> {
            focusRing.setStroke(ring, focused ? RING_COLOR : Color.TRANSPARENT);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(130).start();
            v.setElevation(focused ? 8 : 0);
        });
        tile.setOnClickListener(v -> {
            prefs.edit().putString("last", FuluActivity.SITE_FULU.equals(site) ? "fulu" : "gate").apply();
            startActivity(new Intent(this, FuluActivity.class).putExtra(FuluActivity.EXTRA_SITE, site));
        });
        return tile;
    }
}
