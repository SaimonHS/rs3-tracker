package com.rstracker;

import java.util.Arrays;

/**
 * DJI "DUML" frames spoken by the RS 3 Mini over Bluetooth LE.
 *
 * Not an official API: the layout, checksums and the start-up frames come from captures of the
 * DJI Ronin app, documented by the open-source rs3mini-bridge project. Pure Java (no Android).
 */
public final class Duml {
    private Duml() {}

    private static final int[] T8 = new int[256];
    private static final int[] T16 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int a = i, b = i;
            for (int k = 0; k < 8; k++) {
                a = (a & 1) != 0 ? (a >>> 1) ^ 0x8C : a >>> 1;
                b = (b & 1) != 0 ? (b >>> 1) ^ 0x8408 : b >>> 1;
            }
            T8[i] = a;
            T16[i] = b;
        }
    }

    /** Header checksum: reflected CRC-8 (poly 0x31), seed 0x77. */
    public static int crc8(byte[] d, int off, int n) {
        int c = 0x77;
        for (int i = off; i < off + n; i++) c = T8[(c ^ d[i]) & 0xFF];
        return c;
    }

    /** Frame checksum: reflected CRC-16 (poly 0x1021), seed 0x3692. */
    public static int crc16(byte[] d, int off, int n) {
        int c = 0x3692;
        for (int i = off; i < off + n; i++) c = (c >>> 8) ^ T16[(c ^ d[i]) & 0xFF];
        return c & 0xFFFF;
    }

    /** Sender 0x02 (phone app) to receiver 0x04 (gimbal). */
    public static final int TO_GIMBAL = 0x0402;
    /** Sender 0x02 to receiver 0xE5 (used by the Ronin app's heartbeat). */
    public static final int TO_PARAMS = 0xE502;

    private static int seq = 0x1700;

    public static synchronized byte[] frame(int target, int flags, int set, int id, byte[] payload) {
        int n = 13 + payload.length;
        byte[] f = new byte[n];
        f[0] = 0x55;
        f[1] = (byte) (n & 0xFF);
        f[2] = (byte) (((n >> 8) & 0x03) | 0x04); // protocol version 1
        f[3] = (byte) crc8(f, 0, 3);
        f[4] = (byte) (target & 0xFF);
        f[5] = (byte) ((target >> 8) & 0xFF);
        do {
            seq = (seq + 1) & 0xFFFF;
        } while ((seq & 0xFF) == 0x55 || (seq >> 8) == 0x55);
        f[6] = (byte) (seq & 0xFF);
        f[7] = (byte) (seq >> 8);
        f[8] = (byte) flags;
        f[9] = (byte) set;
        f[10] = (byte) id;
        System.arraycopy(payload, 0, f, 11, payload.length);
        int c = crc16(f, 0, n - 2);
        f[n - 2] = (byte) (c & 0xFF);
        f[n - 1] = (byte) (c >> 8);
        return f;
    }

    /**
     * Joystick command, the same one the Ronin app's virtual joystick sends.
     * yaw (pan) and pitch (tilt) are -1..1 of full stick throw (1024 +/- 660 counts).
     */
    public static byte[] stick(float yaw, float pitch) {
        int p = 1024 + Math.round(clamp(pitch) * 660f);
        int y = 1024 + Math.round(clamp(yaw) * 660f);
        byte[] payload = {
                (byte) (p & 0xFF), (byte) (p >> 8),
                0x00, 0x04,                         // roll stays centred (1024)
                (byte) (y & 0xFF), (byte) (y >> 8),
                0x00, 0x00, 0x02                    // mode, reserved, source = app joystick
        };
        return frame(TO_GIMBAL, 0x40, 0x04, 0x01, payload);
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    static final byte[] HEARTBEAT_PAYLOAD = hex("1051010000000c00005000f1036624c01d00001c");

    public static byte[] heartbeat() { return frame(TO_PARAMS, 0x00, 0x04, 0x12, HEARTBEAT_PAYLOAD); }

    public static byte[] recenter() { return frame(TO_GIMBAL, 0x40, 0x04, 0x4C, new byte[]{(byte) 0xFE, 0x01}); }

    public static byte[] wake() { return frame(TO_GIMBAL, 0x40, 0x04, 0x0F, new byte[]{0x23, 0x01, 0x10}); }

    public static byte[] pushStatus() { return frame(TO_GIMBAL, 0xC0, 0x04, 0x66, new byte[]{0, 0, 0}); }

    /** Start-up frames replayed from a Ronin app capture. null = send a heartbeat in that slot. */
    public static final String[] PROLOGUE = {
            "550d0433020401004000014eff",
            "550d04330204020040000182e2",
            "550d04330227030040070e24d5",
            "550d0433022704004000010750",
            null,
            "550e046602e50600400032118702",
            "550d043302040700400001d6c4",
            "550d043302e508004000011228",
            null,
            "550d043302270a004007078119",
            null,
            "5514046d02e50c00400071040004000002014583",
            "550e046602e50d0040003211a22f",
            null
    };

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public interface FrameListener {
        void onFrame(byte[] frame);
    }

    /** Re-assembles frames from Bluetooth notifications, which can split or join frames. */
    public static final class Parser {
        private byte[] buf = new byte[0];
        private final FrameListener listener;

        public Parser(FrameListener listener) {
            this.listener = listener;
        }

        public void feed(byte[] chunk) {
            byte[] b = Arrays.copyOf(buf, buf.length + chunk.length);
            System.arraycopy(chunk, 0, b, buf.length, chunk.length);
            buf = b;
            int i = 0;
            while (true) {
                while (i < buf.length && buf[i] != 0x55) i++;
                if (buf.length - i < 4) break;
                if (crc8(buf, i, 3) != (buf[i + 3] & 0xFF)) {
                    i++;
                    continue;
                }
                int n = (buf[i + 1] & 0xFF) | ((buf[i + 2] & 0x03) << 8);
                if (n < 13) {
                    i++;
                    continue;
                }
                if (buf.length - i < n) break;
                int got = (buf[i + n - 2] & 0xFF) | ((buf[i + n - 1] & 0xFF) << 8);
                if (crc16(buf, i, n - 2) == got) {
                    listener.onFrame(Arrays.copyOfRange(buf, i, i + n));
                    i += n;
                } else {
                    i++;
                }
            }
            buf = Arrays.copyOfRange(buf, i, buf.length);
            if (buf.length > 4096) buf = Arrays.copyOfRange(buf, buf.length - 1024, buf.length);
        }
    }
}
