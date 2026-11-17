package com.obdwidget;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal, synchronous ELM327 client over classic Bluetooth SPP (RFCOMM).
 *
 * Written for old devices: blocking I/O on a worker thread, no coroutines, no
 * AndroidX. Every public method must be called off the UI thread.
 *
 * Everything this class does is mirrored into {@link Diag}, so the settings
 * screen can show exactly which step failed.
 */
public final class Elm327Client {

    /** Well-known Serial Port Profile UUID. */
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int ATZ_TIMEOUT_MS = 6000;
    /** AT commands answer in well under 100 ms; a silent adapter must fail fast. */
    private static final int AT_TIMEOUT_MS = 2500;
    private static final int PID_TIMEOUT_MS = 9000;
    /**
     * BluetoothSocket.connect() has no timeout on Android 4.x and can block for
     * minutes on a busy/unresponsive adapter. The call is therefore run on a
     * helper thread and abandoned after this long.
     */
    private static final int CONNECT_TIMEOUT_MS = 15000;

    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    /** Suppresses per-command logging for multi-PID readers (keeps Diag small). */
    private volatile boolean quiet;

    public void setQuiet(boolean value) {
        quiet = value;
    }

    public boolean isQuiet() {
        return quiet;
    }

    // ---- Adapter discovery ------------------------------------------------

    /**
     * Finds a usable ELM327 adapter among paired devices.
     *
     * @param preferredAddress MAC chosen in settings, or null/"" for auto-detect
     * @return the device, or null when nothing suitable is paired
     */
    public static BluetoothDevice findAdapter(BluetoothAdapter adapter,
                                              String preferredAddress) {
        if (adapter == null) {
            Diag.log("findAdapter: no BluetoothAdapter on this device");
            return null;
        }
        if (!adapter.isEnabled()) {
            Diag.log("findAdapter: Bluetooth is turned off");
            return null;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Diag.log("findAdapter: BLUETOOTH_CONNECT not granted (" + e + ")");
            return null;
        }
        if (bonded == null || bonded.isEmpty()) {
            Diag.log("findAdapter: no paired devices at all");
            return null;
        }
        Diag.logf("findAdapter: %d paired device(s), preferred='%s'",
                bonded.size(), preferredAddress);

        if (preferredAddress != null && preferredAddress.length() > 0) {
            for (BluetoothDevice d : bonded) {
                if (preferredAddress.equalsIgnoreCase(d.getAddress())) {
                    Diag.log("findAdapter: using configured adapter " + preferredAddress);
                    return d;
                }
            }
            Diag.log("findAdapter: configured " + preferredAddress
                    + " is no longer paired, falling back to auto-detect");
        }

        // Only list everything when auto-detecting: otherwise this spams the log
        // with a dozen lines on every poll.
        for (BluetoothDevice d : bonded) {
            Diag.logf("  paired: %s  [%s]", safeName(d), d.getAddress());
        }

        BluetoothDevice fallback = null;
        for (BluetoothDevice d : bonded) {
            String name = safeName(d);
            if (name == null) {
                if (fallback == null) {
                    fallback = d;
                }
                continue;
            }
            String lower = name.toLowerCase(Locale.US);
            if (lower.contains("obd") || lower.contains("elm")
                    || lower.contains("vlink") || lower.contains("vgate")
                    || lower.contains("konnwei") || lower.contains("kcan")
                    || lower.contains("vscan") || lower.contains("obdii")) {
                Diag.log("findAdapter: auto-selected by name -> " + name);
                return d;
            }
            if (fallback == null) {
                fallback = d;
            }
        }
        if (fallback != null) {
            Diag.log("findAdapter: no name matched, using first paired device "
                    + safeName(fallback));
        }
        return fallback;
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    // ---- Connection -------------------------------------------------------

    /** Opens a socket, trying the two most common strategies. */
    public void connect(BluetoothDevice device) throws IOException {
        connect(device, 2);
    }

    /**
     * Opens the RFCOMM socket.
     *
     * @param maxStrategies how many connection strategies to try (2 for normal
     *                      polling, 4 for the diagnostic self-test)
     */
    public void connect(BluetoothDevice device, int maxStrategies) throws IOException {
        close();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        // isDiscovering()/cancelDiscovery() need BLUETOOTH_SCAN on Android 12+,
        // and we never scan, so only bother with this on older releases.
        if (adapter != null && Build.VERSION.SDK_INT < 31) {
            try {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                    Diag.log("connect: cancelled an active discovery (it blocks RFCOMM)");
                }
            } catch (SecurityException e) {
                Diag.log("connect: cancelDiscovery denied: " + e.getMessage());
            }
        }

        IOException last = null;
        for (int strategy = 0; strategy < maxStrategies; strategy++) {
            String name = strategyName(strategy);
            long started = System.currentTimeMillis();
            BluetoothSocket s = null;
            try {
                s = createSocket(device, strategy);
                Diag.logf("connect: strategy %d/%d '%s' -> socket created, connecting…",
                        strategy + 1, maxStrategies, name);
                connectSocket(s, CONNECT_TIMEOUT_MS);
                long dt = System.currentTimeMillis() - started;
                Diag.logf("connect: strategy %d '%s' SUCCESS in %d ms",
                        strategy + 1, name, dt);
                socket = s;
                in = s.getInputStream();
                out = s.getOutputStream();
                return;
            } catch (Exception e) {
                long dt = System.currentTimeMillis() - started;
                Diag.logf("connect: strategy %d '%s' FAILED after %d ms: %s",
                        strategy + 1, name, dt, e.toString());
                if (e instanceof IOException) {
                    last = (IOException) e;
                } else {
                    last = new IOException(e.toString(), e);
                }
                closeQuietly(s);
                sleep(400);
            }
        }
        throw last != null ? last : new IOException("no connection strategy worked");
    }

    /**
     * Connects with a hard deadline: closes the socket when the platform call
     * refuses to return, which makes the blocked connect() throw and lets the
     * next strategy run.
     */
    private static void connectSocket(final BluetoothSocket s, int timeoutMs)
            throws IOException {
        final IOException[] failure = new IOException[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.connect();
                } catch (Throwable t) {
                    failure[0] = (t instanceof IOException)
                            ? (IOException) t
                            : new IOException(t.toString(), t);
                }
            }
        }, "rfcomm-connect");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            Diag.logf("connect: no answer within %d ms -> closing socket", timeoutMs);
            closeQuietly(s);
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("connect timeout after " + timeoutMs + " ms");
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static String strategyName(int strategy) {
        switch (strategy) {
            case 0:
                return "SPP secure (SDP)";
            case 1:
                return "RFCOMM channel 1";
            case 2:
                return "SPP insecure";
            default:
                return "RFCOMM channel 1 insecure";
        }
    }

    /**
     * Strategy 0/1 cover virtually every ELM327 clone; 2/3 exist because some
     * Android builds reject the secure RFCOMM socket for unauthenticated
     * clones. Reflection is only used for the fallbacks, guarded by try/catch.
     */
    private BluetoothSocket createSocket(BluetoothDevice device, int strategy)
            throws Exception {
        switch (strategy) {
            case 0:
                return device.createRfcommSocketToServiceRecord(SPP_UUID);
            case 1:
                return (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class)
                        .invoke(device, Integer.valueOf(1));
            case 2:
                return (BluetoothSocket) device.getClass()
                        .getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class)
                        .invoke(device, SPP_UUID);
            default:
                return (BluetoothSocket) device.getClass()
                        .getMethod("createInsecureRfcommSocket", int.class)
                        .invoke(device, Integer.valueOf(1));
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public void close() {
        if (socket != null) {
            Diag.log("connect: closing socket");
            closeQuietly(socket);
            socket = null;
        }
        in = null;
        out = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Exception ignored) {
            // The socket is being discarded; nothing useful to do.
        }
    }

    // ---- Adapter setup ----------------------------------------------------

    /**
     * Brings the adapter into a known state so replies are short and easy to
     * parse: echo off, no linefeeds, no spaces, automatic protocol.
     *
     * @return true when the adapter answered ATI with something non-empty
     */
    public boolean beginSession() {
        try {
            // Wake-up: cheap clones swallow the very first byte after connect.
            sendRaw("\r");
            sleep(300);
            drain();

            String atz = commandWithTimeout("ATZ", ATZ_TIMEOUT_MS);
            Diag.log("ATZ  -> " + Diag.quote(atz));
            sleep(300);
            drain();

            Diag.log("ATE0 -> " + Diag.quote(commandWithTimeout("ATE0", AT_TIMEOUT_MS)));
            Diag.log("ATL0 -> " + Diag.quote(commandWithTimeout("ATL0", AT_TIMEOUT_MS)));
            Diag.log("ATS0 -> " + Diag.quote(commandWithTimeout("ATS0", AT_TIMEOUT_MS)));

            String ati = commandWithTimeout("ATI", AT_TIMEOUT_MS);
            Diag.log("ATI  -> " + Diag.quote(ati));
            if (ati == null || stripPrompt(ati).trim().length() == 0) {
                Diag.log("beginSession: ATI returned nothing -> adapter is silent");
                return false;
            }

            Diag.log("AT@1  -> " + Diag.quote(commandWithTimeout("AT@1", AT_TIMEOUT_MS)));
            Diag.log("ATDPN -> " + Diag.quote(commandWithTimeout("ATDPN", AT_TIMEOUT_MS)));
            Diag.log("ATRV  -> " + Diag.quote(commandWithTimeout("ATRV", AT_TIMEOUT_MS)));
            Diag.log("ATSP0 -> " + Diag.quote(commandWithTimeout("ATSP0", AT_TIMEOUT_MS)));
            return true;
        } catch (IOException e) {
            Diag.log("beginSession: I/O error: " + e);
            return false;
        }
    }

    // ---- PID polling ------------------------------------------------------

    /**
     * Requests one Mode 01 PID and returns the payload bytes (the bytes after
     * the "41 &lt;pid&gt;" echo).
     *
     * A single retry covers the common case of the first answer after a
     * protocol change being dropped.
     */
    public byte[] readPid(String pidHex) {
        String cmd = "01" + pidHex;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String reply = commandWithTimeout(cmd, PID_TIMEOUT_MS);
                if (!quiet) {
                    Diag.logf("%s attempt %d -> %s", cmd, attempt, Diag.quote(reply));
                }
                byte[] data = parsePidReply(reply, pidHex);
                if (data != null) {
                    if (!quiet) {
                        Diag.logf("%s payload %s", cmd, Diag.hex(data));
                    }
                    return data;
                }
                if (attempt == 1) {
                    sleep(300);
                }
            } catch (IOException e) {
                Diag.logf("%s attempt %d I/O error: %s", cmd, attempt, e.toString());
                if (attempt == 2) {
                    return null;
                }
                sleep(300);
            }
        }
        return null;
    }

    /**
     * Sends an arbitrary AT/OBD command and returns the raw reply up to the
     * '&gt;' prompt. Used by the diagnostics self-test.
     */
    public String rawCommand(String cmd) {
        try {
            return commandWithTimeout(cmd, PID_TIMEOUT_MS);
        } catch (IOException e) {
            Diag.logf("%s I/O error: %s", cmd, e.toString());
            return null;
        }
    }

    /**
     * Extracts payload bytes from a raw ELM327 reply.
     *
     * Handles multiple frames, "SEARCHING...", "NODATA", CAN headers, spaces
     * and multi-line answers.
     */
    static byte[] parsePidReply(String reply, String pidHex) {
        if (reply == null) {
            return null;
        }
        String upper = reply.toUpperCase(Locale.US)
                .replace(" ", "").replace("\r", "").replace("\n", "");
        String expected = "41" + pidHex.toUpperCase(Locale.US);
        int idx = upper.indexOf(expected);
        if (idx < 0) {
            return null;
        }
        String tail = upper.substring(idx + expected.length());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < tail.length() && hex.length() < 16; i++) {
            char c = tail.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                hex.append(c);
            } else {
                break;
            }
        }
        if (hex.length() < 2) {
            return null;
        }
        int count = hex.length() / 2;
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    /** Converts signed bytes into the unsigned doubles the formulas expect. */
    public static double[] toDoubles(byte[] data) {
        if (data == null) {
            return null;
        }
        double[] out = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] & 0xFF;
        }
        return out;
    }

    // ---- Low level --------------------------------------------------------

    private String commandWithTimeout(String cmd, int timeoutMs) throws IOException {
        drain();
        long started = System.currentTimeMillis();
        sendRaw(cmd + "\r");
        String reply = readUntilPrompt(timeoutMs);
        long dt = System.currentTimeMillis() - started;
        if (!quiet) {
            Diag.logf("TX '%s'  RX %d char(s) in %d ms", cmd, reply == null ? 0 : reply.length(), dt);
        }
        return reply;
    }

    private void sendRaw(String s) throws IOException {
        if (out == null) {
            throw new IOException("not connected (output stream is null)");
        }
        out.write(s.getBytes("US-ASCII"));
        out.flush();
    }

    /**
     * Reads until the '&gt;' prompt, EOF, or a quiet period.
     *
     * A quiet-period fallback matters because a few clones never send '&gt;'
     * after certain commands; waiting for the full timeout there would stall
     * every poll.
     */
    private String readUntilPrompt(int timeoutMs) throws IOException {
        if (in == null) {
            throw new IOException("not connected (input stream is null)");
        }
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastByteAt = 0;
        while (System.currentTimeMillis() < deadline) {
            int avail;
            try {
                avail = in.available();
            } catch (IOException e) {
                Diag.log("read: stream died: " + e);
                break;
            }
            if (avail > 0) {
                byte[] buf = new byte[avail];
                int n = in.read(buf);
                if (n <= 0) {
                    break;
                }
                sb.append(new String(buf, 0, n, "US-ASCII"));
                lastByteAt = System.currentTimeMillis();
                if (sb.indexOf(">") >= 0) {
                    break;
                }
            } else {
                if (lastByteAt > 0 && System.currentTimeMillis() - lastByteAt > 400) {
                    Diag.log("read: no '>' prompt, but the line went quiet");
                    break;
                }
                sleep(15);
            }
        }
        if (sb.indexOf(">") < 0 && sb.length() > 0) {
            Diag.logf("read: reply without prompt terminator (%d chars)", sb.length());
        }
        return sb.toString();
    }

    private static String stripPrompt(String s) {
        int idx = s.indexOf('>');
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private void drain() {
        if (in == null) {
            return;
        }
        try {
            while (in.available() > 0) {
                in.skip(in.available());
            }
        } catch (IOException ignored) {
            // A dead stream surfaces on the next real read.
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}