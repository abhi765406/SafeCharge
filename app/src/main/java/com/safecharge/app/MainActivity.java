package com.safecharge.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int[] ALERT_VALUES = {0, 80, 90, 100};
    private static final String[] ALERT_LABELS = {"Off", "80%", "90%", "100% (full)"};

    private TextView statusText, liveText, healthText, ramText, storageText, historyText, boostInfo;
    private Button modeButton;
    private LinearLayout healthCard, historyCard;
    private View ramBar, storageBar;
    private final Object barLock = new Object();

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showBattery(BatteryHelper.fromIntent(c, i));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences p = Prefs.get(this);
        LinearLayout root = UI.screen(this, "Safe Charge");
        UI.note(this, root, "Charge like Safe Mode - without restarting your phone.");

        // ------------------------------------------------ charge mode
        LinearLayout c = UI.card(this, root, "Smart Charge Mode");
        statusText = UI.line(this, c, "");
        liveText = UI.note(this, c, "");
        modeButton = UI.add(this, c, "", UI.GREEN, new View.OnClickListener() {
            public void onClick(View v) { toggleMode(); }
        });
        UI.add(this, c, "Boost now (stop background apps)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { boostNow(); }
        });
        boostInfo = UI.note(this, c, "");

        UI.note(this, c, "While charging, Safe Charge repeatedly stops background apps so the phone stays cool and idle - the same reason Safe Mode charges faster. Options apply from the next time you plug in.");
        option(c, p, Prefs.OPT_KILL, "Stop background apps every 2 minutes", true);
        option(c, p, Prefs.OPT_SYNC, "Pause auto-sync while charging", true);
        option(c, p, Prefs.OPT_DIM, "Dim screen while charging (needs 'Modify system settings')", false);
        option(c, p, Prefs.OPT_BT, "Turn Bluetooth off while charging", false);
        option(c, p, Prefs.OPT_WIFI, "Turn Wi-Fi off while charging (pauses downloads)", false);
        option(c, p, Prefs.OPT_DEEP, "Deep mode: force-stop apps (needs ADB bridge or root)", false);

        UI.note(this, c, "Alert me when battery reaches:");
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ALERT_LABELS));
        int cur = p.getInt(Prefs.ALERT_LEVEL, 100);
        for (int i = 0; i < ALERT_VALUES.length; i++) if (ALERT_VALUES[i] == cur) sp.setSelection(i);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                p.edit().putInt(Prefs.ALERT_LEVEL, ALERT_VALUES[pos]).apply();
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        c.addView(sp);
        UI.note(this, c, "You also get a warning if the battery gets hotter than 42 C while charging.");

        // ------------------------------------------------ tools
        LinearLayout t = UI.card(this, root, "Free up phone");
        UI.add(this, t, "Free up storage (junk, big files, app caches)", UI.GREEN, new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, CleanerActivity.class)); }
        });
        UI.add(this, t, "Freeze unused apps (keep them, stop them)", UI.RED, new View.OnClickListener() {
            public void onClick(View v) { AppListActivity.open(MainActivity.this, AppListActivity.FREEZE); }
        });
        UI.add(this, t, "Frozen apps (bring back)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { AppListActivity.open(MainActivity.this, AppListActivity.FROZEN); }
        });
        UI.add(this, t, "Auto-start apps (slow your boot)", UI.ORANGE, new View.OnClickListener() {
            public void onClick(View v) { AppListActivity.open(MainActivity.this, AppListActivity.STARTUP); }
        });
        UI.add(this, t, "Protected apps (never stopped)", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { AppListActivity.open(MainActivity.this, AppListActivity.PROTECT); }
        });
        UI.add(this, t, "Setup and permissions", UI.TEXT2, new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SetupActivity.class)); }
        });

        // ------------------------------------------------ health
        healthCard = UI.card(this, root, "Phone health");
        healthText = UI.line(this, healthCard, "");
        ramText = UI.line(this, healthCard, "");
        storageText = UI.line(this, healthCard, "");

        // ------------------------------------------------ history
        historyCard = UI.card(this, root, "Charging history (is it faster now?)");
        historyText = UI.line(this, historyCard, "");

        UI.note(this, root, "Tip: slow charging is also caused by weak cables, USB ports of computers, and an old battery. "
                + "The live mA reading above shows what your charger really delivers (about 1000+ mA is normal for a wall charger).");
    }

    private void option(LinearLayout parent, final SharedPreferences p, final String key, String label, boolean def) {
        CheckBox cb = UI.check(this, parent, label, p.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                p.edit().putBoolean(key, on).apply();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (Prefs.get(this).getBoolean(Prefs.ENABLED, false) && !ChargeService.running) {
            safeStart();
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
    }

    private void safeStart() {
        try {
            ChargeService.start(this);
        } catch (Throwable e) {
            UI.toast(this, "Could not start: " + e.getMessage());
        }
    }

    private void toggleMode() {
        SharedPreferences p = Prefs.get(this);
        boolean on = p.getBoolean(Prefs.ENABLED, false);
        if (on) {
            p.edit().putBoolean(Prefs.ENABLED, false).apply();
            stopService(new Intent(this, ChargeService.class));
        } else {
            p.edit().putBoolean(Prefs.ENABLED, true).apply();
            safeStart();
            UI.toast(this, "Smart Charge Mode is on. Open Setup to allow background run.");
        }
        modeButton.postDelayed(new Runnable() {
            public void run() { refresh(); }
        }, 400);
        refresh();
    }

    private void boostNow() {
        final long before = BoostEngine.freeRam(this);
        boostInfo.setText("Boosting...");
        final boolean deep = Prefs.get(this).getBoolean(Prefs.OPT_DEEP, false);
        new Thread(new Runnable() {
            public void run() {
                final int n = BoostEngine.boost(MainActivity.this, deep);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                final long after = BoostEngine.freeRam(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        long diff = after - before;
                        boostInfo.setText("Stopped " + n + " apps. Free RAM: "
                                + BatteryHelper.formatBytes(before) + " to " + BatteryHelper.formatBytes(after)
                                + (diff > 0 ? "  (+" + BatteryHelper.formatBytes(diff) + ")" : ""));
                        refresh();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ display

    private void refresh() {
        boolean on = Prefs.get(this).getBoolean(Prefs.ENABLED, false);
        modeButton.setText(on ? "Turn Smart Charge Mode OFF" : "Turn Smart Charge Mode ON");
        showBattery(BatteryHelper.read(this));

        long total = BoostEngine.totalRam(this);
        long free = BoostEngine.freeRam(this);
        int usedPct = total > 0 ? (int) (100 - free * 100 / total) : 0;
        ramText.setText("RAM: " + BatteryHelper.formatBytes(free) + " free of " + BatteryHelper.formatBytes(total)
                + " (" + usedPct + "% used)");
        swapBar(true, usedPct, usedPct > 85 ? UI.RED : UI.BLUE);

        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long tot = st.getTotalBytes();
            long fr = st.getAvailableBytes();
            int up = tot > 0 ? (int) (100 - fr * 100 / tot) : 0;
            storageText.setText("Storage: " + BatteryHelper.formatBytes(fr) + " free of " + BatteryHelper.formatBytes(tot)
                    + " (" + up + "% used)");
            swapBar(false, up, up > 85 ? UI.RED : UI.GREEN);
        } catch (Exception e) {
            storageText.setText("Storage: unavailable");
        }

        List<String> lines = BatteryHelper.sessionLines(this);
        if (lines.isEmpty()) {
            historyText.setText("No sessions yet. Turn Smart Charge Mode on and charge for a few minutes - each charge is logged here with its speed in %/hour.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append("\n\n");
            historyText.setText(sb.toString().trim());
        }
    }

    private void swapBar(boolean ram, int pct, int color) {
        synchronized (barLock) {
            if (ram) {
                if (ramBar != null) healthCard.removeView(ramBar);
                ramBar = UI.bar(this, pct, color);
                healthCard.addView(ramBar, healthCard.indexOfChild(ramText) + 1);
            } else {
                if (storageBar != null) healthCard.removeView(storageBar);
                storageBar = UI.bar(this, pct, color);
                healthCard.addView(storageBar, healthCard.indexOfChild(storageText) + 1);
            }
        }
    }

    private void showBattery(BatteryHelper.Info b) {
        boolean on = Prefs.get(this).getBoolean(Prefs.ENABLED, false);
        String mode = !on ? "Smart Charge Mode: OFF"
                : (ChargeService.running ? (b.plugged ? "Smart Charge Mode: ACTIVE - keeping phone quiet"
                : "Smart Charge Mode: ON - waiting for charger")
                : "Smart Charge Mode: starting...");
        statusText.setText(mode);
        statusText.setTextColor(on ? UI.GREEN : UI.TEXT2);

        String live = "Battery " + b.percent + "%  |  " + b.status + "  |  " + b.plugName;
        if (b.plugged && b.currentMa > 0) live += "  |  " + b.currentMa + " mA";
        liveText.setText(live);

        healthText.setText(String.format(Locale.US, "Battery: %d%%, %.1f C, %.2f V, health %s %s",
                b.percent, b.tempC, b.volts, b.health, b.tech));
        if (b.tempC >= 42f) healthText.setTextColor(UI.RED);
        else healthText.setTextColor(UI.TEXT);

        if (b.plugged && b.plugName.startsWith("USB")) {
            liveText.append("\nTip: you are on a USB port. Use the wall adapter for faster charging.");
        }
    }
}
