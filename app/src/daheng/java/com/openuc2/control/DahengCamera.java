package com.openuc2.control;

import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import galaxy.DataStream;
import galaxy.Device;
import galaxy.DeviceManager;
import galaxy.EnumDefineSet;
import galaxy.ExceptionSet;
import galaxy.FeatureSet;
import galaxy.GxIJNI;

/**
 * Daheng (Galaxy) USB3 Vision cameras, through Daheng's Android SDK. The SDK
 * is not in git: copied to app/libs/galaxy (README, "Daheng cameras") it is
 * built in, together with this class. Android 6+, ARM phones only.
 *
 * One thread pulls debayered frames from the SDK, paints them into the
 * preview, and hands one over when a grab waits for it.
 */
final class DahengCamera implements Camera, DeviceManager.OnUpdateDeviceListFinishedListener {

    private static final int ENUM_TIMEOUT_MS = 1000;
    private static final int FRAME_TIMEOUT_MS = 500;
    private static final int JPEG_QUALITY = 95;
    /** The action the SDK's own USB permission receiver listens for. */
    private static final String SDK_PERMISSION = "com.example.USB_PERMISSION";

    private final Application app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Painter painter = new Painter();
    private final Object lock = new Object();

    private volatile Device device;
    private volatile boolean running, open;
    private volatile int width, height;
    private volatile boolean flush;   // drop what queued up while the stage moved

    private boolean wanted;           // guarded by lock: a grab is waiting
    private Bitmap grabbed;           // guarded by lock

    DahengCamera(Application app, Listener listener) {
        this.app = app;
        this.listener = listener;
    }

    @Override
    public void open() {
        if (running) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            report("Daheng cameras need Android 6 or newer");
            return;
        }
        try {
            DeviceManager dm = DeviceManager.getInstance();   // loads the native SDK
            dm.setOnUpdateDeviceListFinishedListener(this);
            report("Looking for a Daheng camera…");
            enumerate(dm);
        } catch (Throwable e) {   // UnsatisfiedLinkError on a phone the SDK has no libraries for
            report("Daheng SDK: " + message(e));
        }
    }

    /** Asks for USB permission, enumerates, then calls onUpdateDeviceListFinished. */
    private void enumerate(DeviceManager dm) throws Exception {
        try {
            dm.startUpdateDeviceList(app, ENUM_TIMEOUT_MS);
        } catch (IllegalArgumentException e) {
            // Android 14 refuses the SDK's permission PendingIntent, mutable with an implicit
            // intent (the SDK targets API 28), after the SDK has set itself up. Finish the job
            // with an explicit intent; the SDK's own receiver does the rest.
            // ponytail: reflection into Galaxy SDK 1.2.2407 internals; drop it once Daheng
            // puts a package on that intent.
            set(dm, "m_updateDeviceListType", enumConstant("galaxy.GxIJNI$UpdateDeviceListType", "UPDATE_LOCAL"));
            set(dm, "m_permissionIntent", PendingIntent.getBroadcast(app, 0,
                    new Intent(SDK_PERMISSION).setPackage(app.getPackageName()), PendingIntent.FLAG_MUTABLE));
            ContextCompat.registerReceiver(app, (BroadcastReceiver) get(dm, "m_usbPermissionReceiver"),
                    new IntentFilter(SDK_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);
            Method request = DeviceManager.class.getDeclaredMethod("__requestPermission");
            request.setAccessible(true);
            request.invoke(dm);
        }
    }

    @Override
    public void onUpdateDeviceListFinished() {
        List<DeviceManager.DeviceInfo> found;
        try {
            found = DeviceManager.getInstance().getDeviceInfoList();
        } catch (ExceptionSet e) {
            report("Daheng SDK: " + message(e));
            return;
        }
        if (found == null || found.isEmpty()) {
            report("No Daheng camera found. Plug it in and allow USB access.");
            return;
        }
        if (running) return;
        running = true;
        DeviceManager.DeviceInfo info = found.get(0);
        new Thread(() -> stream(info), "DahengCamera").start();
    }

    /** Stream thread: open, stream, paint, hand over grabs; close on the way out. */
    private void stream(DeviceManager.DeviceInfo info) {
        Device d = null;
        try {
            d = DeviceManager.getInstance().openDeviceByIndex(info.getIndex());
            try {
                d.TriggerMode.set(EnumDefineSet.SwitchEntry.OFF.getValue());   // free-running
            } catch (ExceptionSet ignored) {
                // no trigger on this model: free-running anyway
            }
            width = (int) d.Width.get();
            height = (int) d.Height.get();
            DataStream stream = d.getDataStream(0);
            d.streamOn();
            device = d;
            open = true;
            report(info.getModelName() + " · " + width + " × " + height);

            GxIJNI.ProcessParam param = new GxIJNI.ProcessParam();
            GxIJNI.FrameInfo frame = new GxIJNI.FrameInfo();
            boolean dropNext = false;
            while (running) {
                if (flush) {
                    flush = false;
                    stream.flushQueue();
                    dropNext = true;   // the first frame after a flush may have straddled the move
                }
                Bitmap bmp;
                try {
                    bmp = stream.getBitmap(param, frame, FRAME_TIMEOUT_MS);
                } catch (ExceptionSet.Timeout t) {
                    continue;
                }
                if (bmp == null || frame.getStatus() != 0) continue;   // incomplete frame
                if (painter.due()) painter.draw(bmp);
                if (dropNext) {
                    dropNext = false;
                    continue;
                }
                synchronized (lock) {
                    if (wanted && !flush) {
                        grabbed = bmp;
                        wanted = false;
                        lock.notifyAll();
                    }
                }
            }
        } catch (Exception e) {
            report("Daheng camera: " + message(e));
        } finally {
            open = false;
            device = null;
            if (d != null) {
                try {
                    d.streamOff();
                } catch (Exception ignored) {
                    // unplugged
                }
                try {
                    d.closeDevice();
                } catch (Exception ignored) {
                    // unplugged
                }
            }
            running = false;
        }
    }

    @Override
    public byte[] grabJpeg(long timeoutMs) throws Exception {
        if (!open) throw new IOException("No camera open");
        Bitmap bmp;
        synchronized (lock) {
            grabbed = null;
            wanted = true;
            flush = true;
            long end = SystemClock.uptimeMillis() + timeoutMs;
            while (grabbed == null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    wanted = false;
                    throw new TimeoutException("Daheng camera delivered no frame within " + timeoutMs + " ms");
                }
                lock.wait(left);
            }
            bmp = grabbed;
            grabbed = null;
        }
        // ponytail: 8-bit JPEG like the other cameras; a 12-bit sensor wants RawImage + TIFF.
        ByteArrayOutputStream out = new ByteArrayOutputStream(bmp.getByteCount() / 8);
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            throw new IOException("JPEG encoding failed");
        }
        return out.toByteArray();
    }

    @Override
    public String setExposure(double exposureMs, double gain) throws Exception {
        Device d = device;
        if (d == null) throw new IOException("No camera open");
        off(d.ExposureAuto);
        double us = clamp(exposureMs * 1000, d.ExposureTime.getMin(), d.ExposureTime.getMax());
        d.ExposureTime.set(us);
        off(d.GainAuto);
        double g = clamp(gain, d.Gain.getMin(), d.Gain.getMax());
        d.Gain.set(g);
        off(d.BalanceWhiteAuto);   // colour must not drift between tiles either
        return String.format(Locale.US, "%.2f ms · %.1f dB", us / 1000, g);
    }

    private static void off(FeatureSet.EnumFeature auto) throws ExceptionSet {
        if (auto != null && auto.isImplemented()) auto.set(EnumDefineSet.AutoEntry.OFF.getValue());
    }

    @Override
    public void setPreview(SurfaceTexture surface) {
        painter.setTarget(surface);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** The stream thread closes the device within one frame timeout. */
    @Override
    public void close() {
        running = false;
        painter.setTarget(null);
    }

    private void report(String status) {
        main.post(() -> listener.onCameraState(status, open ? width : 0, open ? height : 0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static Object get(Object o, String field) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String field, Object value) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String type, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) Class.forName(type), name);
    }
}
