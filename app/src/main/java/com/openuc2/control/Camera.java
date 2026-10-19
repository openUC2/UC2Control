package com.openuc2.control;

import android.graphics.SurfaceTexture;

/**
 * A camera as the tile scan sees it: a live preview drawn into a
 * SurfaceTexture, and on request one fresh frame as JPEG.
 *
 * {@link UvcCamera} drives any USB webcam. A HIK (MVS) or Daheng (Galaxy)
 * backend implements these same methods with the vendor's Android SDK:
 * grab a frame, draw it into the preview, encode it on request.
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
     * Blocks until a frame exposed after this call arrives (the first one
     * delivered may straddle a stage move, so it is dropped) and returns it
     * as JPEG.
     */
    byte[] grabJpeg(long timeoutMs) throws Exception;

    /** Fix exposure (false) so tiles match in brightness; true hands it back to the camera. */
    void setAutoExposure(boolean auto);

    boolean isOpen();

    void close();
}
