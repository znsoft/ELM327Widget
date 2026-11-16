package com.obdwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/**
 * AppWidgetProvider for the multi-parameter OBD readout.
 *
 * The widget draws up to {@link WidgetLayoutCalc#MAX_ROWS} rows (label + value).
 * How many are shown and how large the text is depends on the height reported
 * by the launcher, so the widget stays readable when resized.
 *
 * Polling is driven by AlarmManager rather than updatePeriodMillis because the
 * platform minimum for updatePeriodMillis is 30 minutes — far too slow for live
 * engine data.
 */
public class ObdWidgetProvider extends AppWidgetProvider {

    private static final int REQUEST_REFRESH = 1001;

    private static final int[] ROW_IDS = {
            R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4, R.id.row_5, R.id.row_6};
    private static final int[] LABEL_IDS = {
            R.id.label_1, R.id.label_2, R.id.label_3,
            R.id.label_4, R.id.label_5, R.id.label_6};
    private static final int[] VALUE_IDS = {
            R.id.value_1, R.id.value_2, R.id.value_3,
            R.id.value_4, R.id.value_5, R.id.value_6};

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
                         int[] appWidgetIds) {
        // Also called after a reboot or a launcher restart: rebuild everything.
        restartPipeline(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, mgr, appWidgetId, newOptions);
        Diag.log("widget resized -> repaint with new sizes");
        repaintFromStored(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            // Tap on the widget (or "Save" in settings). After a long screen-off
            // the alarm chain may be dead, so a single refresh request is not
            // enough: cancel, repaint, re-arm and poll — exactly what Save does.
            restartPipeline(context);
        } else if (ACTION_TICK.equals(action)) {
            WidgetUpdateService.requestRefresh(context);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ObdWidgetProvider.class);
        int[] remaining = mgr.getAppWidgetIds(cn);
        if (remaining == null || remaining.length == 0) {
            cancelPoll(context);
        }
    }

    // ---- Restart pipeline -------------------------------------------------

    /**
     * Fully restarts the widget: drops the stale alarm chain, repaints the
     * stored values at once, re-arms polling and asks the service to read now.
     */
    public static void restartPipeline(Context context) {
        Diag.log("restart: cancel alarm -> repaint -> re-arm -> poll");
        cancelPoll(context);
        repaintFromStored(context);
        scheduleNextPoll(context, 1000L);
        WidgetUpdateService.requestRefresh(context);
    }

    /** Repaints every widget instance from the last persisted readings. */
    static void repaintFromStored(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ObdWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) {
            Diag.log("restart: no widgets on the home screen");
            return;
        }
        List<ObdParam> params = Prefs.getSelected(context);
        long ts = Prefs.getLastOkTimestamp(context);
        long age = ts <= 0L ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - ts);
        float alpha = Fade.alphaForAge(age);

        String[] values = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            String last = Prefs.getLastText(context, params.get(i));
            values[i] = last == null ? "--" : last;
        }
        for (int id : ids) {
            mgr.updateAppWidget(id,
                    buildViews(context, id, params, values, "", alpha));
        }
        Diag.logf("restart: repainted %d widget(s), %d parameter(s), alpha=%.2f",
                Integer.valueOf(ids.length), Integer.valueOf(params.size()),
                Float.valueOf(alpha));
    }

    // ---- RemoteViews ------------------------------------------------------

    /**
     * Builds the widget view tree.
     *
     * @param values one string per selected parameter, already formatted
     * @param alpha  brightness of the numbers (fresh = 1.0)
     */
    static RemoteViews buildViews(Context context, int widgetId,
                                  List<ObdParam> params, String[] values,
                                  String status, float alpha) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.widget_layout);

        int[] size = widgetSizeDp(context, widgetId);
        int rows = WidgetLayoutCalc.rowsToShow(params.size(), size[1]);
        float valueSp = WidgetLayoutCalc.valueTextSp(size[1], rows);
        float labelSp = WidgetLayoutCalc.labelTextSp(valueSp);
        float statusSp = WidgetLayoutCalc.statusTextSp(valueSp);
        int valueColor = valueColor(alpha);

        for (int i = 0; i < WidgetLayoutCalc.MAX_ROWS; i++) {
            boolean show = i < rows && i < params.size();
            views.setViewVisibility(ROW_IDS[i], show ? View.VISIBLE : View.GONE);
            if (!show) {
                continue;
            }
            ObdParam param = params.get(i);
            String value = (values != null && i < values.length && values[i] != null)
                    ? values[i] : "--";
            views.setTextViewText(LABEL_IDS[i], param.label);
            views.setTextViewText(VALUE_IDS[i], value);
            views.setTextColor(VALUE_IDS[i], valueColor);
            views.setTextViewTextSize(VALUE_IDS[i],
                    TypedValue.COMPLEX_UNIT_SP, valueSp);
            views.setTextViewTextSize(LABEL_IDS[i],
                    TypedValue.COMPLEX_UNIT_SP, labelSp);
        }

        String statusText = status == null ? "" : status;
        if (params.size() > rows) {
            // More parameters selected than the current widget size can show.
            statusText = statusText.length() == 0
                    ? "+" + (params.size() - rows)
                    : statusText + " · +" + (params.size() - rows);
        }
        if (statusText.length() == 0) {
            views.setViewVisibility(R.id.widget_status, View.GONE);
        } else {
            views.setViewVisibility(R.id.widget_status, View.VISIBLE);
            views.setTextViewText(R.id.widget_status, statusText);
            views.setTextViewTextSize(R.id.widget_status,
                    TypedValue.COMPLEX_UNIT_SP, statusSp);
        }

        Intent refresh = new Intent(context, ObdWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, widgetId, refresh,
                pendingFlags());
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }

    /** Widget size in dp; falls back to the provider defaults when unknown. */
    static int[] widgetSizeDp(Context context, int widgetId) {
        int width = 110;
        int height = 60;
        try {
            Bundle options = AppWidgetManager.getInstance(context)
                    .getAppWidgetOptions(widgetId);
            if (options != null) {
                int w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
                int h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
                if (w > 0) {
                    width = w;
                }
                if (h > 0) {
                    height = h;
                }
            }
        } catch (RuntimeException e) {
            Diag.log("widgetSizeDp failed: " + e);
        }
        return new int[]{width, height};
    }

    static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /** White with the given opacity; the layout background stays dark. */
    static int valueColor(float alpha) {
        float clamped = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        int a = Math.round(255f * clamped);
        return Color.argb(a, 255, 255, 255);
    }

    // ---- Alarm scheduling -------------------------------------------------

    public static final String ACTION_REFRESH = "com.obdwidget.ACTION_REFRESH";
    public static final String ACTION_TICK = "com.obdwidget.ACTION_TICK";

    static void scheduleNextPoll(Context context, long intervalMs) {
        if (intervalMs < 2000L) {
            intervalMs = 2000L;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = tickIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + intervalMs, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + intervalMs, pi);
            }
        } catch (SecurityException e) {
            // Without SCHEDULE_EXACT_ALARM (API 31+) fall back to inexact.
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMs, pi);
        }
    }

    static void cancelPoll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(tickIntent(context));
        }
    }

    private static PendingIntent tickIntent(Context context) {
        Intent i = new Intent(context, ObdWidgetProvider.class);
        i.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, REQUEST_REFRESH, i, pendingFlags());
    }
}