package com.safecharge.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Battery reading, charging-session history and small formatting helpers. */
final class BatteryHelper {

    private BatteryHelper() {}

    static final class Info {
        int percent;
        boolean plugged;
        String plugName = "Not plugged in";
        String status = "Unknown";
        float tempC;
        float volts;
        String health = "Unknown";
        String tech = "";
        long currentMa;
    }

    static Info read(Context c) {
        Info info = new Info();
        Intent b = c.getApplicationContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return info;
        return fromIntent(c, b);
    }

    static Info fromIntent(Context c, Intent b) {
        Info info = new Info();
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        info.percent = scale > 0 && level >= 0 ? (int) ((level * 100f) / scale) : 0;

        int plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        info.plugged = plug != 0;
        info.plugName = plugName(plug);

        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        switch (st) {
            case BatteryManager.BATTERY_STATUS_CHARGING: info.status = "Charging"; break;
            case BatteryManager.BATTERY_STATUS_FULL: info.status = "Full"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: info.status = "Discharging"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: info.status = "Not charging"; break;
            default: info.status = "Unknown";
        }

        info.tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        info.volts = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;

        int h = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: info.health = "Good"; break;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: info.health = "Overheating"; break;
            case BatteryManager.BATTERY_HEALTH_DEAD: info.health = "Dead"; break;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: info.health = "Over voltage"; break;
            case BatteryManager.BATTERY_HEALTH_COLD: info.health = "Cold"; break;
            default: info.health = "Unknown";
        }
        String tech = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        info.tech = tech == null ? "" : tech;
        info.currentMa = currentMa(c);
        return info;
    }

    static String plugName(int plug) {
        switch (plug) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC wall charger";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB port (slow source)";
            case 4: return "Wireless pad"; // BATTERY_PLUGGED_WIRELESS
            case 0: return "Not plugged in";
            default: return "Charger";
        }
    }

    /** Current flowing in/out of the battery in mA (0 if the phone does not report it). */
    static long currentMa(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
                if (bm == null) return 0;
                int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (v == Integer.MIN_VALUE || v == 0) return 0;
                long a = Math.abs((long) v);
                // Most phones report microamps, a few report milliamps.
                return a >= 20000 ? a / 1000 : a;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ---------- charging history ----------

    static void addSession(Context c, long startMs, int startPct, long endMs, int endPct, long avgMa) {
        long dur = endMs - startMs;
        if (dur < 3 * 60 * 1000L) return; // ignore tiny plug/unplug blips
        SharedPreferences p = Prefs.get(c);
        String old = p.getString(Prefs.SESSIONS, "");
        String entry = startMs + "|" + startPct + "|" + endMs + "|" + endPct + "|" + avgMa;
        String all = old.isEmpty() ? entry : entry + ";" + old;
        String[] parts = all.split(";");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < 8; i++) {
            if (i > 0) sb.append(';');
            sb.append(parts[i]);
        }
        p.edit().putString(Prefs.SESSIONS, sb.toString()).apply();
    }

    static List<String> sessionLines(Context c) {
        List<String> out = new ArrayList<>();
        String raw = Prefs.get(c).getString(Prefs.SESSIONS, "");
        if (raw.isEmpty()) return out;
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
        for (String s : raw.split(";")) {
            try {
                String[] a = s.split("\\|");
                long start = Long.parseLong(a[0]);
                int sp = Integer.parseInt(a[1]);
                long end = Long.parseLong(a[2]);
                int ep = Integer.parseInt(a[3]);
                long ma = Long.parseLong(a[4]);
                float hours = (end - start) / 3600000f;
                int gained = ep - sp;
                String speed = gained > 0 && hours > 0
                        ? String.format(Locale.US, "%.0f%%/hour", gained / hours) : "-";
                String line = f.format(new Date(start)) + "  " + sp + "% to " + ep + "%  ("
                        + (int) (hours * 60) + " min)  speed " + speed;
                if (ma > 0) line += "  avg " + ma + " mA";
                out.add(line);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---------- formatting ----------

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    static String ago(long thenMs) {
        if (thenMs <= 0) return "not used recently";
        long days = (System.currentTimeMillis() - thenMs) / 86400000L;
        if (days <= 0) return "used today";
        if (days == 1) return "used yesterday";
        if (days < 60) return "used " + days + " days ago";
        return "used " + (days / 30) + " months ago";
    }
}
