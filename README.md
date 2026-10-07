# Safe Charge

Charge like Safe Mode - without restarting your phone. Built for old, low-RAM
phones (made on a Vivo Y91i, 1 GB RAM) but works on any Android 5.0+ phone.
No external libraries, so the APK is tiny and the build is simple.

## Why Safe Mode charges faster

Safe Mode only stops third-party apps from running. With nothing waking the
CPU, the phone stays cool and idle, and the battery gets most of the charger's
power. Safe Charge copies that idea: while the charger is connected it keeps
background apps stopped, pauses sync, and (optionally) dims the screen and
turns radios off. It cannot make a charger push more power than the charger,
cable and battery allow - it removes what was stealing power and heat.

## Features

**Smart Charge Mode** (the main switch)
- Detects when a charger is plugged in and unplugged, automatically.
- Stops background apps right away and every 2 minutes while charging.
- Pauses auto-sync while charging (restored after).
- Optional: dim screen, Bluetooth off, Wi-Fi off, "deep mode" force-stop.
- Everything it changed is put back when you unplug (even if the app was killed).
- Live charging current in mA, charger type, temperature.
- Alert at 80% / 90% / 100% and an overheating warning.
- Charging history with real speed in %/hour, so you can see if it works.

**Free up storage**
- Finds leftover app caches, thumbnails, installers, temp/log files and your
  15 largest files. Nothing is deleted until you tick it and confirm.
- "Clear every app's cache" in one tap (needs the ADB bridge or root).

**Freeze unused apps (disable, not uninstall)**
- Lists your apps, least-used first, with real size and last-used date.
- "Tick all unused for 30+ days" button, then Freeze.
- Frozen apps stay installed with all data but do not run and vanish from the
  app drawer. Bring them back any time from "Frozen apps".
- Protected apps list (chat, banking, music...) that are never touched.
- Auto-start apps list: apps that launch themselves at boot.

## Important: what needs the one-time ADB step

Android does not let any normal app disable other apps or clear their cache.
Safe Charge solves this with a small built-in ADB bridge that talks to your
own phone (nothing goes over the internet). Setup, once per phone restart:

1. Settings > About phone > tap **Build number** 7 times.
   Settings > Developer options > turn on **USB debugging**.
2. Plug into a computer with ADB and run: `adb tcpip 5555`
3. In the app: **Setup > Connect / check again**. Tap **Allow** (tick
   "Always allow") on the popup.

If your phone is rooted, it works with no ADB step. Everything else
(Smart Charge Mode, junk cleaner, stop-now, alerts, history) needs no ADB.
Note: on Android 11 and newer, `adb tcpip` may not be usable by the bridge;
the rest of the app still works fully.

## Make it survive on Vivo / Oppo / Xiaomi

These phones kill background apps. Open **Setup** and:
- Allow "Let Safe Charge run in background".
- Open "auto-start / app settings" and allow Autostart.
  (Vivo: i Manager > App manager > Autostart.)

## Build the APK

1. Unzip `SafeCharge.zip`.
2. Create a new GitHub repository and upload everything inside the
   `SafeCharge` folder to the repository root. Check that
   `.github/workflows/build.yml` is there (phone uploads sometimes skip
   hidden folders).
3. Actions tab > let it run, or **Run workflow**.
4. Download the **SafeCharge-app** artifact, unzip it, install `app-debug.apk`.
   (Allow "Install unknown apps" when asked.)

Build setup is identical to the System Tuner project: Android Gradle Plugin
8.5.0, Gradle 8.7, JDK 17, compileSdk 34, no dependencies.
