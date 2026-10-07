package com.safecharge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * One screen, four jobs:
 *  FREEZE  - pick rarely-used apps and disable ("freeze") them
 *  FROZEN  - see frozen apps and bring them back
 *  PROTECT - choose apps Safe Charge must never stop
 *  STARTUP - apps that start themselves at boot
 */
public class AppListActivity extends Activity {

    static final String EXTRA_MODE = "mode";
    static final int FREEZE = 0, FROZEN = 1, PROTECT = 2, STARTUP = 3;

    private int mode;
    private final List<AppScanner.Row> rows = new ArrayList<AppScanner.Row>();
    private Adapter adapter;
    private TextView info;
    private int sort = 0; // 0 least used, 1 largest, 2 name
    private boolean usageKnown;
    private boolean loading = true;

    static void open(Context c, int mode) {
        Intent i = new Intent(c, AppListActivity.class);
        i.putExtra(EXTRA_MODE, mode);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mode = getIntent().getIntExtra(EXTRA_MODE, FREEZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);
        int pad = UI.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);

        String title;
        String help;
        switch (mode) {
            case FROZEN:
                title = "Frozen apps";
                help = "These apps are disabled: they use no RAM, battery or background time. Tick and press Unfreeze to use them again.";
                break;
            case PROTECT:
                title = "Protected apps";
                help = "Ticked apps are never stopped by Safe Charge (keep chat, banking or music apps here).";
                break;
            case STARTUP:
                title = "Auto-start apps";
                help = "These apps start themselves every time the phone boots and slow it down. Freeze the ones you do not need all day.";
                break;
            default:
                title = "Freeze unused apps";
                help = "Frozen apps stay installed with all their data, but stop running and vanish from the app drawer until you unfreeze them. Long-press an app for its system info page.";
        }

        TextView t = UI.text(this, title, 22, UI.TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(t, UI.lp(-1, -2, UI.dp(this, 2), UI.dp(this, 6), 0, UI.dp(this, 4)));
        info = UI.note(this, root, help);

        if (mode != FROZEN) {
            LinearLayout sortRow = UI.row(this, root);
            if (mode != PROTECT) {
                UI.addHalf(this, sortRow, UI.button(this, "Least used", UI.BLUE, new View.OnClickListener() {
                    public void onClick(View v) { sort = 0; resort(); }
                }));
            }
            UI.addHalf(this, sortRow, UI.button(this, "Largest", UI.BLUE, new View.OnClickListener() {
                public void onClick(View v) { sort = 1; resort(); }
            }));
            UI.addHalf(this, sortRow, UI.button(this, "A - Z", UI.BLUE, new View.OnClickListener() {
                public void onClick(View v) { sort = 2; resort(); }
            }));
        }
        if (mode == FREEZE) {
            UI.add(this, root, "Tick all apps unused for 30+ days", UI.ORANGE, new View.OnClickListener() {
                public void onClick(View v) { selectUnused(); }
            });
        }

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                AppScanner.Row r = rows.get(pos);
                r.checked = !r.checked;
                adapter.notifyDataSetChanged();
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                openAppInfo(rows.get(pos).pkg);
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0);
        lp.weight = 1;
        lp.setMargins(0, UI.dp(this, 8), 0, UI.dp(this, 4));
        list.setBackgroundDrawable(UI.rounded(UI.CARD, UI.dp(this, 12)));
        root.addView(list, lp);

        buildActions(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void buildActions(LinearLayout root) {
        if (mode == FROZEN) {
            UI.add(this, root, "Unfreeze selected", UI.GREEN, new View.OnClickListener() {
                public void onClick(View v) { unfreeze(); }
            });
        } else if (mode == PROTECT) {
            UI.add(this, root, "Save protected list", UI.GREEN, new View.OnClickListener() {
                public void onClick(View v) { saveProtected(); }
            });
        } else {
            LinearLayout r1 = UI.row(this, root);
            UI.addHalf(this, r1, UI.button(this, "Freeze selected", UI.RED, new View.OnClickListener() {
                public void onClick(View v) { freeze(); }
            }));
            UI.addHalf(this, r1, UI.button(this, "Stop now", UI.ORANGE, new View.OnClickListener() {
                public void onClick(View v) { stopNow(); }
            }));
            UI.add(this, root, "Protect selected (never stop them)", UI.BLUE, new View.OnClickListener() {
                public void onClick(View v) { protectSelected(); }
            });
        }
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        loading = true;
        info.setText("Loading apps...");
        new Thread(new Runnable() {
            public void run() {
                final List<AppScanner.Row> found;
                if (mode == FROZEN) found = AppScanner.userApps(AppListActivity.this, false);
                else if (mode == STARTUP) found = AppScanner.startupApps(AppListActivity.this);
                else found = AppScanner.userApps(AppListActivity.this, true);
                AppScanner.enrich(AppListActivity.this, found);
                final boolean usage = AppScanner.hasUsageAccess(AppListActivity.this);
                final Set<String> prot = Prefs.protectedSet(AppListActivity.this);
                if (mode == PROTECT) {
                    for (AppScanner.Row r : found) r.checked = prot.contains(r.pkg);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        rows.clear();
                        rows.addAll(found);
                        usageKnown = usage;
                        loading = false;
                        if (mode == PROTECT && sort == 0) sort = 2;
                        resort();
                        refreshInfo();
                    }
                });
            }
        }).start();
    }

    private void refreshInfo() {
        String s = rows.size() + " apps";
        if (!usageKnown && mode != FROZEN && mode != PROTECT) {
            s += ". Turn on Usage Access in Setup to see last-used dates and real sizes (sizes shown are only the APK file).";
        }
        info.setText(s);
    }

    private void resort() {
        Collections.sort(rows, new Comparator<AppScanner.Row>() {
            public int compare(AppScanner.Row a, AppScanner.Row b) {
                if (sort == 1) return a.size == b.size ? 0 : (a.size < b.size ? 1 : -1);
                if (sort == 2) return a.label.compareToIgnoreCase(b.label);
                if (a.lastUsed != b.lastUsed) return a.lastUsed < b.lastUsed ? -1 : 1;
                return a.installed < b.installed ? -1 : (a.installed == b.installed ? 0 : 1);
            }
        });
        adapter.notifyDataSetChanged();
    }

    private void selectUnused() {
        if (!usageKnown) {
            UI.toast(this, "Turn on Usage Access first (Setup page) so I know which apps you really use.");
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> prot = Prefs.protectedSet(this);
        int n = 0;
        for (AppScanner.Row r : rows) {
            boolean oldInstall = now - r.installed > 7L * 86400000L;
            boolean unused = r.lastUsed == 0 || now - r.lastUsed > 30L * 86400000L;
            r.checked = unused && oldInstall && !prot.contains(r.pkg);
            if (r.checked) n++;
        }
        adapter.notifyDataSetChanged();
        UI.toast(this, n + " apps ticked. Review the list, then press Freeze.");
    }

    private List<AppScanner.Row> selected() {
        List<AppScanner.Row> out = new ArrayList<AppScanner.Row>();
        for (AppScanner.Row r : rows) if (r.checked) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------ actions

    private void openAppInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + pkg));
            startActivity(i);
        } catch (Exception e) {
            UI.toast(this, "Could not open app info");
        }
    }

    private void stopNow() {
        List<AppScanner.Row> sel = selected();
        if (sel.isEmpty()) { UI.toast(this, "Tick some apps first"); return; }
        List<String> pk = new ArrayList<String>();
        for (AppScanner.Row r : sel) pk.add(r.pkg);
        BoostEngine.killPackages(this, pk);
        UI.toast(this, "Asked Android to stop " + pk.size() + " apps. (Apps that restart themselves need Freeze.)");
    }

    private void protectSelected() {
        List<AppScanner.Row> sel = selected();
        if (sel.isEmpty()) { UI.toast(this, "Tick some apps first"); return; }
        Set<String> p = Prefs.protectedSet(this);
        for (AppScanner.Row r : sel) p.add(r.pkg);
        Prefs.saveProtected(this, p);
        for (AppScanner.Row r : rows) r.checked = false;
        adapter.notifyDataSetChanged();
        UI.toast(this, sel.size() + " apps are now protected");
    }

    private void saveProtected() {
        Set<String> p = Prefs.protectedSet(this);
        for (AppScanner.Row r : rows) {
            if (r.checked) p.add(r.pkg); else p.remove(r.pkg);
        }
        Prefs.saveProtected(this, p);
        UI.toast(this, "Saved. " + selected().size() + " apps protected.");
        finish();
    }

    private void freeze() {
        List<AppScanner.Row> sel = selected();
        Set<String> prot = Prefs.protectedSet(this);
        final StringBuilder script = new StringBuilder();
        int n = 0;
        for (AppScanner.Row r : sel) {
            if (prot.contains(r.pkg)) continue;
            script.append("pm disable-user --user 0 ").append(r.pkg).append('\n');
            n++;
        }
        if (n == 0) { UI.toast(this, "Tick some (unprotected) apps first"); return; }
        final int count = n;
        new AlertDialog.Builder(this)
                .setTitle("Freeze " + n + " apps?")
                .setMessage("They stay installed with their data, but stop running and disappear from your app list. You can bring them back from Frozen apps.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Freeze", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runScript(script.toString(), count, "Frozen");
                    }
                }).show();
    }

    private void unfreeze() {
        List<AppScanner.Row> sel = selected();
        if (sel.isEmpty()) { UI.toast(this, "Tick some apps first"); return; }
        StringBuilder script = new StringBuilder();
        for (AppScanner.Row r : sel) script.append("pm enable ").append(r.pkg).append('\n');
        runScript(script.toString(), sel.size(), "Unfrozen");
    }

    private void runScript(final String script, final int count, final String verb) {
        info.setText("Working...");
        new Thread(new Runnable() {
            public void run() {
                String result = null;
                String error = null;
                try {
                    result = Shell.run(AppListActivity.this, script);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String res = result;
                final String err = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) {
                            showManual(script, err);
                        } else if (res != null && (res.contains("Exception") || res.contains("Error"))) {
                            new AlertDialog.Builder(AppListActivity.this)
                                    .setTitle("Android refused some commands")
                                    .setMessage(res.length() > 700 ? res.substring(0, 700) : res)
                                    .setPositiveButton("OK", null).show();
                        } else {
                            UI.toast(AppListActivity.this, verb + " " + count + " apps");
                        }
                        load();
                    }
                });
            }
        }).start();
    }

    /** No ADB bridge and no root: show the commands so the user can run them from a computer instead. */
    private void showManual(final String script, String why) {
        new AlertDialog.Builder(this)
                .setTitle("One-time setup needed")
                .setMessage("Android does not let normal apps disable other apps. Safe Charge can do it through its ADB bridge (or root), which is not connected yet.\n\n"
                        + "Open Setup > ADB bridge and follow the 3 steps (takes 1 minute with a computer), or copy these commands and run them in 'adb shell' yourself.\n\n"
                        + "Details: " + why)
                .setNeutralButton("Copy commands", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { UI.copy(AppListActivity.this, "commands", script); }
                })
                .setPositiveButton("Open Setup", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(AppListActivity.this, SetupActivity.class));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ list adapter

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name, sub;
            CheckBox cb;
            if (convert == null) {
                Context c = AppListActivity.this;
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = UI.dp(c, 10);
                row.setPadding(p, p, p, p);
                icon = new ImageView(c);
                row.addView(icon, new LinearLayout.LayoutParams(UI.dp(c, 38), UI.dp(c, 38)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2);
                cp.weight = 1;
                cp.setMargins(UI.dp(c, 10), 0, UI.dp(c, 6), 0);
                name = UI.text(c, "", 14, UI.TEXT);
                name.setSingleLine(true);
                sub = UI.text(c, "", 11, UI.TEXT2);
                col.addView(name);
                col.addView(sub);
                row.addView(col, cp);
                cb = new CheckBox(c);
                cb.setFocusable(false);
                cb.setClickable(false);
                row.addView(cb);
                row.setTag(new View[]{icon, name, sub, cb});
            } else {
                row = (LinearLayout) convert;
            }
            View[] h = (View[]) row.getTag();
            icon = (ImageView) h[0];
            name = (TextView) h[1];
            sub = (TextView) h[2];
            cb = (CheckBox) h[3];

            AppScanner.Row r = rows.get(pos);
            boolean prot = Prefs.protectedSet(AppListActivity.this).contains(r.pkg);
            name.setText(r.label + (prot && mode != PROTECT ? "  (protected)" : ""));
            String size = (r.exactSize ? "" : "APK ") + BatteryHelper.formatBytes(r.size);
            String used;
            if (mode == FROZEN) used = "frozen";
            else if (r.lastUsed > 0) used = BatteryHelper.ago(r.lastUsed);
            else used = usageKnown ? "not used in 90+ days" : "last use unknown";
            sub.setText(size + "  |  " + used);
            cb.setChecked(r.checked);
            try {
                Drawable d = getPackageManager().getApplicationIcon(r.pkg);
                icon.setImageDrawable(d);
            } catch (Exception e) {
                icon.setImageDrawable(null);
            }
            return row;
        }
    }
}
