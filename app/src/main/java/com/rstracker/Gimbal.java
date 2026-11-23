package com.rstracker;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bluetooth LE link to the DJI RS 3 Mini, speaking the same commands as the Ronin app's virtual
 * joystick. All Bluetooth work happens on the main thread; setStick() may be called from any thread.
 */
@SuppressLint("MissingPermission")
public final class Gimbal {
    public enum State { OFF, CONNECTING, READY, LOST }

    public interface Listener { void onGimbalChanged(); }

    public interface ScanListener { void onFound(BluetoothDevice device, String name); }

    private static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private static Context app;
    private static BluetoothDevice device;
    private static BluetoothGatt gatt;
    private static BluetoothGattCharacteristic writeChar, notifyChar;
    private static volatile State state = State.OFF;
    public static volatile Integer battery;
    public static volatile boolean asleep;
    public static volatile String name = "";
    public static volatile String error = "";
    private static boolean wanted;
    private static int retries;
    private static int chunk = 20;

    // ---- one-at-a-time GATT queue (Android allows only one operation in flight) ----
    private static final int WRITE_OP = 0, DESC_OP = 1, MTU_OP = 2, WAIT_OP = 3, RUN_OP = 4;

    private static final class Op {
        int kind;
        BluetoothGattCharacteristic c;
        BluetoothGattDescriptor d;
        byte[] data;
        int type;
        long ms;
        Runnable run;
    }

    private static final ArrayDeque<Op> ops = new ArrayDeque<>();
    private static boolean busy;
    private static int curKind = -1;
    private static int token;

    private static final Duml.Parser parser = new Duml.Parser(Gimbal::handle);

    // ---- joystick output ----
    private static volatile float stickYaw, stickPitch;
    private static volatile long stickAt, stickNonZeroAt;
    private static boolean stickOn;

    private Gimbal() {}

    public static State state() { return state; }

    public static boolean ready() { return state == State.READY; }

    public static void addListener(Listener l) { listeners.addIfAbsent(l); }

    public static void removeListener(Listener l) { listeners.remove(l); }

    private static void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onGimbalChanged();
        });
    }

    private static void setState(State s) {
        state = s;
        changed();
    }

    public static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    // ---------------------------------------------------------------- connect

    public static void connect(Context c, BluetoothDevice d) {
        app = c.getApplicationContext();
        main.post(() -> {
            closeGatt();
            device = d;
            wanted = true;
            retries = 0;
            error = "";
            String n = null;
            try {
                n = d.getName();
            } catch (Exception ignored) {
            }
            if (n == null || n.isEmpty()) n = Prefs.str("lastDeviceName", "Gimbal");
            name = n;
            Prefs.put("lastDevice", d.getAddress());
            Prefs.put("lastDeviceName", name);
            open();
        });
    }

    public static void disconnect() {
        main.post(() -> {
            wanted = false;
            error = "";
            if (state == State.READY && writeChar != null) {
                // Leave the stick centred, then hang up.
                stickOn = false;
                main.removeCallbacks(stickTick);
                send(Duml.stick(0f, 0f));
                runLater(Gimbal::hangUp);
                main.postDelayed(Gimbal::hangUp, 800);
            } else {
                hangUp();
            }
        });
    }

    private static void hangUp() {
        if (wanted) return;
        closeGatt();
        if (state != State.OFF) setState(State.OFF);
    }

    private static void open() {
        battery = null;
        asleep = false;
        chunk = 20;
        setState(State.CONNECTING);
        try {
            gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            gatt = null;
            error = "Bluetooth permission missing";
            wanted = false;
            setState(State.OFF);
            return;
        }
        main.removeCallbacks(connectTimeout);
        main.postDelayed(connectTimeout, 20000);
    }

    private static final Runnable connectTimeout = () -> {
        if (state == State.CONNECTING) {
            error = "Gimbal didn't answer. Is the Ronin app closed?";
            closeGatt();
            lost();
        }
    };

    private static void closeGatt() {
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(stickTick);
        stickOn = false;
        ops.clear();
        busy = false;
        token++;
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        notifyChar = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void lost() {
        if (!wanted || device == null) {
            setState(State.OFF);
            return;
        }
        setState(State.LOST);
        long[] backoff = {1000, 2000, 3000, 5000, 8000, 12000};
        if (retries < backoff.length) {
            long d = backoff[retries++];
            main.postDelayed(() -> {
                if (wanted && state == State.LOST && device != null) open();
            }, d);
        } else {
            wanted = false;
            if (error.isEmpty()) error = "Lost the gimbal";
            setState(State.OFF);
        }
    }

    private static final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    } catch (Exception ignored) {
                    }
                    if (!g.discoverServices()) {
                        error = "Couldn't read the gimbal";
                        closeGatt();
                        lost();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt();
                    lost();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g == gatt) setup(g);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) chunk = Math.min(mtu - 3, 180);
                complete(MTU_OP);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> complete(WRITE_OP));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> complete(DESC_OP));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) {
                byte[] copy = v.clone();
                main.post(() -> parser.feed(copy));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            byte[] copy = value.clone();
            main.post(() -> parser.feed(copy));
        }
    };

    /** Mirrors what the Ronin app does right after connecting. */
    private static void setup(BluetoothGatt g) {
        BluetoothGattService s = g.getService(SERVICE);
        BluetoothGattCharacteristic w = s == null ? null : s.getCharacteristic(WRITE);
        BluetoothGattCharacteristic n = s == null ? null : s.getCharacteristic(NOTIFY);
        if (w == null || n == null) {
            error = "That device isn't an RS gimbal";
            wanted = false;
            closeGatt();
            setState(State.OFF);
            return;
        }
        writeChar = w;
        notifyChar = n;
        Op mtu = new Op();
        mtu.kind = MTU_OP;
        enqueue(mtu);
        int writable = BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        if ((n.getProperties() & writable) != 0) write(n, new byte[]{0x01, 0x00});
        try {
            g.setCharacteristicNotification(n, true);
        } catch (Exception ignored) {
        }
        BluetoothGattDescriptor cccd = n.getDescriptor(CCCD);
        if (cccd != null) {
            Op o = new Op();
            o.kind = DESC_OP;
            o.d = cccd;
            o.data = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            enqueue(o);
        }
        for (String h : Duml.PROLOGUE) {
            send(h == null ? Duml.heartbeat() : Duml.hex(h));
            waitMs(50);
        }
        send(Duml.pushStatus());
        waitMs(50);
        for (int i = 0; i < 3; i++) {
            send(Duml.heartbeat());
            waitMs(400);
        }
        for (int i = 0; i < 5; i++) {
            send(Duml.stick(0f, 0f));
            waitMs(140);
        }
        runLater(() -> {
            main.removeCallbacks(connectTimeout);
            retries = 0;
            error = "";
            setState(State.READY);
            main.removeCallbacks(heartbeat);
            main.postDelayed(heartbeat, 1000);
        });
    }

    private static final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (state != State.READY) return;
            if (ops.size() < 16) send(Duml.heartbeat());
            main.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- joystick

    /** yaw/pitch: -1..1 of full stick throw. Keep calling at least every 0.4 s or it stops. */
    public static void setStick(float yaw, float pitch) {
        stickYaw = yaw;
        stickPitch = pitch;
        long now = SystemClock.uptimeMillis();
        stickAt = now;
        if (yaw != 0f || pitch != 0f) stickNonZeroAt = now;
        main.post(() -> {
            if (!stickOn) {
                stickOn = true;
                main.post(stickTick);
            }
        });
    }

    /** Stop moving now. */
    public static void halt() {
        stickYaw = 0f;
        stickPitch = 0f;
        main.post(() -> {
            boolean was = stickOn;
            stickOn = false;
            main.removeCallbacks(stickTick);
            if (state == State.READY && was) {
                for (int i = 0; i < 3; i++) {
                    send(Duml.stick(0f, 0f));
                    waitMs(60);
                }
            }
        });
    }

    private static final Runnable stickTick = new Runnable() {
        @Override
        public void run() {
            if (!stickOn) return;
            long now = SystemClock.uptimeMillis();
            float y = stickYaw, p = stickPitch;
            if (now - stickAt > 400) {
                y = 0f;
                p = 0f;
            }
            if (state == State.READY && ops.size() < 8) send(Duml.stick(y, p));
            if (now - stickAt > 3000 && now - stickNonZeroAt > 3000) {
                stickOn = false; // nobody is steering any more
                return;
            }
            main.postDelayed(this, 100);
        }
    };

    public static void recenter() {
        halt();
        main.post(() -> {
            if (state != State.READY) return;
            waitMs(80);
            send(Duml.recenter());
            waitMs(150);
            send(Duml.recenter());
        });
    }

    public static void wake() {
        main.post(() -> {
            if (state == State.READY) send(Duml.wake());
        });
    }

    // ---------------------------------------------------------------- incoming

    private static void handle(byte[] f) {
        int set = f[9] & 0xFF, id = f[10] & 0xFF;
        int n = f.length - 13; // payload length
        if (set == 0x0D && id == 0x02 && n >= 17) {
            int b = f[11 + n - 1] & 0xFF;
            if (b <= 100 && (battery == null || battery != b)) {
                battery = b;
                changed();
            }
        } else if (set == 0x04 && id == 0x27 && n > 4) {
            boolean a = (f[15] & 1) == 1;
            if (a != asleep) {
                asleep = a;
                changed();
            }
        }
    }

    // ---------------------------------------------------------------- GATT queue

    static void send(byte[] frame) {
        BluetoothGattCharacteristic w = writeChar;
        if (w == null) return;
        int size = chunk;
        for (int i = 0; i < frame.length; i += size) {
            write(w, Arrays.copyOfRange(frame, i, Math.min(frame.length, i + size)));
        }
    }

    private static void write(BluetoothGattCharacteristic c, byte[] data) {
        Op o = new Op();
        o.kind = WRITE_OP;
        o.c = c;
        o.data = data;
        o.type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        enqueue(o);
    }

    private static void waitMs(long ms) {
        Op o = new Op();
        o.kind = WAIT_OP;
        o.ms = ms;
        enqueue(o);
    }

    private static void runLater(Runnable r) {
        Op o = new Op();
        o.kind = RUN_OP;
        o.run = r;
        enqueue(o);
    }

    private static void enqueue(Op o) {
        ops.add(o);
        if (!busy) next();
    }

    @SuppressWarnings("deprecation")
    private static void next() {
        while (!busy) {
            Op o = ops.poll();
            if (o == null) return;
            if (o.kind == RUN_OP) {
                o.run.run();
                continue;
            }
            BluetoothGatt g = gatt;
            if (g == null) {
                ops.clear();
                return;
            }
            busy = true;
            curKind = o.kind;
            final int t = ++token;
            long wait = 700;
            boolean started;
            try {
                if (o.kind == WRITE_OP) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        started = g.writeCharacteristic(o.c, o.data, o.type) == BluetoothStatusCodes.SUCCESS;
                    } else {
                        o.c.setWriteType(o.type);
                        o.c.setValue(o.data);
                        started = g.writeCharacteristic(o.c);
                    }
                    if (o.type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) wait = 150;
                } else if (o.kind == DESC_OP) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        started = g.writeDescriptor(o.d, o.data) == BluetoothStatusCodes.SUCCESS;
                    } else {
                        o.d.setValue(o.data);
                        started = g.writeDescriptor(o.d);
                    }
                } else if (o.kind == MTU_OP) {
                    started = g.requestMtu(185);
                } else {
                    started = true;
                    wait = o.ms;
                }
            } catch (Exception e) {
                started = false;
            }
            if (!started) {
                busy = false;
                continue;
            }
            main.postDelayed(() -> {
                if (token == t && busy) {
                    busy = false;
                    next();
                }
            }, wait);
        }
    }

    private static void complete(int kind) {
        if (busy && curKind == kind) {
            busy = false;
            token++;
            next();
        }
    }

    // ---------------------------------------------------------------- scanning

    private static ScanCallback scanCallback;
    private static final Runnable scanStop = Gimbal::stopScan;

    /** Lists nearby Bluetooth LE devices that have a name. Returns false if Bluetooth is off. */
    public static boolean scan(Context c, ScanListener l) {
        app = c.getApplicationContext();
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) return false;
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) return false;
        stopScan();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (n == null) {
                    try {
                        n = r.getDevice().getName();
                    } catch (Exception ignored) {
                    }
                }
                if (n == null || n.isEmpty()) return;
                final String found = n;
                final BluetoothDevice d = r.getDevice();
                main.post(() -> l.onFound(d, found));
            }
        };
        try {
            s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        } catch (Exception e) {
            scanCallback = null;
            return false;
        }
        main.removeCallbacks(scanStop);
        main.postDelayed(scanStop, 15000);
        return true;
    }

    public static void stopScan() {
        main.removeCallbacks(scanStop);
        ScanCallback cb = scanCallback;
        scanCallback = null;
        if (cb == null || app == null) return;
        try {
            BluetoothAdapter a = adapter(app);
            if (a != null && a.getBluetoothLeScanner() != null) a.getBluetoothLeScanner().stopScan(cb);
        } catch (Exception ignored) {
        }
    }
}
