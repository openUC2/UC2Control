package com.openuc2.control;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One tile scan, on the hardware worker thread: move, settle, grab a frame
 * exposed after the move, save it, report it. The folder has the layout of
 * ImSwitch's ShitScope scans, so its registration and stitching run on it
 * unchanged (tools/stitch_scan.py):
 *
 *   scans/yyyyMMdd_HHmmss/scan.json    pixel size, plan, start position, settings
 *                         tiles.json   per tile: plan + stage position + file
 *                         tiles/tile_0000.jpg …
 *
 * Stitching stays off the phone: tiles from a low-cost stage are misplaced by
 * up to ~0.4 mm and need image registration first. The overview on the phone
 * places them at their commanded positions, like ShitScope's live preview.
 */
final class TileScan implements Runnable {

    private static final long FRAME_TIMEOUT_MS = 3000;
    /** tiles.json is rewritten at most this often (and after the loop), so big scans stay O(n) on flash. */
    private static final long INDEX_EVERY_MS = 1000;

    static final class Tile {
        final int ix, iy;
        final double x, y;   // µm

        Tile(int ix, int iy, double x, double y) {
            this.ix = ix;
            this.iy = iy;
            this.x = x;
            this.y = y;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("ix", ix).put("iy", iy).put("x", x).put("y", y);
        }
    }

    /**
     * Tile centres in scan order, as shitscope_scan.plan_grid: raster runs
     * every row in +X (one approach direction), snake reverses odd rows.
     * Centred puts (x0, y0) in the middle of the grid, else it is the first tile.
     */
    static List<Tile> grid(double x0, double y0, int nx, int ny, double stepX, double stepY,
                           boolean snake, boolean centred) {
        if (centred) {
            x0 -= (nx - 1) * stepX / 2;
            y0 -= (ny - 1) * stepY / 2;
        }
        List<Tile> tiles = new ArrayList<>(nx * ny);
        for (int iy = 0; iy < ny; iy++) {
            for (int i = 0; i < nx; i++) {
                int ix = snake && iy % 2 == 1 ? nx - 1 - i : i;
                tiles.add(new Tile(ix, iy, x0 + ix * stepX, y0 + iy * stepY));
            }
        }
        return tiles;
    }

    interface Listener {
        /** Worker thread, after the tile is on disk. */
        void onTile(int k, Tile tile, byte[] jpeg);

        /** Worker thread. state: "done", "stopped" or "error". */
        void onFinished(String state, String error);
    }

    final File dir;
    private final Stage stage;
    private final Camera camera;
    private final List<Tile> plan;
    private final JSONObject meta;
    private final double exposureMs, gain;
    private final long settleMs;
    private final boolean returnToStart;
    private final Listener listener;
    private volatile boolean stopRequested;
    private volatile String cameraSettings = "";

    TileScan(Stage stage, Camera camera, List<Tile> plan, File dir, JSONObject meta,
             double exposureMs, double gain, long settleMs, boolean returnToStart, Listener listener) {
        this.stage = stage;
        this.camera = camera;
        this.plan = plan;
        this.dir = dir;
        this.meta = meta;
        this.exposureMs = exposureMs;
        this.gain = gain;
        this.settleMs = settleMs;
        this.returnToStart = returnToStart;
        this.listener = listener;
    }

    /** Stops after the current tile; the tiles taken so far are kept. */
    void requestStop() {
        stopRequested = true;
    }

    /** What the camera made of the exposure and gain asked for, once the scan has started. */
    String cameraSettings() {
        return cameraSettings;
    }

    @Override
    public void run() {
        String state = "done", error = null;
        double[] start = stage.position();
        JSONArray records = new JSONArray();
        try {
            File tiles = new File(dir, "tiles");
            if (!tiles.mkdirs() && !tiles.isDirectory()) throw new IOException("Cannot create " + tiles);

            // The same exposure and gain for every tile, whatever the live view did before.
            cameraSettings = camera.setExposure(exposureMs, gain);
            JSONArray planJson = new JSONArray();
            for (Tile t : plan) planJson.put(t.json());
            meta.put("plan", planJson)
                .put("start", new JSONObject().put("X", start[0]).put("Y", start[1]))
                .put("exposure_ms", exposureMs).put("gain", gain).put("camera_settings", cameraSettings)
                .put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            write("scan.json", meta.toString(1).getBytes(StandardCharsets.UTF_8));

            long indexed = 0;
            for (int k = 0; k < plan.size(); k++) {
                if (stopRequested) {
                    state = "stopped";
                    break;
                }
                Tile t = plan.get(k);
                stage.moveTo(t.x, t.y);
                if (settleMs > 0) Thread.sleep(settleMs);
                // ponytail: encode + save inline, the stage waits; overlap them with the
                // next move (writer thread) if tiles per second ever matter.
                byte[] jpeg = camera.grabJpeg(FRAME_TIMEOUT_MS);
                String file = String.format(Locale.US, "tiles/tile_%04d.jpg", k);
                write(file, jpeg);
                double[] p = stage.position();
                records.put(t.json().put("k", k).put("file", file)
                        .put("stage_x", p[0]).put("stage_y", p[1]));
                if (System.currentTimeMillis() - indexed >= INDEX_EVERY_MS) {
                    writeIndex(records);   // a killed app still leaves a valid index
                    indexed = System.currentTimeMillis();
                }
                listener.onTile(k, t, jpeg);
            }
            writeIndex(records);
            if (returnToStart) stage.moveTo(start[0], start[1]);
        } catch (Exception e) {
            state = "error";
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                writeIndex(records);   // keep what was taken
            } catch (IOException ignored) {
                // the disk is the problem; the tile files are still there
            }
        } finally {
            stage.idle();
            listener.onFinished(state, error);
        }
    }

    private void writeIndex(JSONArray records) throws IOException {
        try {
            write("tiles.json", records.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void write(String name, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
    }
}
