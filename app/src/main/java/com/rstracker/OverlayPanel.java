package com.rstracker;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The small floating panel that sits on top of Monitor & Control. Drag it by its top bar. */
final class OverlayPanel {
    private final TrackerService svc;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root, body;
    private final View dot;
    private final TextView status, trackBtn, foldBtn;
    private final JoystickView stick;
    private boolean folded;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;

    OverlayPanel(TrackerService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);

        root = new LinearLayout(s);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(6), dp(10), dp(10));
        // Opaque and nearly black, so a screenshot never mistakes the panel for camera picture.
        GradientDrawable bg = Ui.round(0xFF0B0B0C, dp(20));
        bg.setStroke(dp(1), 0x30FFFFFF);
        root.setBackground(bg);
        root.setMinimumWidth(dp(240));

        LinearLayout head = new LinearLayout(s);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(44));
        dot = Ui.dot(s, Ui.GREY);
        head.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        status = Ui.text(s, "Starting…", 14, Ui.INK, true);
        status.setMaxLines(2);
        status.setMaxWidth(dp(190));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(8);
        sl.rightMargin = dp(4);
        head.addView(status, sl);
        foldBtn = iconButton("–");
        TextView close = iconButton("✕");
        head.addView(foldBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(head);

        body = new LinearLayout(s);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(s);
        row.setOrientation(LinearLayout.HORIZONTAL);
        trackBtn = Ui.button(s, "Track", Ui.YELLOW, Ui.BLACK);
        TextView center = Ui.button(s, "Recenter", Ui.BUTTON, Ui.INK);
        row.addView(trackBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = dp(8);
        row.addView(center, cl);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        body.addView(row, rowLp);

        stick = new JoystickView(s);
        LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(dp(144), dp(144));
        jl.gravity = Gravity.CENTER_HORIZONTAL;
        jl.topMargin = dp(10);
        body.addView(stick, jl);

        TextView learn = Ui.text(s, "Learn directions", 13, Ui.MUTED, false);
        learn.setGravity(Gravity.CENTER);
        learn.setMinHeight(dp(40));
        learn.setClickable(true);
        learn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, Ui.round(0xFFFFFFFF, dp(12))));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(4);
        body.addView(learn, ll);
        root.addView(body);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int screenH = s.getResources().getDisplayMetrics().heightPixels;
        lp.x = Prefs.num("ovX", dp(12));
        lp.y = Prefs.num("ovY", Math.round(screenH * 0.52f));
        wm.addView(root, lp);

        head.setOnTouchListener(this::drag);
        foldBtn.setOnClickListener(v -> setFolded(!folded));
        close.setOnClickListener(v -> svc.stopSelf());
        trackBtn.setOnClickListener(v -> svc.setTracking(!svc.tracking));
        center.setOnClickListener(v -> svc.recenter());
        learn.setOnClickListener(v -> svc.learn());
        stick.setListener(svc::manualStick);
        setFolded(Prefs.flag("ovFolded", false));
    }

    private int dp(float v) {
        return Ui.dp(svc, v);
    }

    private TextView iconButton(String label) {
        TextView t = Ui.text(svc, label, 18, Ui.INK, true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, Ui.round(0xFFFFFFFF, dp(20))));
        return t;
    }

    private boolean drag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = Math.round(e.getRawX() - downX), dy = Math.round(e.getRawY() - downY);
                if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                if (moved) {
                    lp.x = startX + dx;
                    lp.y = startY + dy;
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (moved) {
                    Prefs.put("ovX", lp.x);
                    Prefs.put("ovY", lp.y);
                } else if (folded) {
                    setFolded(false);
                } else {
                    svc.onStatusTap();
                }
                return true;
            default:
                return true;
        }
    }

    private void setFolded(boolean f) {
        folded = f;
        body.setVisibility(f ? View.GONE : View.VISIBLE);
        foldBtn.setText(f ? "+" : "–");
        Prefs.put("ovFolded", f);
    }

    void update(String text, int color, boolean tracking, Integer battery) {
        String t = battery != null ? text + "  ·  " + battery + "%" : text;
        if (!t.contentEquals(status.getText())) status.setText(t);
        Ui.setDot(dot, color);
        trackBtn.setText(tracking ? "Pause" : "Track");
        Ui.setButtonColor(trackBtn, tracking ? Ui.RED : Ui.YELLOW, tracking ? Ui.INK : Ui.BLACK);
        stick.setAlpha(tracking ? 0.5f : 1f);
    }

    void remove() {
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
    }
}
