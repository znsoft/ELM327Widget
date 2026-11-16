package com.obdwidget;

import android.bluetooth.BluetoothDevice;

import java.io.IOException;

/**
 * Keeps one ELM327 connection alive between widget polls.
 *
 * Reconnecting for every poll would run ATZ (a full adapter reboot) every few
 * seconds, which is exactly the kind of thing that makes cheap clones drop the
 * link. The session is reused while it is younger than {@link #IDLE_TIMEOUT_MS}
 * and the same adapter is selected; otherwise it is reopened.
 */
final class Session {

    private static final long IDLE_TIMEOUT_MS = 60_000L;

    private static Elm327Client client;
    private static String address;
    private static long lastUsedMs;
    /** Set while the settings screen runs a self-test/scan: polling must wait. */
    private static volatile boolean paused;

    private Session() {
    }

    static void setPaused(boolean value) {
        paused = value;
        Diag.log("Session: widget polling " + (value ? "paused" : "resumed"));
    }

    static boolean isPaused() {
        return paused;
    }

    /**
     * Returns a connected, initialized client for the given adapter, opening a
     * new connection when the cached one is stale or belongs to another device.
     */
    static synchronized Elm327Client acquire(BluetoothDevice device) throws IOException {
        String addr;
        try {
            addr = device.getAddress();
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission denied", e);
        }
        long now = System.currentTimeMillis();

        if (client != null) {
            boolean sameDevice = address != null && address.equalsIgnoreCase(addr);
            boolean fresh = (now - lastUsedMs) < IDLE_TIMEOUT_MS;
            if (sameDevice && fresh && client.isConnected()) {
                Diag.log("Session: reusing open link to " + addr);
                lastUsedMs = now;
                return client;
            }
            Diag.log("Session: dropping stale link (sameDevice=" + sameDevice
                    + ", fresh=" + fresh + ", connected=" + client.isConnected() + ")");
            client.close();
            client = null;
        }

        Diag.log("Session: opening new link to " + addr);
        Elm327Client c = new Elm327Client();
        try {
            c.connect(device);
            if (!c.beginSession()) {
                throw new IOException("adapter connected but did not answer ATI");
            }
        } catch (IOException e) {
            c.close();
            Diag.log("Session: open failed: " + e);
            throw e;
        }
        client = c;
        address = addr;
        lastUsedMs = System.currentTimeMillis();
        return c;
    }

    /** Marks the link as used, so the idle timer restarts. */
    static synchronized void touch() {
        lastUsedMs = System.currentTimeMillis();
    }

    /** Closes and forgets the current link (after an I/O error). */
    static synchronized void invalidate() {
        if (client != null) {
            client.close();
            client = null;
            Diag.log("Session: link invalidated");
        }
    }
}