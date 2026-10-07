package com.safecharge.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stops background apps. This is what makes charging behave like Safe Mode:
 * Safe Mode simply keeps third-party apps from running, so the phone stays
 * cool and idle while it charges.
 */
final class BoostEngine {

    private BoostEngine() {}

    /** Packages we must never touch: ourselves, launcher, keyboard, user-protected apps. */
    static Set<String> neverTouch(Context c) {
        Set<String> s = Prefs.protectedSet(c);
        s.add(c.getPackageName());
        try {
            PackageManager pm = c.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) s.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
        }
        try {
            String ime = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) s.add(ime.substring(0, ime.indexOf('/')));
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** Enabled third-party apps that are allowed to be stopped. */
    static List<String> targets(Context c) {
        List<String> out = new ArrayList<>();
        Set<String> skip = neverTouch(c);
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            if (!ai.enabled) continue;
            if (skip.contains(ai.packageName)) continue;
            out.add(ai.packageName);
        }
        return out;
    }

    /**
     * Asks Android to kill background processes of all allowed apps.
     * With deep=true and ADB bridge / root available it force-stops them instead,
     * which also cancels their pending alarms and wake-ups.
     * Returns how many apps were targeted.
     */
    static int boost(Context c, boolean deep) {
        List<String> t = targets(c);
        killPackages(c, t);
        if (deep && !t.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String p : t) sb.append("am force-stop ").append(p).append('\n');
                Shell.run(c, sb.toString());
            } catch (Throwable ignored) {
                // No privileged access: the normal kill above already ran.
            }
        }
        return t.size();
    }

    static void killPackages(Context c, List<String> pkgs) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        for (String p : pkgs) {
            try {
                am.killBackgroundProcesses(p);
            } catch (Throwable ignored) {
            }
        }
    }

    static long freeRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    static long totalRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    static Set<String> emptySet() {
        return new HashSet<String>();
    }
}
