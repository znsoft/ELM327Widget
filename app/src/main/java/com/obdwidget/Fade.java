package com.obdwidget;

/**
 * Freshness model for the widget's numeric value.
 *
 * The value is drawn at full brightness only right after a successful read.
 * While a new reading is in flight it is dimmed a little, and when no reading
 * has succeeded for a while it fades step by step — so a frozen number can
 * never be mistaken for a live one.
 *
 * Kept free of Android imports so the thresholds can be unit-tested on the JVM.
 */
final class Fade {

    /** Brightness while a poll is in flight. */
    static final float READING = 0.55f;

    private Fade() {
    }

    /**
     * @param ageMs milliseconds since the last successful read
     *              (use Long.MAX_VALUE when there never was one)
     * @return brightness multiplier 0..1
     */
    static float alphaForAge(long ageMs) {
        if (ageMs < 0) {
            return 1.0f;
        }
        long seconds = ageMs / 1000L;
        if (seconds < 15) {
            return 1.00f;   // fresh
        }
        if (seconds < 60) {
            return 0.85f;   // a little stale
        }
        if (seconds < 300) {
            return 0.65f;   // 1-5 min
        }
        if (seconds < 1800) {
            return 0.45f;   // 5-30 min
        }
        if (seconds < 7200) {
            return 0.30f;   // 30 min - 2 h
        }
        return 0.22f;       // hours: clearly a frozen value
    }

    /** True when the value has not been refreshed for longer than {@code seconds}. */
    static boolean isStale(long ageMs, long seconds) {
        return ageMs > seconds * 1000L;
    }
}