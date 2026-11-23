package com.rstracker;

/**
 * Finds the camera picture inside a screenshot of Monitor & Control: the biggest block of
 * non-black rows and columns (the app's controls sit on black). Pure Java.
 */
public final class LiveViewFinder {
    private LiveViewFinder() {}

    /**
     * px = ARGB pixels of a small w*h copy of the screen.
     * Returns {left, top, right, bottom} in those pixels (right/bottom exclusive), or null.
     */
    public static int[] find(int[] px, int w, int h) {
        boolean[] lit = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            int c = px[i];
            int luma10 = ((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF);
            lit[i] = luma10 > 140; // brightness above ~14 of 255
        }
        float[] rows = new float[h];
        for (int y = 0; y < h; y++) {
            int n = 0;
            for (int x = 0; x < w; x++) if (lit[y * w + x]) n++;
            rows[y] = n / (float) w;
        }
        int[] ry = run(rows, 0.55f, Math.max(1, h / 60));
        if (ry == null || ry[1] - ry[0] < h * 0.12f) return null;
        float[] cols = new float[w];
        int rh = ry[1] - ry[0];
        for (int x = 0; x < w; x++) {
            int n = 0;
            for (int y = ry[0]; y < ry[1]; y++) if (lit[y * w + x]) n++;
            cols[x] = n / (float) rh;
        }
        int[] rx = run(cols, 0.55f, Math.max(1, w / 60));
        if (rx == null || rx[1] - rx[0] < w * 0.3f) return null;
        return new int[]{rx[0], ry[0], rx[1], ry[1]};
    }

    /** Share of non-black pixels inside r = {left, top, right, bottom}. */
    public static float litFraction(int[] px, int w, int h, int[] r) {
        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(w, r[2]), y1 = Math.min(h, r[3]);
        if (x1 <= x0 || y1 <= y0) return 0f;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * w + x];
                if (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF) > 140) n++;
            }
        }
        return n / (float) ((x1 - x0) * (y1 - y0));
    }

    /** Longest stretch of values >= th, allowing gaps of up to `gap`. Returns {start, endExclusive}. */
    static int[] run(float[] a, float th, int gap) {
        int bestStart = -1, bestEnd = -1, start = -1, lastGood = -1;
        for (int i = 0; i <= a.length; i++) {
            boolean good = i < a.length && a[i] >= th;
            if (good) {
                if (start < 0) start = i;
                lastGood = i;
            } else if (start >= 0 && (i == a.length || i - lastGood > gap)) {
                if (lastGood + 1 - start > bestEnd - bestStart) {
                    bestStart = start;
                    bestEnd = lastGood + 1;
                }
                start = -1;
            }
        }
        return bestStart < 0 ? null : new int[]{bestStart, bestEnd};
    }
}
