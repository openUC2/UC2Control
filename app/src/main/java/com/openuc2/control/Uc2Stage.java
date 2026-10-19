package com.openuc2.control;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * openUC2 ESP32 board over USB serial, speaking the UC2-REST JSON protocol.
 *
 * Moves are relative (isabs 0) from the software-tracked position, so the
 * board's own position counter, which it persists across reboots, never
 * matters. A move is done once the board has answered
 * {"qid":q,"steppers":[{"stepperid":i,"position":…,"isDone":1}]} for every
 * axis that moved; replies are framed by "++" and "--" lines.
 */
final class Uc2Stage implements Stage {

    private static final int[] IDS = {UC2Commands.STEPPER_X, UC2Commands.STEPPER_Y, UC2Commands.STEPPER_Z};
    private static final int ACCEL = 100_000;   // steps/s², as in the firmware's own examples

    private final SerialLink link;
    private final double[] umPerStep;   // x, y, z
    private final int speed;            // steps/s
    private volatile double[] pos;      // replaced, never mutated: read from the UI thread
    private int qid = 0;

    Uc2Stage(SerialLink link, double[] umPerStep, int speed, double[] start) {
        this.link = link;
        this.umPerStep = umPerStep.clone();
        this.speed = Math.max(1, speed);
        this.pos = start.clone();
    }

    @Override
    public void moveTo(double xUm, double yUm) throws Exception {
        move(new double[]{xUm, yUm, pos[2]});
    }

    @Override
    public void moveBy(char axis, double um) throws Exception {
        double[] target = pos.clone();
        target[axis - 'X'] += um;
        move(target);
    }

    private void move(double[] target) throws Exception {
        int n = 0;
        int[] axes = new int[3], ids = new int[3];
        long[] steps = new long[3];
        long longest = 0;
        for (int a = 0; a < 3; a++) {
            long d = Math.round((target[a] - pos[a]) / umPerStep[a]);
            if (d == 0) continue;    // no command for it: an axis that does not move sends no isDone
            axes[n] = a;
            ids[n] = IDS[a];
            steps[n] = d;
            longest = Math.max(longest, Math.abs(d));
            n++;
        }
        if (n == 0) return;

        qid = qid % 254 + 1;   // 1..254, like UC2-REST
        int[] moving = Arrays.copyOf(ids, n);
        link.clear();
        link.send(UC2Commands.motorMove(moving, Arrays.copyOf(steps, n), speed, ACCEL, qid));
        awaitDone(link, qid, moving, 3000 + 1000 * longest / speed + 2000L * speed / ACCEL);

        double[] p = pos.clone();
        for (int i = 0; i < n; i++) p[axes[i]] += steps[i] * umPerStep[axes[i]];
        pos = p;
    }

    /** Blocks until every stepper in {@code ids} reported isDone for {@code qid}. */
    static void awaitDone(SerialLink link, int qid, int[] ids, long timeoutMs) throws Exception {
        Set<Integer> pending = new HashSet<>();
        for (int id : ids) pending.add(id);
        long end = System.currentTimeMillis() + timeoutMs;
        StringBuilder frame = null;
        while (!pending.isEmpty()) {
            String line;
            try {
                line = link.readLine(end - System.currentTimeMillis());
            } catch (TimeoutException e) {
                throw new IOException("Board did not report the move done within "
                        + timeoutMs / 1000 + " s (steppers " + pending + ")");
            }
            if (line.equals("++")) {
                frame = new StringBuilder();
            } else if (line.equals("--")) {
                if (frame != null) markDone(frame.toString(), qid, pending);
                frame = null;
            } else if (frame != null) {
                frame.append(line);
            }
        }
    }

    private static void markDone(String json, int qid, Set<Integer> pending) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return;   // a log line that only looked like a frame
        }
        if (o.optInt("qid", -1) != qid) return;
        if (o.optInt("success", 0) < 0) throw new IOException("Board rejected the move: " + json);
        JSONArray steppers = o.optJSONArray("steppers");
        for (int i = 0; steppers != null && i < steppers.length(); i++) {
            JSONObject s = steppers.optJSONObject(i);
            if (s != null && s.optInt("isDone", 0) == 1) pending.remove(s.optInt("stepperid", -1));
        }
    }

    @Override
    public double[] position() {
        return pos.clone();
    }

    @Override
    public void stop() {
        try {
            for (String c : UC2Commands.motorStopAll()) link.send(c);
        } catch (IOException ignored) {
            // not connected: nothing is moving
        }
    }

    @Override public void idle() { }
    @Override public boolean hasZ() { return true; }
}
