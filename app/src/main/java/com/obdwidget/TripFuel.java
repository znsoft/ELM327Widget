package com.obdwidget;

import android.content.Context;

/**
 * Fuel consumption model and trip accumulator.
 *
 * This car does not report fuel rate (PID 5E), fuel level (2F) or MAF (10) —
 * verified by the ECU scan — so "instant consumption" has to be modelled from
 * the air the engine is breathing, exactly like HobDrive and similar apps do
 * when the manufacturer PIDs are unknown.
 *
 * Two paths are implemented:
 *
 * <ul>
 *   <li><b>MAF</b> — {@code fuel = MAF / AFR}. Preferred when PID 10 answers.</li>
 *   <li><b>Speed density</b> — from intake pressure, RPM and intake air
 *       temperature:
 *       <pre>
 *   m_air = MAP · VE · V_disp · RPM / (120 · R · T)
 *   fuel  = m_air / AFR
 *       </pre>
 *       with VE ≈ 0.85 for a port-injected engine (a turbo petrol needs a lower
 *       VE at boost, hence the manual correction in the settings).</li>
 * </ul>
 *
 * The litre counter integrates the flow over engine run time (PID 1F), so a
 * park-up, a reboot or a lost link never adds phantom fuel.
 */
final class TripFuel {

    /** Stoichiometric ratio for petrol. */
    private static final double AFR = 14.7;
    private static final double FUEL_G_PER_L = 745.0;
    private static final double R_AIR = 287.05;
    private static final double VOLUMETRIC_EFFICIENCY = 0.85;
    /** Ignore intervals longer than this when integrating (missed samples). */
    private static final int MAX_STEP_SECONDS = 120;

    private TripFuel() {
    }

    /** Result of one sample. */
    static final class Result {
        final double litres;
        final double flowLh;
        final int runSeconds;

        Result(double litres, double flowLh, int runSeconds) {
            this.litres = litres;
            this.flowLh = flowLh;
            this.runSeconds = runSeconds;
        }
    }

    // ---- Pure model -------------------------------------------------------

    /** Litres per hour from measured mass air flow, g/s. */
    static double flowFromMaf(double mafGps) {
        if (mafGps <= 0) {
            return 0;
        }
        double fuelGps = mafGps / AFR;
        return fuelGps / FUEL_G_PER_L * 3600.0;
    }

    /**
     * Litres per hour from the speed-density model.
     *
     * @param mapKpa   intake manifold absolute pressure, kPa
     * @param rpm      engine speed
     * @param iatC     intake air temperature, °C
     * @param dispL    engine displacement, litres
     * @param ve       volumetric efficiency (0..1)
     */
    static double flowSpeedDensity(double mapKpa, double rpm, double iatC,
                                   double dispL, double ve) {
        if (mapKpa <= 0 || rpm <= 0 || dispL <= 0 || ve <= 0) {
            return 0;
        }
        double mapPa = mapKpa * 1000.0;
        double tempK = iatC + 273.15;
        if (tempK < 200) {
            tempK = 200; // guard against garbage temperature bytes
        }
        double dispM3 = dispL / 1000.0;
        double airKgPerSec = mapPa * ve * dispM3 * rpm / (120.0 * R_AIR * tempK);
        return flowFromMaf(airKgPerSec * 1000.0);
    }

    /** Adds {@code seconds} of flow to the accumulator. */
    static double integrate(double litres, double flowLh, int seconds) {
        if (litres < 0) {
            litres = 0;
        }
        if (seconds <= 0 || flowLh <= 0) {
            return litres;
        }
        if (seconds > MAX_STEP_SECONDS) {
            seconds = MAX_STEP_SECONDS;
        }
        return litres + flowLh * seconds / 3600.0;
    }

    static boolean engineRunning(double rpm, double mafGps) {
        if (rpm >= 0) {
            return rpm > 50;
        }
        return mafGps > 0;
    }

    // ---- Live sampling ----------------------------------------------------

    /**
     * Reads what the ECU has, models the flow, integrates it over engine run
     * time and persists the trip state.
     *
     * @return null when neither MAF nor the MAP/RPM/IAT trio is available
     */
    static Result sample(Elm327Client client, Context ctx) {
        boolean wasQuiet = client.isQuiet();
        client.setQuiet(true); // one log line per sample instead of a dozen
        try {
            double rpm = -1;
            byte[] rpmBytes = client.readPid("0C");
            if (rpmBytes != null && rpmBytes.length > 1) {
                rpm = (((rpmBytes[0] & 0xFF) << 8) | (rpmBytes[1] & 0xFF)) / 4.0;
            }

            int runSeconds = -1;
            byte[] runBytes = client.readPid("1F");
            if (runBytes != null && runBytes.length > 1) {
                runSeconds = ((runBytes[0] & 0xFF) << 8) | (runBytes[1] & 0xFF);
            }

            double maf = -1;
            byte[] mafBytes = client.readPid("10");
            if (mafBytes != null && mafBytes.length > 1) {
                maf = (((mafBytes[0] & 0xFF) << 8) | (mafBytes[1] & 0xFF)) / 100.0;
            }

            double flowLh;
            String source;
            if (maf > 0) {
                flowLh = flowFromMaf(maf);
                source = String.format(java.util.Locale.US, "MAF %.2f g/s", Double.valueOf(maf));
            } else {
                double mapKpa = -1;
                byte[] mapBytes = client.readPid("0B");
                if (mapBytes != null && mapBytes.length > 0) {
                    mapKpa = mapBytes[0] & 0xFF;
                }
                double iatC = -999;
                byte[] iatBytes = client.readPid("0F");
                if (iatBytes != null && iatBytes.length > 0) {
                    iatC = (iatBytes[0] & 0xFF) - 40;
                }
                if (!(mapKpa > 0 && rpm > 0 && iatC > -100)) {
                    Diag.logf("trip: not enough airflow data (MAP=%.0f RPM=%.0f IAT=%.0f)",
                            Double.valueOf(mapKpa), Double.valueOf(rpm), Double.valueOf(iatC));
                    return null;
                }
                double disp = Prefs.getEngineDisplacementL(ctx);
                flowLh = flowSpeedDensity(mapKpa, rpm, iatC, disp, VOLUMETRIC_EFFICIENCY);
                source = String.format(java.util.Locale.US,
                        "MAP %.0f kPa, RPM %.0f, IAT %.0f C, %.1f L",
                        Double.valueOf(mapKpa), Double.valueOf(rpm),
                        Double.valueOf(iatC), Double.valueOf(disp));
            }

            double correction = Prefs.getFuelCorrectionPct(ctx) / 100.0;
            flowLh *= correction;

            long now = System.currentTimeMillis();
            double litres = Prefs.getTripLiters(ctx);
            int prevRun = Prefs.getTripRunSeconds(ctx);
            long prevWall = Prefs.getTripWallMs(ctx);
            boolean running = engineRunning(rpm, maf);
            int step = 0;

            if (running) {
                if (runSeconds >= 0) {
                    if (prevRun >= 0) {
                        int delta = runSeconds - prevRun;
                        if (delta < 0 && prevRun > 60000) {
                            delta += 65536; // 16-bit counter wrapped after ~18 h
                        }
                        if (delta < 0) {
                            Diag.log("trip: engine restarted -> new trip");
                            litres = 0;
                        } else if (delta > 0) {
                            step = delta;
                            litres = integrate(litres, flowLh, delta);
                        }
                    }
                } else if (prevWall > 0) {
                    long deltaMs = now - prevWall;
                    if (deltaMs > 0 && deltaMs < MAX_STEP_SECONDS * 1000L) {
                        step = (int) (deltaMs / 1000L);
                        litres = integrate(litres, flowLh, step);
                    }
                }
            } else {
                // Engine off: keep the trip, but do not add fuel.
                litres = Math.max(0, litres);
            }

            Prefs.setTripState(ctx, (float) litres, runSeconds, now);
            Diag.logf("trip: %s -> %.2f L/h, +%d s, total %.3f L",
                    source, Double.valueOf(flowLh), Integer.valueOf(step),
                    Double.valueOf(litres));
            return new Result(litres, flowLh, runSeconds);
        } finally {
            client.setQuiet(wasQuiet);
        }
    }
}