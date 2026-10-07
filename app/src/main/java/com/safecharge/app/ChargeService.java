package com.safecharge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches the charger. While plugged in it keeps the phone quiet (like Safe Mode):
 * stops background apps repeatedly, optionally dims the screen, pauses sync and
 * radios, and raises "battery full" / "too hot" alerts. When unplugged it puts
 * everything back and records how fast the phone charged.
 */
public class ChargeService extends Service {

    static final String ACTION_BOOST_NOW = "com.safecharge.app.BOOST_NOW";

    private static final String CH_STATUS = "sc_status";
    private static final String CH_ALERT = "sc_alert";
    private static final int ID_STATUS = 11;
    private static final int ID_FULL = 12;
    private static final int ID_HEAT = 13;
    private static final long LOOP_MS = 2 * 60 * 1000L;

    // Read by MainActivity for display
    static volatile boolean running = false;
    static volatile boolean charging = false;
    static volatile int lastTargeted = 0;
    static volatile long lastBoostAt = 0;

    private Handler handler;
    private ExecutorService exec;
    private boolean plugged = false;
    private long sessionStart;
    private int sessionStartPct;
    private long currentSum;
    private int currentCount;
    private boolean fullAlerted;
    private long lastHeatAlert;
    private long lastNotifAt;
    private int lastPct;

    static void start(Context c) {
        Intent i = new Intent(c, ChargeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void boostNow(Context c) {
        Intent i = new Intent(c, ChargeService.class);
        i.setAction(ACTION_BOOST_NOW);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                handleBattery(intent);
            } catch (Throwable ignored) {
            }
        }
    };

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!plugged) return;
            runBoost(false);
            handler.postDelayed(this, LOOP_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadExecutor();
        createChannels();
        startForeground(ID_STATUS, buildStatus("Waiting for charger...", false));
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID_STATUS, buildStatus(plugged ? "Charge mode active" : "Waiting for charger...", plugged));
        if (intent != null && ACTION_BOOST_NOW.equals(intent.getAction())) {
            runBoost(Prefs.get(this).getBoolean(Prefs.OPT_DEEP, false));
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- battery

    private void handleBattery(Intent intent) {
        BatteryHelper.Info b = BatteryHelper.fromIntent(this, intent);
        lastPct = b.percent;
        boolean now = b.plugged;

        if (now && !plugged) {
            plugged = true;
            onPlugged(b);
        } else if (!now && plugged) {
            plugged = false;
            onUnplugged(b);
        }
        charging = plugged;
        if (!plugged) restoreSettings(); // safety net if we were killed while charging

        if (plugged) {
            if (b.currentMa > 0) {
                currentSum += b.currentMa;
                currentCount++;
            }
            checkAlerts(b);
        }

        long t = System.currentTimeMillis();
        if (t - lastNotifAt > 20000) {
            lastNotifAt = t;
            String txt;
            if (plugged) {
                txt = String.format(Locale.US, "Charging %d%%  |  %s  |  %.1f C",
                        b.percent, b.currentMa > 0 ? b.currentMa + " mA" : b.plugName, b.tempC);
            } else {
                txt = "Waiting for charger  |  battery " + b.percent + "%";
            }
            notifyStatus(txt, plugged);
        }
    }

    private void onPlugged(BatteryHelper.Info b) {
        sessionStart = System.currentTimeMillis();
        sessionStartPct = b.percent;
        currentSum = 0;
        currentCount = 0;
        fullAlerted = false;
        applyChargeMode();
        handler.removeCallbacks(loop);
        handler.post(loop);
        if (Prefs.get(this).getBoolean(Prefs.OPT_DEEP, false)) {
            runBoost(true);
        }
    }

    private void onUnplugged(BatteryHelper.Info b) {
        handler.removeCallbacks(loop);
        restoreSettings();
        long avg = currentCount > 0 ? currentSum / currentCount : 0;
        BatteryHelper.addSession(this, sessionStart, sessionStartPct,
                System.currentTimeMillis(), b.percent, avg);
        cancel(ID_FULL);
    }

    private void runBoost(final boolean deep) {
        if (!Prefs.get(this).getBoolean(Prefs.OPT_KILL, true) && !deep) return;
        final Context ctx = getApplicationContext();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lastTargeted = BoostEngine.boost(ctx, deep);
                    lastBoostAt = System.currentTimeMillis();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ---------------------------------------------------------------- alerts

    private void checkAlerts(BatteryHelper.Info b) {
        SharedPreferences p = Prefs.get(this);
        int level = p.getInt(Prefs.ALERT_LEVEL, 100);
        if (level > 0 && !fullAlerted && b.percent >= level) {
            fullAlerted = true;
            alert(ID_FULL, "Battery at " + b.percent + "%",
                    level >= 100 ? "Fully charged - you can unplug now."
                            : "Reached your " + level + "% limit - unplug to protect battery life.");
        }
        int maxTemp = p.getInt(Prefs.TEMP_ALERT, 42);
        long now = System.currentTimeMillis();
        if (maxTemp > 0 && b.tempC >= maxTemp && now - lastHeatAlert > 10 * 60 * 1000L) {
            lastHeatAlert = now;
            alert(ID_HEAT, "Battery is hot: " + String.format(Locale.US, "%.1f", b.tempC) + " C",
                    "Take the phone out of its case and stop using it while it charges.");
        }
    }

    private void alert(int id, String title, String text) {
        Notification.Builder nb = builder(CH_ALERT);
        nb.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setContentIntent(openApp());
        if (Build.VERSION.SDK_INT < 26) {
            nb.setDefaults(Notification.DEFAULT_ALL);
            nb.setPriority(Notification.PRIORITY_HIGH);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, nb.build());
    }

    // ---------------------------------------------------------------- charge mode extras

    private void applyChargeMode() {
        SharedPreferences p = Prefs.get(this);
        if (p.getBoolean(Prefs.RESTORE_PENDING, false)) return; // already applied earlier

        SharedPreferences.Editor e = p.edit();
        e.putInt(Prefs.SAVED_BRIGHT, -1).putInt(Prefs.SAVED_BMODE, -1)
                .putInt(Prefs.SAVED_SYNC, -1).putInt(Prefs.SAVED_BT, 0).putInt(Prefs.SAVED_WIFI, 0);

        if (p.getBoolean(Prefs.OPT_DIM, false) && canWriteSettings()) {
            try {
                ContentResolver cr = getContentResolver();
                int bright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
                int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                e.putInt(Prefs.SAVED_BRIGHT, bright).putInt(Prefs.SAVED_BMODE, mode);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 20);
            } catch (Throwable ignored) {
            }
        }
        if (p.getBoolean(Prefs.OPT_SYNC, true)) {
            try {
                boolean was = ContentResolver.getMasterSyncAutomatically();
                e.putInt(Prefs.SAVED_SYNC, was ? 1 : 0);
                if (was) ContentResolver.setMasterSyncAutomatically(false);
            } catch (Throwable ignored) {
            }
        }
        if (p.getBoolean(Prefs.OPT_BT, false)) {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad != null && ad.isEnabled()) {
                    e.putInt(Prefs.SAVED_BT, 1);
                    ad.disable();
                }
            } catch (Throwable ignored) {
            }
        }
        if (p.getBoolean(Prefs.OPT_WIFI, false)) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) {
                    e.putInt(Prefs.SAVED_WIFI, 1);
                    wm.setWifiEnabled(false);
                }
            } catch (Throwable ignored) {
            }
        }
        e.putBoolean(Prefs.RESTORE_PENDING, true).apply();
    }

    private void restoreSettings() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean(Prefs.RESTORE_PENDING, false)) return;

        int bright = p.getInt(Prefs.SAVED_BRIGHT, -1);
        int bmode = p.getInt(Prefs.SAVED_BMODE, -1);
        if (bright >= 0 && canWriteSettings()) {
            try {
                ContentResolver cr = getContentResolver();
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, bright);
                if (bmode >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, bmode);
            } catch (Throwable ignored) {
            }
        }
        if (p.getInt(Prefs.SAVED_SYNC, -1) == 1) {
            try {
                ContentResolver.setMasterSyncAutomatically(true);
            } catch (Throwable ignored) {
            }
        }
        if (p.getInt(Prefs.SAVED_BT, 0) == 1) {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad != null) ad.enable();
            } catch (Throwable ignored) {
            }
        }
        if (p.getInt(Prefs.SAVED_WIFI, 0) == 1) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) wm.setWifiEnabled(true);
            } catch (Throwable ignored) {
            }
        }
        p.edit().putBoolean(Prefs.RESTORE_PENDING, false).apply();
    }

    private boolean canWriteSettings() {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
    }

    // ---------------------------------------------------------------- notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    CH_STATUS, "Charge status", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ALERT, "Charge alerts", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, f);
    }

    private Notification buildStatus(String text, boolean active) {
        Notification.Builder nb = builder(CH_STATUS);
        nb.setContentTitle(active ? "Safe Charge: charge mode ON" : "Safe Charge is watching")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setContentIntent(openApp());
        if (Build.VERSION.SDK_INT < 26) nb.setPriority(Notification.PRIORITY_LOW);
        return nb.build();
    }

    private void notifyStatus(String text, boolean active) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_STATUS, buildStatus(text, active));
    }

    private void cancel(int id) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }

    @Override
    public void onDestroy() {
        running = false;
        charging = false;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(loop);
        restoreSettings();
        exec.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
