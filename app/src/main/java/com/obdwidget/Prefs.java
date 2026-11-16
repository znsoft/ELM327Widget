package com.obdwidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** SharedPreferences wrapper holding the widget configuration. */
public final class Prefs {

    private static final String FILE = "obd_widget_prefs";

    /** Legacy single-parameter key, kept only to migrate old installs. */
    private static final String KEY_PARAM_LEGACY = "param_id";
    /** Comma separated list of selected parameter ids, in display order. */
    private static final String KEY_SELECTED = "selected_params";

    private static final String KEY_INTERVAL = "interval_sec";
    private static final String KEY_ADAPTER = "adapter_addr";

    /** Wall-clock time of the last successful read (used for the fade). */
    private static final String KEY_LAST_TS = "last_ok_ts";
    /** Per-parameter last value, stored as "last_<id>". */
    private static final String KEY_LAST_PREFIX = "last_";

    /** Consecutive failed polls, used to back off while the adapter is away. */
    private static final String KEY_FAILURES = "consecutive_failures";

    /** Trip fuel: engine size for the airflow model, calibration, accumulator. */
    private static final String KEY_ENGINE_L = "engine_displacement_l";
    private static final String KEY_FUEL_CORR = "fuel_correction_pct";
    private static final String KEY_TRIP_L = "trip_fuel_l";
    private static final String KEY_TRIP_RUN = "trip_run_s";
    private static final String KEY_TRIP_WALL = "trip_wall_ms";

    public static final int DEFAULT_INTERVAL_SEC = 5;
    public static final int MIN_INTERVAL_SEC = 2;
    public static final int MAX_INTERVAL_SEC = 300;

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Selected parameters ---------------------------------------------

    /**
     * Parameters shown on the widget, ordered as they appear. Always returns at
     * least one entry (coolant) so the widget is never empty.
     */
    public static List<ObdParam> getSelected(Context ctx) {
        SharedPreferences sp = get(ctx);
        String raw = sp.getString(KEY_SELECTED, null);

        if (raw == null) {
            // Migration from the single-parameter version.
            String legacy = sp.getString(KEY_PARAM_LEGACY, null);
            List<ObdParam> migrated = new ArrayList<ObdParam>(1);
            migrated.add(ObdParam.byId(legacy));
            return migrated;
        }

        List<ObdParam> result = new ArrayList<ObdParam>();
        for (String id : raw.split(",")) {
            String trimmed = id.trim();
            if (trimmed.length() > 0) {
                ObdParam p = ObdParam.byId(trimmed);
                if (!result.contains(p)) {
                    result.add(p);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(ObdParam.defaultParam());
        }
        return result;
    }

    public static void setSelected(Context ctx, List<ObdParam> params) {
        StringBuilder sb = new StringBuilder();
        if (params != null) {
            for (ObdParam p : params) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p.id);
            }
        }
        if (sb.length() == 0) {
            sb.append(ObdParam.defaultParam().id);
        }
        get(ctx).edit().putString(KEY_SELECTED, sb.toString()).apply();
    }

    // ---- Interval / adapter ----------------------------------------------

    public static int getIntervalSec(Context ctx) {
        int v = get(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SEC);
        if (v < MIN_INTERVAL_SEC) {
            v = MIN_INTERVAL_SEC;
        }
        if (v > MAX_INTERVAL_SEC) {
            v = MAX_INTERVAL_SEC;
        }
        return v;
    }

    public static void setIntervalSec(Context ctx, int sec) {
        if (sec < MIN_INTERVAL_SEC) {
            sec = MIN_INTERVAL_SEC;
        }
        if (sec > MAX_INTERVAL_SEC) {
            sec = MAX_INTERVAL_SEC;
        }
        get(ctx).edit().putInt(KEY_INTERVAL, sec).apply();
    }

    /** MAC address of the chosen ELM327 adapter, or "" for "any paired". */
    public static String getAdapterAddress(Context ctx) {
        return get(ctx).getString(KEY_ADAPTER, "");
    }

    public static void setAdapterAddress(Context ctx, String address) {
        get(ctx).edit().putString(KEY_ADAPTER, address == null ? "" : address).apply();
    }

    // ---- Last successful reading -----------------------------------------

    /** Stores one parameter's value on the widget and stamps the refresh time. */
    public static void setLastReading(Context ctx, ObdParam param, String text) {
        get(ctx).edit()
                .putString(KEY_LAST_PREFIX + param.id, text)
                .putLong(KEY_LAST_TS, System.currentTimeMillis())
                .apply();
    }

    /** Marks all values as freshly read (timestamp only). */
    public static void touchLastOk(Context ctx) {
        get(ctx).edit().putLong(KEY_LAST_TS, System.currentTimeMillis()).apply();
    }

    public static String getLastText(Context ctx, ObdParam param) {
        return get(ctx).getString(KEY_LAST_PREFIX + param.id, null);
    }

    /** Wall-clock time of the last successful read, 0 when never. */
    public static long getLastOkTimestamp(Context ctx) {
        return get(ctx).getLong(KEY_LAST_TS, 0L);
    }

    // ---- Failure counter --------------------------------------------------

    public static int getFailures(Context ctx) {
        return get(ctx).getInt(KEY_FAILURES, 0);
    }

    public static void setFailures(Context ctx, int count) {
        get(ctx).edit().putInt(KEY_FAILURES, Math.max(0, count)).apply();
    }

    // ---- Trip fuel --------------------------------------------------------

    /** Engine displacement in litres; used by the speed-density air model. */
    public static float getEngineDisplacementL(Context ctx) {
        float v = get(ctx).getFloat(KEY_ENGINE_L, 2.0f);
        if (v <= 0.2f) {
            v = 0.2f;
        }
        if (v > 10f) {
            v = 10f;
        }
        return v;
    }

    public static void setEngineDisplacementL(Context ctx, float litres) {
        if (litres <= 0.2f) {
            litres = 0.2f;
        }
        if (litres > 10f) {
            litres = 10f;
        }
        get(ctx).edit().putFloat(KEY_ENGINE_L, litres).apply();
    }

    /** Manual correction of the computed flow, percent (100 = as modelled). */
    public static int getFuelCorrectionPct(Context ctx) {
        int v = get(ctx).getInt(KEY_FUEL_CORR, 100);
        if (v < 50) {
            v = 50;
        }
        if (v > 200) {
            v = 200;
        }
        return v;
    }

    public static void setFuelCorrectionPct(Context ctx, int percent) {
        if (percent < 50) {
            percent = 50;
        }
        if (percent > 200) {
            percent = 200;
        }
        get(ctx).edit().putInt(KEY_FUEL_CORR, percent).apply();
    }

    public static float getTripLiters(Context ctx) {
        return get(ctx).getFloat(KEY_TRIP_L, 0f);
    }

    /** Engine run time (PID 1F, seconds) at the last sample, -1 when unknown. */
    public static int getTripRunSeconds(Context ctx) {
        return get(ctx).getInt(KEY_TRIP_RUN, -1);
    }

    public static long getTripWallMs(Context ctx) {
        return get(ctx).getLong(KEY_TRIP_WALL, 0L);
    }

    public static void setTripState(Context ctx, float litres, int runSeconds,
                                    long wallMs) {
        get(ctx).edit()
                .putFloat(KEY_TRIP_L, Math.max(0f, litres))
                .putInt(KEY_TRIP_RUN, runSeconds)
                .putLong(KEY_TRIP_WALL, wallMs)
                .apply();
    }

    public static void resetTrip(Context ctx) {
        get(ctx).edit()
                .putFloat(KEY_TRIP_L, 0f)
                .putInt(KEY_TRIP_RUN, -1)
                .putLong(KEY_TRIP_WALL, 0L)
                .apply();
    }
}