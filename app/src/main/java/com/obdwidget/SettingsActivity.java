package com.obdwidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configuration form for the widget: which OBD parameter to show, how often to
 * poll, which paired Bluetooth adapter to talk to — plus a diagnostics panel
 * that records every step of the Bluetooth/ELM327 conversation.
 *
 * Serves double duty: launched from the app icon (edits defaults) and from the
 * widget picker via APPWIDGET_CONFIGURE (edits the widget being placed).
 */
public class SettingsActivity extends Activity {

    private static final int REQ_BT = 41;

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private LinearLayout paramList;
    private final List<CheckBox> paramChecks = new ArrayList<CheckBox>();
    private Spinner adapterSpinner;
    private EditText intervalEdit;
    private EditText displacementEdit;
    private EditText correctionEdit;
    private TextView previewText;

    private TextView logText;
    private Button diagButton;

    private List<ObdParam> params;
    private List<String> adapterAddresses;
    private List<String> adapterLabels;

    private boolean diagRunning;
    private String lastShownLog = "";

    private final Handler logHandler = new Handler();
    private final Runnable logTicker = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            logHandler.postDelayed(this, 600);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If we were launched as a widget configurator, a cancel must not
        // leave an orphaned widget on the home screen.
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED);
        }

        Diag.restore(this);

        setContentView(R.layout.activity_settings);

        paramList = (LinearLayout) findViewById(R.id.list_params);
        adapterSpinner = (Spinner) findViewById(R.id.spinner_adapter);
        intervalEdit = (EditText) findViewById(R.id.edit_interval);
        previewText = (TextView) findViewById(R.id.text_preview);
        logText = (TextView) findViewById(R.id.text_log);
        diagButton = (Button) findViewById(R.id.btn_diag);

        logText.setMovementMethod(new ScrollingMovementMethod());

        displacementEdit = (EditText) findViewById(R.id.edit_displacement);
        correctionEdit = (EditText) findViewById(R.id.edit_correction);
        displacementEdit.setText(String.valueOf(Prefs.getEngineDisplacementL(this)));
        correctionEdit.setText(String.valueOf(Prefs.getFuelCorrectionPct(this)));

        ((Button) findViewById(R.id.btn_reset_trip)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.resetTrip(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this,
                                R.string.trip_reset_done, Toast.LENGTH_SHORT).show();
                        Diag.log("trip counter reset by user");
                        WidgetUpdateService.requestRefresh(SettingsActivity.this);
                    }
                });

        setupParamList();
        setupAdapterSpinner();

        intervalEdit.setText(String.valueOf(Prefs.getIntervalSec(this)));
        intervalEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updatePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        saveAndFinish();
                    }
                });

        ((Button) findViewById(R.id.btn_cancel)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });

        ((Button) findViewById(R.id.btn_test)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        savePrefs();
                        Toast.makeText(SettingsActivity.this,
                                "Refreshing widget…", Toast.LENGTH_SHORT).show();
                        Diag.log("--- user tapped 'Test widget' ---");
                        WidgetUpdateService.requestRefresh(SettingsActivity.this);
                    }
                });

        diagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSelfTest();
            }
        });

        ((Button) findViewById(R.id.btn_scan)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startScan();
                    }
                });

        ((Button) findViewById(R.id.btn_copy)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyLog();
                    }
                });

        ((Button) findViewById(R.id.btn_clear)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Diag.clear(SettingsActivity.this);
                        lastShownLog = "";
                        refreshLog();
                    }
                });

        updatePreview();
        ensureRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
        logHandler.removeCallbacks(logTicker);
        logHandler.postDelayed(logTicker, 600);
    }

    @Override
    protected void onPause() {
        logHandler.removeCallbacks(logTicker);
        Diag.flush(this);
        super.onPause();
    }

    // ---- Bluetooth permission (Android 12+) -------------------------------

    private void ensureRuntimePermissions() {
        List<String> needed = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Needed so the foreground-service notification is visible.
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            Diag.log("requesting runtime permissions: " + needed);
            requestPermissions(needed.toArray(new String[needed.size()]), REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BT) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Diag.log("permission request result: granted=" + granted
                + " for " + (permissions == null ? "?" : java.util.Arrays.toString(permissions)));
        if (!granted) {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show();
        }
        // Paired devices become visible only after the grant: rebuild the list.
        setupAdapterSpinner();
        refreshLog();
    }

    // ---- Parameter spinner ------------------------------------------------

    private void setupParamList() {
        params = ObdParam.all();
        List<ObdParam> selected = Prefs.getSelected(this);
        paramList.removeAllViews();
        paramChecks.clear();
        for (ObdParam p : params) {
            CheckBox box = new CheckBox(this);
            box.setText(p.label + (p.unit.length() > 0 ? " (" + p.unit + ")" : ""));
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            box.setChecked(selected.contains(p));
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    updatePreview();
                }
            });
            paramList.addView(box);
            paramChecks.add(box);
        }
    }

    /** Parameters ticked in the list, in catalog order. */
    private List<ObdParam> selectedParams() {
        List<ObdParam> out = new ArrayList<ObdParam>();
        for (int i = 0; i < params.size() && i < paramChecks.size(); i++) {
            if (paramChecks.get(i).isChecked()) {
                out.add(params.get(i));
            }
        }
        if (out.isEmpty()) {
            out.add(ObdParam.defaultParam());
        }
        return out;
    }

    /** The self-test probes a single parameter: the first one selected. */
    private ObdParam firstSelectedParam() {
        return selectedParams().get(0);
    }

    // ---- Adapter spinner --------------------------------------------------

    private void setupAdapterSpinner() {
        adapterAddresses = new ArrayList<String>();
        adapterLabels = new ArrayList<String>();

        adapterAddresses.add("");
        adapterLabels.add(getString(R.string.adapter_auto));

        String current = Prefs.getAdapterAddress(this);
        int selectIndex = 0;

        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null && bt.isEnabled()) {
            try {
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String name;
                        try {
                            name = d.getName();
                        } catch (SecurityException e) {
                            name = null;
                        }
                        if (name == null || name.length() == 0) {
                            name = "?";
                        }
                        String address;
                        try {
                            address = d.getAddress();
                        } catch (SecurityException e) {
                            address = "?";
                        }
                        adapterAddresses.add(address);
                        adapterLabels.add(name + "\n" + address);
                        if (current != null && current.equalsIgnoreCase(address)) {
                            selectIndex = adapterAddresses.size() - 1;
                        }
                    }
                }
            } catch (SecurityException e) {
                Diag.log("setupAdapterSpinner: cannot list bonded devices: " + e);
            }
        }

        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, adapterLabels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapterSpinner.setAdapter(a);
        if (selectIndex < adapterAddresses.size()) {
            adapterSpinner.setSelection(selectIndex);
        }
    }

    private String selectedAdapterAddress() {
        int pos = adapterSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < adapterAddresses.size()) {
            return adapterAddresses.get(pos);
        }
        return "";
    }

    // ---- Preview / persistence -------------------------------------------

    private void updatePreview() {
        List<ObdParam> sel = selectedParams();
        if (sel.size() == 1 && sel.get(0).derived) {
            previewText.setText(getString(R.string.preview_trip));
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < sel.size() && i < 3; i++) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(sel.get(i).label);
        }
        if (sel.size() > 3) {
            names.append("…");
        }
        previewText.setText(getString(R.string.preview_multi,
                Integer.valueOf(sel.size()), names.toString()));
    }

    private float readDisplacement() {
        try {
            return Float.parseFloat(
                    displacementEdit.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 2.0f;
        }
    }

    private int readCorrection() {
        try {
            return Integer.parseInt(correctionEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private int readInterval() {
        int sec;
        try {
            sec = Integer.parseInt(intervalEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            sec = Prefs.DEFAULT_INTERVAL_SEC;
        }
        if (sec < Prefs.MIN_INTERVAL_SEC) {
            sec = Prefs.MIN_INTERVAL_SEC;
        }
        if (sec > Prefs.MAX_INTERVAL_SEC) {
            sec = Prefs.MAX_INTERVAL_SEC;
        }
        return sec;
    }

    private void savePrefs() {
        Prefs.setSelected(this, selectedParams());
        Prefs.setIntervalSec(this, readInterval());
        Prefs.setAdapterAddress(this, selectedAdapterAddress());
        Prefs.setEngineDisplacementL(this, readDisplacement());
        Prefs.setFuelCorrectionPct(this, readCorrection());
    }

    private void saveAndFinish() {
        savePrefs();

        // Same restart path as a tap on the widget: cancel the old alarm chain,
        // repaint, re-arm and poll immediately.
        ObdWidgetProvider.restartPipeline(this);

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Intent result = new Intent();
            result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            setResult(RESULT_OK, result);
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---- Diagnostics panel ------------------------------------------------

    private void refreshLog() {
        String text = Diag.text();
        if (text.length() == 0) {
            text = getString(R.string.diag_empty);
        }
        if (!text.equals(lastShownLog)) {
            lastShownLog = text;
            logText.setText(text);
            scrollLogToBottom();
        }
    }

    private void scrollLogToBottom() {
        try {
            int lines = logText.getLineCount();
            if (lines > 0 && logText.getLayout() != null) {
                int scroll = logText.getLayout().getLineTop(lines) - logText.getHeight();
                if (scroll > 0) {
                    logText.scrollTo(0, scroll);
                }
            }
        } catch (RuntimeException ignored) {
            // Scrolling is cosmetic; never let it break the screen.
        }
    }

    private void copyLog() {
        String text = Diag.text();
        if (text.length() == 0) {
            Toast.makeText(this, R.string.diag_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("OBD Widget log", text));
                Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException e) {
            Diag.log("copyLog failed: " + e);
        }
    }

    private void startSelfTest() {
        if (diagRunning) {
            Toast.makeText(this, R.string.diag_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        diagRunning = true;
        diagButton.setEnabled(false);
        Session.setPaused(true);
        savePrefs();

        final Context app = getApplicationContext();
        final ObdParam param = firstSelectedParam();
        final String wanted = selectedAdapterAddress();
        final String interval = intervalEdit.getText().toString().trim();
        Diag.log("--- self-test started (interval field: '" + interval + "') ---");

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    ok = selfTest(app, param, wanted);
                } catch (Throwable t) {
                    Diag.log("self-test crashed: " + t);
                    ok = false;
                }
                Diag.flush(app);
                final boolean success = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        diagRunning = false;
                        diagButton.setEnabled(true);
                        Session.setPaused(false);
                        refreshLog();
                        Toast.makeText(SettingsActivity.this,
                                success ? "Self-test OK: adapter answered"
                                        : "Self-test failed — see the log below",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "obd-selftest").start();
    }

    /**
     * Full end-to-end probe: environment, pairing, every connection strategy,
     * adapter identity and one PID read. Everything is written to {@link Diag}.
     *
     * @return true when a numeric value was produced
     */
    private boolean selfTest(Context ctx, ObdParam param, String wantedAddress) {
        Diag.section("SELF-TEST");
        Diag.logf("OBD Widget 1.1-debug, Android %s (API %d), %s %s",
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL);
        Diag.logf("parameter: %s  command: 01%s  interval: %d s",
                param.label, param.pid, Prefs.getIntervalSec(ctx));

        if (Build.VERSION.SDK_INT >= 31) {
            boolean granted = ctx.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            Diag.log("BLUETOOTH_CONNECT granted = " + granted);
            if (!granted) {
                Diag.log("ABORT: grant the Bluetooth permission first");
                return false;
            }
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Diag.log("ABORT: this device reports no Bluetooth adapter");
            return false;
        }
        Diag.log("Bluetooth enabled = " + safeIsEnabled(adapter)
                + ", discovering = " + safeIsDiscovering(adapter));
        if (!safeIsEnabled(adapter)) {
            Diag.log("ABORT: turn Bluetooth on and retry");
            return false;
        }

        Session.invalidate(); // make the test start from a clean slate

        BluetoothDevice device = Elm327Client.findAdapter(adapter, wantedAddress);
        if (device == null) {
            Diag.log("ABORT: no paired adapter found");
            return false;
        }
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException e) {
            address = "?";
        }
        Diag.logf("target adapter: %s [%s] bond=%d",
                name == null ? "?" : name, address, device.getBondState());

        Elm327Client client = new Elm327Client();
        try {
            client.connect(device, 4); // try every strategy in the self-test
        } catch (IOException e) {
            Diag.log("ABORT: all connection strategies failed: " + e);
            Diag.log("HINT: check that the adapter is powered (ignition/engine on),"
                    + " is not connected to another phone, and is paired but not"
                    + " 'connected' in the system Bluetooth settings.");
            return false;
        }

        try {
            if (!client.beginSession()) {
                Diag.log("ABORT: socket opened but the adapter never answered ATI");
                Diag.log("HINT: wrong device selected, or the adapter is in a"
                        + " different mode (BLE/beta firmware).");
                return false;
            }

            Diag.log("probe 0100 -> " + Diag.quote(client.rawCommand("0100")));
            Diag.log("probe 0101 -> " + Diag.quote(client.rawCommand("0101")));

            if (param.derived) {
                TripFuel.Result trip = TripFuel.sample(client, ctx);
                if (trip == null) {
                    Diag.log("RESULT: no MAF and no MAP/RPM/IAT — the fuel model"
                            + " cannot be computed on this car");
                    return false;
                }
                Diag.logf("RESULT: %s = %.2f L (%.1f L/h now)",
                        param.label, Double.valueOf(trip.litres),
                        Double.valueOf(trip.flowLh));
                return true;
            }

            byte[] raw = client.readPid(param.pid);
            if (raw == null) {
                Diag.log("RESULT: adapter answers, but PID 01" + param.pid
                        + " gave no payload");
                Diag.log("HINT: ignition must be ON and the engine running for"
                        + " live PIDs; also verify the car supports this PID.");
                return false;
            }
            Double value = param.compute(Elm327Client.toDoubles(raw));
            if (value == null) {
                Diag.log("RESULT: payload " + Diag.hex(raw)
                        + " could not be converted for " + param.label);
                return false;
            }
            Diag.logf("RESULT: %s = %s", param.label, param.format(value));
            return true;
        } finally {
            client.close();
            Session.invalidate();
        }
    }

    // ---- PID scan ---------------------------------------------------------

    /**
     * Asks the ECU which standard PIDs it supports, then reads a value for
     * each of them plus VIN / calibration ID / fault codes.
     */
    private void startScan() {
        if (diagRunning) {
            Toast.makeText(this, R.string.diag_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        diagRunning = true;
        diagButton.setEnabled(false);
        savePrefs();
        Session.setPaused(true);

        final Context app = getApplicationContext();
        final String wanted = selectedAdapterAddress();
        Diag.log("--- PID scan started ---");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                try {
                    // Release the widget's link first: one client per adapter.
                    Session.invalidate();
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (adapter == null) {
                        throw new IOException("нет Bluetooth-адаптера в устройстве");
                    }
                    if (Build.VERSION.SDK_INT >= 31
                            && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        throw new IOException("не выдано разрешение BLUETOOTH_CONNECT");
                    }
                    BluetoothDevice device =
                            Elm327Client.findAdapter(adapter, wanted);
                    if (device == null) {
                        throw new IOException("не найдено спаренное устройство ELM327");
                    }
                    Scan.run(device);
                } catch (Throwable t) {
                    Diag.log("scan failed: " + t);
                    error = t.toString();
                }
                Diag.flush(app);
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        diagRunning = false;
                        diagButton.setEnabled(true);
                        Session.setPaused(false);
                        refreshLog();
                        Toast.makeText(SettingsActivity.this,
                                err == null
                                        ? "Скан завершён — результат в журнале"
                                        : "Скан не удался: " + err,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "obd-scan").start();
    }

    private static boolean safeIsEnabled(BluetoothAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean safeIsDiscovering(BluetoothAdapter adapter) {
        try {
            return adapter.isDiscovering();
        } catch (SecurityException e) {
            return false;
        }
    }
}