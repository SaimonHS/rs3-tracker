package com.rstracker;

import java.util.List;

/**
 * Chooses who to follow and turns their position in the picture into joystick commands.
 * Positions are 0..1 across the camera picture (0,0 = top-left). Pure Java (no Android).
 */
public final class Follow {

    public static final class Box {
        public final float x, y, w, h, score;

        public Box(float x, float y, float w, float h, float score) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.score = score;
        }

        public float cx() { return x + w / 2f; }

        public float cy() { return y + h / 2f; }
    }

    /** Settings snapshot, filled from Prefs every frame. */
    public static final class Config {
        public boolean face = false;
        public String aim = "upper";
        public boolean tilt = true;
        public float maxSpeed = 0.5f;   // fraction of full stick throw
        public float response = 4f;     // 1..10
        public float smooth = 5f;       // 1..10
        public float deadzone = 0.07f;  // fraction of the picture
        public float goalX = 0.5f, goalY = 0.4f;
        /** Which picture axis (0 = x, 1 = y) moves when the stick pans, and which way (+1/-1). */
        public int yawAxis = 0, yawSign = -1, pitchAxis = 1, pitchSign = 1;
    }

    private static final class Axis {
        boolean on, init;
        float pe, d;
    }

    public volatile Box box;
    public volatile long seen;
    /** Wider matching while learning directions, when the picture jumps a lot. */
    public volatile boolean loose;
    private float vx, vy;
    private final Axis yawAxis = new Axis(), pitchAxis = new Axis();
    private long lastT;
    private float outYaw, outPitch;

    public void reset() {
        yawAxis.on = pitchAxis.on = false;
        yawAxis.init = pitchAxis.init = false;
        outYaw = outPitch = 0f;
        lastT = 0;
    }

    public boolean fresh(long now) {
        return box != null && now - seen < 350;
    }

    /** The point that should sit on the goal: a bit below the top of the person, or the face centre. */
    public static float[] aim(Box b, Config c) {
        if (c.face) return new float[]{b.cx(), b.cy()};
        float f = "head".equals(c.aim) ? 0.12f : "body".equals(c.aim) ? 0.5f : 0.26f;
        return new float[]{b.cx(), b.y + b.h * f};
    }

    public void track(List<Box> dets, long now) {
        Box cur = box;
        if (cur == null || now - seen > 1500) {
            box = null;
            Box big = null;
            for (Box b : dets) if (big == null || b.w * b.h > big.w * big.h) big = b;
            if (big != null) {
                box = big;
                seen = now;
                vx = vy = 0f;
            }
            return;
        }
        float dt = Math.min(0.5f, (now - seen) / 1000f);
        float px = cur.cx() + vx * dt, py = cur.cy() + vy * dt;
        float size = Math.max(0.06f, Math.max(cur.w, cur.h));
        Box best = null;
        float bestCost = Float.MAX_VALUE;
        for (Box b : dets) {
            float cost = (float) Math.hypot(b.cx() - px, b.cy() - py) / size
                    + 0.6f * Math.abs((float) Math.log((b.w * b.h + 1e-6f) / (cur.w * cur.h + 1e-6f)));
            if (cost < bestCost) {
                bestCost = cost;
                best = b;
            }
        }
        if (best != null && bestCost < (loose ? 4f : 1.3f)) {
            float k = 0.65f;
            Box nb = new Box(lerp(cur.x, best.x, k), lerp(cur.y, best.y, k),
                    lerp(cur.w, best.w, k), lerp(cur.h, best.h, k), best.score);
            float gap = Math.max(0.03f, (now - seen) / 1000f);
            vx = vx * 0.6f + (nb.cx() - cur.cx()) / gap * 0.4f;
            vy = vy * 0.6f + (nb.cy() - cur.cy()) / gap * 0.4f;
            box = nb;
            seen = now;
        }
    }

    /** One control step. Returns {yaw, pitch} stick, as fractions of full throw. */
    public float[] step(long now, boolean active, Config c) {
        float dt = lastT == 0 ? 0.066f : clamp((now - lastT) / 1000f, 0.01f, 0.3f);
        lastT = now;
        float uy = 0f, up = 0f;
        Box b = box;
        boolean ok = active && b != null && now - seen < 350;
        if (ok) {
            float[] a = aim(b, c);
            float ex = a[0] - c.goalX, ey = a[1] - c.goalY;
            float kp = 0.5f * c.response, kd = 0.05f * c.response;
            float eYaw = c.yawAxis == 0 ? ex : ey;
            float ePitch = c.pitchAxis == 0 ? ex : ey;
            float dzYaw = c.yawAxis == 0 ? c.deadzone : c.deadzone * 1.5f;
            float dzPitch = c.pitchAxis == 0 ? c.deadzone : c.deadzone * 1.5f;
            // Move the picture against the error: a stick that shifts the picture by sign*axis needs -err/sign.
            uy = -axis(yawAxis, eYaw, dzYaw, dt, kp, kd) / c.yawSign;
            if (c.tilt) {
                up = -axis(pitchAxis, ePitch, dzPitch, dt, kp, kd) / c.pitchSign;
            } else {
                pitchAxis.on = false;
                pitchAxis.init = false;
            }
        } else {
            yawAxis.on = pitchAxis.on = false;
            yawAxis.init = pitchAxis.init = false;
        }
        float tau = ok ? 0.04f + 0.05f * c.smooth : 0.05f;
        float k = 1f - (float) Math.exp(-dt / tau);
        outYaw += (clamp(uy, -1f, 1f) - outYaw) * k;
        outPitch += (clamp(up, -1f, 1f) - outPitch) * k;
        return new float[]{shape(outYaw * c.maxSpeed), shape(outPitch * c.maxSpeed)};
    }

    private static float axis(Axis s, float err, float dz, float dt, float kp, float kd) {
        if (!s.init) {
            s.pe = err;
            s.d = 0f;
            s.init = true;
        }
        float a = Math.abs(err);
        if (!s.on && a > dz) s.on = true;
        else if (s.on && a < dz * 0.3f) s.on = false;
        float raw = (err - s.pe) / dt;
        s.pe = err;
        s.d = clamp(s.d * 0.6f + raw * 0.4f, -3f, 3f);
        if (!s.on) return 0f;
        float eff = err - Math.signum(err) * dz * 0.3f;
        return kp * eff + kd * s.d;
    }

    /** Tiny commands become zero; small ones get a minimum so the gimbal actually moves. */
    static float shape(float v) {
        float a = Math.abs(v);
        if (a < 0.012f) return 0f;
        if (a < 0.03f) return Math.signum(v) * 0.03f;
        return v;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
