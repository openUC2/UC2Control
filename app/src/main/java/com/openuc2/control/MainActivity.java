package com.openuc2.control;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements UsbSerialManager.Listener {

    private static final int LED_GRID_SIZE = 64;

    private UsbSerialManager serial;
    private ScanTab scanTab;
    private final ConsoleBuffer console = new ConsoleBuffer();

    // Header / connection
    private View statusDot;
    private TextView statusText;
    private MaterialButton connectBtn;
    private MaterialButton changeBaudBtn;
    private Spinner baudSpinner;

    // Tabs
    private View[] tabPages;

    // Stage
    private EditText stepInput, speedInput;

    // Console
    private TextView consoleView;
    private ScrollView consoleScroll;
    private EditText cmdInput;
    private SwitchCompat autoScrollSwitch;

    @ColorInt private int colTx, colRx, colErr, colMeta;

    // Views that stay usable while disconnected.
    private static final int[] ALWAYS_ENABLED = {
            R.id.spinner_driver, R.id.switch_dtr, R.id.switch_rts, R.id.switch_newline,
            R.id.btn_clear_console, R.id.btn_share_log, R.id.switch_autoscroll, R.id.et_cmd
    };

    // ======================================================================
    // Lifecycle
    // ======================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        colTx   = ContextCompat.getColor(this, R.color.console_tx);
        colRx   = ContextCompat.getColor(this, R.color.console_rx);
        colErr  = ContextCompat.getColor(this, R.color.console_err);
        colMeta = ContextCompat.getColor(this, R.color.console_meta);

        serial = new UsbSerialManager(this);
        serial.setListener(this);

        bindViews();
        setupTabs();
        setupConnectionBar();
        setupStage();
        setupLight();
        setupSetup();
        setupConsole();
        scanTab = new ScanTab(this, serial);

        setUiEnabled(false);
        printBanner();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        scanTab.save();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (scanTab != null) scanTab.release();
        if (serial != null) serial.release();
        super.onDestroy();
    }

    /** Auto-connect when the user plugs a board in and picks this app. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;

        UsbDevice device = getAttachedDevice(intent);
        logMeta("USB device attached" + (device == null ? "" : ": " + UsbDrivers.label(device)));
        // Let enumeration settle before probing.
        connectBtn.postDelayed(() -> {
            if (!serial.isConnected()) serial.connect(selectedBaud(), device);
        }, 400);
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getAttachedDevice(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    /**
     * If CONNECTING has not resolved in 45 s, drop back to DISCONNECTED.
     * Android normally answers a USB permission request either way, but a
     * dismissed dialog on some ROMs sends nothing at all — and a permanently
     * disabled Connect button is exactly the dead end this app is meant to
     * stop happening.
     */
    private void armConnectWatchdog() {
        connectBtn.removeCallbacks(connectWatchdog);
        connectBtn.postDelayed(connectWatchdog, 45_000);
    }

    private final Runnable connectWatchdog = () -> {
        if (serial.getState() == UsbSerialManager.State.CONNECTING) {
            logMeta("Still not connected after 45 s — giving up so you can retry.");
            serial.disconnect();
            onStateChanged(UsbSerialManager.State.DISCONNECTED, null);
        }
    };

    private void printBanner() {
        logMeta("UC2 Control " + BuildConfig.VERSION_NAME
                + " — plug in an openUC2 board and tap Connect.");
        logMeta("If nothing is found, tap Diagnostics for a full USB dump.");
    }

    // ======================================================================
    // View binding
    // ======================================================================

    private void bindViews() {
        statusDot     = findViewById(R.id.status_dot);
        statusText    = findViewById(R.id.tv_status);
        connectBtn    = findViewById(R.id.btn_connect);
        changeBaudBtn = findViewById(R.id.btn_change_baud);
        baudSpinner   = findViewById(R.id.spinner_baud);

        tabPages = new View[]{
                findViewById(R.id.tab_content_scan),
                findViewById(R.id.tab_content_stage),
                findViewById(R.id.tab_content_light),
                findViewById(R.id.tab_content_setup),
                findViewById(R.id.tab_content_console)
        };

        stepInput  = findViewById(R.id.et_step);
        speedInput = findViewById(R.id.et_speed);

        consoleView      = findViewById(R.id.tv_console);
        consoleScroll    = findViewById(R.id.console_scroll);
        cmdInput         = findViewById(R.id.et_cmd);
        autoScrollSwitch = findViewById(R.id.switch_autoscroll);
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabs);
        int[] titles = {R.string.tab_scan, R.string.tab_stage, R.string.tab_light,
                        R.string.tab_setup, R.string.tab_console};
        for (int t : titles) tabs.addTab(tabs.newTab().setText(t));

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        showPage(0);
    }

    private void showPage(int index) {
        for (int i = 0; i < tabPages.length; i++) {
            tabPages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    // ======================================================================
    // Connection bar
    // ======================================================================

    private void setupConnectionBar() {
        ArrayAdapter<CharSequence> bauds = ArrayAdapter.createFromResource(
                this, R.array.baud_rate_labels, android.R.layout.simple_spinner_item);
        bauds.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(bauds);
        baudSpinner.setSelection(0);   // 115200, the firmware default

        connectBtn.setOnClickListener(v -> {
            if (serial.getState() == UsbSerialManager.State.CONNECTED) {
                serial.disconnect();
            } else if (serial.getState() == UsbSerialManager.State.DISCONNECTED) {
                serial.connect(selectedBaud());
            }
        });

        changeBaudBtn.setOnClickListener(v -> {
            if (serial.isConnected()) {
                serial.changeBaud(selectedBaud());
            } else {
                toast("Connect first");
            }
        });

        findViewById(R.id.btn_diagnostics).setOnClickListener(v -> showDiagnostics());
    }

    /**
     * The baud rate is parsed from the spinner label itself.
     *
     * The previous build kept a separate int[] alongside the string array; the
     * two had drifted, so picking "250000" actually opened the port at 921600
     * and 921600 was not selectable at all. With one source of truth that class
     * of bug cannot come back.
     */
    private int selectedBaud() {
        Object item = baudSpinner.getSelectedItem();
        if (item != null) {
            try {
                return Integer.parseInt(item.toString().trim());
            } catch (NumberFormatException ignored) { }
        }
        return 115200;
    }

    private void showDiagnostics() {
        String report = serial.diagnostics();
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setTypeface(androidx.core.content.res.ResourcesCompat
                .getFont(this, R.font.ibm_plex_mono));
        body.setTextSize(11f);
        int pad = dp(16);
        body.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("USB diagnostics")
                .setView(scroll)
                .setPositiveButton("Copy to console", (d, w) -> {
                    console.appendLine("\n--- USB diagnostics ---", colMeta);
                    console.appendLine(report, colMeta);
                    refreshConsole();
                    toast("Added to console");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ======================================================================
    // Stage tab
    // ======================================================================

    private void setupStage() {
        jog(R.id.btn_x_plus,  UC2Commands.STEPPER_X,  1);
        jog(R.id.btn_x_minus, UC2Commands.STEPPER_X, -1);
        jog(R.id.btn_y_plus,  UC2Commands.STEPPER_Y,  1);
        jog(R.id.btn_y_minus, UC2Commands.STEPPER_Y, -1);
        jog(R.id.btn_z_plus,  UC2Commands.STEPPER_Z,  1);
        jog(R.id.btn_z_minus, UC2Commands.STEPPER_Z, -1);
        jog(R.id.btn_a_plus,  UC2Commands.STEPPER_A,  1);
        jog(R.id.btn_a_minus, UC2Commands.STEPPER_A, -1);

        forever(R.id.btn_x_forever_plus,  UC2Commands.STEPPER_X,  1);
        forever(R.id.btn_x_forever_minus, UC2Commands.STEPPER_X, -1);
        forever(R.id.btn_y_forever_plus,  UC2Commands.STEPPER_Y,  1);
        forever(R.id.btn_y_forever_minus, UC2Commands.STEPPER_Y, -1);
        forever(R.id.btn_z_forever_plus,  UC2Commands.STEPPER_Z,  1);
        forever(R.id.btn_z_forever_minus, UC2Commands.STEPPER_Z, -1);
        forever(R.id.btn_a_forever_plus,  UC2Commands.STEPPER_A,  1);
        forever(R.id.btn_a_forever_minus, UC2Commands.STEPPER_A, -1);

        stop(R.id.btn_x_stop, UC2Commands.STEPPER_X);
        stop(R.id.btn_y_stop, UC2Commands.STEPPER_Y);
        stop(R.id.btn_z_stop, UC2Commands.STEPPER_Z);
        stop(R.id.btn_a_stop, UC2Commands.STEPPER_A);

        findViewById(R.id.btn_stop_all).setOnClickListener(v ->
                serial.sendAll(UC2Commands.motorStopAll()));

        onClick(R.id.btn_auto_enable_on,  UC2Commands.motorAutoEnable(true));
        onClick(R.id.btn_auto_enable_off, UC2Commands.motorAutoEnable(false));
        onClick(R.id.btn_motors_on,       UC2Commands.motorEnable(true));
        onClick(R.id.btn_motors_off,      UC2Commands.motorEnable(false));
    }

    private void jog(int viewId, int stepper, int sign) {
        findViewById(viewId).setOnClickListener(v ->
                send(UC2Commands.motorStep(stepper, sign * intOf(stepInput, 1000), speed())));
    }

    private void forever(int viewId, int stepper, int sign) {
        findViewById(viewId).setOnClickListener(v ->
                send(UC2Commands.motorForever(stepper, sign * Math.abs(speed()))));
    }

    private void stop(int viewId, int stepper) {
        findViewById(viewId).setOnClickListener(v -> send(UC2Commands.motorStop(stepper)));
    }

    private int speed() { return intOf(speedInput, 15000); }

    // ======================================================================
    // Light tab
    // ======================================================================

    private void setupLight() {
        ViewGroup container = findViewById(R.id.laser_container);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < UC2Commands.LASER_CHANNELS; i++) {
            final int channel = i;
            View row = inflater.inflate(R.layout.item_laser_channel, container, false);

            TextView label = row.findViewById(R.id.laser_label);
            SeekBar seek   = row.findViewById(R.id.laser_seek);
            TextView value = row.findViewById(R.id.laser_value);
            MaterialButton on  = row.findViewById(R.id.laser_on);
            MaterialButton off = row.findViewById(R.id.laser_off);

            label.setText(String.format(Locale.US, "Laser %d", channel));
            value.setText("0");

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    // Label tracks the thumb live; the command is only sent on
                    // release. Dragging fires ~100 events/second and the firmware
                    // queue cannot keep up with that at 115200 baud.
                    value.setText(String.valueOf(progress));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    send(UC2Commands.setLaser(channel, sb.getProgress()));
                }
            });

            on.setOnClickListener(v -> {
                seek.setProgress(UC2Commands.LASER_MAX);
                value.setText(String.valueOf(UC2Commands.LASER_MAX));
                send(UC2Commands.laserOn(channel));
            });
            off.setOnClickListener(v -> {
                seek.setProgress(0);
                value.setText("0");
                send(UC2Commands.laserOff(channel));
            });

            container.addView(row);
        }

        onClick(R.id.btn_led_fill, UC2Commands.ledFill(255, 255, 255));
        onClick(R.id.btn_led_off,  UC2Commands.ledOff());

        // Radii match the WebSerial reference: 5 outer, 3 middle, 2 centre.
        onClick(R.id.btn_ring_outer,  UC2Commands.ledRing(5, 255, 255, 255));
        onClick(R.id.btn_ring_middle, UC2Commands.ledRing(3, 255, 255, 255));
        onClick(R.id.btn_ring_center, UC2Commands.ledRing(2, 255, 255, 255));

        onClick(R.id.btn_half_top,    UC2Commands.ledHalf("top", 255, 255, 255));
        onClick(R.id.btn_half_bottom, UC2Commands.ledHalf("bottom", 255, 255, 255));
        onClick(R.id.btn_half_left,   UC2Commands.ledHalf("left", 255, 255, 255));
        onClick(R.id.btn_half_right,  UC2Commands.ledHalf("right", 255, 255, 255));

        buildLedGrid();
    }

    private void buildLedGrid() {
        GridLayout grid = findViewById(R.id.led_grid);
        int cell = dp(32);
        int margin = dp(2);

        for (int i = 0; i < LED_GRID_SIZE; i++) {
            final int index = i;
            TextView tile = new TextView(this);
            tile.setText(String.valueOf(i));
            tile.setGravity(Gravity.CENTER);
            tile.setTextSize(10f);
            tile.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tile.setBackgroundResource(R.drawable.bg_led_cell);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = cell;
            lp.setMargins(margin, margin, margin, margin);
            tile.setLayoutParams(lp);

            tile.setOnClickListener(v -> {
                boolean turningOn = !v.isSelected();
                v.setSelected(turningOn);
                int level = turningOn ? 255 : 0;
                send(UC2Commands.ledSingle(index, level, level, level));
            });

            grid.addView(tile);
        }
    }

    // ======================================================================
    // Setup tab
    // ======================================================================

    private void setupSetup() {
        // Quick homing, using the same defaults as the WebSerial demo.
        findViewById(R.id.btn_home_x).setOnClickListener(v -> quickHome(UC2Commands.STEPPER_X));
        findViewById(R.id.btn_home_y).setOnClickListener(v -> quickHome(UC2Commands.STEPPER_Y));
        findViewById(R.id.btn_home_z).setOnClickListener(v -> quickHome(UC2Commands.STEPPER_Z));
        findViewById(R.id.btn_home_a).setOnClickListener(v -> quickHome(UC2Commands.STEPPER_A));

        findViewById(R.id.btn_home_custom).setOnClickListener(v -> send(UC2Commands.homeStepper(
                intOf(findViewById(R.id.et_home_id), 2),
                intOf(findViewById(R.id.et_home_timeout), 20000),
                intOf(findViewById(R.id.et_home_speed), 15000),
                intOf(findViewById(R.id.et_home_dir), -1),
                intOf(findViewById(R.id.et_home_endstop), 0))));

        findViewById(R.id.btn_tmc_update).setOnClickListener(v -> send(UC2Commands.tmcSettings(
                intOf(findViewById(R.id.et_tmc_msteps), 16),
                intOf(findViewById(R.id.et_tmc_rms), 700),
                intOf(findViewById(R.id.et_tmc_sgthrs), 15),
                intOf(findViewById(R.id.et_tmc_semin), 5),
                intOf(findViewById(R.id.et_tmc_semax), 2),
                intOf(findViewById(R.id.et_tmc_blank), 24),
                intOf(findViewById(R.id.et_tmc_toff), 4),
                intOf(findViewById(R.id.et_tmc_axis), 2))));
        onClick(R.id.btn_tmc_get, UC2Commands.getTmc());

        findViewById(R.id.btn_can_set).setOnClickListener(v ->
                send(UC2Commands.canAddress(intOf(findViewById(R.id.et_can_address), 1))));

        onClick(R.id.btn_get_state,   UC2Commands.getState());
        onClick(R.id.btn_get_motor,   UC2Commands.getMotor());
        onClick(R.id.btn_get_modules, UC2Commands.getModules());
        onClick(R.id.btn_bt_pair,     UC2Commands.btScan());

        findViewById(R.id.btn_restart).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Restart board?")
                        .setMessage("The ESP32 will reboot and the serial link will drop.")
                        .setPositiveButton("Restart", (d, w) -> send(UC2Commands.restart()))
                        .setNegativeButton("Cancel", null)
                        .show());

        // Force-driver picker
        Spinner driverSpinner = findViewById(R.id.spinner_driver);
        ArrayAdapter<String> drivers = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, UsbDrivers.DRIVER_NAMES);
        drivers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        driverSpinner.setAdapter(drivers);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                serial.setForcedDriverIndex(pos);
                if (pos > 0) logMeta("Driver forced to " + UsbDrivers.DRIVER_NAMES[pos]
                        + " — reconnect to apply.");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        SwitchCompat dtr = findViewById(R.id.switch_dtr);
        SwitchCompat rts = findViewById(R.id.switch_rts);
        View.OnClickListener lines = v ->
                serial.setControlLines(dtr.isChecked(), rts.isChecked());
        dtr.setOnClickListener(lines);
        rts.setOnClickListener(lines);

        SwitchCompat newline = findViewById(R.id.switch_newline);
        newline.setOnCheckedChangeListener((b, checked) -> serial.setAppendNewline(checked));
    }

    private void quickHome(int stepper) {
        send(UC2Commands.homeStepper(stepper,
                intOf(findViewById(R.id.et_home_timeout), 20000),
                intOf(findViewById(R.id.et_home_speed), 15000),
                intOf(findViewById(R.id.et_home_dir), -1),
                intOf(findViewById(R.id.et_home_endstop), 0)));
    }

    // ======================================================================
    // Console tab
    // ======================================================================

    private void setupConsole() {
        findViewById(R.id.btn_send_cmd).setOnClickListener(v -> sendFromInput());

        cmdInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput();
                return true;
            }
            return false;
        });

        findViewById(R.id.btn_clear_console).setOnClickListener(v -> {
            console.clear();
            refreshConsole();
        });

        findViewById(R.id.btn_share_log).setOnClickListener(v -> shareLog());
    }

    private void sendFromInput() {
        String text = cmdInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (!serial.isConnected()) {
            toast("Not connected");
            return;
        }
        send(text);
        cmdInput.setText("");
    }

    private void shareLog() {
        if (console.isEmpty()) {
            toast("Console is empty");
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("UC2 Control ").append(BuildConfig.VERSION_NAME).append('\n');
        body.append("Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" · ").append(android.os.Build.MANUFACTURER)
            .append(' ').append(android.os.Build.MODEL).append("\n\n");
        body.append(serial.diagnostics()).append('\n');
        body.append("--- console ---\n").append(console.plainText());

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "UC2 Control log");
        share.putExtra(Intent.EXTRA_TEXT, body.toString());
        startActivity(Intent.createChooser(share, "Share log"));
    }

    private void refreshConsole() {
        consoleView.setText(console.text());
        if (autoScrollSwitch.isChecked()) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void logMeta(String message) {
        console.appendLine(message, colMeta);
        refreshConsole();
    }

    // ======================================================================
    // Enable / disable
    // ======================================================================

    /**
     * Walk the tab pages and enable or disable every control, then re-enable
     * the few that make sense offline. Enumerating ids by hand is what let the
     * old build drift out of sync with the layout.
     */
    private void setUiEnabled(boolean connected) {
        for (View page : tabPages) {
            // The Scan tab works without a board (camera only) and says so itself.
            if (page.getId() != R.id.tab_content_scan) setEnabledRecursive(page, connected);
        }
        for (int id : ALWAYS_ENABLED) {
            View v = findViewById(id);
            if (v != null) v.setEnabled(true);
        }
        changeBaudBtn.setEnabled(connected);
    }

    private void setEnabledRecursive(View view, boolean enabled) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
            return;
        }
        // Labels and hints stay at full contrast; only things you can operate
        // grey out. Disabling every TextView made the page look broken rather
        // than simply inactive.
        if (view instanceof android.widget.Button
                || view instanceof EditText
                || view instanceof SeekBar
                || view instanceof Spinner
                || view instanceof android.widget.CompoundButton
                || view.isClickable()) {
            view.setEnabled(enabled);
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private void onClick(int viewId, String command) {
        findViewById(viewId).setOnClickListener(v -> send(command));
    }

    private void send(String command) {
        if (!serial.isConnected()) {
            toast("Not connected");
            return;
        }
        serial.send(command);
    }

    private int intOf(EditText field, int fallback) {
        if (field == null) return fallback;
        try {
            String s = field.getText().toString().trim();
            return s.isEmpty() ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void setDotColor(int colorRes) {
        View dot = statusDot;
        if (dot.getBackground() instanceof GradientDrawable) {
            // Mutate so the four dots in the drawable cache do not share state.
            GradientDrawable shape = (GradientDrawable) dot.getBackground().mutate();
            shape.setColor(ContextCompat.getColor(this, colorRes));
        }
    }

    // ======================================================================
    // UsbSerialManager.Listener — always on the main thread
    // ======================================================================

    @Override
    public void onStateChanged(UsbSerialManager.State state, String detail) {
        switch (state) {
            case CONNECTED:
                connectBtn.removeCallbacks(connectWatchdog);
                statusText.setText(getString(R.string.status_connected,
                        detail == null ? "" : detail, serial.getCurrentBaud()));
                setDotColor(R.color.status_on);
                connectBtn.setText(R.string.disconnect);
                connectBtn.setEnabled(true);
                setUiEnabled(true);
                break;
            case CONNECTING:
                statusText.setText(getString(R.string.status_connecting,
                        detail == null ? "device" : detail));
                setDotColor(R.color.status_busy);
                connectBtn.setText(R.string.connecting);
                connectBtn.setEnabled(false);
                setUiEnabled(false);
                armConnectWatchdog();
                break;
            case DISCONNECTED:
            default:
                connectBtn.removeCallbacks(connectWatchdog);
                statusText.setText(R.string.status_disconnected);
                setDotColor(R.color.status_off);
                connectBtn.setText(R.string.connect);
                connectBtn.setEnabled(true);
                setUiEnabled(false);
                break;
        }
    }

    @Override
    public void onSerialData(String data) {
        console.append(data, colRx);
        refreshConsole();
    }

    @Override
    public void onLog(UsbSerialManager.LogLevel level, String message) {
        switch (level) {
            case TX:
                console.appendLine("> " + message, colTx);
                break;
            case ERROR:
                console.appendLine("! " + message, colErr);
                setDotColor(R.color.status_err);
                toast(message);
                break;
            case RX:
                console.appendLine(message, colRx);
                break;
            case INFO:
            default:
                console.appendLine("· " + message, colMeta);
                break;
        }
        refreshConsole();
    }
}
