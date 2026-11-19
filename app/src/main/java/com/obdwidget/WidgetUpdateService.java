package com.obdwidget;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
 * Foreground polling service.
 *
 * Android 8+ forbids starting a background service from an alarm broadcast, and
 * from Android 12 the app is only briefly allow-listed after being visible. A
 * plain alarm -> service chain therefore dies a minute after the app leaves the
 * screen, which looked like a frozen widget. This service instead runs one
 * continuous loop while it is in the foreground, so polling keeps working with
 * the app in the background.
 */
public class WidgetUpdateService extends Service {

    public static final String ACTION_REFRESH = "com.obdwidget.ACTION_REFRESH";
    public static final String ACTION_STOP = "com.obdwidget.ACTION_STOP";

    private static final int NOTIF_ID = 4711;
    private static final String CHANNEL_ID = "obd_poll";
    /** Back-off cap while the adapter is unreachable, to spare the battery. */
    private static final long MAX_RETRY_DELAY_MS = 30000L;

    private static volatile boolean loopRequested;

    private Thread worker;
    private final Object sleeper = new Object();
    private volatile int lastResultCount;

    public static void requestRefresh(Context ctx) {
        Intent i = new Intent(ctx, WidgetUpdateService.class);
        i.setAction(ACTION_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (RuntimeException e) {
            // Happens when the system refuses a background start. The widget is
            // still repainted by the caller, and the user's next tap retries.
            Diag.log("requestRefresh refused: " + e);
        }
    }

    public static void requestStop(Context ctx) {
        Intent i = new Intent(ctx, WidgetUpdateService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (RuntimeException e) {
            Diag.log("requestStop refused: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            Diag.log("service: stop requested");
            loopRequested = false;
            wake();
            return START_NOT_STICKY;
        }

        ensureChannel();
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)));
            } catch (RuntimeException e) {
                // The system refused the foreground promotion (background start).
                // Stop at once: otherwise the 5 s foreground timeout crashes us.
                Diag.log("startForeground refused: " + e);
                loopRequested = false;
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        loopRequested = true;
        synchronized (this) {
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        loop();
                    }
                }, "obd-poll");
                worker.start();
                Diag.log("service: polling loop started");
            } else {
                wake();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        loopRequested = false;
        wake();
        super.onDestroy();
    }

    // ---- The loop ---------------------------------------------------------

    private void loop() {
        Context app = getApplicationContext();
        try {
            while (loopRequested) {
                long delay;
                try {
                    delay = cycle(app);
                } catch (Throwable t) {
                    Diag.log("poll crashed: " + t);
                    delay = 5000L;
                }
                Diag.flush(app);
                if (!loopRequested) {
                    break;
                }
                sleepFor(delay);
            }
        } finally {
            Diag.log("service: polling loop stopped");
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void sleepFor(long ms) {
        if (ms < 1000L) {
            ms = 1000L;
        }
        synchronized (sleeper) {
            try {
                sleeper.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                loopRequested = false;
            }
        }
    }

    private void wake() {
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    /** One poll cycle; returns how long to wait before the next one. */
    private long cycle(Context ctx) {
        Diag.section("widget poll");
        final List<ObdParam> params = Prefs.getSelected(ctx);

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, ObdWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) {
            Diag.log("service: no widgets left -> stopping");
            loopRequested = false;
            return 1000L;
        }

        final long age = ageMs(ctx);
        Diag.logf("params=%d interval=%ds lastAge=%s failures=%d",
                Integer.valueOf(params.size()), Integer.valueOf(Prefs.getIntervalSec(ctx)),
                age == Long.MAX_VALUE ? "never" : (age / 1000) + "s",
                Integer.valueOf(Prefs.getFailures(ctx)));

        // Dim what is on screen while the new reading is on its way; the last
        // status stays visible so the widget never looks like a blank panel.
        publishStored(ctx, params, Prefs.getLastStatus(ctx), Fade.READING);

        if (!hasBluetoothPermission(ctx)) {
            Diag.log("BLUETOOTH_CONNECT is not granted");
            return failure(ctx, R.string.status_perm, true);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Diag.log("device has no Bluetooth adapter");
            return failure(ctx, R.string.status_no_bt, false);
        }
        if (Session.isPaused()) {
            Diag.log("poll skipped: a self-test/scan is using the adapter");
            publishStale(ctx, params, null);
            return Prefs.getIntervalSec(ctx) * 1000L;
        }
        if (!adapter.isEnabled()) {
            Diag.log("Bluetooth is off");
            return failure(ctx, R.string.status_bt_off, true);
        }

        BluetoothDevice device = Elm327Client.findAdapter(
                adapter, Prefs.getAdapterAddress(ctx));
        if (device == null) {
            return failure(ctx, R.string.status_no_adapter, true);
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
            return failure(ctx, R.string.status_connect_failed, true);
        } catch (RuntimeException e) {
            Diag.log("poll: unexpected error: " + e);
            return failure(ctx, R.string.status_link_error, true);
        }
        return Prefs.getIntervalSec(ctx) * 1000L;
    }

    private long failure(Context ctx, int reasonRes, boolean count) {
        List<ObdParam> params = Prefs.getSelected(ctx);
        if (count) {
            int fails = Prefs.getFailures(ctx) + 1;
            Prefs.setFailures(ctx, fails);
            Diag.logf("failure #%d (%s)", Integer.valueOf(fails),
                    ctx.getString(reasonRes));
        }
        publishStale(ctx, params, ctx.getString(reasonRes));

        long base = Prefs.getIntervalSec(ctx) * 1000L;
        int fails = Prefs.getFailures(ctx);
        long delay = base;
        if (fails > 0) {
            delay = Math.min(base * Math.min(fails, 6), MAX_RETRY_DELAY_MS);
        }
        Diag.logf("next poll in %d ms (failures=%d)", Long.valueOf(delay),
                Integer.valueOf(fails));
        return delay;
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

    private ReadResult pollSelected(BluetoothDevice device, Context ctx,
                                    List<ObdParam> params) throws IOException {
        Elm327Client client;
        try {
            client = Session.acquire(device);
        } catch (IOException e) {
            // Retrying the same connect immediately only doubles the wait.
            Diag.log("poll: cannot open link: " + e);
            throw e;
        }
        // Each PID read already retries twice inside Elm327Client.
        try {
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
        lastResultCount = ok;
        return new ReadResult(values, ok);
    }

    // ---- Rendering --------------------------------------------------------

    private void publishValues(Context ctx, List<ObdParam> params, String[] values,
                               String status, float alpha) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, ObdWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) {
            return;
        }
        Diag.logf("publish: %d value(s), alpha=%.2f status='%s'",
                Integer.valueOf(params.size()), Float.valueOf(alpha), status);
        if (status != null && status.length() > 0) {
            Prefs.setLastStatus(ctx, status);
        }
        for (int id : ids) {
            mgr.updateAppWidget(id, ObdWidgetProvider.buildViews(
                    ctx, id, params, values, status, alpha));
        }
        updateNotification(ctx, status, values, params);
    }

    private void publishStored(Context ctx, List<ObdParam> params, String status,
                               float alpha) {
        publishValues(ctx, params, storedValues(ctx, params), status, alpha);
    }

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

    // ---- Notification -----------------------------------------------------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, SettingsActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                ObdWidgetProvider.pendingFlags());

        Intent stop = new Intent(this, WidgetUpdateService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                ObdWidgetProvider.pendingFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        0, getString(R.string.notif_stop), stopPi).build());
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_SERVICE);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(
                    Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotification(Context ctx, String status, String[] values,
                                    List<ObdParam> params) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < params.size() && i < 3; i++) {
            if (values != null && i < values.length && values[i] != null) {
                if (text.length() > 0) {
                    text.append(" · ");
                }
                text.append(values[i]);
            }
        }
        if (status != null && status.length() > 0) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append(status);
        }
        if (text.length() == 0) {
            text.append(getString(R.string.notif_starting));
        }
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text.toString()));
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
            // Service may already be gone.
        }
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