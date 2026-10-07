package com.safecharge.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Tiny helpers so every screen is built in code (no XML ids that can go missing). */
final class UI {

    private UI() {}

    static final int BG = Color.parseColor("#F4F6F8");
    static final int CARD = Color.WHITE;
    static final int GREEN = Color.parseColor("#2E7D32");
    static final int BLUE = Color.parseColor("#1565C0");
    static final int ORANGE = Color.parseColor("#EF6C00");
    static final int RED = Color.parseColor("#C62828");
    static final int TEXT = Color.parseColor("#1A1A1A");
    static final int TEXT2 = Color.parseColor("#6B7280");
    static final int TRACK = Color.parseColor("#E5E7EB");

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** White rounded card with a bold title, added to parent. */
    static LinearLayout card(Context c, LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(rounded(CARD, dp(c, 16)));
        int pad = dp(c, 14);
        card.setPadding(pad, pad, pad, pad);
        parent.addView(card, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(c, 12)));
        if (title != null) {
            TextView t = text(c, title, 16, TEXT);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(t, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(c, 8)));
        }
        return card;
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Adds a secondary-grey line to a parent and returns it. */
    static TextView note(Context c, LinearLayout parent, String s) {
        TextView t = text(c, s, 12, TEXT2);
        parent.addView(t, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(c, 2), 0, dp(c, 2)));
        return t;
    }

    static TextView line(Context c, LinearLayout parent, String s) {
        TextView t = text(c, s, 13, TEXT);
        parent.addView(t, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(c, 2), 0, dp(c, 2)));
        return t;
    }

    static Button button(Context c, String label, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackgroundDrawable(rounded(color, dp(c, 10)));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    /** Adds a full-width button to a vertical parent. */
    static Button add(Context c, LinearLayout parent, String label, int color, View.OnClickListener l) {
        Button b = button(c, label, color, l);
        parent.addView(b, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(c, 6), 0, 0));
        return b;
    }

    /** Two equal-width buttons side by side. */
    static LinearLayout row(Context c, LinearLayout parent) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(c, 6), 0, 0));
        return r;
    }

    static void addHalf(Context c, LinearLayout row, Button b) {
        row.addView(b, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 3), 0, dp(c, 3), 0));
        ((LinearLayout.LayoutParams) b.getLayoutParams()).weight = 1;
    }

    static CheckBox check(Context c, LinearLayout parent, String label, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextSize(13);
        cb.setTextColor(TEXT);
        cb.setChecked(checked);
        parent.addView(cb, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        return cb;
    }

    /** Simple horizontal progress bar made of two weighted views. */
    static View bar(Context c, int percent, int color) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundDrawable(rounded(TRACK, dp(c, 4)));
        track.setWeightSum(100f);
        View fill = new View(c);
        fill.setBackgroundDrawable(rounded(color, dp(c, 4)));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(c, 8));
        fp.weight = percent;
        track.addView(fill, fp);
        track.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 8), 0, dp(c, 4), 0, dp(c, 4)));
        return track;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    static void copy(Context c, String label, String s) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, s));
            toast(c, "Copied to clipboard");
        }
    }

    /** Standard screen: grey background, scroll area, title row with a Back button. */
    static LinearLayout screen(Activity a, String title) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 12);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        a.setContentView(sv);
        TextView t = text(a, title, 22, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(t, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 2), dp(a, 8), 0, dp(a, 10)));
        return root;
    }
}
