package com.openuc2.control;

import android.app.Application;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCControl;
import com.serenegiant.utils.UVCUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Any USB (UVC) webcam, through UVCAndroid (libusb + libuvc, no root).
 *
 * The library renders the preview itself; frames for the scan come from its
 * NV21 frame callback, are copied only while a grab is pending, and are
 * encoded with the platform's YuvImage. The largest mode the camera offers
 * is used.
 */
final class UvcCamera implements Camera, IFrameCallback {

    private static final int JPEG_QUALITY = 95;
    private static final int MJPEG = 7;   // UVC frame descriptor subtype; Size.type

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private ICameraHelper helper;
    private SurfaceTexture preview;
    private volatile boolean open;
    private volatile int width, height;

    private byte[] grabbed;   // guarded by lock
    private int skip = -1;    // guarded by lock: frames still to drop, -1 = no grab pending

    UvcCamera(Application app, Listener listener) {
        UVCUtils.init(app);
        this.listener = listener;
    }

    @Override
    public void open() {
        if (open) return;   // already streaming; reselecting would reopen the device
        if (helper == null) {
            // A new helper reports every attached camera through onAttach.
            helper = new CameraHelper();
            helper.setStateCallback(callback);
            report("Looking for a USB camera…");
            return;
        }
        List<UsbDevice> devices = helper.getDeviceList();
        if (devices != null && !devices.isEmpty()) {
            helper.selectDevice(devices.get(0));
        } else {
            report("No USB camera found. Plug the webcam into the OTG adapter; "
                    + "with a hub the stage stays connected too.");
        }
    }

    private final ICameraHelper.StateCallback callback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (!open) helper.selectDevice(device);   // asks for USB permission
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            Size best = largest(helper.getSupportedSizeList());
            if (best != null) helper.openCamera(best); else helper.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            helper.startPreview();
            Size size = helper.getPreviewSize();
            width = size.width;
            height = size.height;
            if (preview != null) helper.addSurface(preview, false);
            helper.setFrameCallback(UvcCamera.this, UVCCamera.PIXEL_FORMAT_NV21);
            open = true;
            String name = device.getProductName();
            report((name == null ? "USB camera" : name) + " · " + width + " × " + height);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (preview != null) helper.removeSurface(preview);
            open = false;
            report("Camera closed");
        }

        @Override public void onDeviceClose(UsbDevice device) { }

        @Override
        public void onDetach(UsbDevice device) {
            open = false;
            report("Camera unplugged");
        }

        @Override
        public void onCancel(UsbDevice device) {
            report("USB permission for the camera was denied. Tap Camera to ask again.");
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            open = false;
            report("Camera error: " + e.getMessage());
        }
    };

    /**
     * Most pixels wins; MJPEG on a tie, it is the mode with the frame rate.
     * ponytail: no resolution picker; add one if a camera's largest mode makes the live view too slow.
     */
    private static Size largest(List<Size> sizes) {
        if (sizes == null) return null;
        Size best = null;
        for (Size s : sizes) {
            long a = (long) s.width * s.height;
            long b = best == null ? -1 : (long) best.width * best.height;
            if (a > b || (a == b && s.type == MJPEG)) best = s;
        }
        return best;
    }

    private void report(String status) {
        main.post(() -> listener.onCameraState(status, open ? width : 0, open ? height : 0));
    }

    @Override
    public void setPreview(SurfaceTexture surface) {
        if (helper != null && open) {
            if (preview != null) helper.removeSurface(preview);
            if (surface != null) helper.addSurface(surface, false);
        }
        preview = surface;
    }

    /** Native capture thread, every frame. Copies only when a grab waits for it. */
    @Override
    public void onFrame(ByteBuffer frame) {
        synchronized (lock) {
            if (skip < 0) return;
            if (skip > 0) {
                skip--;
                return;
            }
            int bytes = width * height * 3 / 2;
            frame.clear();
            if (frame.remaining() < bytes) return;   // partial frame: wait for the next one
            byte[] copy = new byte[bytes];
            frame.get(copy);
            grabbed = copy;
            skip = -1;
            lock.notifyAll();
        }
    }

    @Override
    public byte[] grabJpeg(long timeoutMs) throws Exception {
        if (!open) throw new IOException("No camera open");
        byte[] nv21;
        synchronized (lock) {
            grabbed = null;
            skip = 1;   // the first frame delivered may have been exposed during the move
            long end = SystemClock.uptimeMillis() + timeoutMs;
            while (grabbed == null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    skip = -1;
                    throw new TimeoutException("Camera delivered no frame within " + timeoutMs + " ms");
                }
                lock.wait(left);
            }
            nv21 = grabbed;
            grabbed = null;
        }
        // ponytail: JPEG only (webcams send MJPEG anyway); add a lossless TIFF path for
        // quantitative intensities or a 12-bit HIK/Daheng camera.
        int w = width, h = height;
        ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
        if (!new YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(new Rect(0, 0, w, h), JPEG_QUALITY, out)) {
            throw new IOException("JPEG encoding failed");
        }
        return out.toByteArray();
    }

    @Override
    public String setExposure(double exposureMs, double gain) throws Exception {
        UVCControl c = helper == null || !open ? null : helper.getUVCControl();
        if (c == null) throw new IOException("No camera open");
        // Fully manual: in shutter priority some webcams still float the gain.
        c.setAutoExposureMode(UVCControl.UVC_AUTO_EXPOSURE_MODE_MANUAL);
        // UVC counts exposure in 100 µs steps.
        int exposure = clamp((int) Math.round(exposureMs * 10), c.updateExposureTimeAbsoluteLimit());
        c.setExposureTimeAbsolute(exposure);
        int g = clamp((int) Math.round(gain), c.updateGainLimit());
        c.setGain(g);
        try {
            c.setWhiteBalanceAuto(false);   // colour must not drift between tiles either
        } catch (Exception ignored) {
            // not every webcam has it
        }
        return String.format(Locale.US, "%.1f ms · gain %d", exposure / 10.0, g);
    }

    /** Clamps to a UVC {min, max, default} limit; unknown limits pass the value through. */
    private static int clamp(int v, int[] limit) {
        return limit == null || limit.length < 2 || limit[0] > limit[1]
                ? v : Math.max(limit[0], Math.min(limit[1], v));
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        if (helper != null) helper.release();
        helper = null;
    }
}
