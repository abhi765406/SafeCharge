package com.safecharge.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Frees storage: removes junk files, lists your biggest files so you can
 * decide, and (with the ADB bridge or root) clears the cache of every app at once.
 */
public class CleanerActivity extends Activity {

    private static final int REQ_STORAGE = 77;
    private static final long BIG_FILE = 50L * 1024 * 1024;

    private static final class Item {
        String label;
        List<File> files = new ArrayList<File>();
        long bytes;
        CheckBox box;
    }

    private LinearLayout results;
    private TextView storageText;
    private TextView status;
    private View storageBarHolder;
    private LinearLayout card1;
    private final List<Item> items = new ArrayList<Item>();
    private boolean scanning;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = UI.screen(this, "Free up storage");

        card1 = UI.card(this, root, "Phone storage");
        storageText = UI.line(this, card1, "");

        LinearLayout apps = UI.card(this, root, "Clear all app caches");
        UI.note(this, apps, "Removes temporary cache of every installed app in one go (your logins and data stay). Needs the ADB bridge or root - see Setup.");
        UI.add(this, apps, "Clear every app's cache", UI.GREEN, new View.OnClickListener() {
            public void onClick(View v) { trimCaches(); }
        });
        UI.add(this, apps, "Open Android's storage screen", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });

        LinearLayout junk = UI.card(this, root, "Junk and big files");
        UI.note(this, junk, "Finds leftover caches, installers, logs and your largest files. Nothing is deleted until you tick it and confirm.");
        UI.add(this, junk, "Scan now", UI.BLUE, new View.OnClickListener() {
            public void onClick(View v) { startScan(); }
        });
        status = UI.note(this, junk, "");
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        junk.addView(results);
        UI.add(this, junk, "Delete ticked items", UI.RED, new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });

        UI.add(this, root, "Disabled-apps trick: freeze apps you rarely use", UI.ORANGE, new View.OnClickListener() {
            public void onClick(View v) { AppListActivity.open(CleanerActivity.this, AppListActivity.FREEZE); }
        });

        if (!hasStorage() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showStorage();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        showStorage();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showStorage() {
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            int used = total > 0 ? (int) (100 - free * 100 / total) : 0;
            storageText.setText(BatteryHelper.formatBytes(free) + " free of " + BatteryHelper.formatBytes(total)
                    + " (" + used + "% used)");
            if (storageBarHolder != null) card1.removeView(storageBarHolder);
            storageBarHolder = UI.bar(this, used, used > 85 ? UI.RED : UI.GREEN);
            card1.addView(storageBarHolder);
        } catch (Exception e) {
            storageText.setText("Storage info unavailable");
        }
    }

    // --------------------------------------------------------------- scan

    private void startScan() {
        if (!hasStorage()) {
            UI.toast(this, "Allow the Storage permission first");
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
            return;
        }
        if (scanning) return;
        scanning = true;
        status.setText("Scanning... this can take up to a minute on a big phone.");
        results.removeAllViews();
        items.clear();
        new Thread(new Runnable() {
            public void run() {
                final List<Item> found = scan();
                runOnUiThread(new Runnable() {
                    public void run() {
                        scanning = false;
                        showResults(found);
                    }
                });
            }
        }).start();
    }

    private List<Item> scan() {
        File root = Environment.getExternalStorageDirectory();
        Item caches = new Item();
        caches.label = "App caches left on storage";
        Item thumbs = new Item();
        thumbs.label = "Thumbnail caches (rebuilt automatically)";
        Item apks = new Item();
        apks.label = "Leftover installer files (.apk)";
        Item temp = new Item();
        temp.label = "Temp and log files";
        List<Item> big = new ArrayList<Item>();

        File[] appDirs = new File(root, "Android/data").listFiles();
        if (appDirs != null) {
            for (File d : appDirs) {
                File cache = new File(d, "cache");
                if (cache.isDirectory()) add(caches, cache);
            }
        }
        String[] th = {"DCIM/.thumbnails", "Pictures/.thumbnails", ".thumbnails"};
        for (String p : th) {
            File f = new File(root, p);
            if (f.isDirectory()) add(thumbs, f);
        }

        int[] budget = {120000}; // max files visited, keeps low-end phones responsive
        walk(root, 0, apks, temp, big, budget);

        Collections.sort(big, new Comparator<Item>() {
            public int compare(Item a, Item b) { return a.bytes == b.bytes ? 0 : (a.bytes < b.bytes ? 1 : -1); }
        });
        List<Item> out = new ArrayList<Item>();
        Item[] groups = {caches, thumbs, apks, temp};
        for (Item g : groups) if (!g.files.isEmpty() && g.bytes > 0) out.add(g);
        for (int i = 0; i < big.size() && i < 15; i++) out.add(big.get(i));
        return out;
    }

    private void walk(File dir, int depth, Item apks, Item temp, List<Item> big, int[] budget) {
        if (depth > 7 || budget[0] <= 0) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (budget[0]-- <= 0) return;
            String name = f.getName();
            if (f.isDirectory()) {
                if (depth == 0 && name.equals("Android")) continue;
                walk(f, depth + 1, apks, temp, big, budget);
            } else {
                String low = name.toLowerCase();
                long len = f.length();
                if (low.endsWith(".apk")) {
                    apks.files.add(f);
                    apks.bytes += len;
                } else if (low.endsWith(".tmp") || low.endsWith(".temp") || low.endsWith(".log") || low.endsWith(".bak")
                        || low.endsWith(".old") || low.equals(".nomedia.tmp")) {
                    temp.files.add(f);
                    temp.bytes += len;
                }
                if (len >= BIG_FILE) {
                    Item it = new Item();
                    it.label = "Big file: " + name;
                    it.files.add(f);
                    it.bytes = len;
                    big.add(it);
                }
            }
        }
    }

    private static void add(Item item, File f) {
        item.files.add(f);
        item.bytes += sizeOf(f);
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) total += sizeOf(c);
        return total;
    }

    private void showResults(List<Item> found) {
        items.addAll(found);
        results.removeAllViews();
        if (found.isEmpty()) {
            status.setText("Nothing worth cleaning found. Try freezing unused apps instead.");
            return;
        }
        long total = 0;
        for (Item it : found) total += it.bytes;
        status.setText("Found " + BatteryHelper.formatBytes(total) + " you can review:");
        for (Item it : found) {
            boolean big = it.label.startsWith("Big file");
            String text = it.label + "  -  " + BatteryHelper.formatBytes(it.bytes)
                    + (it.files.size() > 1 ? "  (" + it.files.size() + " items)" : "");
            it.box = UI.check(this, results, text, !big); // big files start unticked: your call
        }
    }

    private void confirmDelete() {
        long bytes = 0;
        int n = 0;
        for (Item it : items) {
            if (it.box != null && it.box.isChecked()) {
                bytes += it.bytes;
                n++;
            }
        }
        if (n == 0) {
            UI.toast(this, "Nothing ticked");
            return;
        }
        final long total = bytes;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + BatteryHelper.formatBytes(total) + "?")
                .setMessage("This permanently deletes the ticked items.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { deleteTicked(); }
                }).show();
    }

    private void deleteTicked() {
        final List<Item> chosen = new ArrayList<Item>();
        for (Item it : items) if (it.box != null && it.box.isChecked()) chosen.add(it);
        new Thread(new Runnable() {
            public void run() {
                long freed = 0;
                for (Item it : chosen) {
                    for (File f : it.files) {
                        long s = sizeOf(f);
                        deleteRecursive(f);
                        if (!f.exists()) freed += s;
                    }
                }
                final long done = freed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        UI.toast(CleanerActivity.this, "Freed " + BatteryHelper.formatBytes(done));
                        showStorage();
                        startScan();
                    }
                });
            }
        }).start();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // --------------------------------------------------------------- system cache trim

    private void trimCaches() {
        status.setText("Clearing app caches...");
        final long before = freeBytes();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    Shell.run(CleanerActivity.this, "pm trim-caches 999999999999");
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String error = err;
                final long freed = Math.max(0, freeBytes() - before);
                runOnUiThread(new Runnable() {
                    public void run() {
                        showStorage();
                        if (error != null) {
                            new AlertDialog.Builder(CleanerActivity.this)
                                    .setTitle("ADB bridge or root needed")
                                    .setMessage("Android only lets the system clear other apps' caches. Open Setup > ADB bridge to enable it once.\n\nDetails: " + error)
                                    .setPositiveButton("Open Setup", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            startActivity(new Intent(CleanerActivity.this, SetupActivity.class));
                                        }
                                    })
                                    .setNegativeButton("Close", null).show();
                        } else {
                            UI.toast(CleanerActivity.this, "Caches cleared. Freed about " + BatteryHelper.formatBytes(freed));
                        }
                    }
                });
            }
        }).start();
    }

    private long freeBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }
}
