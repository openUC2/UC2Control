package com.openuc2.control;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * The qBOX ODMR board's ASCII serial protocol (TechnicalDocs-openUC2-QBox,
 * ODMR_Server/SERIAL_PROTOCOL.md): one command per line, answered by a line
 * starting with a tag. Every call blocks, so call it from one worker thread;
 * that also keeps replies from interleaving. Only {@link #stopSweep} may be
 * called from elsewhere while a sweep runs.
 */
final class OdmrDevice {

    interface SweepListener {
        void onPoint(int index, int total, double fMHz, int intensity);
    }

    /** RATIO reply. {@code f3}/{@code i3}/{@code r13}/{@code r23} are NaN/-1 in 2-point mode. */
    static final class Ratio {
        double f1, f2, f3 = Double.NaN, r12, r13 = Double.NaN, r23 = Double.NaN;
        int i1, i2, i3 = -1;
        double t;   // s since the monitor started, set by the caller
    }

    private final SerialLink link;

    OdmrDevice(SerialLink link) {
        this.link = link;
    }

    /** Sends {@code cmd} and returns the first reply line whose first word is one of {@code tags}. */
    String request(String cmd, long timeoutMs, String... tags) throws Exception {
        link.clear();
        link.send(cmd);
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            String line = link.readLine(end - System.currentTimeMillis());
            String tag = line.split("\\s+", 2)[0];
            for (String t : tags) if (t.equals(tag)) return line;
        }
    }

    JSONObject version() throws Exception {
        return json(request("VERSION", 4000, "VERSION"));
    }

    /** {@code {"gain":32,"integration_time":0}} */
    JSONObject tsl() throws Exception {
        return json(request("GETTSL", 4000, "TSL"));
    }

    /** Tunes to {@code fMHz}, reads the photodiode, returns the intensity. */
    int measure(double fMHz) throws Exception {
        String[] p = request("MEASURE " + num(fMHz), 4000, "DATA").split("\\s+");
        return Integer.parseInt(p[2]);
    }

    /** Last photodiode reading, fast (alignment). */
    int intensity() throws Exception {
        return Integer.parseInt(request("INTENSITY", 2500, "INT").split("\\s+")[1]);
    }

    /** 2-point ratio when {@code f3} is 0. */
    Ratio ratio(double f1, double f2, double f3, int avg) throws Exception {
        String line = request("RATIO " + num(f1) + " " + num(f2) + " " + num(f3) + " " + avg, 6000, "RATIO");
        if (line.contains("ERR")) throw new IOException(line);
        return parseRatio(json(line));
    }

    static Ratio parseRatio(JSONObject o) throws JSONException {
        Ratio r = new Ratio();
        org.json.JSONArray pts = o.getJSONArray("points");
        r.f1 = pts.getJSONObject(0).getDouble("f");
        r.i1 = pts.getJSONObject(0).getInt("I");
        r.f2 = pts.getJSONObject(1).getDouble("f");
        r.i2 = pts.getJSONObject(1).getInt("I");
        r.r12 = o.getDouble("r12");
        if (pts.length() > 2) {
            r.f3 = pts.getJSONObject(2).getDouble("f");
            r.i3 = pts.getJSONObject(2).getInt("I");
            r.r13 = o.optDouble("r13", 0);
            r.r23 = o.optDouble("r23", 0);
        }
        return r;
    }

    /** TSL2591 gain register value: 0x00, 0x10, 0x20 or 0x30. */
    void setGain(int gain) throws Exception {
        String line = request(String.format("GAIN 0x%02X", gain), 4000, "OK", "ERR");
        if (line.startsWith("ERR")) throw new IOException(line);
    }

    /** 0..5 = 100..600 ms. */
    void setIntegration(int index) throws Exception {
        String line = request("INTTIME " + index, 4000, "OK", "ERR");
        if (line.startsWith("ERR")) throw new IOException(line);
    }

    void rfOff() throws Exception {
        request("ADFOFF", 4000, "OK");
    }

    /**
     * Runs a sweep, reporting each point; returns true when it was stopped
     * early. Waits at most 8 s for each point.
     */
    boolean sweep(double fb, double fe, double fs, int avg, int settleMs, SweepListener l) throws Exception {
        link.clear();
        link.send("SWEEP " + num(fb) + " " + num(fe) + " " + num(fs) + " " + avg + " " + settleMs);
        while (true) {
            String line = link.readLine(8000);
            String[] p = line.split("\\s+");
            if (!p[0].equals("SWEEP") || p.length < 2) continue;
            switch (p[1]) {
                case "DATA":
                    l.onPoint(Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Double.parseDouble(p[4]), Integer.parseInt(p[5]));
                    break;
                case "DONE": return false;
                case "STOP": return true;
                case "ERR":  throw new IOException(line);
                default:     break;   // START {...}
            }
        }
    }

    /** Ends a running sweep; written directly, the firmware reads it between points. */
    void stopSweep() throws IOException {
        link.send("SWEEPSTOP");
    }

    private static JSONObject json(String line) throws JSONException {
        return new JSONObject(line.substring(line.indexOf('{')));
    }

    /** 2870.0 → "2870", 2870.5 → "2870.5": what the firmware's parser expects. */
    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
