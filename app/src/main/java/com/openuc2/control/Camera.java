package com.openuc2.control;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;

/**
 * A camera as the tile scan sees it: a live preview drawn into a
 * SurfaceTexture, fixed exposure and gain, and on request one fresh frame
 * as JPEG.
 *
 * Implementations: {@link UvcCamera} (any USB webcam), {@link PhoneCamera}
 * (the phone's own camera) and DahengCamera (Daheng USB3 cameras, built
 * only when Daheng's SDK is in app/libs/galaxy). A HIK backend would
 * implement the same methods with Hikrobot's SDK.
 */
public interface Camera {

    interface Listener {
        /** Main thread. {@code width} is 0 while no camera is open. */
        void onCameraState(String status, int width, int height);
    }

    /** Find and open the first camera; reports through the listener. */
    void open();

    void setPreview(SurfaceTexture surface);

    /**
     * Blocks until a frame exposed after this call arrives and returns it
     * as JPEG. A frame that may straddle the last stage move is never used.
     */
    byte[] grabJpeg(long timeoutMs) throws Exception;

    /**
     * Fixed exposure time and gain from now on, automatic modes off, so all
     * tiles of a scan are taken alike. Gain is in the camera's own unit: ISO
     * on the phone, dB on Daheng, the raw control value on webcams; both are
     * clamped to what the camera supports. Returns what was set, for the
     * status line. Hardware thread.
     */
    String setExposure(double exposureMs, double gain) throws Exception;

    boolean isOpen();

    void close();

    /**
     * Draws frames into the preview for cameras that only hand out pixels,
     * at most ~15 per second. Any thread.
     */
    final class Painter {
        private static final long FRAME_MS = 66;

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();
        private Surface surface;
        private long last;

        synchronized void setTarget(SurfaceTexture texture) {
            if (surface != null) surface.release();   // disconnects, so another producer can attach
            surface = texture == null ? null : new Surface(texture);
        }

        /** True when a frame should be drawn now; lets the caller skip converting one. */
        synchronized boolean due() {
            return surface != null && SystemClock.uptimeMillis() - last >= FRAME_MS;
        }

        synchronized void draw(Bitmap frame) {
            if (surface == null) return;
            last = SystemClock.uptimeMillis();
            try {
                Canvas c = surface.lockCanvas(null);
                dst.set(0, 0, c.getWidth(), c.getHeight());   // the view has the frame's aspect ratio
                c.drawBitmap(frame, null, dst, paint);
                surface.unlockCanvasAndPost(c);
            } catch (Exception ignored) {
                // the preview went away between frames
            }
        }
    }
}
