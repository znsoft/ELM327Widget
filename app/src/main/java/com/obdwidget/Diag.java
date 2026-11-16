package com.obdwidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny in-app logger used by the settings screen's diagnostics panel.
 *
 * Every Bluetooth step, AT command and raw ELM327 reply is recorded here. The
 * buffer is kept in memory (fast, survives across the widget service and the
 * activity because they share the app process) and mirrored into
 * SharedPreferences so the trace is still readable after a process restart.
 *
 * Lines are also written to logcat under the "ObdDiag" tag.
 */
public final class Diag {

    private static final String PREFS = "obd_widget_diag";
    private static final String KEY_LOG = "log";
    private static final int MAX_LINES = 2000;

    private static final Object LOCK = new Object();
    private static final ArrayList<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static boolean restored;

    private Diag() {
    }

    // ---- Writing ----------------------------------------------------------

    public static void log(String message) {
        String line;
        synchronized (LOCK) {
            line = TIME.format(new Date()) + "  " + message;
            LINES.add(line);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
        android.util.Log.i("ObdDiag", message);
    }

    public static void logf(String format, Object... args) {
        log(String.format(Locale.US, format, args));
    }

    public static void section(String title) {
        log("");
        log("=== " + title + " ===");
    }

    // ---- Reading ----------------------------------------------------------

    public static String text() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder(LINES.size() * 64);
            for (int i = 0; i < LINES.size(); i++) {
                sb.append(LINES.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    // ---- Persistence ------------------------------------------------------

    /** Mirrors the buffer to disk; called at the end of a widget update. */
    public static void flush(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putString(KEY_LOG, text()).apply();
        } catch (RuntimeException ignored) {
            // Diagnostics must never break the actual widget update.
        }
    }

    /** Loads the previous run's trace once, so a fresh process still shows it. */
    public static void restore(Context ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (LOCK) {
            if (restored) {
                return;
            }
            restored = true;
        }
        String saved;
        try {
            saved = prefs(ctx).getString(KEY_LOG, "");
        } catch (RuntimeException e) {
            return;
        }
        if (saved == null || saved.length() == 0) {
            return;
        }
        String[] parts = saved.split("\n");
        synchronized (LOCK) {
            LINES.clear();
            for (String part : parts) {
                if (part != null && part.length() > 0) {
                    LINES.add(part);
                }
            }
        }
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) {
            LINES.clear();
        }
        if (ctx != null) {
            prefs(ctx).edit().remove(KEY_LOG).apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Formatting helpers ----------------------------------------------

    /**
     * Escapes a raw ELM327 reply for the log: CR/LF become \r\n, control bytes
     * become \xNN so a stray binary byte is visible instead of garbling the UI.
     */
    public static String quote(String s) {
        if (s == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c > 0x7E) {
                sb.append(String.format(Locale.US, "\\x%02X", (int) c & 0xFF));
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString();
        if (out.length() > 300) {
            out = out.substring(0, 300) + "…";
        }
        return out;
    }

    /** Hex dump of a byte array, e.g. "7B". */
    public static String hex(byte[] data) {
        if (data == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }
}