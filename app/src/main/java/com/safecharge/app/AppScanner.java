package com.safecharge.app;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.os.storage.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds installed apps with their size and last-used time. */
final class AppScanner {

    private AppScanner() {}

    static final class Row {
        String pkg;
        String label;
        long size;          // best known size in bytes
        boolean exactSize;  // true if app+data+cache, false if only the APK file
        long lastUsed;      // 0 = unknown / not used in 90 days
        long installed;
        boolean enabled;
        boolean checked;
    }

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (a == null) return false;
            int mode = a.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Third-party apps only (system apps are never listed).
     * wantEnabled=true gives normal apps, false gives apps that are currently disabled/frozen.
     */
    static List<Row> userApps(Context c, boolean wantEnabled) {
        PackageManager pm = c.getPackageManager();
        List<Row> rows = new ArrayList<>();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            if (ai.packageName.equals(c.getPackageName())) continue;
            if (ai.enabled != wantEnabled) continue;
            rows.add(makeRow(pm, ai));
        }
        return rows;
    }

    /** Apps that ask Android to start them automatically at boot. */
    static List<Row> startupApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> rec = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_BOOT_COMPLETED), 0);
        for (ResolveInfo ri : rec) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || !seen.add(pkg) || pkg.equals(c.getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                if (!ai.enabled) continue;
                rows.add(makeRow(pm, ai));
            } catch (Exception ignored) {
            }
        }
        return rows;
    }

    private static Row makeRow(PackageManager pm, ApplicationInfo ai) {
        Row r = new Row();
        r.pkg = ai.packageName;
        CharSequence l = pm.getApplicationLabel(ai);
        r.label = l == null ? ai.packageName : l.toString();
        r.enabled = ai.enabled;
        try {
            r.size = new File(ai.sourceDir).length();
        } catch (Exception ignored) {
        }
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            r.installed = pi.firstInstallTime;
        } catch (Exception ignored) {
        }
        return r;
    }

    /** Fills in last-used time and real storage size where Android allows it. Slow-ish: background thread. */
    static void enrich(Context c, List<Row> rows) {
        if (!hasUsageAccess(c)) return;

        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(now - 90L * 86400000L, now);
            if (map != null) {
                for (Row r : rows) {
                    UsageStats us = map.get(r.pkg);
                    if (us != null) r.lastUsed = us.getLastTimeUsed();
                }
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 26) {
            Api26.sizes(c, rows);
        }
    }

    private static final class Api26 {
        static void sizes(Context c, List<Row> rows) {
            try {
                StorageStatsManager ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
                if (ssm == null) return;
                for (Row r : rows) {
                    try {
                        StorageStats st = ssm.queryStatsForPackage(
                                StorageManager.UUID_DEFAULT, r.pkg, Process.myUserHandle());
                        r.size = st.getAppBytes() + st.getDataBytes() + st.getCacheBytes();
                        r.exactSize = true;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
