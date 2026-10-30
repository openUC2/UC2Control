package com.openuc2.control;

import java.util.Locale;

/**
 * Builders for the JSON commands the openUC2 ESP32 firmware expects.
 *
 * The reference for this protocol is the WebSerial demo
 * (indexWebSerialTest.html + js/hardwareControl.js + js/advancedControls.js).
 * Where the two ever disagree, the web demo wins — it is what is actually
 * tested against current firmware.
 *
 * Note on LEDs: current firmware uses the *action* form
 * ({"led":{"action":"rings","radius":5,...}}). The older LEDArrMode/led_array
 * form is still accepted by some builds, so it is kept here as
 * {@link #ledLegacyFill} for boards running old firmware.
 */
public final class UC2Commands {

    /** Stepper ids, per the web demo: "Axis mapping: A=0, X=1, Y=2, Z=3." */
    public static final int STEPPER_A = 0;
    public static final int STEPPER_X = 1;
    public static final int STEPPER_Y = 2;
    public static final int STEPPER_Z = 3;

    public static final int LASER_CHANNELS = 5;   // web demo exposes light0..light4
    public static final int LASER_MAX = 1023;

    private UC2Commands() {}

    // ======================================================================
    // State / board
    // ======================================================================

    public static String getState()   { return "{\"task\":\"/state_get\"}"; }
    public static String getMotor()   { return "{\"task\":\"/motor_get\"}"; }
    public static String getModules() { return "{\"task\":\"/modules_get\"}"; }
    public static String getTmc()     { return "{\"task\":\"/tmc_get\"}"; }
    public static String getHome()    { return "{\"task\":\"/home_get\"}"; }
    public static String restart()    { return "{\"task\":\"/state_act\",\"restart\":1}"; }

    // ======================================================================
    // Lasers / illumination (PWM channels 0..4, value 0..1023)
    // ======================================================================

    public static String setLaser(int channel, int value) {
        return String.format(Locale.US,
                "{\"task\":\"/laser_act\",\"LASERid\":%d,\"LASERval\":%d}",
                channel, clamp(value, 0, LASER_MAX));
    }

    public static String laserOn(int channel)  { return setLaser(channel, LASER_MAX); }
    public static String laserOff(int channel) { return setLaser(channel, 0); }

    /** Servo-style PWM frequency for a channel. */
    public static String setLaserFreq(int channel, int freq) {
        return String.format(Locale.US,
                "{\"task\":\"/laser_act\",\"LASERid\":%d,\"LASERFreq\":%d}", channel, freq);
    }

    // ======================================================================
    // Motors
    // ======================================================================

    /** Relative move by {@code steps}. Negative steps reverse direction. */
    public static String motorStep(int stepperId, int steps, int speed) {
        return String.format(Locale.US,
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"position\":%d,\"speed\":%d,\"isabs\":0,\"isaccel\":0}]}}",
                stepperId, steps, speed);
    }

    /** Absolute move to {@code position}. */
    public static String motorMoveAbsolute(int stepperId, int position, int speed) {
        return String.format(Locale.US,
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"position\":%d,\"speed\":%d,\"isabs\":1,\"isaccel\":0}]}}",
                stepperId, position, speed);
    }

    /**
     * Relative move of several steppers in one command, accelerated, tagged
     * with {@code qid} so the board's per-axis "isDone" replies can be matched
     * to it. Used by the tile scan ({@link Uc2Stage}).
     */
    public static String motorMove(int[] stepperIds, long[] steps, int speed, int accel, int qid) {
        StringBuilder sb = new StringBuilder("{\"task\":\"/motor_act\",\"motor\":{\"steppers\":[");
        for (int i = 0; i < stepperIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US,
                    "{\"stepperid\":%d,\"position\":%d,\"speed\":%d,\"isabs\":0," +
                    "\"isaccel\":1,\"accel\":%d}", stepperIds[i], steps[i], speed, accel));
        }
        return sb.append("]},\"qid\":").append(qid).append('}').toString();
    }

    /** Run until stopped. Negative speed reverses direction. */
    public static String motorForever(int stepperId, int speed) {
        return String.format(Locale.US,
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"isforever\":1,\"speed\":%d,\"isabs\":0,\"isaccel\":0}]}}",
                stepperId, speed);
    }

    public static String motorStop(int stepperId) {
        return String.format(Locale.US,
                "{\"task\":\"/motor_act\",\"motor\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"isstop\":1}]}}", stepperId);
    }

    /** Stop every axis. Sent one command per axis by the caller's convenience. */
    public static String[] motorStopAll() {
        return new String[] {
                motorStop(STEPPER_A), motorStop(STEPPER_X),
                motorStop(STEPPER_Y), motorStop(STEPPER_Z)
        };
    }

    /** Power the coils permanently (isenauto=0) or only while moving (isenauto=1). */
    public static String motorAutoEnable(boolean auto) {
        return "{\"task\":\"/motor_act\",\"isen\":1,\"isenauto\":" + (auto ? 1 : 0) + "}";
    }

    /** Cut / restore coil current entirely. */
    public static String motorEnable(boolean enabled) {
        return "{\"task\":\"/motor_act\",\"isen\":" + (enabled ? 1 : 0) + "}";
    }

    /** Declare the current position of an axis to be {@code posval}. */
    public static String motorSetPosition(int stepperId, int posval) {
        return String.format(Locale.US,
                "{\"task\":\"/motor_act\",\"setpos\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"posval\":%d}]}}", stepperId, posval);
    }

    // ======================================================================
    // LED array — current "action" protocol
    // ======================================================================

    public static String ledFill(int r, int g, int b) {
        return String.format(Locale.US,
                "{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"fill\"," +
                "\"r\":%d,\"g\":%d,\"b\":%d}}", clamp8(r), clamp8(g), clamp8(b));
    }

    public static String ledOff() {
        return "{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"off\"}}";
    }

    /** Concentric ring by radius. The web demo uses 5 = outer, 3 = middle, 2 = centre. */
    public static String ledRing(int radius, int r, int g, int b) {
        return String.format(Locale.US,
                "{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"rings\"," +
                "\"radius\":%d,\"r\":%d,\"g\":%d,\"b\":%d}}",
                radius, clamp8(r), clamp8(g), clamp8(b));
    }

    /** region: "top" | "bottom" | "left" | "right" — used for oblique illumination. */
    public static String ledHalf(String region, int r, int g, int b) {
        return String.format(Locale.US,
                "{\"task\":\"/ledarr_act\",\"qid\":17,\"led\":{\"action\":\"halves\"," +
                "\"region\":\"%s\",\"r\":%d,\"g\":%d,\"b\":%d}}",
                region, clamp8(r), clamp8(g), clamp8(b));
    }

    /** Single LED in the matrix, addressed by index. */
    public static String ledSingle(int index, int r, int g, int b) {
        return String.format(Locale.US,
                "{\"task\":\"/ledarr_act\",\"led\":{\"action\":\"single\"," +
                "\"ledIndex\":%d,\"r\":%d,\"g\":%d,\"b\":%d}}",
                index, clamp8(r), clamp8(g), clamp8(b));
    }

    /** Legacy LEDArrMode form, for boards still running older firmware. */
    public static String ledLegacyFill(int r, int g, int b) {
        return String.format(Locale.US,
                "{\"task\":\"/ledarr_act\",\"led\":{\"LEDArrMode\":1,\"led_array\":[" +
                "{\"id\":0,\"r\":%d,\"g\":%d,\"b\":%d}]}}", clamp8(r), clamp8(g), clamp8(b));
    }

    // ======================================================================
    // Homing
    // ======================================================================

    public static String homeStepper(int stepperId, int timeout, int speed,
                                     int direction, int endstopPolarity) {
        return String.format(Locale.US,
                "{\"task\":\"/home_act\",\"home\":{\"steppers\":[" +
                "{\"stepperid\":%d,\"timeout\":%d,\"speed\":%d,\"direction\":%d," +
                "\"endstoppolarity\":%d}]}}",
                stepperId, timeout, speed, direction, endstopPolarity);
    }

    // ======================================================================
    // TMC stepper driver / CAN
    // ======================================================================

    public static String tmcSettings(int msteps, int rmsCurrent, int sgthrs,
                                     int semin, int semax, int blankTime,
                                     int toff, int axis) {
        return String.format(Locale.US,
                "{\"task\":\"/tmc_act\",\"msteps\":%d,\"rms_current\":%d,\"sgthrs\":%d," +
                "\"semin\":%d,\"semax\":%d,\"blank_time\":%d,\"toff\":%d,\"axis\":%d}",
                msteps, rmsCurrent, sgthrs, semin, semax, blankTime, toff, axis);
    }

    /** Hint from the web demo: MASTER=1, A=10, X=11, Y=12, Z=13, LED=30, Laser=20. */
    public static String canAddress(int address) {
        return String.format(Locale.US,
                "{\"task\":\"/can_act\",\"address\":%d,\"nodeId\":%d,\"canMotorAxis\":1}",
                address, address);
    }

    // ======================================================================
    // Misc
    // ======================================================================

    /** Pair a PS4/BT controller. */
    public static String btScan() { return "{\"task\":\"/bt_scan\"}"; }

    // ======================================================================

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clamp8(int v) { return clamp(v, 0, 255); }
}
