package com.safecharge.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs privileged commands (pm disable-user, am force-stop, pm trim-caches ...).
 * Tries the on-phone ADB bridge first (no root needed), then root (su).
 * Always call from a background thread.
 */
final class Shell {

    private Shell() {}

    static final class Access {
        boolean adb;
        boolean root;
        String adbMessage = "";
    }

    private static Boolean rootCache = null;

    /** Checks both routes. Slow (may prompt on the phone) - background thread only. */
    static Access probe(Context c) {
        Access a = new Access();
        try {
            String r = runAdb(c, "id");
            a.adb = r.contains("uid=");
            a.adbMessage = a.adb ? "Connected" : "No answer";
        } catch (Exception e) {
            a.adbMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        rootCache = null;
        a.root = hasRoot();
        return a;
    }

    static boolean hasRoot() {
        if (rootCache != null) return rootCache;
        boolean ok = false;
        try {
            ok = runRoot("id").contains("uid=0");
        } catch (Exception ignored) {
        }
        rootCache = ok;
        return ok;
    }

    /** Runs the script (one command per line) via ADB bridge, falling back to root. */
    static String run(Context c, String script) throws Exception {
        String adbError;
        try {
            return runAdb(c, script);
        } catch (Exception e) {
            adbError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (hasRoot()) return runRoot(script);
        throw new Exception("No ADB bridge or root available (" + adbError + ")");
    }

    private static String runAdb(Context c, String script) throws Exception {
        // adbd of older Android versions only accepts small messages: send in chunks.
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : script.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (cur.length() > 0 && cur.length() + line.length() + 1 > 3000) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        if (chunks.isEmpty()) chunks.add("true\n");

        StringBuilder result = new StringBuilder();
        for (String chunk : chunks) {
            AdbClient client = new AdbClient(c);
            try {
                client.connect();
                result.append(client.shell(chunk));
            } finally {
                client.close();
            }
        }
        return result.toString();
    }

    static String runRoot(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        DataOutputStream os = new DataOutputStream(p.getOutputStream());
        os.writeBytes(script + "\nexit\n");
        os.flush();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        p.waitFor();
        return sb.toString();
    }

    /** One-line summary for the user. */
    static String describe(Access a) {
        if (a.adb) return "ADB bridge connected";
        if (a.root) return "Root available";
        return "Not available";
    }
}
