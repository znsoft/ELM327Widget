package com.obdwidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes one selectable OBD-II parameter: which mode/PID to request and how
 * to turn the raw reply bytes into a human readable value.
 *
 * Only "Mode 01" (current data) PIDs with a simple 1- or 2-byte formula are
 * used so parsing stays reliable over a slow ELM327 serial link.
 */
public final class ObdParam {

    /** How the raw data bytes map onto the displayed number. */
    public enum Formula {
        /** A - 40 (temperature PIDs) */
        MINUS_40,
        /** ((A * 256) + B) / 4 (RPM) */
        RPM,
        /** A (direct byte value) */
        RAW,
        /** (A * 256) + B (two-byte counters such as run time / distance) */
        TWO_BYTES,
        /** A * 100 / 255 (percentage) */
        PERCENT,
        /** ((A * 256) + B) / 10 (voltage) */
        VOLT_X10,
        /** ((A * 256) + B) / 100 (kPa-ish scaled sensors) */
        X100,
        /** A - 128 (advance / offset signed) */
        OFFSET_128,
        /** ((A * 256) + B) / 20 (kPa absolute) */
        X20
    }

    public final String id;          // stable key stored in preferences
    public final String label;       // shown in widget and settings list
    public final String unit;        // appended to the value
    public final String pid;         // hex command after "01", e.g. "05"
    public final int dataBytes;      // 1 or 2 payload bytes used by formula
    public final Formula formula;
    /** True for values the app computes itself instead of reading one PID. */
    public final boolean derived;

    private ObdParam(String id, String label, String unit, String pid,
                     int dataBytes, Formula formula) {
        this(id, label, unit, pid, dataBytes, formula, false);
    }

    private ObdParam(String id, String label, String unit, String pid,
                     int dataBytes, Formula formula, boolean derived) {
        this.id = id;
        this.label = label;
        this.unit = unit;
        this.pid = pid;
        this.dataBytes = dataBytes;
        this.formula = formula;
        this.derived = derived;
    }

    // ---- Catalog of supported parameters ----------------------------------

    public static final ObdParam COOLANT = new ObdParam(
            "coolant", "Coolant temp", "\u00B0C", "05", 1, Formula.MINUS_40);
    public static final ObdParam INTAKE_AIR = new ObdParam(
            "intake_air", "Intake air temp", "\u00B0C", "0F", 1, Formula.MINUS_40);
    public static final ObdParam AMBIENT = new ObdParam(
            "ambient", "Ambient air temp", "\u00B0C", "46", 1, Formula.MINUS_40);
    public static final ObdParam ENGINE_LOAD = new ObdParam(
            "engine_load", "Engine load", "%", "04", 1, Formula.PERCENT);
    public static final ObdParam THROTTLE = new ObdParam(
            "throttle", "Throttle position", "%", "11", 1, Formula.PERCENT);
    public static final ObdParam FUEL_LEVEL = new ObdParam(
            "fuel_level", "Fuel level", "%", "2F", 1, Formula.PERCENT);
    public static final ObdParam RPM = new ObdParam(
            "rpm", "Engine RPM", "", "0C", 2, Formula.RPM);
    public static final ObdParam SPEED = new ObdParam(
            "speed", "Vehicle speed", "km/h", "0D", 1, Formula.RAW);
    public static final ObdParam MANIFOLD = new ObdParam(
            "manifold", "Intake manifold pressure", "kPa", "0B", 1, Formula.RAW);
    public static final ObdParam MAF = new ObdParam(
            "maf", "Mass air flow", "g/s", "10", 2, Formula.X100);
    public static final ObdParam TIMING = new ObdParam(
            "timing", "Timing advance", "\u00B0", "0E", 1, Formula.OFFSET_128);
    public static final ObdParam RUN_TIME = new ObdParam(
            "run_time", "Run time since start", "s", "1F", 2, Formula.TWO_BYTES);
    public static final ObdParam DISTANCE = new ObdParam(
            "distance", "Distance w/ MIL on", "km", "21", 2, Formula.TWO_BYTES);
    public static final ObdParam BARO = new ObdParam(
            "baro", "Barometric pressure", "kPa", "33", 1, Formula.RAW);
    public static final ObdParam CONTROL_VOLT = new ObdParam(
            "control_volt", "Control module voltage", "V", "42", 2, Formula.VOLT_X10);

    /**
     * Not a PID: fuel burned since the engine started, accumulated by
     * {@link TripFuel} from airflow (MAF or speed-density). The widget shows the
     * trip total as the big number and the current flow rate underneath.
     */
    public static final ObdParam TRIP_FUEL = new ObdParam(
            "trip_fuel", "Trip fuel used", "L", "trip", 0, Formula.RAW, true);

    private static final List<ObdParam> ALL = new ArrayList<ObdParam>();

    static {
        ALL.add(COOLANT);
        ALL.add(INTAKE_AIR);
        ALL.add(AMBIENT);
        ALL.add(ENGINE_LOAD);
        ALL.add(THROTTLE);
        ALL.add(RPM);
        ALL.add(SPEED);
        ALL.add(MANIFOLD);
        ALL.add(MAF);
        ALL.add(TIMING);
        ALL.add(FUEL_LEVEL);
        ALL.add(RUN_TIME);
        ALL.add(DISTANCE);
        ALL.add(BARO);
        ALL.add(CONTROL_VOLT);
        ALL.add(TRIP_FUEL);
    }

    public static List<ObdParam> all() {
        return ALL;
    }

    public static ObdParam defaultParam() {
        return COOLANT;
    }

    /** Never returns null: falls back to the default (coolant). */
    public static ObdParam byId(String id) {
        if (id != null) {
            for (ObdParam p : ALL) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
        }
        return COOLANT;
    }

    // ---- Parsing ----------------------------------------------------------

    /**
     * Applies this parameter's formula to the payload bytes.
     *
     * @param data payload bytes (already stripped of mode/pid echo and headers)
     * @return the numeric value, or null when not enough bytes are present
     */
    public Double compute(double[] data) {
        if (derived) {
            return null; // handled by TripFuel, not by a single PID
        }
        if (data == null || data.length < dataBytes) {
            return null;
        }
        double a = data[0];
        double b = data.length > 1 ? data[1] : 0;
        switch (formula) {
            case MINUS_40:
                return a - 40.0;
            case RPM:
                return ((a * 256.0) + b) / 4.0;
            case PERCENT:
                return a * 100.0 / 255.0;
            case VOLT_X10:
                return ((a * 256.0) + b) / 1000.0 * 10.0 / 10.0;
            case X100:
                return ((a * 256.0) + b) / 100.0;
            case X20:
                return ((a * 256.0) + b) / 20.0;
            case OFFSET_128:
                return a - 128.0;
            case TWO_BYTES:
                return (a * 256.0) + b;
            case RAW:
            default:
                return a;
        }
    }

    /** Decimal places appropriate for the parameter. */
    public int decimals() {
        switch (formula) {
            case PERCENT:
            case VOLT_X10:
            case X100:
            case X20:
                return 1;
            default:
                return 0;
        }
    }

    public String format(Double value) {
        if (value == null) {
            return "--";
        }
        return String.format(java.util.Locale.US, "%." + decimals() + "f%s",
                value, unit.length() > 0 ? " " + unit : "");
    }
}