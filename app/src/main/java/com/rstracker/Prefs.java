package com.rstracker;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings. */
public final class Prefs {
    private static SharedPreferences sp;

    private Prefs() {}

    public static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("rstracker", Context.MODE_PRIVATE);
    }

    public static String str(String key, String def) { return sp.getString(key, def); }

    public static int num(String key, int def) { return sp.getInt(key, def); }

    public static boolean flag(String key, boolean def) { return sp.getBoolean(key, def); }

    public static void put(String key, String v) { sp.edit().putString(key, v).apply(); }

    public static void put(String key, int v) { sp.edit().putInt(key, v).apply(); }

    public static void put(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    public static String follow() { return str("follow", "person"); }

    public static int pitchSign() { return num("pitchSign", 1); }

    public static void resetDirections() {
        put("yawAxis", 0);
        put("yawSign", -1);
        put("pitchAxis", 1);
        put("pitchSign", 1);
        put("dirState", "default");
    }

    public static Follow.Config config() {
        Follow.Config c = new Follow.Config();
        c.face = "face".equals(follow());
        c.aim = str("aim", "upper");
        c.tilt = flag("tilt", true);
        c.maxSpeed = num("maxSpeed", 50) / 100f;
        c.response = num("response", 4);
        c.smooth = num("smooth", 5);
        c.deadzone = num("deadzone", 7) / 100f;
        c.goalX = num("goalX", 50) / 100f;
        c.goalY = num("goalY", 40) / 100f;
        c.yawAxis = num("yawAxis", 0) == 1 ? 1 : 0;
        c.yawSign = num("yawSign", -1) >= 0 ? 1 : -1;
        c.pitchAxis = 1 - c.yawAxis;
        c.pitchSign = num("pitchSign", 1) >= 0 ? 1 : -1;
        return c;
    }
}
