package com.openuc2.control;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.SurfaceTexture;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scan grid, scan loop and the two stage protocols, against a scripted serial link. */
public class ScanTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Records what the stage sends and answers with scripted lines. */
    private static final class FakeLink extends SerialLink {
        final List<String> sent = new ArrayList<>();
        private final Function<String, List<String>> replies;

        FakeLink(Function<String, List<String>> replies) {
            super(null);
            this.replies = replies;
        }

        @Override
        public void send(String command) {
            sent.add(command);
            for (String line : replies.apply(command)) offer(line);
        }
    }

    private static String xy(TileScan.Tile t) {
        return t.ix + "," + t.iy + "@" + t.x + "," + t.y;
    }

    // ---- grid --------------------------------------------------------------

    @Test
    public void rasterRunsEveryRowInPlusXCentredOnTheStart() {
        List<TileScan.Tile> g = TileScan.grid(100, 100, 3, 2, 10, 20, false, true);
        List<String> got = new ArrayList<>();
        for (TileScan.Tile t : g) got.add(xy(t));
        assertEquals(Arrays.asList(
                "0,0@90.0,90.0", "1,0@100.0,90.0", "2,0@110.0,90.0",
                "0,1@90.0,110.0", "1,1@100.0,110.0", "2,1@110.0,110.0"), got);
    }

    @Test
    public void snakeReversesOddRowsAndUncentredStartsAtTheOrigin() {
        List<TileScan.Tile> g = TileScan.grid(0, 0, 3, 2, 10, 20, true, false);
        List<String> got = new ArrayList<>();
        for (TileScan.Tile t : g) got.add(xy(t));
        assertEquals(Arrays.asList(
                "0,0@0.0,0.0", "1,0@10.0,0.0", "2,0@20.0,0.0",
                "2,1@20.0,20.0", "1,1@10.0,20.0", "0,1@0.0,20.0"), got);
    }

    // ---- scan loop -----------------------------------------------------------

    /** "Frames" that say where the stage was, so a tile can be checked against its position. */
    private static class FakeCamera implements Camera {
        final Stage stage;
        String exposure;   // set by setExposure, required before any grab

        FakeCamera(Stage stage) { this.stage = stage; }

        @Override public void open() { }
        @Override public void setPreview(SurfaceTexture surface) { }
        @Override public byte[] grabJpeg(long timeoutMs) throws Exception {
            if (exposure == null) throw new IllegalStateException("grab before the exposure was fixed");
            double[] p = stage.position();
            return (p[0] + "," + p[1]).getBytes(StandardCharsets.UTF_8);
        }
        @Override public String setExposure(double exposureMs, double gain) {
            exposure = exposureMs + " ms, gain " + gain;
            return exposure;
        }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> run(TileScan[] scan, Stage stage, File dir, int stopAfter) throws Exception {
        List<String> events = new ArrayList<>();
        scan[0] = new TileScan(stage, new FakeCamera(stage),
                TileScan.grid(10, 20, 2, 2, 100, 50, false, true), dir,
                new JSONObject().put("um_per_px", 0.5), 12.5, 3, 0, true, new TileScan.Listener() {
                    @Override public void onTile(int k, TileScan.Tile t, byte[] jpeg) {
                        events.add("tile " + k);
                        if (k + 1 == stopAfter) scan[0].requestStop();
                    }
                    @Override public void onFinished(String state, String error) {
                        events.add(state + (error == null ? "" : ": " + error));
                    }
                });
        scan[0].run();
        return events;
    }

    @Test
    public void scanWritesTheShitScopeLayoutAndReturnsToStart() throws Exception {
        File dir = new File(tmp.getRoot(), "scans/20261018_120000");
        Stage stage = new Stage.Virtual(new double[]{10, 20, 0});
        TileScan[] scan = new TileScan[1];
        assertEquals(Arrays.asList("tile 0", "tile 1", "tile 2", "tile 3", "done"), run(scan, stage, dir, 0));

        // What ImSwitch's shitscope_scan.analyse_saved_scan reads.
        JSONObject meta = new JSONObject(read(new File(dir, "scan.json")));
        assertEquals(0.5, meta.getDouble("um_per_px"), 0);
        assertEquals(4, meta.getJSONArray("plan").length());
        assertEquals(10, meta.getJSONObject("start").getDouble("X"), 0);
        assertEquals(12.5, meta.getDouble("exposure_ms"), 0);   // fixed once, for every tile
        assertEquals("12.5 ms, gain 3.0", meta.getString("camera_settings"));
        JSONArray tiles = new JSONArray(read(new File(dir, "tiles.json")));
        assertEquals(4, tiles.length());
        for (int k = 0; k < 4; k++) {
            JSONObject t = tiles.getJSONObject(k);
            assertEquals(String.format("tiles/tile_%04d.jpg", k), t.getString("file"));
            assertEquals(k, t.getInt("k"));
            // the frame was taken where the plan says, after the move
            assertEquals(t.getDouble("x") + "," + t.getDouble("y"), read(new File(dir, t.getString("file"))));
        }
        assertEquals(60, tiles.getJSONObject(3).getDouble("x"), 1e-9);   // centred: 10 ± 100/2
        assertEquals(45, tiles.getJSONObject(3).getDouble("y"), 1e-9);   //           20 ± 50/2

        assertArrayEquals(new double[]{10, 20, 0}, stage.position(), 0);
    }

    @Test
    public void stopKeepsTheTilesTaken() throws Exception {
        File dir = new File(tmp.getRoot(), "scan");
        Stage stage = new Stage.Virtual(new double[3]);
        TileScan[] scan = new TileScan[1];
        assertEquals(Arrays.asList("tile 0", "tile 1", "stopped"), run(scan, stage, dir, 2));
        assertEquals(2, new JSONArray(read(new File(dir, "tiles.json"))).length());
        assertArrayEquals(new double[3], stage.position(), 0);
    }

    @Test
    public void cameraFailureEndsTheScanWithTheReason() throws Exception {
        Stage stage = new Stage.Virtual(new double[3]);
        FakeCamera broken = new FakeCamera(stage) {
            @Override public byte[] grabJpeg(long timeoutMs) throws IOException {
                throw new IOException("Camera delivered no frame");
            }
        };
        List<String> events = new ArrayList<>();
        new TileScan(stage, broken, TileScan.grid(0, 0, 2, 1, 1, 1, false, false), tmp.getRoot(),
                new JSONObject(), 10, 0, 0, true, new TileScan.Listener() {
                    @Override public void onTile(int k, TileScan.Tile t, byte[] jpeg) { events.add("tile"); }
                    @Override public void onFinished(String state, String error) { events.add(state + ": " + error); }
                }).run();
        assertEquals(Arrays.asList("error: Camera delivered no frame"), events);
    }

    // ---- PCB stage ---------------------------------------------------------

    private static FakeLink pcbLink() {
        return new FakeLink(cmd -> Arrays.asList(cmd.equals("BUSY") ? "0" : "OK"));
    }

    @Test
    public void pcbMoveTowardMinusOvershootsThenApproachesFromMinus() throws Exception {
        FakeLink link = pcbLink();
        PcbStage stage = new PcbStage(link, 10, 5, 100, 6, new double[3]);

        stage.moveTo(-100, 0);   // -10 µsteps in X: overshoot to -16, back +6
        assertEquals(Arrays.asList("MOVE -16 0 100", "BUSY", "MOVE 6 0 100", "BUSY"), link.sent);
        assertArrayEquals(new double[]{-100, 0, 0}, stage.position(), 1e-9);

        link.sent.clear();
        stage.moveTo(100, 50);   // +20 / +10: already the approach direction
        assertEquals(Arrays.asList("MOVE 20 10 100", "BUSY"), link.sent);
        assertArrayEquals(new double[]{100, 50, 0}, stage.position(), 1e-9);
    }

    @Test
    public void pcbWithoutOvershootMovesStraight() throws Exception {
        FakeLink link = pcbLink();
        PcbStage stage = new PcbStage(link, 10, 10, 100, 0, new double[3]);
        stage.moveBy('Y', -30);
        assertEquals(Arrays.asList("MOVE 0 -3 100", "BUSY"), link.sent);
    }

    @Test
    public void pcbErrorReplyFailsTheMove() {
        FakeLink link = new FakeLink(cmd -> Arrays.asList("ERR busy"));
        PcbStage stage = new PcbStage(link, 1, 1, 100, 0, new double[3]);
        try {
            stage.moveTo(5, 0);
            fail("an ERR reply must not be taken for a move");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("ERR busy"));
        }
        assertArrayEquals(new double[]{0, 0, 0}, stage.position(), 1e-9);
    }

    // ---- UC2 board ---------------------------------------------------------

    private static final Pattern QID = Pattern.compile("\"qid\":(\\d+)");

    /** Ack, a stray frame of another command, then one isDone frame per moved stepper. */
    private static List<String> uc2Replies(String cmd) {
        Matcher m = QID.matcher(cmd);
        if (!m.find()) return new ArrayList<>();
        String q = m.group(1);
        List<String> r = new ArrayList<>(Arrays.asList(
                "++", "{\"qid\":" + q + ",\"success\":1}", "--",
                "[I] some firmware log line",
                "++", "{\"steppers\":[{\"stepperid\":2,\"position\":7,\"isDone\":1}],\"qid\":999}", "--"));
        for (int id = 1; id <= 3; id++) {
            if (cmd.contains("\"stepperid\":" + id + ",")) {
                r.addAll(Arrays.asList("++",
                        "{\"steppers\":[{\"stepperid\":" + id + ",\"position\":0,\"isDone\":1}],\"qid\":" + q + "}",
                        "--"));
            }
        }
        return r;
    }

    @Test
    public void uc2MoveSendsBothAxesAndWaitsForEachDone() throws Exception {
        FakeLink link = new FakeLink(ScanTest::uc2Replies);
        Uc2Stage stage = new Uc2Stage(link, new double[]{0.5, 0.25, 1}, 15000, new double[3]);

        stage.moveTo(100, -50);
        assertEquals(1, link.sent.size());
        assertEquals(UC2Commands.motorMove(new int[]{1, 2}, new long[]{200, -200}, 15000, 100_000, 1),
                link.sent.get(0));
        assertArrayEquals(new double[]{100, -50, 0}, stage.position(), 1e-9);

        stage.moveBy('Z', 3);   // only Z moves, only Z is waited for
        assertTrue(link.sent.get(1).contains("\"stepperid\":3,\"position\":3,"));
        assertArrayEquals(new double[]{100, -50, 3}, stage.position(), 1e-9);
    }

    @Test
    public void uc2MissingDoneTimesOut() throws Exception {
        FakeLink link = new FakeLink(cmd -> new ArrayList<>());
        link.offer("++");
        link.offer("{\"steppers\":[{\"stepperid\":1,\"isDone\":1}],\"qid\":5}");
        link.offer("--");
        try {
            Uc2Stage.awaitDone(link, 5, new int[]{1, 2}, 100);
            fail("stepper 2 never reported done");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("[2]"));
        }
    }
}
