package com.openuc2.control;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns everything USB: permission, open/close, baud changes, reads and writes.
 * The UI layer only calls connect()/send()/disconnect() and reacts to callbacks,
 * which always arrive on the main thread.
 *
 * Two things here are deliberate and worth not "simplifying" later:
 *
 *  - Incoming bytes are batched. A chatty firmware at 921600 baud delivers
 *    thousands of USB packets per second; posting one Runnable per packet to
 *    the main thread is a guaranteed ANR. Reads accumulate into a buffer that
 *    is flushed to the UI at {@link #FLUSH_INTERVAL_MS}.
 *  - Any failure tears the port down completely. The previous version left a
 *    dead port object in place, so isConnected() stayed true after a cable
 *    drop and every later connect() attempt answered "already connected".
 */
public class UsbSerialManager implements SerialInputOutputManager.Listener {

    private static final String TAG = "UC2Serial";
    private static final String ACTION_USB_PERMISSION = "com.openuc2.control.USB_PERMISSION";

    /** UI refresh cadence for received data. 20 Hz reads as instant. */
    private static final int FLUSH_INTERVAL_MS = 50;
    private static final int WRITE_TIMEOUT_MS = 2000;

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public enum LogLevel { INFO, TX, RX, ERROR }

    public interface Listener {
        void onStateChanged(State state, String detail);
        void onSerialData(String data);
        void onLog(LogLevel level, String message);
    }

    /** Unbatched received text, on the USB read thread. For request/reply protocols. */
    public interface RawListener {
        void onRaw(String text);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final StringBuilder rxBuffer = new StringBuilder();
    private final Object rxLock = new Object();
    private boolean flushScheduled = false;

    private Listener listener;
    private volatile RawListener rawListener;
    private volatile UsbSerialPort port;
    private volatile SerialInputOutputManager ioManager;
    private volatile State state = State.DISCONNECTED;

    private int currentBaud = 115200;
    private boolean dtr = true;
    private boolean rts = true;
    private boolean appendNewline = true;
    private int forcedDriverIndex = 0;
    private String deviceLabel = "";

    private BroadcastReceiver permissionReceiver;

    public UsbSerialManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ======================================================================
    // Configuration
    // ======================================================================

    public void setListener(Listener l)          { this.listener = l; }
    public void setRawListener(RawListener l)    { this.rawListener = l; }
    public boolean isConnected()                 { return state == State.CONNECTED; }
    public State getState()                      { return state; }
    public int getCurrentBaud()                  { return currentBaud; }
    public String getDeviceLabel()               { return deviceLabel; }
    public void setForcedDriverIndex(int index)  { this.forcedDriverIndex = index; }
    public int getForcedDriverIndex()            { return forcedDriverIndex; }
    public void setAppendNewline(boolean enable) { this.appendNewline = enable; }
    public boolean getAppendNewline()            { return appendNewline; }
    public boolean getDtr()                      { return dtr; }
    public boolean getRts()                      { return rts; }

    private UsbManager usbManager() {
        return (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
    }

    public String diagnostics() {
        return UsbDrivers.describeAttachedDevices(usbManager());
    }

    // ======================================================================
    // Connect
    // ======================================================================

    /** Connect to the first suitable device at {@code baudRate}. */
    public void connect(int baudRate) {
        connect(baudRate, null);
    }

    /**
     * Connect to {@code target}, or to the best auto-detected device when it is
     * null. Requests USB permission first if we do not already hold it.
     */
    public void connect(int baudRate, UsbDevice target) {
        if (state != State.DISCONNECTED) {
            log(LogLevel.INFO, "Connect ignored — already "
                    + state.name().toLowerCase(java.util.Locale.US));
            return;
        }
        currentBaud = baudRate;

        UsbManager manager = usbManager();
        if (manager == null) {
            fail("This device has no USB host service.");
            return;
        }

        UsbSerialDriver driver = resolveDriver(manager, target);
        if (driver == null) return;   // resolveDriver already reported why

        UsbDevice device = driver.getDevice();
        deviceLabel = UsbDrivers.label(device);
        setState(State.CONNECTING, deviceLabel);
        log(LogLevel.INFO, "Using " + driver.getClass().getSimpleName() + " for " + deviceLabel);

        if (!manager.hasPermission(device)) {
            requestPermission(manager, driver, baudRate);
        } else {
            openDriver(manager, driver, baudRate);
        }
    }

    /** Pick a driver: explicit device, forced class, or auto-detect. */
    private UsbSerialDriver resolveDriver(UsbManager manager, UsbDevice target) {
        if (target != null) {
            UsbSerialDriver forced = UsbDrivers.forceDriver(target, forcedDriverIndex);
            if (forced != null) return forced;
            UsbSerialDriver probed = UsbDrivers.probe(target);
            if (probed != null) return probed;
            fail("No driver matches " + UsbDrivers.label(target)
                    + ". Pick one under Force driver.");
            return null;
        }

        List<UsbSerialDriver> drivers = UsbDrivers.findDrivers(manager);
        if (!drivers.isEmpty()) {
            UsbSerialDriver auto = drivers.get(0);
            if (forcedDriverIndex > 0) {
                UsbSerialDriver forced =
                        UsbDrivers.forceDriver(auto.getDevice(), forcedDriverIndex);
                if (forced != null) return forced;
            }
            return auto;
        }

        // Nothing matched. If a device is attached at all, let the user force a
        // driver onto it rather than dead-ending.
        java.util.Collection<UsbDevice> attached = manager.getDeviceList().values();
        if (attached.isEmpty()) {
            fail("No USB device found. Check the OTG adapter and use a data cable.");
            return null;
        }
        if (forcedDriverIndex > 0) {
            // Never force a serial driver onto the webcam sharing the OTG hub.
            for (UsbDevice d : attached) {
                if (UsbDrivers.isCamera(d)) continue;
                UsbSerialDriver forced = UsbDrivers.forceDriver(d, forcedDriverIndex);
                if (forced != null) return forced;
            }
        }
        fail(attached.size() + " USB device(s) attached but none is a known serial chip. "
                + "Open Diagnostics, or set Force driver.");
        return null;
    }

    private void requestPermission(UsbManager manager, UsbSerialDriver driver, int baudRate) {
        log(LogLevel.INFO, "Requesting USB permission…");
        unregisterPermissionReceiver();

        permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                unregisterPermissionReceiver();
                boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    openDriver(manager, driver, baudRate);
                } else {
                    fail("USB permission denied. Re-plug the board and tap Allow.");
                }
            }
        };

        // ContextCompat picks the right registerReceiver overload per API level;
        // NOT_EXPORTED is required from Android 14 on.
        ContextCompat.registerReceiver(appContext, permissionReceiver,
                new IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);

        // Android 14 rejects a mutable PendingIntent carrying an implicit
        // intent, so the target package must be set explicitly.
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(appContext.getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(appContext, 0, intent, flags);

        try {
            manager.requestPermission(driver.getDevice(), pi);
        } catch (Exception e) {
            unregisterPermissionReceiver();
            fail("Could not request USB permission: " + e.getMessage());
        }
    }

    private synchronized void unregisterPermissionReceiver() {
        if (permissionReceiver != null) {
            try {
                appContext.unregisterReceiver(permissionReceiver);
            } catch (IllegalArgumentException ignored) {
                // not registered — fine
            }
            permissionReceiver = null;
        }
    }

    private void openDriver(UsbManager manager, UsbSerialDriver driver, int baudRate) {
        ioExecutor.submit(() -> {
            UsbSerialPort newPort = null;
            try {
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection == null) {
                    fail("Could not open the USB device. Another app may be holding it — "
                            + "unplug and re-plug the board.");
                    return;
                }

                List<UsbSerialPort> ports = driver.getPorts();
                if (ports.isEmpty()) {
                    connection.close();
                    fail("Driver reported no serial ports on this device.");
                    return;
                }

                newPort = ports.get(0);
                newPort.open(connection);
                newPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE);

                // Order matters. On boards with the classic auto-reset circuit
                // the state (DTR=0, RTS=1) holds EN low, i.e. the ESP32 stays in
                // reset. Raising DTR before RTS never passes through it.
                applyControlLines(newPort, dtr, rts);

                port = newPort;
                currentBaud = baudRate;

                ioManager = new SerialInputOutputManager(newPort, this);
                ioManager.start();

                setState(State.CONNECTED, deviceLabel);
                log(LogLevel.INFO, "Connected to " + deviceLabel + " at " + baudRate + " baud");
            } catch (IOException e) {
                abortOpen(newPort, "Open failed: " + e.getMessage());
            } catch (Exception e) {
                abortOpen(newPort, "Unexpected error while opening: " + e);
            }
        });
    }

    /** Roll back a half-finished open so the next connect() starts clean. */
    private void abortOpen(UsbSerialPort newPort, String message) {
        ioManager = null;
        port = null;
        closeQuietly(newPort);
        fail(message);
    }

    private void applyControlLines(UsbSerialPort p, boolean wantDtr, boolean wantRts) {
        try {
            p.setDTR(wantDtr);
            p.setRTS(wantRts);
        } catch (Exception e) {
            // Plenty of chips (and CDC in some firmwares) do not implement these.
            log(LogLevel.INFO, "DTR/RTS not supported by this chip — continuing");
        }
    }

    /** Change DTR/RTS on the fly; useful when a board refuses to talk. */
    public void setControlLines(boolean wantDtr, boolean wantRts) {
        this.dtr = wantDtr;
        this.rts = wantRts;
        UsbSerialPort p = port;
        if (p == null || !p.isOpen()) return;
        ioExecutor.submit(() -> {
            applyControlLines(p, wantDtr, wantRts);
            log(LogLevel.INFO, "DTR=" + wantDtr + " RTS=" + wantRts);
        });
    }

    // ======================================================================
    // Disconnect / baud change
    // ======================================================================

    public void disconnect() {
        if (state == State.DISCONNECTED && port == null) return;
        ioExecutor.submit(() -> {
            teardown();
            setState(State.DISCONNECTED, null);
            log(LogLevel.INFO, "Disconnected");
        });
    }

    /** Tear the port down. Always runs on the IO thread. */
    private void teardown() {
        SerialInputOutputManager mgr = ioManager;
        ioManager = null;
        if (mgr != null) {
            try {
                mgr.setListener(null);
                mgr.stop();
            } catch (Exception ignored) { }
        }
        UsbSerialPort p = port;
        port = null;
        closeQuietly(p);
    }

    private void closeQuietly(UsbSerialPort p) {
        if (p == null) return;
        try {
            p.close();
        } catch (Exception ignored) { }
    }

    /**
     * Re-open the port at a new baud rate.
     *
     * The WebSerial reference closes and re-opens rather than reconfiguring in
     * place, and so do we: several chips ignore a mid-stream setParameters, and
     * a full re-open also resets the board so its boot banner confirms the new
     * rate is right.
     */
    public void changeBaud(int newBaud) {
        if (state != State.CONNECTED) {
            fail("Not connected — cannot change baud rate.");
            return;
        }
        UsbSerialPort p = port;
        if (p == null) return;
        final UsbSerialDriver driver = p.getDriver();

        ioExecutor.submit(() -> {
            teardown();
            setState(State.CONNECTING, deviceLabel);
            log(LogLevel.INFO, "Re-opening at " + newBaud + " baud…");
            UsbManager manager = usbManager();
            if (manager == null) {
                setState(State.DISCONNECTED, null);
                return;
            }
            openDriver(manager, driver, newBaud);
        });
    }

    // ======================================================================
    // Send
    // ======================================================================

    /** Queue a command for writing. Adds a newline unless disabled. */
    public void send(String command) {
        send(command, true);
    }

    /**
     * Same, but {@code logTx=false} keeps it out of the console. Status polls
     * (BUSY every 20 ms during a scan) would otherwise flood the main thread.
     */
    public void send(String command, boolean logTx) {
        UsbSerialPort p = port;
        if (p == null || state != State.CONNECTED) {
            fail("Not connected — command not sent.");
            return;
        }
        final String wire = appendNewline && !command.endsWith("\n") ? command + "\n" : command;
        ioExecutor.submit(() -> {
            UsbSerialPort target = port;
            if (target == null || !target.isOpen()) {
                fail("Port closed before the command could be written.");
                return;
            }
            try {
                target.write(wire.getBytes(StandardCharsets.UTF_8), WRITE_TIMEOUT_MS);
                if (logTx) log(LogLevel.TX, command);
            } catch (IOException e) {
                fail("Write failed: " + e.getMessage());
                teardown();
                setState(State.DISCONNECTED, null);
            }
        });
    }

    /** Send several commands in order. */
    public void sendAll(String... commands) {
        for (String c : commands) send(c);
    }

    public void release() {
        unregisterPermissionReceiver();
        ioExecutor.submit(this::teardown);
        ioExecutor.shutdown();
        listener = null;
    }

    // ======================================================================
    // SerialInputOutputManager.Listener — called on the IO thread
    // ======================================================================

    @Override
    public void onNewData(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        RawListener raw = rawListener;
        if (raw != null) raw.onRaw(text);
        synchronized (rxLock) {
            rxBuffer.append(text);
            if (flushScheduled) return;
            flushScheduled = true;
        }
        mainHandler.postDelayed(this::flushRx, FLUSH_INTERVAL_MS);
    }

    private void flushRx() {
        String chunk;
        synchronized (rxLock) {
            flushScheduled = false;
            if (rxBuffer.length() == 0) return;
            chunk = rxBuffer.toString();
            rxBuffer.setLength(0);
        }
        Listener l = listener;
        if (l != null) l.onSerialData(chunk);
    }

    @Override
    public void onRunError(Exception e) {
        // The read thread has died: the cable was pulled, the board reset, or
        // the chip stalled. Tear everything down so a later connect() works.
        String message = e == null ? "unknown error" : String.valueOf(e.getMessage());
        ioExecutor.submit(() -> {
            teardown();
            setState(State.DISCONNECTED, null);
            log(LogLevel.ERROR, "Connection lost: " + message);
        });
    }

    // ======================================================================
    // Callbacks
    // ======================================================================

    private void setState(State newState, String detail) {
        state = newState;
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onStateChanged(newState, detail);
        });
    }

    private void log(LogLevel level, String message) {
        if (level == LogLevel.ERROR) Log.e(TAG, message); else Log.d(TAG, message);
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onLog(level, message);
        });
    }

    /** Report an error and make sure we are back in a connectable state. */
    private void fail(String message) {
        log(LogLevel.ERROR, message);
        if (state == State.CONNECTING) setState(State.DISCONNECTED, null);
    }
}
