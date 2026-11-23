package com.rstracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Watches the screen (Monitor & Control's camera picture), finds you in it and steers the gimbal.
 * Runs as a foreground service so it keeps going while Monitor & Control is on screen.
 */
public final class TrackerService extends Service {
    public static final String ACT_START = "com.rstracker.START";
    public static final String ACT_STOP = "com.rstracker.STOP";
    public static final String ACT_TOGGLE = "com.rstracker.TOGGLE";
    static volatile TrackerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int capW, capH, dpi;

    // worker-thread state
    private Bitmap frame, small, crop;
    private Canvas cropCanvas;
    private ByteBuffer pad;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Detector detector;
    private long detectorFailedAt;
    private final Follow follow = new Follow();
    private long lastProcessed, lastFind, runawaySince;
    private int[] rect, pending;
    private int pendingHits;

    // shared state
    volatile boolean tracking;
    volatile boolean manual;
    private volatile Learner learner;
    private volatile boolean resumeAfterLearn;
    private volatile String detectorError;
    private volatile long lastFrameAt;
    private volatile String flash;
    private volatile long flashUntil;
    private OverlayPanel overlay;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (ACT_STOP.equals(a)) {
            stopSelf();
        } else if (ACT_TOGGLE.equals(a)) {
            if (projection != null) setTracking(!tracking);
            else stopSelf();
        } else if (ACT_START.equals(a) && projection == null) {
            begin(i);
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent i) {
        Prefs.init(this);
        tracking = true;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, notification());
            }
        } catch (Exception e) {
            stopSelf();
            return;
        }
        int code = i.getIntExtra("code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra("data", Intent.class) : legacyData(i);
        capW = i.getIntExtra("w", 720);
        capH = i.getIntExtra("h", 1560);
        dpi = i.getIntExtra("dpi", 320);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = data == null ? null : mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return;
        }
        thread = new HandlerThread("rs-tracker");
        thread.start();
        worker = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(TrackerService.this::stopSelf);
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                worker.post(() -> resize(width, height));
            }
        }, main);
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, worker);
        try {
            display = projection.createVirtualDisplay("RS Tracker", capW, capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
        } catch (Exception e) {
            stopSelf();
            return;
        }
        instance = this;
        if (Settings.canDrawOverlays(this)) {
            try {
                overlay = new OverlayPanel(this);
            } catch (Exception e) {
                overlay = null;
            }
        }
        worker.post(this::loadDetector);
        main.post(statusTick);
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyData(Intent i) {
        return i.getParcelableExtra("data");
    }

    // ---------------------------------------------------------------- controls (main thread)

    void setTracking(boolean on) {
        if (learner != null) return;
        manual = false;
        tracking = on;
        Handler w = worker;
        if (w != null) w.post(follow::reset);
        if (!on) Gimbal.halt();
        refreshNotification();
    }

    void manualStick(float x, float y, boolean active) {
        if (learner != null) return;
        if (active) {
            if (tracking) {
                setTracking(false);
                say("Paused while you steer");
            }
            manual = true;
            Gimbal.setStick(x, -y);
        } else if (manual) {
            manual = false;
            Gimbal.halt();
        }
    }

    void recenter() {
        if (learner != null) return;
        if (tracking) setTracking(false);
        Gimbal.recenter();
        say("Recentering. Tap Track to follow again.");
    }

    void onStatusTap() {
        if (Gimbal.ready() && Gimbal.asleep) {
            Gimbal.wake();
            say("Waking the gimbal…");
        }
    }

    void learn() {
        Handler w = worker;
        if (w == null) return;
        w.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (learner != null) return;
            if (!Gimbal.ready()) {
                say("Connect the gimbal first");
                return;
            }
            if (!follow.fresh(now)) {
                say("Stand in the picture first, then tap Learn");
                return;
            }
            resumeAfterLearn = tracking;
            tracking = false;
            manual = false;
            Gimbal.halt();
            learner = new Learner(now, Prefs.pitchSign());
            say("Learning. Stand still for about 8 seconds.");
        });
    }

    private void say(String msg) {
        flash = msg;
        flashUntil = SystemClock.uptimeMillis() + 4000;
    }

    // ---------------------------------------------------------------- capture (worker thread)

    private void resize(int w, int h) {
        if (display == null || w <= 0 || h <= 0) return;
        float s = Math.min(1f, 720f / Math.min(w, h));
        int nw = Math.max(2, Math.round(w * s) & ~1), nh = Math.max(2, Math.round(h * s) & ~1);
        if (nw == capW && nh == capH) return;
        capW = nw;
        capH = nh;
        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, worker);
        try {
            display.resize(capW, capH, dpi);
            display.setSurface(reader.getSurface());
        } catch (Exception ignored) {
        }
        if (old != null) old.close();
        rect = null;
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            lastFrameAt = now;
            if (now - lastProcessed < 60) return;
            lastProcessed = now;
            Image.Plane p = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int rowPx = p.getRowStride() / p.getPixelStride();
            if (frame == null || frame.getWidth() != rowPx || frame.getHeight() != h) {
                frame = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buf = p.getBuffer();
            buf.rewind();
            int need = rowPx * h * 4;
            if (buf.remaining() < need) {
                if (pad == null || pad.capacity() < need) pad = ByteBuffer.allocateDirect(need);
                pad.clear();
                pad.put(buf);
                pad.position(0);
                frame.copyPixelsFromBuffer(pad);
            } else {
                frame.copyPixelsFromBuffer(buf);
            }
            process(w, h, now);
        } catch (Throwable e) {
            say("Problem reading the screen");
        } finally {
            img.close();
        }
    }

    private void process(int w, int h, long now) {
        if (rect == null || now - lastFind > 700) {
            lastFind = now;
            findPicture(w, h);
        }
        int[] r = rect != null ? rect : new int[]{0, 0, w, h};
        int rw = r[2] - r[0], rh = r[3] - r[1];
        if (rw < 32 || rh < 32) return;

        boolean wantFace = "face".equals(Prefs.follow());
        if (detector == null || detector.face != wantFace) loadDetector();
        if (detector == null) return;

        float s = Math.min(1f, 640f / rw);
        int cw = Math.max(32, Math.round(rw * s)), ch = Math.max(32, Math.round(rh * s));
        if (crop == null || crop.getWidth() != cw || crop.getHeight() != ch) {
            crop = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            cropCanvas = new Canvas(crop);
        }
        cropCanvas.drawBitmap(frame, new Rect(r[0], r[1], r[2], r[3]), new Rect(0, 0, cw, ch), paint);

        List<Follow.Box> found;
        try {
            found = detector.detect(crop, now);
        } catch (Throwable e) {
            found = Collections.emptyList();
        }
        Learner l = learner;
        follow.loose = l != null;
        follow.track(found, now);
        Follow.Config c = Prefs.config();

        if (l != null) {
            float[] aim = follow.fresh(now) ? Follow.aim(follow.box, c) : null;
            float[] st = l.step(now, aim);
            if (l.done) {
                endLearning(l);
                return;
            }
            if (Gimbal.ready()) Gimbal.setStick(st[0], st[1]);
            return;
        }

        float[] st = follow.step(now, tracking && !manual, c);
        if (tracking && !manual && Gimbal.ready()) {
            Gimbal.setStick(st[0], st[1]);
            // Safety: if it keeps turning hard for 10 s, something is wrong (e.g. it isn't the picture we're moving).
            if (c.maxSpeed > 0f && Math.abs(st[0]) > 0.8f * c.maxSpeed) {
                if (runawaySince == 0) runawaySince = now;
                else if (now - runawaySince > 10000) {
                    runawaySince = 0;
                    main.post(() -> {
                        setTracking(false);
                        say("Paused: it kept turning for 10 s");
                    });
                }
            } else {
                runawaySince = 0;
            }
        }
    }

    private void endLearning(Learner l) {
        learner = null;
        Gimbal.halt();
        if (l.ok) {
            Prefs.put("yawAxis", l.yawAxis);
            Prefs.put("yawSign", l.yawSign);
            Prefs.put("pitchAxis", l.pitchAxis);
            Prefs.put("pitchSign", l.pitchSign);
            Prefs.put("dirState", "learned");
        }
        follow.reset();
        say(l.message);
        if (resumeAfterLearn) main.post(() -> setTracking(true));
    }

    private void loadDetector() {
        boolean face = "face".equals(Prefs.follow());
        if (detector != null && detector.face == face) return;
        if (detector == null && SystemClock.uptimeMillis() - detectorFailedAt < 5000 && detectorFailedAt != 0) return;
        if (detector != null) {
            detector.close();
            detector = null;
        }
        try {
            detector = new Detector(this, face);
            detectorError = null;
        } catch (Throwable e) {
            detector = null;
            detectorFailedAt = SystemClock.uptimeMillis();
            detectorError = "Person finder didn't load";
        }
    }

    /** Where is the camera picture on screen? Re-checked every 0.7 s; needs two votes to move. */
    private void findPicture(int w, int h) {
        int sw = 96, sh = Math.max(16, Math.round(h * 96f / w));
        if (small == null || small.getWidth() != sw || small.getHeight() != sh) {
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        }
        new Canvas(small).drawBitmap(frame, new Rect(0, 0, w, h), new Rect(0, 0, sw, sh), paint);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        int[] f = LiveViewFinder.find(px, sw, sh);
        if (f == null) return;
        int[] c = {f[0] * w / sw, f[1] * h / sh, f[2] * w / sw, f[3] * h / sh};
        if (rect == null) {
            rect = c;
            pending = null;
        } else if (iou(rect, c) > 0.85f) {
            int[] b = new int[4];
            for (int k = 0; k < 4; k++) b[k] = Math.round(rect[k] * 0.7f + c[k] * 0.3f);
            rect = b;
            pending = null;
        } else if (pending != null && iou(pending, c) > 0.85f) {
            if (++pendingHits >= 2) {
                rect = c;
                pending = null;
                follow.box = null;
            }
        } else {
            pending = c;
            pendingHits = 1;
        }
    }

    private static float iou(int[] a, int[] b) {
        int x0 = Math.max(a[0], b[0]), y0 = Math.max(a[1], b[1]);
        int x1 = Math.min(a[2], b[2]), y1 = Math.min(a[3], b[3]);
        float inter = Math.max(0, x1 - x0) * (float) Math.max(0, y1 - y0);
        float union = (a[2] - a[0]) * (float) (a[3] - a[1]) + (b[2] - b[0]) * (float) (b[3] - b[1]) - inter;
        return union <= 0 ? 0f : inter / union;
    }

    // ---------------------------------------------------------------- status (main thread)

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (instance != TrackerService.this) return;
            long now = SystemClock.uptimeMillis();
            String s;
            int color;
            Gimbal.State g = Gimbal.state();
            if (detectorError != null) {
                s = detectorError;
                color = Ui.RED;
            } else if (g == Gimbal.State.OFF) {
                s = "Gimbal not connected";
                color = Ui.RED;
            } else if (g != Gimbal.State.READY) {
                s = "Connecting to gimbal…";
                color = Ui.YELLOW;
            } else if (Gimbal.asleep) {
                s = "Gimbal asleep. Tap here to wake";
                color = Ui.YELLOW;
            } else if (now - lastFrameAt > 2500) {
                s = "No picture. Open Monitor & Control";
                color = Ui.YELLOW;
            } else if (learner != null) {
                s = "Learning directions…";
                color = Ui.YELLOW;
            } else if (manual) {
                s = "Steering by hand";
                color = Ui.GREY;
            } else if (!tracking) {
                s = "Paused";
                color = Ui.GREY;
            } else if (follow.fresh(now)) {
                s = "Tracking you";
                color = Ui.GREEN;
            } else if (follow.box != null) {
                s = "Lost you, holding still";
                color = Ui.YELLOW;
            } else {
                s = "Looking for you…";
                color = Ui.YELLOW;
            }
            String f = flash;
            if (f != null && now < flashUntil) s = f;
            if (overlay != null) overlay.update(s, color, tracking, Gimbal.ready() ? Gimbal.battery : null);
            main.postDelayed(this, 300);
        }
    };

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel("tracker", "Tracking", NotificationManager.IMPORTANCE_LOW));
        }
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, TrackerService.class).setAction(ACT_TOGGLE), flags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, TrackerService.class).setAction(ACT_STOP), flags);
        return new Notification.Builder(this, "tracker")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("RS Tracker")
                .setContentText(tracking ? "Following you" : "Paused")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, tracking ? "Pause" : "Track", toggle).build())
                .addAction(new Notification.Action.Builder((Icon) null, "Stop", stop).build())
                .build();
    }

    private void refreshNotification() {
        if (instance != this) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, notification());
    }

    @Override
    public void onDestroy() {
        instance = null;
        tracking = false;
        learner = null;
        Gimbal.halt();
        main.removeCallbacks(statusTick);
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
        final VirtualDisplay d = display;
        final ImageReader r = reader;
        final MediaProjection p = projection;
        display = null;
        reader = null;
        projection = null;
        Handler w = worker;
        Runnable cleanup = () -> {
            try {
                if (d != null) d.release();
            } catch (Exception ignored) {
            }
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
            if (detector != null) detector.close();
            detector = null;
        };
        if (w != null) w.post(cleanup);
        else cleanup.run();
        try {
            if (p != null) p.stop();
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
