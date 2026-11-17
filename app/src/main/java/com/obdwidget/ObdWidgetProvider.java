package com.obdwidget;

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
 * A Chronometer at the bottom shows how long ago the numbers were refreshed. It
 * ticks on its own inside the launcher, so even if the polling service is
 * killed the age keeps counting — a frozen widget is immediately obvious.
 *
 * Polling itself lives in {@link WidgetUpdateService}, which runs as a
 * foreground service because Android reserves background service starts.
 */
public class ObdWidgetProvider extends AppWidgetProvider {

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
        // Runs from a broadcast (widget added, launcher restart, reboot), where
        // Android refuses to start a service. Only repaint; polling resumes on
        // the user's next tap or from the settings screen.
        repaintFromStored(context);
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
            // Tap on the widget (or "Save" in settings): repaint at once and
            // make sure the polling service is alive.
            restartPipeline(context);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ObdWidgetProvider.class);
        int[] remaining = mgr.getAppWidgetIds(cn);
        if (remaining == null || remaining.length == 0) {
            Diag.log("last widget removed -> stopping the polling service");
            WidgetUpdateService.requestStop(context);
        }
    }

    // ---- Restart pipeline -------------------------------------------------

    /** Repaints stored values and asks the polling service to refresh now. */
    public static void restartPipeline(Context context) {
        Diag.log("restart: repaint -> ensure poll service");
        repaintFromStored(context);
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
                    buildViews(context, id, params, values,
                            Prefs.getLastStatus(context), alpha));
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
        int maxChars = 1;
        for (int i = 0; i < params.size() && i < rows; i++) {
            String v = (values != null && i < values.length && values[i] != null)
                    ? values[i] : "--";
            if (v.length() > maxChars) {
                maxChars = v.length();
            }
        }
        // Keep room for the label: a wide number must not hide it.
        valueSp = WidgetLayoutCalc.fitValueSp(valueSp, size[0], maxChars);
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
            statusText = statusText.length() == 0
                    ? "+" + (params.size() - rows)
                    : statusText + " · +" + (params.size() - rows);
        }

        // Self-ticking age counter: keeps running even if the service is dead.
        long ts = Prefs.getLastOkTimestamp(context);
        if (ts > 0L) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - ts);
            long base = SystemClock.elapsedRealtime() - ageMs;
            views.setChronometer(R.id.widget_age, base, "%s", true);
            views.setViewVisibility(R.id.widget_age, View.VISIBLE);
            views.setTextViewTextSize(R.id.widget_age,
                    TypedValue.COMPLEX_UNIT_SP, statusSp);
        } else {
            views.setViewVisibility(R.id.widget_age, View.GONE);
        }

        if (statusText.length() == 0) {
            views.setViewVisibility(R.id.widget_status, View.GONE);
        } else {
            views.setViewVisibility(R.id.widget_status, View.VISIBLE);
            views.setTextViewText(R.id.widget_status, statusText);
            views.setTextViewTextSize(R.id.widget_status,
                    TypedValue.COMPLEX_UNIT_SP, statusSp);
        }

        // A tap starts the polling service directly as a foreground service.
        // Android allows that for a PendingIntent triggered by the user, while
        // "receiver -> startForegroundService" was denied with RCVR state.
        Intent refresh = new Intent(context, WidgetUpdateService.class);
        refresh.setAction(WidgetUpdateService.ACTION_REFRESH);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= 26) {
            pi = PendingIntent.getForegroundService(context, widgetId, refresh,
                    pendingFlags());
        } else {
            pi = PendingIntent.getService(context, widgetId, refresh, pendingFlags());
        }
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

    // ---- Intents ----------------------------------------------------------

    public static final String ACTION_REFRESH = "com.obdwidget.ACTION_REFRESH";
}