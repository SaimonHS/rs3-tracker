package com.rstracker;

/**
 * "Learn directions": nudges the gimbal right, back, up, back, and watches which way you move in
 * the picture. Fed one frame at a time; returns the stick to hold for that frame. Pure Java.
 */
public final class Learner {
    public static final float PULSE = 0.4f;

    private static final int BASE = 0, PAN_GO = 1, PAN_WAIT = 2, PAN_MEASURE = 3, PAN_BACK = 4,
            BACK_WAIT = 5, BASE2 = 6, TILT_GO = 7, TILT_WAIT = 8, TILT_MEASURE = 9, TILT_BACK = 10, END = 11;
    private static final long[] DURATION = {600, 700, 1100, 400, 700, 1100, 400, 600, 1100, 400, 600, 0};

    private int step = BASE;
    private long started;
    private final float[] sum = new float[8];
    private final int[] count = new int[4];
    private final int previousPitchSign;

    public boolean done, ok;
    public String message = "";
    public int yawAxis, yawSign, pitchAxis, pitchSign;

    public Learner(long now, int previousPitchSign) {
        started = now;
        this.previousPitchSign = previousPitchSign >= 0 ? 1 : -1;
    }

    /** aim = where you are in the picture this frame, or null if not seen. Returns {yaw, pitch}. */
    public float[] step(long now, float[] aim) {
        if (done) return new float[]{0f, 0f};
        int slot = step == BASE ? 0 : step == PAN_MEASURE ? 1 : step == BASE2 ? 2 : step == TILT_MEASURE ? 3 : -1;
        if (slot >= 0 && aim != null) {
            sum[slot * 2] += aim[0];
            sum[slot * 2 + 1] += aim[1];
            count[slot]++;
        }
        if (now - started >= DURATION[step]) {
            step++;
            started = now;
            if (step == END) {
                finish();
                return new float[]{0f, 0f};
            }
        }
        switch (step) {
            case PAN_GO: return new float[]{PULSE, 0f};
            case PAN_BACK: return new float[]{-PULSE, 0f};
            case TILT_GO: return new float[]{0f, PULSE};
            case TILT_BACK: return new float[]{0f, -PULSE};
            default: return new float[]{0f, 0f};
        }
    }

    private void finish() {
        done = true;
        for (int c : count) {
            if (c == 0) {
                message = "Lost you during the test. Stand still in the picture and try again.";
                return;
            }
        }
        float bx = sum[0] / count[0], by = sum[1] / count[0];
        float px = sum[2] / count[1], py = sum[3] / count[1];
        float b2x = sum[4] / count[2], b2y = sum[5] / count[2];
        float tx = sum[6] / count[3], ty = sum[7] / count[3];
        float dx = px - bx, dy = py - by;
        if (Math.hypot(dx, dy) < 0.02) {
            message = "Couldn't see the gimbal move. Is it awake, and is Max speed above 10%?";
            return;
        }
        yawAxis = Math.abs(dx) >= Math.abs(dy) ? 0 : 1;
        yawSign = (yawAxis == 0 ? dx : dy) > 0 ? 1 : -1;
        pitchAxis = 1 - yawAxis;
        float t = pitchAxis == 0 ? tx - b2x : ty - b2y;
        ok = true;
        if (Math.abs(t) < 0.015f) {
            pitchSign = previousPitchSign;
            message = "Pan learned. Tilt barely moved, so the tilt direction wasn't changed.";
        } else {
            pitchSign = t > 0 ? 1 : -1;
            message = "Directions learned.";
        }
    }
}
