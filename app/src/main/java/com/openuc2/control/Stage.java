package com.openuc2.control;

/**
 * An XY(Z) stage as the tile scan sees it. Positions are µm, tracked in
 * software from the moves sent (neither stage reports a position we could
 * trust across reboots). Blocking calls run on the hardware worker thread,
 * never on the UI thread.
 *
 * Implementations: {@link Uc2Stage} (openUC2 ESP32, UC2-REST JSON) and
 * {@link PcbStage} (ShitScope PCB planar stage, ASCII). A negative µm/step
 * inverts an axis, as in UC2-REST.
 */
public interface Stage {

    /** Absolute XY move; returns once the stage reports it is done. */
    void moveTo(double xUm, double yUm) throws Exception;

    /** Relative move of one axis, 'X', 'Y' or 'Z'. */
    void moveBy(char axis, double um) throws Exception;

    /** Last commanded position {x, y, z} in µm. */
    double[] position();

    /** Stop now. Any thread. The tracked position is not corrected afterwards. */
    void stop();

    /** Nothing more to do for a while (after a jog or a scan). */
    void idle();

    boolean hasZ();

    /** Position only, no hardware: lets the camera, storage and overview be tried without a stage. */
    final class Virtual implements Stage {
        private final double[] pos;

        Virtual(double[] start) { pos = start.clone(); }

        @Override public void moveTo(double xUm, double yUm) { pos[0] = xUm; pos[1] = yUm; }
        @Override public void moveBy(char axis, double um) { pos[axis - 'X'] += um; }
        @Override public double[] position() { return pos.clone(); }
        @Override public void stop() { }
        @Override public void idle() { }
        @Override public boolean hasZ() { return true; }
    }
}
