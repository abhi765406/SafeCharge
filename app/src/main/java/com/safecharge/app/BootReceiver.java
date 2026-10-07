package com.safecharge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the charge watcher after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (Prefs.get(context).getBoolean(Prefs.ENABLED, false)) {
                ChargeService.start(context);
            }
        } catch (Throwable ignored) {
            // Some phones block starting a service from the background; opening the app fixes it.
        }
    }
}
