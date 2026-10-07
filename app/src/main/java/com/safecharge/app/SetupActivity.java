package com.safecharge.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Permissions, background-run settings and the one-time ADB bridge. */
public class SetupActivity extends Activity {

    private TextView storageS, writeS, usageS, batteryS, adbS, rootS;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = UI.screen(this, "Setup");

        // ---- permissions
        LinearLayout p = UI.card(this, root, "Permissions (one tap each)");

        storageS = UI.line(this, p, "");
        UI.add(this, p, "Allow storage access (for cleaner)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 5);
                }
            }
        });

        writeS = UI.line(this, p, "");
        UI.add(this, p, "Allow 'Modify system settings' (screen dimming)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startSafe(i);
                }
            }
        });

        usageS = UI.line(this, p, "");
        UI.add(this, p, "Allow 'Usage access' (last-used dates, real app sizes)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { startSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        });

        batteryS = UI.line(this, p, "");
        UI.add(this, p, "Let Safe Charge run in background", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    if (!startSafe(i)) startSafe(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });

        // ---- vivo / other brands: auto-start
        LinearLayout a = UI.card(this, root, "Keep it alive (Vivo, Oppo, Xiaomi...)");
        UI.note(this, a, "Many phones kill background apps. Allow Safe Charge to Auto-start and run in background, otherwise it cannot notice when you plug in.\n"
                + "Vivo: i Manager > App manager > Autostart / High background power consumption > Safe Charge ON.");
        UI.add(this, a, "Open auto-start / app settings", UI.ORANGE, new View.OnClickListener() {
            public void onClick(View v) { openAutostart(); }
        });

        // ---- ADB bridge
        LinearLayout d = UI.card(this, root, "ADB bridge (for freezing apps and clearing all caches)");
        UI.note(this, d, "Android blocks normal apps from disabling other apps. This bridge lets Safe Charge use the phone's own ADB service on this phone only - no internet involved. One time per phone restart:");
        UI.note(this, d, "1. Settings > About phone > tap Build number 7 times, then Developer options > USB debugging ON.\n"
                + "2. Connect the phone to a computer with ADB and run:  adb tcpip 5555\n"
                + "3. Unplug if you like, press Connect below, and tap Allow (tick 'Always allow') on the popup.");
        UI.add(this, d, "Copy:  adb tcpip 5555", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { UI.copy(SetupActivity.this, "adb", "adb tcpip 5555"); }
        });
        adbS = UI.line(this, d, "ADB bridge: not checked");
        rootS = UI.line(this, d, "Root: not checked");
        UI.add(this, d, "Connect / check again", UI.GREEN, new View.OnClickListener() {
            public void onClick(View v) { probe(); }
        });
        UI.note(this, d, "Already rooted? Safe Charge uses root automatically (grant the superuser prompt).");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean storage = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean write = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
        boolean usage = AppScanner.hasUsageAccess(this);
        boolean battery = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        set(storageS, "Storage access", storage);
        set(writeS, "Modify system settings", write);
        set(usageS, "Usage access", usage);
        set(batteryS, "Unrestricted background run", battery);
    }

    private void set(TextView t, String name, boolean ok) {
        t.setText((ok ? "[ON]  " : "[OFF] ") + name);
        t.setTextColor(ok ? UI.GREEN : UI.ORANGE);
    }

    private void probe() {
        adbS.setText("ADB bridge: connecting... (look for an Allow popup)");
        rootS.setText("Root: checking...");
        new Thread(new Runnable() {
            public void run() {
                final Shell.Access acc = Shell.probe(SetupActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        adbS.setText("ADB bridge: " + (acc.adb ? "CONNECTED" : "not connected (" + acc.adbMessage + ")"));
                        adbS.setTextColor(acc.adb ? UI.GREEN : UI.ORANGE);
                        rootS.setText("Root: " + (acc.root ? "available" : "not available"));
                        rootS.setTextColor(acc.root ? UI.GREEN : UI.TEXT2);
                    }
                });
            }
        }).start();
    }

    private boolean startSafe(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openAutostart() {
        String[][] known = {
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        };
        for (String[] k : known) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(k[0], k[1]));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startSafe(i);
        UI.toast(this, "Open Battery / Autostart here and allow Safe Charge");
    }
}
