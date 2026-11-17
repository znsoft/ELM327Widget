package com.obdwidget;

/**
 * Fits the selected parameters into the widget's current size.
 *
 * Widgets are resized by the user, so the number of rows and the font size are
 * derived from the height reported by AppWidgetManager. Kept free of Android
 * imports so the arithmetic can be verified on the JVM.
 */
final class WidgetLayoutCalc {

    /** Hard limit: the layout provides this many pre-built rows. */
    static final int MAX_ROWS = 6;

    /** Roughly how much vertical space one row needs, dp. */
    private static final float MIN_ROW_DP = 16f;
    /** Space reserved for the status line, dp. */
    private static final float STATUS_DP = 14f;

    private WidgetLayoutCalc() {
    }

    /**
     * How many rows to draw: everything selected, capped by the space available
     * and by {@link #MAX_ROWS}.
     */
    static int rowsToShow(int selectedCount, int heightDp) {
        if (selectedCount <= 0) {
            return 1;
        }
        int room = (int) ((Math.max(20, heightDp) - STATUS_DP) / MIN_ROW_DP);
        if (room < 1) {
            room = 1;
        }
        return Math.min(selectedCount, Math.min(MAX_ROWS, room));
    }

    /** Value font size for the given height and row count, in sp. */
    static float valueTextSp(int heightDp, int rows) {
        float usable = Math.max(20, heightDp) - STATUS_DP;
        float rowHeight = usable / Math.max(1, rows);
        float sp = rowHeight * 0.62f;
        if (sp < 9f) {
            sp = 9f;
        }
        // A single-row widget should read like a gauge, not like a table cell.
        if (sp > 44f) {
            sp = 44f;
        }
        return sp;
    }

    /** Label font size: small, but never unreadable. */
    static float labelTextSp(float valueSp) {
        float sp = valueSp * 0.52f;
        if (sp < 7f) {
            sp = 7f;
        }
        if (sp > 14f) {
            sp = 14f;
        }
        return sp;
    }

    /** Status line font size. */
    static float statusTextSp(float valueSp) {
        float sp = valueSp * 0.45f;
        if (sp < 7f) {
            sp = 7f;
        }
        if (sp > 11f) {
            sp = 11f;
        }
        return sp;
    }

    /**
     * Caps the value font so a wide number cannot squeeze the label out of a
     * narrow widget: about 55% of the width is reserved for the number, and the
     * average glyph is roughly 0.62 em wide.
     */
    static float fitValueSp(float valueSp, int widthDp, int maxChars) {
        if (maxChars <= 1) {
            return valueSp;
        }
        float budget = Math.max(40f, widthDp) * 0.55f;
        float limit = budget / (maxChars * 0.62f);
        if (limit < 9f) {
            limit = 9f;
        }
        return Math.min(valueSp, limit);
    }
}