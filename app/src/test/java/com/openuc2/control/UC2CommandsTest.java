package com.openuc2.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the wire format against the WebSerial reference tool
 * (indexWebSerialTest.html + js/hardwareControl.js + js/advancedControls.js).
 *
 * Every expected string here was taken from that reference. If firmware changes
 * the protocol, this is the file to update first — and the failure will say so
 * before anyone plugs a board in.
 */
public class UC2CommandsTest {

    // ---- axis mapping ----------------------------------------------------

    @Test
    public void axisIdsMatchReference() {
        // "Axis mapping: A=0, X=1, Y=2, Z=3."
        assertEquals(0, UC2Commands.STEPPER_A);
        assertEquals(1, UC2Commands.STEPPER_X);
        assertEquals(2, UC2Commands.STEPPER_Y);
        assertEquals(3, UC2Commands.STEPPER_Z);
    }

    // ---- motors ----------------------------------------------------------

    @Test
    public void motorStepMatchesAxisXplus() {
        // axisXplus() with stepSize 1000
        assertEquals(
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":["
                        + "{\"stepperid\":1,\"position\":1000,\"speed\":15000,"
                        + "\"isabs\":0,\"isaccel\":0}]}}",
                UC2Commands.motorStep(UC2Commands.STEPPER_X, 1000, 15000));
    }

    @Test
    public void motorStepHandlesNegativeSteps() {
        assertEquals(
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":["
                        + "{\"stepperid\":2,\"position\":-1000,\"speed\":15000,"
                        + "\"isabs\":0,\"isaccel\":0}]}}",
                UC2Commands.motorStep(UC2Commands.STEPPER_Y, -1000, 15000));
    }

    @Test
    public void motorForeverMatchesReference() {
        assertEquals(
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":["
                        + "{\"stepperid\":1,\"isforever\":1,\"speed\":-1500,"
                        + "\"isabs\":0,\"isaccel\":0}]}}",
                UC2Commands.motorForever(UC2Commands.STEPPER_X, -1500));
    }

    @Test
    public void motorStopMatchesReference() {
        assertEquals(
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":["
                        + "{\"stepperid\":3,\"isstop\":1}]}}",
                UC2Commands.motorStop(UC2Commands.STEPPER_Z));
    }

    @Test
    public void motorMoveMatchesFirmwareExample() {
        // FocusMotor/MotorJsonParser example: relative, accelerated, with a qid.
        assertEquals(
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":["
                        + "{\"stepperid\":1,\"position\":10000,\"speed\":20000,\"isabs\":0,\"isaccel\":1,\"accel\":100000},"
                        + "{\"stepperid\":2,\"position\":-5,\"speed\":20000,\"isabs\":0,\"isaccel\":1,\"accel\":100000}"
                        + "]},\"qid\":5}",
                UC2Commands.motorMove(new int[]{1, 2}, new long[]{10000, -5}, 20000, 100000, 5));
    }

    @Test
    public void stopAllCoversEveryAxis() {
        String[] all = UC2Commands.motorStopAll();
        assertEquals(4, all.length);
        for (int id = 0; id <= 3; id++) {
            final String needle = "\"stepperid\":" + id;
            boolean found = false;
            for (String cmd : all) if (cmd.contains(needle)) found = true;
            assertTrue("no stop for stepper " + id, found);
        }
    }

    @Test
    public void autoEnableMatchesReference() {
        assertEquals("{\"task\":\"/motor_act\",\"isen\":1,\"isenauto\":1}",
                UC2Commands.motorAutoEnable(true));
        assertEquals("{\"task\":\"/motor_act\",\"isen\":1,\"isenauto\":0}",
                UC2Commands.motorAutoEnable(false));
    }

    // ---- lasers ----------------------------------------------------------

    @Test
    public void laserMatchesReference() {
        assertEquals("{\"task\":\"/laser_act\",\"LASERid\":2,\"LASERval\":1023}",
                UC2Commands.setLaser(2, 1023));
        assertEquals("{\"task\":\"/laser_act\",\"LASERid\":0,\"LASERval\":0}",
                UC2Commands.laserOff(0));
    }

    @Test
    public void laserValueIsClamped() {
        assertTrue(UC2Commands.setLaser(1, 99999).contains("\"LASERval\":1023"));
        assertTrue(UC2Commands.setLaser(1, -50).contains("\"LASERval\":0"));
    }

    // ---- LED array (the protocol the old build got wrong) ----------------

    @Test
    public void ledUsesActionProtocolNotLegacyArrayMode() {
        String fill = UC2Commands.ledFill(255, 255, 255);
        assertTrue("must use the action protocol", fill.contains("\"action\":\"fill\""));
        assertTrue("must not use the retired LEDArrMode form",
                !fill.contains("LEDArrMode"));
    }

    @Test
    public void ledFillAndOffMatchReference() {
        assertEquals("{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"fill\","
                        + "\"r\":255,\"g\":255,\"b\":255}}",
                UC2Commands.ledFill(255, 255, 255));
        assertEquals("{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"off\"}}",
                UC2Commands.ledOff());
    }

    @Test
    public void ledRingMatchesReference() {
        // turnOnOuterRing() uses radius 5
        assertEquals("{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"rings\","
                        + "\"radius\":5,\"r\":255,\"g\":255,\"b\":255}}",
                UC2Commands.ledRing(5, 255, 255, 255));
    }

    @Test
    public void ledHalfMatchesReference() {
        assertEquals("{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"halves\","
                        + "\"region\":\"left\",\"r\":255,\"g\":255,\"b\":255}}",
                UC2Commands.ledHalf("left", 255, 255, 255));
    }

    @Test
    public void ledSingleMatchesReference() {
        assertEquals("{\"task\":\"/ledarr_act\",\"led\":{\"action\":\"single\","
                        + "\"ledIndex\":12,\"r\":255,\"g\":255,\"b\":255}}",
                UC2Commands.ledSingle(12, 255, 255, 255));
    }

    // ---- homing / TMC / CAN ---------------------------------------------

    @Test
    public void homingMatchesReference() {
        assertEquals("{\"task\":\"/home_act\",\"home\":{\"steppers\":["
                        + "{\"stepperid\":2,\"timeout\":20000,\"speed\":15000,"
                        + "\"direction\":-1,\"endstoppolarity\":0}]}}",
                UC2Commands.homeStepper(2, 20000, 15000, -1, 0));
    }

    @Test
    public void tmcMatchesReference() {
        assertEquals("{\"task\":\"/tmc_act\",\"msteps\":16,\"rms_current\":700,"
                        + "\"sgthrs\":15,\"semin\":5,\"semax\":2,\"blank_time\":24,"
                        + "\"toff\":4,\"axis\":2}",
                UC2Commands.tmcSettings(16, 700, 15, 5, 2, 24, 4, 2));
    }

    @Test
    public void canMatchesReference() {
        assertEquals("{\"task\":\"/can_act\",\"address\":12,\"nodeId\":12,\"canMotorAxis\":1}",
                UC2Commands.canAddress(12));
    }

    @Test
    public void simpleGettersMatchReference() {
        assertEquals("{\"task\":\"/state_get\"}", UC2Commands.getState());
        assertEquals("{\"task\":\"/motor_get\"}", UC2Commands.getMotor());
        assertEquals("{\"task\":\"/bt_scan\"}", UC2Commands.btScan());
    }

    /**
     * Locale guard: on a device set to e.g. Arabic or Hindi, String.format with
     * the default locale renders digits in that locale's numerals and the JSON
     * becomes unparseable on the board. Every builder must pin Locale.US.
     */
    @Test
    public void numbersAreLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("ar", "EG"));
            assertEquals("{\"task\":\"/laser_act\",\"LASERid\":1,\"LASERval\":500}",
                    UC2Commands.setLaser(1, 500));
            assertTrue(UC2Commands.motorStep(1, 1000, 15000).contains("\"position\":1000"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
