package com.obdwidget;

import android.Manifest;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Polls all selected parameters and pushes them into the home-screen widget.
 *
 * The widget shows freshness through brightness:
 * <ul>
 *   <li>dims while a new reading is in flight;</li>
 *   <li>returns to full brightness as soon as values arrive;</li>
 *   <li>fades step by step when readings stop, so an old number never looks
 *       live.</li>
 * </ul>
 *
 * A plain Service: Android 4.4 has no foreground-service restrictions and the
 * work is short. Diagnostics go to {@link Diag}.
 */
public class WidgetUpdateService extends Service {

    public static final String ACTION_REFRESH = "com.obdwidget.ACTION_REFRESH";

    /** Back-off cap while the adapter is unreachable, to spare the battery. */
    private static final long MAX_RETRY_DELAY_MS = 30000L;
    /** If a previous update never finished, allow a new one after this long. */
    private static final long STUCK_TIMEOUT_MS = 120000L;

    private static volatile boolean running;
    private static volatile long runningSince;

    public static void requestRefresh(Context ctx) {
        Intent i = new Intent(ctx, WidgetUpdateService.class);
        i.setAction(ACTION_REFRESH);
        try {
            ctx.startService(i);
        } catch (RuntimeException e) {
            Diag.log("startService failed: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long now = System.currentTimeMillis();
        if (running && now - runningSince < STUCK_TIMEOUT_MS) {
            Diag.log("update requested while one is still running -> skipped");
            return START_NOT_STICKY;
        }
        if (running) {
            Diag.log("previous update looked stuck for >"
                    + (STUCK_TIMEOUT_MS / 1000) + " s -> starting a new one");
        }
        running = true;
        runningSince = now;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    updateAll(app);
                } catch (Throwable t) {
                    Diag.log("update crashed: " + t);
                    finishFailure(app, R.string.status_internal, true);
                } finally {
                    Diag.flush(app);
                    running = false;
                    stopSelf();
                }
            }
        }, "obd-widget-update").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- The actual work --------------------------------------------------

    private void updateAll(Context ctx) {
        Diag.section("widget poll");
        final List<ObdParam> params = Prefs.getSelected(ctx);
        final long age = ageMs(ctx);
        Diag.logf("params=%d interval=%ds lastAge=%s failures=%d",
                Integer.valueOf(params.size()), Integer.valueOf(Prefs.getIntervalSec(ctx)),
                age == Long.MAX_VALUE ? "never" : (age / 1000) + "s",
                Integer.valueOf(Prefs.getFailures(ctx)));

        // 1. Dim what is on screen while the new reading is on its way.
        publishStored(ctx, params, "", Fade.READING);

        if (!hasBluetoothPermission(ctx)) {
            Diag.log("BLUETOOTH_CONNECT is not granted");
            finishFailure(ctx, R.string.status_perm, true);
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Diag.log("device has no Bluetooth adapter");
            finishFailure(ctx, R.string.status_no_bt, false);
            return;
        }

        if (Session.isPaused()) {
            Diag.log("poll skipped: a self-test/scan is using the adapter");
            publishStale(ctx, params, null);
            scheduleNext(ctx, 0);
            return;
        }

        if (!adapter.isEnabled()) {
            Diag.log("Bluetooth is off");
            finishFailure(ctx, R.string.status_bt_off, true);
            return;
        }

        BluetoothDevice device = Elm327Client.findAdapter(
                adapter, Prefs.getAdapterAddress(ctx));
        if (device == null) {
            finishFailure(ctx, R.string.status_no_adapter, true);
            return;
        }

        String name = deviceLabel(device);
        try {
            ReadResult result = pollSelected(device, ctx, params);
            if (result.okCount == 0) {
                Diag.log("poll: no usable payload for any selected parameter");
                Prefs.setFailures(ctx, 0);
                publishStale(ctx, params, ctx.getString(R.string.status_no_data));
            } else {
                Prefs.touchLastOk(ctx);
                Prefs.setFailures(ctx, 0);
                for (int i = 0; i < params.size(); i++) {
                    if (result.values[i] != null) {
                        Prefs.setLastReading(ctx, params.get(i), result.values[i]);
                    }
                }
                publishValues(ctx, params, result.values, shortName(name), 1.0f);
            }
        } catch (IOException e) {
            Diag.log("poll: connect failed: " + e);
            finishFailure(ctx, R.string.status_connect_failed, true);
        } catch (RuntimeException e) {
            Diag.log("poll: unexpected error: " + e);
            finishFailure(ctx, R.string.status_link_error, true);
        } finally {
            scheduleNext(ctx, 0);
        }
    }

    /** Values read in one cycle plus how many of them are usable. */
    private static final class ReadResult {
        final String[] values;
        final int okCount;

        ReadResult(String[] values, int okCount) {
            this.values = values;
            this.okCount = okCount;
        }
    }

    /** Acquires (or reuses) the link and reads every selected parameter. */
    private ReadResult pollSelected(BluetoothDevice device, Context ctx,
                                    List<ObdParam> params) throws IOException {
        Elm327Client client;
        try {
            client = Session.acquire(device);
            return readAll(client, ctx, params);
        } catch (IOException first) {
            Diag.log("poll: first attempt failed (" + first + "), reconnecting once");
            Session.invalidate();
            client = Session.acquire(device);
            return readAll(client, ctx, params);
        } finally {
            Session.touch();
        }
    }

    private ReadResult readAll(Elm327Client client, Context ctx,
                               List<ObdParam> params) {
        String[] values = new String[params.size()];
        int ok = 0;
        for (int i = 0; i < params.size(); i++) {
            ObdParam param = params.get(i);
            if (param.derived) {
                TripFuel.Result trip = TripFuel.sample(client, ctx);
                if (trip != null) {
                    values[i] = String.format(Locale.US, "%.2f L",
                            Double.valueOf(trip.litres));
                    ok++;
                }
                continue;
            }
            byte[] raw = client.readPid(param.pid);
            Double value = param.compute(Elm327Client.toDoubles(raw));
            if (value != null) {
                values[i] = param.format(value);
                ok++;
            }
        }
        Diag.logf("poll: %d/%d parameter(s) read", Integer.valueOf(ok),
                Integer.valueOf(params.size()));
        return new ReadResult(values, ok);
    }

    // ---- Rendering --------------------------------------------------------

    /**
     * Draws the given values with a status. The numbers keep their brightness
     * unless {@code alpha} says otherwise.
     */
    private void publishValues(Context ctx, List<ObdParam> params, String[] values,
                               String status, float alpha) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, ObdWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) {
            Diag.log("publish: no widgets on the home screen");
            return;
        }
        Diag.logf("publish: %d value(s), alpha=%.2f status='%s'",
                Integer.valueOf(params.size()), Float.valueOf(alpha), status);
        for (int id : ids) {
            mgr.updateAppWidget(id, ObdWidgetProvider.buildViews(
                    ctx, id, params, values, status, alpha));
        }
    }

    /** Repaints the persisted values, used for the dim-while-reading state. */
    private void publishStored(Context ctx, List<ObdParam> params, String status,
                               float alpha) {
        publishValues(ctx, params, storedValues(ctx, params), status, alpha);
    }

    /**
     * Keeps the last values on screen but fades them according to their age and
     * adds a short "reason · age" line, so a stale number is obvious.
     */
    private void publishStale(Context ctx, List<ObdParam> params, String reason) {
        long age = ageMs(ctx);
        boolean everRead = Prefs.getLastOkTimestamp(ctx) > 0L;
        float alpha = everRead ? Fade.alphaForAge(age) : 1.0f;

        String status;
        if (!everRead) {
            status = reason == null ? "" : reason;
        } else {
            String agePart = ageText(ctx, age);
            status = (reason == null || reason.length() == 0)
                    ? agePart
                    : reason + " · " + agePart;
        }
        publishValues(ctx, params, storedValues(ctx, params), status, alpha);
    }

    private static String[] storedValues(Context ctx, List<ObdParam> params) {
        String[] values = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            String last = Prefs.getLastText(ctx, params.get(i));
            values[i] = last == null ? "--" : last;
        }
        return values;
    }

    /** Counts a failed attempt, renders the stale state and reschedules. */
    private void finishFailure(Context ctx, int reasonRes, boolean countFailure) {
        List<ObdParam> params = Prefs.getSelected(ctx);
        if (countFailure) {
            int fails = Prefs.getFailures(ctx) + 1;
            Prefs.setFailures(ctx, fails);
            Diag.logf("failure #%d (%s)", Integer.valueOf(fails),
                    ctx.getString(reasonRes));
        }
        publishStale(ctx, params, ctx.getString(reasonRes));
    }

    // ---- Scheduling -------------------------------------------------------

    /**
     * Schedules the next poll. Repeated failures back off up to
     * {@link #MAX_RETRY_DELAY_MS}, so an absent adapter does not keep the radio
     * busy every few seconds.
     */
    private void scheduleNext(Context ctx, int extraDelayMs) {
        long base = Prefs.getIntervalSec(ctx) * 1000L;
        int fails = Prefs.getFailures(ctx);
        long delay = base;
        if (fails > 0) {
            int factor = Math.min(fails, 6);
            delay = Math.min(base * factor, MAX_RETRY_DELAY_MS);
        }
        delay += extraDelayMs;
        Diag.logf("schedule: next poll in %d ms (failures=%d)", Long.valueOf(delay),
                Integer.valueOf(fails));
        ObdWidgetProvider.scheduleNextPoll(ctx, delay);
    }

    // ---- Helpers ----------------------------------------------------------

    private static long ageMs(Context ctx) {
        long ts = Prefs.getLastOkTimestamp(ctx);
        if (ts <= 0L) {
            return Long.MAX_VALUE;
        }
        long age = System.currentTimeMillis() - ts;
        return age < 0L ? 0L : age;
    }

    private static String ageText(Context ctx, long age) {
        if (age == Long.MAX_VALUE) {
            return ctx.getString(R.string.age_never);
        }
        long seconds = age / 1000L;
        if (seconds < 60) {
            return seconds + " " + ctx.getString(R.string.unit_sec);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " " + ctx.getString(R.string.unit_min);
        }
        return (minutes / 60) + " " + ctx.getString(R.string.unit_hour);
    }

    private static boolean hasBluetoothPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return true; // install-time permission on Android 4.x/5.x
        }
        if (Build.VERSION.SDK_INT < 31) {
            return true; // BLUETOOTH / BLUETOOTH_ADMIN are install-time
        }
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String deviceLabel(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }
        if (name != null && name.length() > 0) {
            return name;
        }
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "?";
        }
    }

    private static String shortName(String s) {
        if (s == null) {
            return "?";
        }
        return s.length() <= 16 ? s : s.substring(0, 16);
    }
}