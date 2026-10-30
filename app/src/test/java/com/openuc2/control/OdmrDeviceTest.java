package com.openuc2.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** The ODMR protocol against a scripted board: replies as the firmware sends them. */
public class OdmrDeviceTest {

    /** Answers each command with the given lines, like a board would. */
    private static final class Board extends SerialLink {
        final List<String> sent = new ArrayList<>();
        final java.util.Map<String, String[]> replies = new java.util.HashMap<>();

        Board() { super(null); }

        Board on(String cmdPrefix, String... lines) {
            replies.put(cmdPrefix, lines);
            return this;
        }

        @Override
        public void send(String command) {
            sent.add(command);
            for (java.util.Map.Entry<String, String[]> e : replies.entrySet()) {
                if (command.startsWith(e.getKey())) for (String l : e.getValue()) offer(l);
            }
        }
    }

    @Test
    public void numbersAreSentWithoutTrailingZero() {
        assertEquals("2870", OdmrDevice.num(2870.0));
        assertEquals("2870.5", OdmrDevice.num(2870.5));
    }

    @Test
    public void measureSkipsUnrelatedLinesAndParsesIntensity() throws Exception {
        Board b = new Board().on("MEASURE", "LED rainbow", "DATA 2870 19444 0.0");
        assertEquals(19444, new OdmrDevice(b).measure(2870));
        assertEquals("MEASURE 2870", b.sent.get(0));
    }

    @Test
    public void ratioTwoAndThreePoint() throws Exception {
        Board b = new Board()
                .on("RATIO 2865 2875 0", "RATIO {\"avg\":3,\"points\":[{\"f\":2865,\"I\":100},{\"f\":2875,\"I\":300}],\"r12\":-0.5}")
                .on("RATIO 2865 2875 2870", "RATIO {\"avg\":3,\"points\":[{\"f\":2865,\"I\":100},{\"f\":2875,\"I\":300},"
                        + "{\"f\":2870,\"I\":200}],\"r12\":-0.5,\"r13\":-0.33,\"r23\":0.2}");
        OdmrDevice d = new OdmrDevice(b);
        OdmrDevice.Ratio two = d.ratio(2865, 2875, 0, 3);
        assertEquals(-0.5, two.r12, 1e-9);
        assertEquals(300, two.i2);
        assertTrue(Double.isNaN(two.f3));
        OdmrDevice.Ratio three = d.ratio(2865, 2875, 2870, 3);
        assertEquals(200, three.i3);
        assertEquals(0.2, three.r23, 1e-9);
    }

    @Test
    public void sweepReportsPointsUntilDone() throws Exception {
        Board b = new Board().on("SWEEP", "SWEEP START {\"total\":2}",
                "SWEEP DATA 0 2 2860 500", "SWEEP DATA 1 2 2862 480", "SWEEP DONE 2");
        List<Double> f = new ArrayList<>();
        boolean stopped = new OdmrDevice(b).sweep(2860, 2862, 2, 1, 10, (i, n, mhz, I) -> f.add(mhz));
        assertFalse(stopped);
        assertEquals(2, f.size());
        assertEquals("SWEEP 2860 2862 2 1 10", b.sent.get(0));
    }

    @Test
    public void sweepStoppedEarly() throws Exception {
        Board b = new Board().on("SWEEP", "SWEEP DATA 0 50 2860 500", "SWEEP STOP 1");
        assertTrue(new OdmrDevice(b).sweep(2860, 2960, 2, 1, 10, (i, n, mhz, I) -> { }));
    }

    @Test(expected = TimeoutException.class)
    public void silentBoardTimesOut() throws Exception {
        new OdmrDevice(new Board()).request("PING", 50, "PONG");
    }
}
