package com.openuc2.control;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The phone's own back camera, through Camera2.
 *
 * Tiles are full-resolution JPEGs from a still capture issued after the
 * move, so they are fresh by construction. setExposure fixes exposure time,
 * ISO, focus and white balance for the live view and every tile.
 *
 * The preview is painted from a small YUV stream rather than a preview
 * Surface: Camera2 rotates a Surface preview to fit the screen while the
 * JPEGs keep the sensor's orientation, and the stage map needs both to agree.
 */
final class PhoneCamera implements Camera {

    private static final int PREVIEW_MAX_WIDTH = 960;
    private static final long MIN_FRAME_NS = 33_333_333L;   // live view up to 30 fps
    private static final byte JPEG_QUALITY = 95;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Painter painter = new Painter();

    private HandlerThread thread;
    private Handler handler;
    private CameraCharacteristics chars;
    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private ImageReader previewReader, jpegReader;
    private volatile CaptureResult lastResult;
    private volatile boolean open;
    private volatile int width, height;

    // Fixed by setExposure; null means automatic.
    private volatile Integer iso;
    private volatile Long exposureNs;
    private volatile Float focus;
    private volatile boolean lockAuto;   // phones without manual exposure: lock what auto chose

    // Preview conversion, camera thread only.
    private int[] argb;
    private Bitmap frame;

    PhoneCamera(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")   // ScanTab holds CAMERA before calling open()
    @Override
    public void open() {
        if (thread != null) return;   // open, or opening
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = backCamera(cm);
            if (id == null) {
                report("This phone has no camera");
                return;
            }
            chars = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpeg = largest(map.getOutputSizes(ImageFormat.JPEG));
            Size small = previewSize(map.getOutputSizes(ImageFormat.YUV_420_888), jpeg);
            thread = new HandlerThread("PhoneCamera");
            thread.start();
            handler = new Handler(thread.getLooper());
            jpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 2);
            previewReader = ImageReader.newInstance(small.getWidth(), small.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(this::onPreviewFrame, handler);
            width = jpeg.getWidth();
            height = jpeg.getHeight();
            report("Opening the phone camera…");
            cm.openCamera(id, deviceCallback, handler);
        } catch (Exception e) {
            fail("Phone camera: " + message(e));
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice d) {
            device = d;
            try {
                // ponytail: deprecated list form, kept for minSdk 21; SessionConfiguration needs API 28.
                d.createCaptureSession(Arrays.asList(previewReader.getSurface(), jpegReader.getSurface()),
                        sessionCallback, handler);
            } catch (Exception e) {
                fail("Phone camera: " + message(e));
            }
        }

        @Override
        public void onDisconnected(CameraDevice d) {
            fail("The phone camera was taken by another app");
        }

        @Override
        public void onError(CameraDevice d, int error) {
            fail("Phone camera error " + error);
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession s) {
            session = s;
            try {
                repeat();
                open = true;
                report(String.format(Locale.US, "Phone camera · %d × %d%s", width, height,
                        manualSensor() ? "" : " · no manual exposure on this phone"));
            } catch (Exception e) {
                fail("Phone camera: " + message(e));
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession s) {
            fail("Phone camera: the streams could not be configured");
        }
    };

    /** (Re)starts the live stream with the current settings. */
    private void repeat() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewReader.getSurface());
        apply(b);
        session.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                lastResult = result;   // where autofocus has the lens, for setExposure
            }
        }, handler);
    }

    private void apply(CaptureRequest.Builder b) {
        Long ns = exposureNs;
        if (ns != null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(ns, MIN_FRAME_NS));
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            if (focus != null) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            if (lockable(true)) b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        } else if (lockAuto) {
            if (lockable(false)) b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            if (lockable(true)) b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        } else if (has(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            // Live view before the settings are fixed: let the user focus.
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    }

    private void onPreviewFrame(ImageReader reader) {
        try {
            Image img = reader.acquireLatestImage();
            if (img == null) return;
            try {
                if (painter.due()) painter.draw(toBitmap(img));
            } finally {
                img.close();
            }
        } catch (IllegalStateException closed) {
            // the camera was closed while a frame was on its way
        }
    }

    /** YUV_420_888 to ARGB at half size: one luma and one chroma sample per output pixel. */
    private Bitmap toBitmap(Image img) {
        int w = img.getWidth() / 2, h = img.getHeight() / 2;
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            argb = new int[w * h];
        }
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int cRow = p[1].getRowStride(), cPix = p[1].getPixelStride();   // U and V share them
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = 298 * Math.max(0, (yb.get(2 * y * yRow + 2 * x * yPix) & 0xff) - 16);
                int c = y * cRow + x * cPix;
                int u = (ub.get(c) & 0xff) - 128, v = (vb.get(c) & 0xff) - 128;
                argb[y * w + x] = 0xff000000
                        | clamp8((l + 409 * v + 128) >> 8) << 16
                        | clamp8((l - 100 * u - 208 * v + 128) >> 8) << 8
                        | clamp8((l + 516 * u + 128) >> 8);
            }
        }
        frame.setPixels(argb, 0, w, 0, 0, w, h);
        return frame;
    }

    @Override
    public byte[] grabJpeg(long timeoutMs) throws Exception {
        CameraCaptureSession s = session;
        if (!open || s == null) throw new IOException("No camera open");
        BlockingQueue<byte[]> shot = new ArrayBlockingQueue<>(1);
        jpegReader.setOnImageAvailableListener(r -> {
            try {
                Image img = r.acquireNextImage();
                if (img == null) return;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    shot.offer(jpeg);
                } finally {
                    img.close();
                }
            } catch (IllegalStateException closed) {
                // the camera was closed mid-capture; the grab times out
            }
        }, handler);
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(jpegReader.getSurface());
        apply(b);
        b.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
        b.set(CaptureRequest.JPEG_ORIENTATION, 0);   // sensor orientation, as the preview shows it
        s.capture(b.build(), null, handler);
        byte[] jpeg = shot.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (jpeg == null) throw new TimeoutException("Phone camera delivered no picture within " + timeoutMs + " ms");
        return jpeg;
    }

    @Override
    public String setExposure(double exposureMs, double gain) throws Exception {
        if (!open) throw new IOException("No camera open");
        if (!manualSensor()) {
            lockAuto = true;
            repeat();
            return "no manual exposure on this phone: auto exposure and white balance locked";
        }
        Range<Long> exposures = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isos = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        CaptureResult last = lastResult;
        focus = last == null ? null : last.get(CaptureResult.LENS_FOCUS_DISTANCE);   // where autofocus left it
        iso = isos.clamp((int) Math.round(gain));   // before exposureNs: apply() reads both once that is set
        exposureNs = exposures.clamp(Math.round(exposureMs * 1e6));
        repeat();
        return String.format(Locale.US, "%.2f ms · ISO %d · focus and white balance locked",
                exposureNs / 1e6, iso);
    }

    /** White balance (true) or exposure lock available. Phones before API 23 cannot say; they ignore an unsupported lock. */
    private boolean lockable(boolean whiteBalance) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Boolean.TRUE.equals(chars.get(whiteBalance
                ? CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
                : CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
    }

    private boolean manualSensor() {
        return has(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    }

    private boolean has(CameraCharacteristics.Key<int[]> key, int value) {
        int[] values = chars.get(key);
        if (values != null) for (int v : values) if (v == value) return true;
        return false;
    }

    @Override
    public void setPreview(SurfaceTexture surface) {
        painter.setTarget(surface);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        try {
            if (session != null) session.close();
            if (device != null) device.close();
        } catch (Exception ignored) {
            // already gone
        }
        session = null;
        device = null;
        if (previewReader != null) previewReader.close();
        if (jpegReader != null) jpegReader.close();
        previewReader = jpegReader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        iso = null;
        exposureNs = null;
        focus = null;
        lockAuto = false;
        painter.setTarget(null);   // frees the preview for the next camera
    }

    private void fail(String status) {
        report(status);
        close();
    }

    private void report(String status) {
        main.post(() -> listener.onCameraState(status, open ? width : 0, open ? height : 0));
    }

    private static String backCamera(CameraManager cm) throws CameraAccessException {
        String[] ids = cm.getCameraIdList();
        for (String id : ids) {
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    /** Largest stream of the JPEG's aspect ratio up to PREVIEW_MAX_WIDTH, else the smallest one. */
    private static Size previewSize(Size[] sizes, Size jpeg) {
        double aspect = (double) jpeg.getWidth() / jpeg.getHeight();
        Size best = null, smallest = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() < smallest.getWidth()) smallest = s;
            boolean fits = s.getWidth() <= PREVIEW_MAX_WIDTH
                    && Math.abs((double) s.getWidth() / s.getHeight() - aspect) < 0.01;
            if (fits && (best == null || s.getWidth() > best.getWidth())) best = s;
        }
        return best != null ? best : smallest;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
