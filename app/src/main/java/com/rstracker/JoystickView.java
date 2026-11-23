package com.rstracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Round on-screen joystick, like the Ronin app's. Reports x/y in -1..1 (y down is positive). */
public final class JoystickView extends View {
    public interface Listener {
        void onStick(float x, float y, boolean active);
    }

    private Listener listener;
    private float kx, ky;
    private boolean active;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            send(true);
            postDelayed(this, 100);
        }
    };

    public JoystickView(Context c) {
        super(c);
        base.setColor(0x1FFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1.5f));
        ring.setColor(0x4DFFFFFF);
        knob.setColor(0xFFFFFFFF);
        arrow.setColor(0x99FFFFFF);
        setContentDescription("Joystick");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void send(boolean on) {
        if (listener != null) listener.onStick(kx, ky, on);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - Ui.dp(getContext(), 2);
        c.drawCircle(cx, cy, r, base);
        c.drawCircle(cx, cy, r, ring);
        float a = r * 0.11f, o = r * 0.8f;
        triangle(c, cx, cy - o, 0, -1, a);
        triangle(c, cx, cy + o, 0, 1, a);
        triangle(c, cx - o, cy, -1, 0, a);
        triangle(c, cx + o, cy, 1, 0, a);
        float kr = r * 0.3f;
        c.drawCircle(cx + kx * (r - kr), cy + ky * (r - kr), kr, knob);
    }

    private void triangle(Canvas c, float x, float y, float dx, float dy, float a) {
        float bx = x - dx * a * 0.6f, by = y - dy * a * 0.6f;
        tri.reset();
        tri.moveTo(x + dx * a, y + dy * a);
        tri.lineTo(bx - dy * a, by + dx * a);
        tri.lineTo(bx + dy * a, by - dx * a);
        tri.close();
        c.drawPath(tri, arrow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f * 0.75f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                removeCallbacks(repeat);
                postDelayed(repeat, 100);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - w / 2f) / r, dy = (e.getY() - h / 2f) / r;
                float m = (float) Math.hypot(dx, dy);
                if (m > 1f) {
                    dx /= m;
                    dy /= m;
                }
                if (m < 0.1f) {
                    dx = 0f;
                    dy = 0f;
                }
                kx = dx;
                ky = dy;
                send(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = false;
                kx = 0f;
                ky = 0f;
                removeCallbacks(repeat);
                send(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
