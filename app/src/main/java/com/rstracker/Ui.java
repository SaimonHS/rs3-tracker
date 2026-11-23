package com.rstracker;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Colours and small view helpers (the UI is built in code, no XML layouts). */
final class Ui {
    static final int BG = 0xFF0D0D0F;
    static final int CARD = 0xFF19191C;
    static final int BUTTON = 0xFF2A2A2F;
    static final int INK = 0xFFFFFFFF;
    static final int MUTED = 0xFFA1A1AA;
    static final int YELLOW = 0xFFFFD21F;
    static final int RED = 0xFFFF453A;
    static final int GREEN = 0xFF30D158;
    static final int GREY = 0xFF8E8E93;
    static final int BLACK = 0xFF111111;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView button(Context c, String s, int bg, int fg) {
        TextView t = text(c, s, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 46));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), round(bg, dp(c, 23)), null));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static void setButtonColor(TextView b, int bg, int fg) {
        Drawable d = b.getBackground();
        if (d instanceof RippleDrawable) {
            Drawable inner = ((RippleDrawable) d).getDrawable(0);
            if (inner instanceof GradientDrawable) ((GradientDrawable) inner).setColor(bg);
        }
        b.setTextColor(fg);
    }

    static View dot(Context c, int color) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }

    static void setDot(View v, int color) {
        if (v.getBackground() instanceof GradientDrawable) ((GradientDrawable) v.getBackground()).setColor(color);
    }
}
