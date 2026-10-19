package com.openuc2.control;

import java.io.IOException;

/**
 * The ShitScope PCB planar stage: XY only, ASCII, exactly one reply line per
 * command, as driven by ImSwitch's StepperXYStageManager.
 *
 *   MOVE dx dy speed   relative move in µsteps   → OK | ERR …
 *   BUSY               → 1 while moving, else 0
 *   STOP               halt and cut coil current → OK
 *
 * With an overshoot set, every move ends travelling in +X/+Y: a move toward
 * −X/−Y first goes that many µsteps past the target and comes back. That
 * takes up the ~230 µm friction dead band of a reversal before the target
 * instead of at it, which is why raster (every row +X) is the scan pattern
 * for this stage.
 */
final class PcbStage implements Stage {

    private static final long REPLY_MS = 3000;
    private static final int POLL_MS = 20;

    private final SerialLink link;
    private final double umX, umY;    // µm per µstep
    private final int speed;          // µsteps/s
    private final int overshoot;      // µsteps, 0 = approach from either side
    private volatile double[] pos;

    PcbStage(SerialLink link, double umPerStepX, double umPerStepY, int speed, int overshoot,
             double[] start) {
        this.link = link;
        this.umX = umPerStepX;
        this.umY = umPerStepY;
        this.speed = Math.max(1, speed);
        this.overshoot = Math.max(0, overshoot);
        this.pos = start.clone();
    }

    @Override
    public void moveTo(double xUm, double yUm) throws Exception {
        long dx = Math.round((xUm - pos[0]) / umX);
        long dy = Math.round((yUm - pos[1]) / umY);
        long ox = dx < 0 ? -overshoot : 0;
        long oy = dy < 0 ? -overshoot : 0;
        if (ox != 0 || oy != 0) {
            // A second MOVE would replace a running one in the firmware, so
            // the first leg must have finished: step() waits for BUSY 0.
            step(dx + ox, dy + oy);
            step(-ox, -oy);
        } else {
            step(dx, dy);
        }
    }

    @Override
    public void moveBy(char axis, double um) throws Exception {
        if (axis == 'Z') return;   // no Z on this stage; the jog pad hides it
        moveTo(pos[0] + (axis == 'X' ? um : 0), pos[1] + (axis == 'Y' ? um : 0));
    }

    private void step(long dx, long dy) throws Exception {
        if (dx == 0 && dy == 0) return;
        String reply = query("MOVE " + dx + " " + dy + " " + speed);
        if (reply.startsWith("ERR")) throw new IOException("MOVE " + dx + " " + dy + ": " + reply);
        pos = new double[]{pos[0] + dx * umX, pos[1] + dy * umY, pos[2]};

        // Firmware ramps at ~1500 µsteps/s² and caps at 2000 µsteps/s; 2x margin + 2 s,
        // the same estimate StepperXYStageManager uses.
        long moveMs = 1000 * Math.max(Math.abs(dx), Math.abs(dy)) / Math.min(speed, 2000)
                + 1000L * speed / 1500;
        waitIdle(2 * moveMs + 2000);
    }

    private void waitIdle(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        int unknown = 0;
        while (System.currentTimeMillis() < end) {
            String r = query("BUSY");
            if (r.equals("0")) return;
            // Firmware without BUSY: fall back to waiting out the estimate. One odd
            // reply is tolerated: a STOP sent from the UI thread answers OK in between.
            if (!r.equals("1") && ++unknown >= 3) {
                Thread.sleep(timeoutMs / 2);
                return;
            }
            Thread.sleep(POLL_MS);
        }
        throw new IOException("PCB stage still busy after " + timeoutMs / 1000 + " s");
    }

    private String query(String command) throws Exception {
        link.clear();
        link.send(command);
        return link.readLine(REPLY_MS);
    }

    @Override
    public double[] position() {
        return pos.clone();
    }

    @Override
    public void stop() {
        try {
            link.send("STOP");
        } catch (IOException ignored) {
            // not connected: nothing is moving
        }
    }

    /** Cuts coil current between operations, as the manager's idle watchdog does, so the traces stay cool. */
    @Override
    public void idle() {
        try {
            query("STOP");
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Override public boolean hasZ() { return false; }
}
