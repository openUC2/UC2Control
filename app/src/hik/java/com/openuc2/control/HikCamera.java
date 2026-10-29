package com.openuc2.control;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import MvCameraControlWrapper.CameraControlException;
import MvCameraControlWrapper.MvCameraControl;
import MvCameraControlWrapper.MvCameraControlDefines;

import static MvCameraControlWrapper.MvCameraControlDefines.MV_E_ABNORMAL_IMAGE;
import static MvCameraControlWrapper.MvCameraControlDefines.MV_E_NODATA;
import static MvCameraControlWrapper.MvCameraControlDefines.MV_OK;

/**
 * HIK (Hikrobot) USB3 Vision cameras, through Hikrobot's MvCamCtrl SDK for
 * Android. The SDK is not in git: copied to app/libs/hik (README, "HIK
 * cameras") it is built in, together with this class. ARM phones only, and
 * rooted ones: the SDK opens /dev/bus/usb itself instead of asking Android.
 *
 * One thread finds and opens the camera, pulls frames as bitmaps, paints them
 * into the preview, and hands one over when a grab waits for it.
 */
final class HikCamera implements Camera {

    private static final int FRAME_TIMEOUT_MS = 500;
    private static final int JPEG_QUALITY = 95;
    /** "Off" of ExposureAuto, GainAuto, BalanceWhiteAuto and TriggerMode (MvCameraNode.xlsx). */
    private static final int OFF = 0;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Painter painter = new Painter();
    private final Object lock = new Object();

    private volatile MvCameraControlDefines.Handle handle;
    private volatile boolean running, open;
    private volatile int width, height;
    private volatile boolean flush;   // drop what queued up while the stage moved

    private boolean wanted;           // guarded by lock: a grab is waiting
    private Bitmap grabbed;           // guarded by lock

    /** Same constructor as DahengCamera, which ScanTab creates alike; the SDK needs no Context. */
    HikCamera(Application app, Listener listener) {
        this.listener = listener;
    }

    @Override
    public void open() {
        if (running) return;
        running = true;
        new Thread(this::stream, "HikCamera").start();
    }

    /** Stream thread: find, open, stream, paint, hand over grabs; close on the way out. */
    private void stream() {
        MvCameraControlDefines.Handle h = null;
        try {
            report("Looking for a HIK camera…");
            List<MvCameraControlDefines.MV_CC_DEVICE_INFO> found =
                    MvCameraControl.MV_CC_EnumDevices(MvCameraControlDefines.MV_USB_DEVICE);   // loads the native SDK
            if (found == null || found.isEmpty()) {
                report("No HIK camera found. Hikrobot's SDK needs a rooted phone (README, HIK cameras).");
                return;
            }
            MvCameraControlDefines.MV_CC_DEVICE_INFO info = found.get(0);
            h = MvCameraControl.MV_CC_CreateHandle(info, false);   // false: no log files
            check(MvCameraControl.MV_CC_OpenDevice(h), "OpenDevice");
            MvCameraControl.MV_CC_SetEnumValue(h, "TriggerMode", OFF);   // free-running; models without trigger refuse, harmlessly
            width = intValue(h, "Width");
            height = intValue(h, "Height");
            byte[] bmp = new byte[width * height * 4 + 2048];   // the SDK hands frames over as BMP files
            check(MvCameraControl.MV_CC_StartGrabbing(h), "StartGrabbing");
            handle = h;
            open = true;
            report(info.usb3VInfo.modelName + " · " + width + " × " + height);

            MvCameraControlDefines.MV_FRAME_OUT_INFO frame = new MvCameraControlDefines.MV_FRAME_OUT_INFO();
            BitmapFactory.Options small = new BitmapFactory.Options();
            small.inSampleSize = 2;   // plenty for the live view, a quarter of the pixels
            boolean dropNext = false;
            while (running) {
                if (flush) {
                    flush = false;
                    // No flush in this SDK: restarting acquisition drops what queued up.
                    check(MvCameraControl.MV_CC_StopGrabbing(h), "StopGrabbing");
                    check(MvCameraControl.MV_CC_StartGrabbing(h), "StartGrabbing");
                    dropNext = true;   // in case a frame from before the restart still comes first
                }
                boolean take;
                synchronized (lock) {
                    take = wanted && !flush;
                }
                boolean paint = painter.due();
                if (!take && !paint) {   // nobody needs a frame now; the SDK keeps only the newest
                    SystemClock.sleep(10);
                    continue;
                }
                int ret = MvCameraControl.MV_CC_GetBitmapTimeout(h, bmp, frame, FRAME_TIMEOUT_MS);
                if (ret == MV_E_NODATA || ret == MV_E_ABNORMAL_IMAGE) continue;   // none yet, or incomplete
                check(ret, "GetBitmapTimeout");
                if (frame.lostPacket > 0) continue;   // incomplete
                if (dropNext) {
                    dropNext = false;
                    take = false;
                    if (!paint) continue;
                }
                Bitmap b = BitmapFactory.decodeByteArray(bmp, 0, frame.frameLen, take ? null : small);
                if (b == null) throw new IOException("Unreadable frame, pixel format " + frame.pixelType);
                if (paint) painter.draw(b);
                if (!take) continue;
                synchronized (lock) {
                    if (wanted && !flush) {
                        grabbed = b;
                        wanted = false;
                        lock.notifyAll();
                    }
                }
            }
        } catch (Throwable e) {   // UnsatisfiedLinkError on a phone the SDK has no libraries for
            report("HIK camera: " + message(e));
        } finally {
            open = false;
            handle = null;
            if (h != null) {   // return codes no longer matter: closing either way
                MvCameraControl.MV_CC_StopGrabbing(h);
                MvCameraControl.MV_CC_CloseDevice(h);
                MvCameraControl.MV_CC_DestroyHandle(h);
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
                    throw new TimeoutException("HIK camera delivered no frame within " + timeoutMs + " ms");
                }
                lock.wait(left);
            }
            bmp = grabbed;
            grabbed = null;
        }
        // ponytail: 8-bit JPEG like the other cameras; a 12-bit sensor wants raw Mono12 frames + TIFF.
        ByteArrayOutputStream out = new ByteArrayOutputStream(bmp.getByteCount() / 8);
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            throw new IOException("JPEG encoding failed");
        }
        return out.toByteArray();
    }

    @Override
    public String setExposure(double exposureMs, double gain) throws Exception {
        MvCameraControlDefines.Handle h = handle;
        if (h == null) throw new IOException("No camera open");
        // Automatic modes off first; a model without one refuses, harmlessly.
        MvCameraControl.MV_CC_SetEnumValue(h, "ExposureAuto", OFF);
        MvCameraControl.MV_CC_SetEnumValue(h, "GainAuto", OFF);
        MvCameraControl.MV_CC_SetEnumValue(h, "BalanceWhiteAuto", OFF);   // colour must not drift between tiles either
        MvCameraControlDefines.MVCC_FLOATVALUE range = floatValue(h, "ExposureTime");
        float us = (float) clamp(exposureMs * 1000, range.min, range.max);
        check(MvCameraControl.MV_CC_SetFloatValue(h, "ExposureTime", us), "ExposureTime");
        range = floatValue(h, "Gain");
        float db = (float) clamp(gain, range.min, range.max);
        check(MvCameraControl.MV_CC_SetFloatValue(h, "Gain", db), "Gain");
        return String.format(Locale.US, "%.2f ms · %.1f dB", us / 1000, db);
    }

    @Override
    public void setPreview(SurfaceTexture surface) {
        painter.setTarget(surface);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** The stream thread closes the camera within one frame timeout. */
    @Override
    public void close() {
        running = false;
        painter.setTarget(null);
    }

    private void report(String status) {
        main.post(() -> listener.onCameraState(status, open ? width : 0, open ? height : 0));
    }

    /** The SDK returns error codes, listed in Hikrobot's developer guide (Appendixes, Error Code). */
    private static void check(int ret, String call) throws IOException {
        if (ret != MV_OK) throw new IOException(call + " failed: 0x" + Integer.toHexString(ret));
    }

    private static int intValue(MvCameraControlDefines.Handle h, String node) throws IOException {
        MvCameraControlDefines.MVCC_INTVALUE v = new MvCameraControlDefines.MVCC_INTVALUE();
        check(MvCameraControl.MV_CC_GetIntValue(h, node, v), node);
        return (int) v.curValue;
    }

    private static MvCameraControlDefines.MVCC_FLOATVALUE floatValue(MvCameraControlDefines.Handle h, String node)
            throws IOException {
        MvCameraControlDefines.MVCC_FLOATVALUE v = new MvCameraControlDefines.MVCC_FLOATVALUE();
        check(MvCameraControl.MV_CC_GetFloatValue(h, node, v), node);
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String message(Throwable e) {
        if (e instanceof CameraControlException) {
            CameraControlException c = (CameraControlException) e;
            return c.errMsg + " (0x" + Integer.toHexString(c.errCode) + ")";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
