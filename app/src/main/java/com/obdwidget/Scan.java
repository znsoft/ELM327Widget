package com.obdwidget;

import android.bluetooth.BluetoothDevice;

import java.io.IOException;
import java.util.Locale;

/**
 * Diagnostic scan of everything the ECU is willing to tell us:
 *
 * <ul>
 *   <li>Mode 01 supported-PID bit masks (0100, 0120, 0140 …) — the definitive
 *       list of standard parameters this car reports;</li>
 *   <li>a live value for every supported PID;</li>
 *   <li>Mode 09 identification: VIN, calibration ID, ECU name;</li>
 *   <li>Mode 03 stored fault codes.</li>
 * </ul>
 *
 * The result goes to {@link Diag}, i.e. to the settings log and logcat.
 */
final class Scan {

    private Scan() {
    }

    static void run(BluetoothDevice device) throws IOException {
        // The adapter accepts a single RFCOMM client, so take over the existing
        // session instead of opening a competing socket.
        Elm327Client client;
        try {
            client = Session.acquire(device);
        } catch (IOException first) {
            Diag.log("scan: connect failed (" + first + "), resetting link and retrying");
            Session.invalidate();
            sleep(700);
            client = Session.acquire(device);
        }
        Diag.section("PID SCAN");
        try {
            Diag.log("protocol number (ATDPN): " + Diag.quote(client.rawCommand("ATDPN")));
            Diag.log("protocol name   (ATDP) : " + Diag.quote(client.rawCommand("ATDP")));

            boolean[] supported = readSupported(client);
            Diag.log("supported standard PIDs: " + count(supported));

            Diag.section("VALUES");
            for (int pid = 0x01; pid <= 0xA6; pid++) {
                if (!supported[pid]) {
                    continue;
                }
                String name = PidNames.name(pid);
                byte[] data = client.readPid(String.format(Locale.US, "%02X", pid));
                String value = PidNames.describe(pid, data);
                if (name == null) {
                    Diag.logf("%02X  (PID не описан)               = %s", pid, value);
                } else {
                    Diag.logf("%02X  %s = %s", pid, name, value);
                }
            }

            Diag.section("IDENTIFICATION");
            Diag.log("VIN (0902)            : " + readMode09(client, "02"));
            Diag.log("Калибровка (0904)     : " + readMode09(client, "04"));
            Diag.log("Имя ЭБУ (090A)        : " + readMode09(client, "0A"));

            Diag.section("FAULT CODES");
            readDtcs(client);
            Session.touch();
        } finally {
            // Link is intentionally left open: the widget resumes and reuses it.
            Diag.log("scan: finished");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Supported PID masks ---------------------------------------------

    private static boolean[] readSupported(Elm327Client client) {
        boolean[] supported = new boolean[256];
        for (int block = 0x00; block <= 0xE0; block += 0x20) {
            String pidHex = String.format(Locale.US, "%02X", block);
            byte[] mask = client.readPid(pidHex);
            if (mask == null || mask.length < 4) {
                Diag.logf("scan: block %s -> no bit mask (stop)", pidHex);
                break;
            }
            for (int i = 0; i < 32; i++) {
                int byteValue = mask[i / 8] & 0xFF;
                if ((byteValue & (0x80 >> (i % 8))) != 0) {
                    supported[block + 1 + i] = true;
                }
            }
            Diag.logf("scan: block %s mask %s", pidHex, Diag.hex(mask));
            if (block + 0x20 > 0xE0 || !supported[block + 0x20]) {
                break;
            }
        }
        return supported;
    }

    private static int count(boolean[] flags) {
        int n = 0;
        for (boolean f : flags) {
            if (f) {
                n++;
            }
        }
        return n;
    }

    // ---- Mode 09 identification ------------------------------------------

    private static String readMode09(Elm327Client client, String sub) {
        String reply = client.rawCommand("09" + sub);
        if (reply == null) {
            return "<нет ответа>";
        }
        String key = "49" + sub;
        StringBuilder ascii = new StringBuilder();
        String[] lines = reply.toUpperCase(Locale.US).split("[\r\n]+");
        for (String line : lines) {
            String normalized = line.replace(" ", "");
            int at = normalized.indexOf(key);
            if (at < 0) {
                continue;
            }
            String tail = normalized.substring(at + key.length());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char ch = tail.charAt(i);
                if (Character.digit(ch, 16) >= 0) {
                    hex.append(ch);
                } else {
                    break;
                }
            }
            for (int i = 0; i + 1 < hex.length(); i += 2) {
                int b = Integer.parseInt(hex.substring(i, i + 2), 16);
                if (b >= 0x20 && b <= 0x7E) {
                    ascii.append((char) b);
                }
            }
        }
        String out = ascii.toString().trim();
        return out.length() == 0 ? "<нет данных>" : out;
    }

    // ---- Mode 03 fault codes ---------------------------------------------

    private static void readDtcs(Elm327Client client) {
        // The ECU tells us how many stored codes it has (PID 0101, byte A).
        // Without it, 0x55 CAN padding at the end of the frame turns into
        // phantom codes like "P0055".
        byte[] status = client.readPid("01");
        int expected = 0;
        boolean mil = false;
        if (status != null && status.length > 0) {
            mil = (status[0] & 0x80) != 0;
            expected = status[0] & 0x7F;
        }
        Diag.logf("MIL=%s, ЭБУ заявляет кодов: %d", mil ? "да" : "нет", expected);
        if (expected == 0) {
            Diag.log("DTC: кодов неисправностей нет");
            return;
        }

        String reply = client.rawCommand("03");
        if (reply == null) {
            Diag.log("DTC: нет ответа");
            return;
        }
        int found = 0;
        String[] lines = reply.toUpperCase(Locale.US).split("[\r\n]+");
        for (String line : lines) {
            String normalized = line.replace(" ", "");
            int at = normalized.indexOf("43");
            if (at < 0) {
                continue;
            }
            String tail = normalized.substring(at + 2);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char ch = tail.charAt(i);
                if (Character.digit(ch, 16) >= 0) {
                    hex.append(ch);
                } else {
                    break;
                }
            }
            for (int i = 0; i + 3 < hex.length() && found < expected; i += 4) {
                int b0 = Integer.parseInt(hex.substring(i, i + 2), 16);
                int b1 = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
                if (b0 == 0 && b1 == 0) {
                    continue;
                }
                // 0x55 0x55 is ISO-TP padding, not a code.
                if (b0 == 0x55 && b1 == 0x55) {
                    continue;
                }
                char system = "PCBU".charAt((b0 >> 6) & 0x03);
                int code = ((b0 & 0x3F) << 8) | b1;
                Diag.logf("DTC %c%04X", system, code);
                found++;
            }
        }
        Diag.logf("DTC найдено: %d (ЭБУ заявил %d)", found, expected);
    }
}