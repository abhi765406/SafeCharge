package com.safecharge.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** All saved settings live here so every screen and the service agree. */
final class Prefs {

    private Prefs() {}

    static final String FILE = "safecharge";

    // Master switch + charge-mode options
    static final String ENABLED = "enabled";
    static final String OPT_KILL = "opt_kill";
    static final String OPT_DIM = "opt_dim";
    static final String OPT_SYNC = "opt_sync";
    static final String OPT_BT = "opt_bt";
    static final String OPT_WIFI = "opt_wifi";
    static final String OPT_DEEP = "opt_deep";
    static final String ALERT_LEVEL = "alert_level";   // 0 = off
    static final String TEMP_ALERT = "temp_alert";     // degrees C, 0 = off

    // Things we changed while charging and must put back afterwards
    static final String RESTORE_PENDING = "restore_pending";
    static final String SAVED_BRIGHT = "saved_bright";
    static final String SAVED_BMODE = "saved_bmode";
    static final String SAVED_SYNC = "saved_sync";
    static final String SAVED_BT = "saved_bt";
    static final String SAVED_WIFI = "saved_wifi";

    static final String PROTECTED = "protected";
    static final String SESSIONS = "sessions";
    static final String ADB_PORT = "adb_port";

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Set<String> protectedSet(Context c) {
        Set<String> stored = get(c).getStringSet(PROTECTED, null);
        return stored == null ? new HashSet<String>() : new HashSet<String>(stored);
    }

    static void saveProtected(Context c, Set<String> set) {
        get(c).edit().putStringSet(PROTECTED, new HashSet<String>(set)).apply();
    }
}
