package com.rstracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Set-up screen: connect the gimbal, start tracking, and settings. */
public final class MainActivity extends Activity implements Gimbal.Listener {
    private static final int REQ_BLUETOOTH = 1, REQ_CAPTURE = 2, REQ_NOTIFY = 3;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout column;
    private View gimbalDot;
    private TextView gimbalText, findBtn, disconnectBtn, startBtn, overlayText, directionText;
    private Runnable afterBluetooth;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.init(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(28), dp(18), dp(40));
        scroll.addView(column, new ScrollView.LayoutParams(MATCH, WRAP));
        setContentView(scroll);

        header();
        gimbalCard();
        startCard();
        followCard();
        motionCard();
        directionsCard();
        helpCard();
        autoConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Gimbal.addListener(this);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Gimbal.removeListener(this);
        handler.removeCallbacks(tick);
    }

    @Override
    public void onGimbalChanged() {
        refresh();
    }

    // ---------------------------------------------------------------- screen

    private void header() {
        column.addView(Ui.text(this, "RS Tracker", 30, Ui.INK, true));
        TextView s = Ui.text(this, "Your RS 3 Mini follows you, using the Sony picture in Monitor & Control.", 15, Ui.MUTED, false);
        s.setPadding(0, dp(6), 0, dp(4));
        column.addView(s);
    }

    private void gimbalCard() {
        LinearLayout c = card("Gimbal");
        LinearLayout row = hrow(0);
        gimbalDot = Ui.dot(this, Ui.GREY);
        row.addView(gimbalDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        gimbalText = Ui.text(this, "Not connected", 16, Ui.INK, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.leftMargin = dp(10);
        row.addView(gimbalText, tl);
        c.addView(row);

        LinearLayout buttons = hrow(12);
        findBtn = Ui.button(this, "Find gimbal", Ui.YELLOW, Ui.BLACK);
        disconnectBtn = Ui.button(this, "Disconnect", Ui.BUTTON, Ui.INK);
        buttons.addView(findBtn, weight(0));
        buttons.addView(disconnectBtn, weight(8));
        c.addView(buttons);
        findBtn.setOnClickListener(v -> findGimbal());
        disconnectBtn.setOnClickListener(v -> {
            Gimbal.disconnect();
            handler.postDelayed(this::refresh, 300);
        });
        note(c, "Close the DJI Ronin app first (swipe it away). The gimbal only talks to one app at a time.");

        LinearLayout test = hrow(12);
        JoystickView joystick = new JoystickView(this);
        test.addView(joystick, new LinearLayout.LayoutParams(dp(132), dp(132)));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView recenter = Ui.button(this, "Recenter", Ui.BUTTON, Ui.INK);
        TextView wake = Ui.button(this, "Wake motors", Ui.BUTTON, Ui.INK);
        side.addView(recenter, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(MATCH, WRAP);
        wl.topMargin = dp(8);
        side.addView(wake, wl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        sl.leftMargin = dp(14);
        sl.gravity = Gravity.CENTER_VERTICAL;
        test.addView(side, sl);
        c.addView(test);
        joystick.setListener((x, y, active) -> {
            TrackerService s = TrackerService.instance;
            if (s != null) {
                s.manualStick(x, y, active);
            } else if (active) {
                Gimbal.setStick(x, -y);
            } else {
                Gimbal.halt();
            }
        });
        recenter.setOnClickListener(v -> {
            TrackerService s = TrackerService.instance;
            if (s != null) s.recenter();
            else Gimbal.recenter();
        });
        wake.setOnClickListener(v -> Gimbal.wake());
        note(c, "Test it: drag the stick and the gimbal should move.");
    }

    private void startCard() {
        LinearLayout c = card("Start");
        startBtn = Ui.button(this, "Start tracking", Ui.YELLOW, Ui.BLACK);
        startBtn.setTextSize(17);
        startBtn.setMinHeight(dp(56));
        c.addView(startBtn, new LinearLayout.LayoutParams(MATCH, WRAP));
        startBtn.setOnClickListener(v -> onStartPressed());
        overlayText = Ui.text(this, "", 14, Ui.MUTED, false);
        overlayText.setPadding(0, dp(12), 0, dp(2));
        overlayText.setOnClickListener(v -> askOverlayPermission());
        c.addView(overlayText);
        note(c, "Android asks what to share: pick Entire screen (simplest) or Monitor & Control. Monitor & Control then opens with a small RS Tracker panel on top. If nothing happens after picking the app, come back here once.");
    }

    private void followCard() {
        LinearLayout c = card("Follow");
        segmented(c, "follow", "person", new String[]{"person", "face"}, new String[]{"Whole person", "Face"});
        label(c, "Keep in frame");
        segmented(c, "aim", "upper", new String[]{"head", "upper", "body"}, new String[]{"Head", "Upper body", "Whole body"});
        toggle(c, "Follow up and down (tilt)", "tilt", true);
        note(c, "Whole person works from far away and when you turn around. Face is best for close-up talking shots.");
    }

    private void motionCard() {
        LinearLayout c = card("Motion");
        slider(c, "Max speed", "maxSpeed", 50, 10, 100, "%");
        slider(c, "Response", "response", 4, 1, 10, "");
        slider(c, "Smoothness", "smooth", 5, 1, 10, "");
        slider(c, "Dead zone", "deadzone", 7, 2, 20, "%");
        slider(c, "Your spot: left to right", "goalX", 50, 10, 90, "%");
        slider(c, "Your spot: top to bottom", "goalY", 40, 10, 90, "%");
        note(c, "The picture arrives a little late over Wi-Fi, so calm settings track best. Swinging back and forth? Lower Response or raise Smoothness.");
    }

    private void directionsCard() {
        LinearLayout c = card("Directions");
        directionText = Ui.text(this, "", 15, Ui.INK, true);
        c.addView(directionText);
        note(c, "Do this once: start tracking, stand still in the picture, then tap \"Learn directions\" on the floating panel. The gimbal nudges right, up and back to learn which way is which.");
        LinearLayout r1 = hrow(12);
        TextView flipPan = Ui.button(this, "Flip pan", Ui.BUTTON, Ui.INK);
        TextView flipTilt = Ui.button(this, "Flip tilt", Ui.BUTTON, Ui.INK);
        r1.addView(flipPan, weight(0));
        r1.addView(flipTilt, weight(8));
        c.addView(r1);
        LinearLayout r2 = hrow(8);
        TextView swap = Ui.button(this, "Swap pan/tilt", Ui.BUTTON, Ui.INK);
        TextView reset = Ui.button(this, "Reset", Ui.BUTTON, Ui.INK);
        r2.addView(swap, weight(0));
        r2.addView(reset, weight(8));
        c.addView(r2);
        flipPan.setOnClickListener(v -> {
            Prefs.put("yawSign", -Prefs.config().yawSign);
            Prefs.put("dirState", "custom");
            refresh();
        });
        flipTilt.setOnClickListener(v -> {
            Prefs.put("pitchSign", -Prefs.config().pitchSign);
            Prefs.put("dirState", "custom");
            refresh();
        });
        swap.setOnClickListener(v -> {
            int yaw = Prefs.config().yawAxis;
            Prefs.put("yawAxis", 1 - yaw);
            Prefs.put("pitchAxis", yaw);
            Prefs.put("dirState", "custom");
            refresh();
        });
        reset.setOnClickListener(v -> {
            Prefs.resetDirections();
            refresh();
        });
    }

    private void helpCard() {
        LinearLayout c = card("How to use");
        String[] steps = {
                "1. Connect your ZV-E10 II in Monitor & Control as usual.",
                "2. Close the DJI Ronin app, then tap Find gimbal and pick your RS 3 Mini.",
                "3. Tap Start tracking and choose Entire screen (or Monitor & Control).",
                "4. Step into the picture. It follows the biggest person and holds still if it loses you.",
                "5. First time only: tap \"Learn directions\" on the floating panel.",
        };
        for (String s : steps) {
            TextView t = Ui.text(this, s, 15, Ui.INK, false);
            t.setPadding(0, dp(4), 0, dp(4));
            c.addView(t);
        }
        label(c, "If something's off");
        note(c, "Gimbal not in the list: close the Ronin app, switch the gimbal off and on, and turn on Bluetooth (and Location on older phones).");
        note(c, "Turns the wrong way: Learn directions again, or use Flip pan / Flip tilt.");
        note(c, "Shaky or swinging: lower Response, raise Smoothness or Dead zone.");
        note(c, "Unofficial: this uses DJI's undocumented Bluetooth commands (decoded by the open-source rs3mini-bridge project). It isn't made or supported by DJI or Sony, and a gimbal firmware update could stop it working. Keep a hand near the gimbal the first time.");
    }

    // ---------------------------------------------------------------- state

    private void refresh() {
        Gimbal.State s = Gimbal.state();
        String text;
        int color;
        switch (s) {
            case READY:
                text = Gimbal.name + (Gimbal.battery != null ? "  ·  " + Gimbal.battery + "%" : "")
                        + (Gimbal.asleep ? "  ·  asleep" : "");
                color = Gimbal.asleep ? Ui.YELLOW : Ui.GREEN;
                break;
            case CONNECTING:
                text = "Connecting to " + Gimbal.name + "…";
                color = Ui.YELLOW;
                break;
            case LOST:
                text = "Connection lost, retrying…";
                color = Ui.YELLOW;
                break;
            default:
                text = Gimbal.error.isEmpty() ? "Not connected" : Gimbal.error;
                color = Ui.GREY;
                break;
        }
        gimbalText.setText(text);
        Ui.setDot(gimbalDot, color);
        findBtn.setText(s == Gimbal.State.READY ? "Change gimbal" : "Find gimbal");
        disconnectBtn.setVisibility(s == Gimbal.State.OFF ? View.GONE : View.VISIBLE);

        boolean running = TrackerService.instance != null;
        startBtn.setText(running ? "Stop tracking" : "Start tracking");
        Ui.setButtonColor(startBtn, running ? Ui.RED : Ui.YELLOW, running ? Ui.INK : Ui.BLACK);
        overlayText.setText(Settings.canDrawOverlays(this)
                ? "Floating panel: allowed"
                : "Floating panel: not allowed yet. Tap here to allow it.");

        String dir = Prefs.str("dirState", "default");
        directionText.setText("learned".equals(dir) ? "Learned on your gimbal"
                : "custom".equals(dir) ? "Set by hand" : "Using standard directions (not learned yet)");
    }

    // ---------------------------------------------------------------- gimbal

    private boolean hasBluetoothPermission() {
        for (String p : bluetoothPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private static String[] bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void withBluetooth(Runnable next) {
        if (hasBluetoothPermission()) {
            bluetoothOn(next);
        } else {
            afterBluetooth = next;
            requestPermissions(bluetoothPermissions(), REQ_BLUETOOTH);
        }
    }

    private void bluetoothOn(Runnable next) {
        BluetoothAdapter a = Gimbal.adapter(this);
        if (a == null) {
            toast("This phone has no Bluetooth");
            return;
        }
        if (!a.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
            }
            toast("Turn on Bluetooth, then tap again");
            return;
        }
        next.run();
    }

    private void autoConnect() {
        String address = Prefs.str("lastDevice", null);
        if (address == null || Gimbal.state() != Gimbal.State.OFF || !hasBluetoothPermission()) return;
        BluetoothAdapter a = Gimbal.adapter(this);
        if (a == null || !a.isEnabled()) return;
        try {
            Gimbal.connect(this, a.getRemoteDevice(address));
        } catch (Exception ignored) {
        }
    }

    private void findGimbal() {
        withBluetooth(() -> {
            final List<BluetoothDevice> devices = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
            final AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Looking for your gimbal…")
                    .setAdapter(adapter, (d, which) -> {
                        Gimbal.stopScan();
                        Gimbal.connect(this, devices.get(which));
                        handler.postDelayed(this::refresh, 200);
                    })
                    .setNegativeButton("Cancel", (d, w) -> Gimbal.stopScan())
                    .create();
            dialog.setOnDismissListener(d -> Gimbal.stopScan());
            boolean started = Gimbal.scan(this, (device, name) -> {
                for (BluetoothDevice known : devices) {
                    if (known.getAddress().equals(device.getAddress())) return;
                }
                String upper = name.toUpperCase(Locale.ROOT);
                boolean likely = upper.contains("DJI") || upper.startsWith("RS") || upper.contains("RONIN");
                if (likely) {
                    devices.add(0, device);
                    names.add(0, name);
                } else {
                    devices.add(device);
                    names.add(name);
                }
                adapter.notifyDataSetChanged();
                if (likely) dialog.setTitle("Tap your gimbal");
            });
            if (!started) {
                toast("Couldn't search for Bluetooth devices. Is Bluetooth on?");
                return;
            }
            dialog.show();
            handler.postDelayed(() -> {
                if (!dialog.isShowing()) return;
                boolean any = false;
                for (String n : names) {
                    String u = n.toUpperCase(Locale.ROOT);
                    if (u.contains("DJI") || u.startsWith("RS") || u.contains("RONIN")) any = true;
                }
                if (!any) dialog.setTitle("No gimbal yet. Close the Ronin app and restart the gimbal");
            }, 8000);
        });
    }

    // ---------------------------------------------------------------- start / stop

    private void onStartPressed() {
        if (TrackerService.instance != null) {
            stopService(new Intent(this, TrackerService.class));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.flag("askedNotify", false)) {
            Prefs.put("askedNotify", true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        if (!Settings.canDrawOverlays(this) && !Prefs.flag("skipOverlay", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("Floating panel")
                    .setMessage("Let RS Tracker show its small control panel on top of Monitor & Control? Without it you can still pause and stop from the notification.")
                    .setPositiveButton("Allow", (d, w) -> askOverlayPermission())
                    .setNegativeButton("Not now", (d, w) -> {
                        Prefs.put("skipOverlay", true);
                        requestCapture();
                    })
                    .show();
            return;
        }
        requestCapture();
    }

    private void askOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            toast("Turn on \"Display over other apps\", then come back and tap Start tracking");
        } catch (Exception e) {
            toast("Open Settings > Apps > RS Tracker > Display over other apps");
        }
    }

    private void requestCapture() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            toast("Screen sharing isn't available on this phone");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            toast("Screen sharing was cancelled");
            return;
        }
        int[] size = captureSize();
        Intent i = new Intent(this, TrackerService.class)
                .setAction(TrackerService.ACT_START)
                .putExtra("code", result)
                .putExtra("data", data)
                .putExtra("w", size[0])
                .putExtra("h", size[1])
                .putExtra("dpi", size[2]);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            toast("Couldn't start tracking: " + e.getMessage());
            return;
        }
        handler.postDelayed(this::openMonitorApp, 500);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_BLUETOOTH) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            Runnable next = afterBluetooth;
            afterBluetooth = null;
            if (ok && next != null) bluetoothOn(next);
            else if (!ok) toast("Bluetooth permission is needed to reach the gimbal");
        } else if (code == REQ_NOTIFY) {
            onStartPressed();
        }
    }

    @SuppressWarnings("deprecation")
    private int[] captureSize() {
        int w, h;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        float s = Math.min(1f, 720f / Math.max(1, Math.min(w, h)));
        int dpi = getResources().getDisplayMetrics().densityDpi;
        return new int[]{Math.max(2, Math.round(w * s) & ~1), Math.max(2, Math.round(h * s) & ~1), Math.max(120, Math.round(dpi * s))};
    }

    @SuppressWarnings("deprecation")
    private void openMonitorApp() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.contains("monitor") && (label.contains("ctrl") || label.contains("control"))) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    try {
                        startActivity(launch);
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        toast("Now open Monitor & Control");
    }

    // ---------------------------------------------------------------- view helpers

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(Ui.CARD, dp(18)));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        column.addView(c, lp);
        TextView t = Ui.text(this, title, 13, Ui.MUTED, true);
        t.setPadding(0, 0, 0, dp(10));
        c.addView(t);
        return c;
    }

    private LinearLayout hrow(int topMarginDp) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(topMarginDp);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout.LayoutParams weight(int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = dp(leftMarginDp);
        return lp;
    }

    private void note(LinearLayout parent, String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, false);
        t.setPadding(0, dp(10), 0, 0);
        t.setLineSpacing(0, 1.15f);
        parent.addView(t);
    }

    private void label(LinearLayout parent, String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, true);
        t.setPadding(0, dp(14), 0, dp(6));
        parent.addView(t);
    }

    private void segmented(LinearLayout parent, String key, String def, String[] values, String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.round(0xFF26262B, dp(12)));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        final TextView[] items = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            TextView t = Ui.text(this, labels[i], 14, Ui.INK, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(42));
            t.setPadding(dp(4), 0, dp(4), 0);
            final String value = values[i];
            t.setOnClickListener(v -> {
                Prefs.put(key, value);
                paintSegmented(items, values, key, def);
            });
            items[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        paintSegmented(items, values, key, def);
        parent.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void paintSegmented(TextView[] items, String[] values, String key, String def) {
        String current = Prefs.str(key, def);
        for (int i = 0; i < items.length; i++) {
            boolean on = values[i].equals(current);
            items[i].setBackground(on ? Ui.round(Ui.YELLOW, dp(10)) : null);
            items[i].setTextColor(on ? Ui.BLACK : Ui.INK);
        }
    }

    private void slider(LinearLayout parent, String name, String key, int def, int min, int max, String unit) {
        final TextView t = Ui.text(this, "", 15, Ui.INK, false);
        t.setPadding(0, dp(10), 0, 0);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Prefs.num(key, def) - min);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.YELLOW));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.YELLOW));
        final Runnable show = () -> t.setText(name + ": " + Prefs.num(key, def) + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (fromUser) {
                    Prefs.put(key, v + min);
                    show.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        show.run();
        parent.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(40));
        parent.addView(bar, lp);
    }

    private void toggle(LinearLayout parent, String name, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(name);
        s.setTextColor(Ui.INK);
        s.setTextSize(15);
        s.setChecked(Prefs.flag(key, def));
        s.setMinHeight(dp(48));
        s.setOnCheckedChangeListener((b, on) -> Prefs.put(key, on));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(8);
        parent.addView(s, lp);
    }
}
