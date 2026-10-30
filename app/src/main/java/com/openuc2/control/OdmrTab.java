package com.openuc2.control;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.MPPointF;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ODMR tab: the qBOX NV-centre experiment (TechnicalDocs-openUC2-QBox,
 * ODMR_Server) over this app's USB connection. Frequency sweep, live monitor,
 * B-field ratio, alignment with setup tutorial, info; CSV/PNG saved to
 * Downloads and shared. Same measurements and CSV columns as the board's
 * WebSerial app (youseetoo.github.io/odmr).
 *
 * Serial calls run one at a time on {@link #worker} ({@link OdmrDevice}
 * blocks); results are shown on the main thread.
 */
final class OdmrTab {

    private static final String DOCS = "https://docs.openuc2.com/usage/disc/qbox/09_ODMR/";
    private static final int[] SERIES_COLORS = {0xFF023773, 0xFFDC3545, 0xFF1F9C7C, 0xFFE08E0B, 0xFF7048B6, 0xFF0A8A4A};
    private static final int[] WINDOWS_S = {10, 30, 60, 120, 0};          // R.array.odmr_windows, 0 = all
    private static final int[] INTERVALS_MS = {100, 250, 500, 1000};      // R.array.odmr_intervals
    private static final double[] RATIO_RANGES = {0, 0.1, 0.5, 1};       // R.array.odmr_ratio_ranges, 0 = auto
    private static final int[] INT_MS = {100, 200, 300, 400, 500, 600};   // R.array.odmr_int_times
    /** Highest TSL2591 count per [gain][integration time], measured (as on the board's alignment page). */
    private static final int[][] EST_MAX = {
            {879, 1736, 2593, 3451, 4309, 5168},
            {37888, 65535, 65535, 65535, 65535, 65535},
            {37888, 65535, 65535, 65535, 65535, 65535},
            {37888, 65535, 65535, 65535, 65535, 65535},
    };
    private static final int MAX_SERIES = 20, MAX_POINTS = 5000;
    /** Axis labels: 2820, 19400, 0.025 (no thousands separators). Main thread only. */
    private static final ValueFormatter PLAIN = new ValueFormatter() {
        private final DecimalFormat f = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));
        @Override public String getFormattedValue(float v) { return f.format(v); }
    };

    /** Serial work for the worker; returns what to show on the main thread. */
    private interface Job {
        Runnable run() throws Exception;
    }

    /** One repeating measurement: what to do (read on the main thread), how often, what on failure. */
    private interface Tick {
        Job job();
        long intervalMs();
        void failed(Exception e);
    }

    /** Runs a Tick's job on the worker again and again, at least intervalMs apart, until stopped. */
    private final class Loop {
        private final Tick tick;
        boolean running;
        private int run;   // bumped by start and stop, so an old chain ends

        Loop(Tick tick) {
            this.tick = tick;
        }

        void start() {
            if (running || !connected) return;
            running = true;
            next(++run);
            refreshButtons();
        }

        void stop() {
            running = false;
            run++;
            refreshButtons();
        }

        private void next(int r) {
            if (r != run) return;
            long due = SystemClock.uptimeMillis() + tick.intervalMs();
            Job job = tick.job();
            worker.execute(() -> {
                Runnable show;
                try {
                    show = job.run();
                } catch (Exception e) {
                    show = () -> tick.failed(e);
                }
                Runnable s = show;
                main.post(() -> { if (r == run) s.run(); });
                main.postAtTime(() -> next(r), due);
            });
        }
    }

    private static final class Series {
        final List<Entry> points;
        final double fb, fe, fs;
        int color;
        boolean visible = true;

        Series(List<Entry> points, double fb, double fe, double fs, int color) {
            this.points = points;
            this.fb = fb;
            this.fe = fe;
            this.fs = fs;
            this.color = color;
        }
    }

    private final AppCompatActivity activity;
    private final OdmrDevice device;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View root;
    private final TextView deviceLine;
    private boolean connected, shown, infoAsked;

    // Sweep
    private final LineChart sweepChart;
    private final EditText fBegin, fEnd, fStep, sweepAvg, sweepSettle, yMin, yMax;
    private final MaterialButton sweepBtn;
    private final ProgressBar sweepProgress;
    private final TextView sweepStatus, sweepSummary, seriesHint;
    private final LinearLayout seriesList;
    private final List<Series> series = new ArrayList<>();
    private final List<Entry> sweepPoints = new ArrayList<>();   // the sweep in progress
    private boolean sweepRunning;

    // Live monitor
    private final LineChart liveChart;
    private final EditText liveFreq;
    private final Spinner liveWindow, liveInterval;
    private final MaterialButton liveBtn;
    private final TextView liveValue, liveCount;
    private final List<double[]> live = new ArrayList<>();   // t s, f MHz, I
    private final Loop liveLoop;
    private long liveT0;
    private int liveReadings;

    // B-field ratio
    private final LineChart ratioChart;
    private final EditText rf1, rf2, rf3, ratioInterval;
    private final RadioButton threePoint;
    private final SeekBar ratioAvg;
    private final Spinner ratioWindow, ratioRange;
    private final MaterialButton ratioStart, ratioStop;
    private final TextView ratioR12, ratioValues, ratioCounts, ratioAvgLabel;
    private final List<OdmrDevice.Ratio> ratios = new ArrayList<>();
    private final Loop ratioLoop;
    private long ratioT0;
    private int ratioCount, ratioErrors;

    // Alignment
    private final LineChart alignChart;
    private final Spinner alignWindow, alignY, gain, intTime;
    private final MaterialButton alignStart, alignStop, tslApply;
    private final TextView alignValue, alignStatus, alignRange, tslStatus;
    private final ProgressBar alignBar;
    private final List<Entry> align = new ArrayList<>();
    private final Loop alignLoop;
    private long alignT0;
    private int rangeMax, rangeOpt, rangeGood, rangeSat;

    // Tutorial
    private final TextView stepTitle, stepText, stepProgress;
    private final MaterialButton stepPrev, stepNext;
    private final LinearLayout stepList;
    private final String[] stepTitles, stepTexts;
    private int step;

    OdmrTab(AppCompatActivity activity, SerialLink link) {
        this.activity = activity;
        this.device = new OdmrDevice(link);
        prefs = activity.getSharedPreferences("odmr", Context.MODE_PRIVATE);
        root = activity.findViewById(R.id.tab_content_odmr);
        deviceLine = find(R.id.tv_odmr_device);
        deviceLine.setOnClickListener(v -> { if (connected) fetchInfo(); });   // retry
        setupPages();

        // ---- Sweep ----
        sweepChart = chart(R.id.chart_sweep, "f / MHz · I", "%.1f MHz · %.0f");
        fBegin = find(R.id.et_f_begin);
        fEnd = find(R.id.et_f_end);
        fStep = find(R.id.et_f_step);
        sweepAvg = find(R.id.et_sweep_avg);
        sweepSettle = find(R.id.et_sweep_settle);
        yMin = find(R.id.et_sweep_ymin);
        yMax = find(R.id.et_sweep_ymax);
        sweepBtn = find(R.id.btn_sweep);
        sweepProgress = find(R.id.pb_sweep);
        sweepStatus = find(R.id.tv_sweep_status);
        sweepSummary = find(R.id.tv_sweep_summary);
        seriesHint = find(R.id.tv_series_hint);
        seriesList = find(R.id.list_series);
        sweepBtn.setOnClickListener(v -> toggleSweep());
        click(R.id.btn_sweep_fit, sweepChart::fitScreen);
        click(R.id.btn_sweep_clear, () -> {
            series.clear();
            sweepPoints.clear();
            saveSeries();
            renderSeriesList();
            renderSweep();
        });
        click(R.id.btn_sweep_csv, () -> saveCsv("sweep", sweepCsv()));
        click(R.id.btn_sweep_png, () -> {
            if (sweepChart.getData() == null) { toast(R.string.odmr_no_data); return; }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            sweepChart.getChartBitmap().compress(Bitmap.CompressFormat.PNG, 100, png);
            save(fileName("sweep", "png"), png.toByteArray(), "image/png");
        });

        // ---- Live ----
        liveChart = chart(R.id.chart_live, "t / s · I", "%.1f s · %.0f");
        liveFreq = find(R.id.et_live_freq);
        liveWindow = spinner(R.id.sp_live_window, R.array.odmr_windows, 1, this::drawLive);
        liveInterval = spinner(R.id.sp_live_interval, R.array.odmr_intervals, 2, null);
        liveBtn = find(R.id.btn_live);
        liveValue = find(R.id.tv_live_value);
        liveCount = find(R.id.tv_live_count);
        liveLoop = new Loop(new Tick() {
            @Override public Job job() {
                double f = number(liveFreq, 2870);
                return () -> {
                    int intensity = device.measure(f);   // tunes, reads, RF off again
                    return () -> addLive(f, intensity);
                };
            }
            @Override public long intervalMs() { return INTERVALS_MS[liveInterval.getSelectedItemPosition()]; }
            @Override public void failed(Exception e) { showError(e); }
        });
        liveBtn.setOnClickListener(v -> {
            if (liveLoop.running) {
                liveLoop.stop();
            } else {
                if (live.isEmpty()) liveT0 = SystemClock.uptimeMillis();
                liveLoop.start();
            }
        });
        click(R.id.btn_live_fit, liveChart::fitScreen);
        click(R.id.btn_live_clear, () -> {
            live.clear();
            liveReadings = 0;
            liveT0 = SystemClock.uptimeMillis();
            liveValue.setText("---");
            liveCount.setText("");
            drawLive();
        });
        click(R.id.btn_live_csv, () -> {
            if (live.isEmpty()) { saveCsv("live", null); return; }
            StringBuilder csv = new StringBuilder("t_s;f_MHz;I\n");
            for (double[] d : live) csv.append(String.format(Locale.US, "%.3f;%s;%d\n", d[0], OdmrDevice.num(d[1]), (int) d[2]));
            saveCsv("live", csv.toString());
        });

        // ---- B-field ratio ----
        ratioChart = chart(R.id.chart_ratio, "t / s · r₁₂", "%.1f s · r₁₂ %.4f");
        LimitLine zero = new LimitLine(0f);
        zero.setLineColor(color(R.color.uc2_grey));
        zero.setLineWidth(1f);
        ratioChart.getAxisLeft().addLimitLine(zero);
        rf1 = find(R.id.et_rf1);
        rf2 = find(R.id.et_rf2);
        rf3 = find(R.id.et_rf3);
        ratioInterval = find(R.id.et_ratio_interval);
        threePoint = find(R.id.rb_3pt);
        ratioAvg = find(R.id.sb_ratio_avg);
        ratioAvgLabel = find(R.id.tv_ratio_avg);
        ratioWindow = spinner(R.id.sp_ratio_window, R.array.odmr_windows, 1, this::drawRatio);
        ratioRange = spinner(R.id.sp_ratio_range, R.array.odmr_ratio_ranges, 0, this::drawRatio);
        ratioStart = find(R.id.btn_ratio_start);
        ratioStop = find(R.id.btn_ratio_stop);
        ratioR12 = find(R.id.tv_ratio_r12);
        ratioValues = find(R.id.tv_ratio_values);
        ratioCounts = find(R.id.tv_ratio_counts);
        threePoint.setOnCheckedChangeListener((v, on) ->
                find(R.id.col_rf3).setVisibility(on ? View.VISIBLE : View.INVISIBLE));
        ratioAvg.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { showRatioAvg(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        showRatioAvg();
        ratioLoop = new Loop(new Tick() {
            @Override public Job job() {
                double f1 = number(rf1, 2865), f2 = number(rf2, 2875);
                double f3 = threePoint.isChecked() ? number(rf3, 2870) : 0;
                int avg = ratioAvg.getProgress() + 1;
                return () -> {
                    OdmrDevice.Ratio r = device.ratio(f1, f2, f3, avg);
                    return () -> addRatio(r);
                };
            }
            @Override public long intervalMs() { return Math.max(200, (long) number(ratioInterval, 500)); }
            @Override public void failed(Exception e) {
                ratioErrors++;
                showRatioCounts();
                showError(e);
            }
        });
        ratioStart.setOnClickListener(v -> {
            clearRatio();
            ratioLoop.start();
        });
        ratioStop.setOnClickListener(v -> {
            ratioLoop.stop();
            rfOff();
        });
        click(R.id.btn_ratio_fit, ratioChart::fitScreen);
        click(R.id.btn_ratio_clear, this::clearRatio);
        click(R.id.btn_ratio_csv, () -> saveCsv("ratio", ratioCsv()));
        showRatioCounts();

        // ---- Alignment ----
        alignChart = chart(R.id.chart_align, "t / s · I", "%.1f s · %.0f");
        alignWindow = spinner(R.id.sp_align_window, R.array.odmr_windows, 1, this::drawAlign);
        alignY = spinner(R.id.sp_align_y, R.array.odmr_align_y, 0, this::drawAlign);
        gain = spinner(R.id.sp_gain, R.array.odmr_gains, 2, this::updateAlignRange);
        intTime = spinner(R.id.sp_int_time, R.array.odmr_int_times, 0, () -> {
            updateAlignRange();
            updateSummary();
        });
        alignStart = find(R.id.btn_align_start);
        alignStop = find(R.id.btn_align_stop);
        tslApply = find(R.id.btn_tsl_apply);
        alignValue = find(R.id.tv_align_value);
        alignStatus = find(R.id.tv_align_status);
        alignRange = find(R.id.tv_align_range);
        tslStatus = find(R.id.tv_tsl_status);
        alignBar = find(R.id.pb_align);
        alignLoop = new Loop(new Tick() {
            @Override public Job job() {
                return () -> {
                    int v = device.intensity();
                    return () -> addAlign(v);
                };
            }
            @Override public long intervalMs() { return 400; }
            @Override public void failed(Exception e) { showError(e); }
        });
        alignStart.setOnClickListener(v -> {
            if (align.isEmpty()) alignT0 = SystemClock.uptimeMillis();
            alignLoop.start();
        });
        alignStop.setOnClickListener(v -> alignLoop.stop());
        click(R.id.btn_align_clear, () -> {
            align.clear();
            alignT0 = SystemClock.uptimeMillis();
            drawAlign();
        });
        tslApply.setOnClickListener(v -> applyTsl());

        // ---- Tutorial + info ----
        stepTitles = activity.getResources().getStringArray(R.array.odmr_tutorial_titles);
        stepTexts = activity.getResources().getStringArray(R.array.odmr_tutorial_texts);
        stepTitle = find(R.id.tv_step_title);
        stepText = find(R.id.tv_step_text);
        stepProgress = find(R.id.tv_step_progress);
        stepPrev = find(R.id.btn_step_prev);
        stepNext = find(R.id.btn_step_next);
        stepList = find(R.id.list_steps);
        for (int i = 0; i < stepTitles.length; i++) {
            int n = i;
            TextView t = new TextView(activity);
            t.setText((i + 1) + ". " + stepTitles[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setPadding(0, dp(6), 0, dp(6));
            t.setOnClickListener(v -> showStep(n));
            stepList.addView(t);
        }
        stepPrev.setOnClickListener(v -> showStep(step - 1));
        stepNext.setOnClickListener(v -> showStep(step + 1));
        showStep(0);
        click(R.id.btn_odmr_docs, () -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOCS))));

        // Listeners last: they draw, and everything they touch exists now.
        for (EditText e : new EditText[]{fBegin, fEnd, fStep, sweepAvg, sweepSettle}) onChange(e, this::updateSummary);
        onChange(yMin, this::renderSweep);
        onChange(yMax, this::renderSweep);
        loadSeries();
        renderSeriesList();
        renderSweep();
        updateSummary();
        updateAlignRange();
        drawLive();
        drawRatio();
        refreshButtons();
    }

    // ======================================================================
    // Connection
    // ======================================================================

    /** The tab was opened: ask the board for its version once there is one. */
    void show() {
        shown = true;
        if (connected && !infoAsked) fetchInfo();
    }

    void onConnected(boolean c) {
        connected = c;
        if (c) {
            deviceLine.setText(R.string.odmr_connected);
            if (shown) fetchInfo();
        } else {
            infoAsked = false;
            liveLoop.stop();
            ratioLoop.stop();
            alignLoop.stop();
            deviceLine.setText(R.string.odmr_not_connected);
        }
        refreshButtons();
    }

    void release() {
        liveLoop.stop();
        ratioLoop.stop();
        alignLoop.stop();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    /** Firmware version for the status line, and the sensor's gain/integration time. */
    private void fetchInfo() {
        infoAsked = true;
        worker.execute(() -> {
            try {
                JSONObject v = device.version();
                String line = activity.getString(R.string.odmr_firmware,
                        v.optString("version"), v.optString("build_date"), v.optString("git_hash"));
                main.post(() -> deviceLine.setText(line));
            } catch (Exception e) {
                main.post(() -> deviceLine.setText(R.string.odmr_no_version));
                return;
            }
            try {
                JSONObject t = device.tsl();
                int g = Math.min(3, Math.max(0, t.getInt("gain") >> 4));
                int it = Math.min(5, Math.max(0, t.getInt("integration_time")));
                main.post(() -> {
                    gain.setSelection(g);
                    intTime.setSelection(it);
                    updateAlignRange();
                    updateSummary();
                });
            } catch (Exception ignored) {
                // no TSL2591 on this board
            }
        });
    }

    private void refreshButtons() {
        if (alignLoop == null) return;   // a loop's start/stop during construction
        enable(sweepBtn, connected || sweepRunning);
        running(sweepBtn, sweepRunning, R.string.odmr_start_sweep, R.string.odmr_stop_sweep);
        enable(liveBtn, connected || liveLoop.running);
        running(liveBtn, liveLoop.running, R.string.odmr_start_live, R.string.odmr_stop_live);
        enable(ratioStart, connected && !ratioLoop.running);
        enable(ratioStop, ratioLoop.running);
        enable(alignStart, connected && !alignLoop.running);
        enable(alignStop, alignLoop.running);
        enable(tslApply, connected);
    }

    private void showError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        deviceLine.setText(activity.getString(R.string.odmr_error, msg));
    }

    private void rfOff() {
        if (!connected) return;
        worker.execute(() -> {
            try {
                device.rfOff();
            } catch (Exception ignored) {
                // best effort: the next sweep or measurement retunes anyway
            }
        });
    }

    // ======================================================================
    // Sweep
    // ======================================================================

    private void toggleSweep() {
        if (sweepRunning) {
            try {
                device.stopSweep();
            } catch (IOException ignored) {
                // disconnected: the sweep ends by timeout
            }
            return;
        }
        double fb = number(fBegin, 2820), fe = number(fEnd, 2920), fs = number(fStep, 2);
        int avg = Math.max(1, (int) number(sweepAvg, 1)), settle = Math.max(1, (int) number(sweepSettle, 10));
        if (!(fb < fe) || !(fs > 0)) { toast(R.string.odmr_sweep_invalid); return; }

        sweepRunning = true;
        sweepPoints.clear();
        sweepProgress.setProgress(0);
        sweepStatus.setText(R.string.odmr_running);
        refreshButtons();
        worker.execute(() -> {
            String status;
            try {
                boolean stopped = device.sweep(fb, fe, fs, avg, settle,
                        (i, n, f, intensity) -> main.post(() -> sweepPoint(i, n, f, intensity)));
                status = activity.getString(stopped ? R.string.odmr_stopped : R.string.odmr_done);
            } catch (Exception e) {
                status = activity.getString(R.string.odmr_error, e.getMessage());
            }
            try {
                device.rfOff();   // microwave back to idle between sweeps
            } catch (Exception ignored) {
                // disconnected
            }
            String s = status;
            main.post(() -> sweepFinished(s, fb, fe, fs));
        });
    }

    private void sweepPoint(int index, int total, double f, int intensity) {
        sweepPoints.add(new Entry((float) f, intensity));
        sweepProgress.setMax(total);
        sweepProgress.setProgress(index + 1);
        sweepStatus.setText(activity.getString(R.string.odmr_running_n, index + 1, total));
        renderSweep();
    }

    private void sweepFinished(String status, double fb, double fe, double fs) {
        sweepRunning = false;
        sweepStatus.setText(status);
        if (!sweepPoints.isEmpty()) {
            series.add(new Series(new ArrayList<>(sweepPoints), fb, fe, fs,
                    SERIES_COLORS[series.size() % SERIES_COLORS.length]));
            while (series.size() > MAX_SERIES) series.remove(0);
            sweepPoints.clear();
            saveSeries();
            renderSeriesList();
        }
        renderSweep();
        refreshButtons();
    }

    private void renderSweep() {
        List<ILineDataSet> sets = new ArrayList<>();
        for (Series s : series) if (s.visible) sets.add(line(s.points, s.color, false));
        if (!sweepPoints.isEmpty()) sets.add(line(sweepPoints, Color.BLACK, true));
        YAxis y = sweepChart.getAxisLeft();
        double lo = number(yMin, Double.NaN), hi = number(yMax, Double.NaN);
        if (lo < hi) {   // both set: fixed, else auto
            y.setAxisMinimum((float) lo);
            y.setAxisMaximum((float) hi);
        } else {
            y.resetAxisMinimum();
            y.resetAxisMaximum();
        }
        show(sweepChart, sets);
    }

    /** Points and duration of the planned sweep: per point the PLL settle time plus one integration per average. */
    private void updateSummary() {
        double fb = number(fBegin, Double.NaN), fe = number(fEnd, Double.NaN), fs = number(fStep, Double.NaN);
        if (!(fb < fe) || !(fs > 0)) { sweepSummary.setText(""); return; }
        int avg = Math.max(1, (int) number(sweepAvg, 1)), settle = Math.max(1, (int) number(sweepSettle, 10));
        int n = (int) Math.floor((fe - fb) / fs + 1e-9) + 1;
        long sec = Math.round(n * (settle + avg * INT_MS[intTime.getSelectedItemPosition()]) / 1000.0);
        String s = activity.getString(R.string.odmr_sweep_summary, n, (int) (sec / 60), (int) (sec % 60));
        sweepSummary.setText(n > 600 ? activity.getString(R.string.odmr_sweep_too_long, s) : s);
    }

    private void renderSeriesList() {
        seriesList.removeAllViews();
        seriesHint.setText(series.isEmpty() ? R.string.odmr_no_series : R.string.odmr_series_hint);
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox show = new CheckBox(activity);
            show.setChecked(s.visible);
            show.setOnCheckedChangeListener((v, on) -> {
                s.visible = on;
                saveSeries();
                renderSweep();
            });
            row.addView(show);

            View swatch = new View(activity);
            swatch.setBackgroundColor(s.color);
            swatch.setContentDescription(activity.getString(R.string.odmr_series_hint));
            swatch.setOnClickListener(v -> {
                int next = 0;
                for (int c = 0; c < SERIES_COLORS.length; c++) if (SERIES_COLORS[c] == s.color) next = c + 1;
                s.color = SERIES_COLORS[next % SERIES_COLORS.length];
                v.setBackgroundColor(s.color);
                saveSeries();
                renderSweep();
            });
            LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(dp(22), dp(22));
            sw.setMarginEnd(dp(10));
            row.addView(swatch, sw);

            TextView label = new TextView(activity);
            label.setText(activity.getString(R.string.odmr_series_label, i + 1, OdmrDevice.num(s.fb),
                    OdmrDevice.num(s.fe), OdmrDevice.num(s.fs), s.points.size()));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(color(R.color.text_primary));
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView delete = new TextView(activity);
            delete.setText("✕");
            delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            delete.setTextColor(color(R.color.danger));
            delete.setPadding(dp(12), dp(8), dp(4), dp(8));
            delete.setOnClickListener(v -> {
                series.remove(s);
                saveSeries();
                renderSeriesList();
                renderSweep();
            });
            row.addView(delete);
            seriesList.addView(row);
        }
    }

    /** All series side by side: f1_MHz;I1;f2_MHz;I2;… as in the WebSerial app; null when empty. */
    private String sweepCsv() {
        List<List<Entry>> cols = new ArrayList<>();
        for (Series s : series) cols.add(s.points);
        if (!sweepPoints.isEmpty()) cols.add(sweepPoints);
        if (cols.isEmpty()) return null;
        StringBuilder csv = new StringBuilder();
        int rows = 0;
        for (int i = 0; i < cols.size(); i++) {
            csv.append(i == 0 ? "" : ";").append("f").append(i + 1).append("_MHz;I").append(i + 1);
            rows = Math.max(rows, cols.get(i).size());
        }
        csv.append('\n');
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < cols.size(); i++) {
                List<Entry> c = cols.get(i);
                if (i > 0) csv.append(';');
                if (r < c.size()) csv.append(fmt(c.get(r).getX())).append(';').append(Math.round(c.get(r).getY()));
                else csv.append(';');
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private void saveSeries() {
        JSONArray all = new JSONArray();
        try {
            for (Series s : series) {
                JSONArray x = new JSONArray(), y = new JSONArray();
                for (Entry e : s.points) {
                    x.put(fmt(e.getX()));
                    y.put(Math.round(e.getY()));
                }
                all.put(new JSONObject().put("fb", s.fb).put("fe", s.fe).put("fs", s.fs)
                        .put("color", s.color).put("visible", s.visible).put("x", x).put("y", y));
            }
        } catch (JSONException e) {
            return;   // only numbers go in; cannot happen
        }
        prefs.edit().putString("series", all.toString()).apply();
    }

    private void loadSeries() {
        try {
            JSONArray all = new JSONArray(prefs.getString("series", "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                JSONArray x = o.getJSONArray("x"), y = o.getJSONArray("y");
                List<Entry> pts = new ArrayList<>();
                for (int j = 0; j < x.length(); j++) pts.add(new Entry((float) x.getDouble(j), (float) y.getDouble(j)));
                Series s = new Series(pts, o.getDouble("fb"), o.getDouble("fe"), o.getDouble("fs"), o.getInt("color"));
                s.visible = o.optBoolean("visible", true);
                series.add(s);
            }
        } catch (JSONException e) {
            series.clear();   // unreadable: start empty
        }
    }

    // ======================================================================
    // Live monitor
    // ======================================================================

    private void addLive(double f, int intensity) {
        live.add(new double[]{(SystemClock.uptimeMillis() - liveT0) / 1000.0, f, intensity});
        if (live.size() > MAX_POINTS) live.remove(0);
        liveReadings++;
        liveValue.setText(String.valueOf(intensity));
        liveCount.setText(activity.getString(R.string.odmr_readings, liveReadings));
        drawLive();
    }

    private void drawLive() {
        List<Entry> pts = new ArrayList<>();
        for (double[] d : live) pts.add(new Entry((float) d[0], (float) d[2]));
        window(liveChart, pts, WINDOWS_S[liveWindow.getSelectedItemPosition()]);
    }

    // ======================================================================
    // B-field ratio
    // ======================================================================

    private void showRatioAvg() {
        ratioAvgLabel.setText(activity.getString(R.string.odmr_avg_n, ratioAvg.getProgress() + 1));
    }

    private void showRatioCounts() {
        ratioCounts.setText(activity.getString(R.string.odmr_ratio_counts, ratioCount, ratioErrors));
    }

    private void clearRatio() {
        ratios.clear();
        ratioCount = 0;
        ratioErrors = 0;
        ratioT0 = SystemClock.uptimeMillis();
        ratioR12.setText("---");
        ratioR12.setTextColor(color(R.color.primary));
        ratioValues.setText("");
        showRatioCounts();
        drawRatio();
    }

    private void addRatio(OdmrDevice.Ratio r) {
        r.t = (SystemClock.uptimeMillis() - ratioT0) / 1000.0;
        ratios.add(r);
        if (ratios.size() > 1000) ratios.remove(0);
        ratioCount++;
        showRatioCounts();
        ratioR12.setText(String.format(Locale.US, "%.4f", r.r12));
        ratioR12.setTextColor(color(r.r12 > 0.005 ? R.color.uc2_green_dark
                : r.r12 < -0.005 ? R.color.danger : R.color.primary));
        StringBuilder v = new StringBuilder(String.format(Locale.US, "f₁ %.1f MHz  I₁ %d\nf₂ %.1f MHz  I₂ %d",
                r.f1, r.i1, r.f2, r.i2));
        if (r.i3 >= 0) v.append(String.format(Locale.US, "\nf₃ %.1f MHz  I₃ %d\nr₁₃ %.4f  r₂₃ %.4f",
                r.f3, r.i3, r.r13, r.r23));
        ratioValues.setText(v);
        drawRatio();
    }

    private void drawRatio() {
        List<Entry> pts = new ArrayList<>();
        for (OdmrDevice.Ratio r : ratios) pts.add(new Entry((float) r.t, (float) r.r12));
        double range = RATIO_RANGES[ratioRange.getSelectedItemPosition()];
        YAxis y = ratioChart.getAxisLeft();
        if (range > 0) {
            y.setAxisMinimum((float) -range);
            y.setAxisMaximum((float) range);
        } else {
            y.resetAxisMinimum();
            y.resetAxisMaximum();
        }
        window(ratioChart, pts, WINDOWS_S[ratioWindow.getSelectedItemPosition()]);
    }

    private String ratioCsv() {
        if (ratios.isEmpty()) return null;
        boolean three = ratios.get(0).i3 >= 0;
        StringBuilder csv = new StringBuilder("t_s;f1;I1;f2;I2;r12" + (three ? ";f3;I3;r13;r23" : "") + "\n");
        for (OdmrDevice.Ratio r : ratios) {
            csv.append(String.format(Locale.US, "%.3f;%s;%d;%s;%d;%.6f", r.t,
                    OdmrDevice.num(r.f1), r.i1, OdmrDevice.num(r.f2), r.i2, r.r12));
            if (three && r.i3 >= 0) csv.append(String.format(Locale.US, ";%s;%d;%.6f;%.6f",
                    OdmrDevice.num(r.f3), r.i3, r.r13, r.r23));
            csv.append('\n');
        }
        return csv.toString();
    }

    // ======================================================================
    // Alignment
    // ======================================================================

    /** Expected maximum for the chosen gain and integration time; optimal is 80 % of it. */
    private void updateAlignRange() {
        rangeMax = EST_MAX[gain.getSelectedItemPosition()][intTime.getSelectedItemPosition()];
        rangeOpt = Math.max(100, Math.round(rangeMax * 0.8f));
        rangeGood = Math.round(rangeOpt * 0.375f);
        rangeSat = Math.round(rangeOpt * 1.1f);
        alignRange.setText(activity.getString(R.string.odmr_align_range, rangeMax, rangeOpt, rangeSat));
        drawAlign();
    }

    private void addAlign(int v) {
        alignValue.setText(String.valueOf(v));
        int status, tint;
        if (v > rangeSat) { status = R.string.odmr_saturated; tint = R.color.danger; }
        else if (v > rangeOpt) { status = R.string.odmr_optimal; tint = R.color.go; }
        else if (v > rangeGood) { status = R.string.odmr_good; tint = R.color.warning; }
        else { status = R.string.odmr_adjust; tint = R.color.danger; }
        alignStatus.setText(status);
        alignStatus.setBackgroundColor(color(tint));
        alignBar.setProgress((int) Math.min(1000, 1000L * v / Math.max(1, rangeMax)));
        alignBar.setProgressTintList(ColorStateList.valueOf(color(tint)));
        align.add(new Entry((SystemClock.uptimeMillis() - alignT0) / 1000f, v));
        if (align.size() > MAX_POINTS) align.remove(0);
        drawAlign();
    }

    private void drawAlign() {
        int win = WINDOWS_S[alignWindow.getSelectedItemPosition()];
        float now = align.isEmpty() ? 0 : align.get(align.size() - 1).getX();
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Entry e : align) {
            if (win > 0 && e.getX() < now - win) continue;
            lo = Math.min(lo, e.getY());
            hi = Math.max(hi, e.getY());
        }
        YAxis y = alignChart.getAxisLeft();
        int mode = alignY.getSelectedItemPosition();   // auto, stretch, full
        if (mode == 2) {
            y.setAxisMinimum(0);
            y.setAxisMaximum(rangeMax);
        } else if (lo <= hi) {
            float m = mode == 1 ? Math.max(1, (hi - lo) * 0.02f) : Math.max(50, (hi - lo) * 0.1f);
            y.setAxisMinimum(mode == 1 ? lo - m : Math.max(0, lo - m));
            y.setAxisMaximum(hi + m);
        } else {
            y.resetAxisMinimum();
            y.resetAxisMaximum();
        }
        window(alignChart, align, win);
    }

    private void applyTsl() {
        int g = gain.getSelectedItemPosition() * 0x10, it = intTime.getSelectedItemPosition();
        tslStatus.setText(R.string.odmr_running);
        worker.execute(() -> {
            try {
                device.setGain(g);
                device.setIntegration(it);
                main.post(() -> tslStatus.setText(R.string.odmr_applied));
            } catch (Exception e) {
                main.post(() -> tslStatus.setText(activity.getString(R.string.odmr_error, e.getMessage())));
            }
        });
    }

    private void showStep(int i) {
        step = Math.max(0, Math.min(stepTitles.length - 1, i));
        stepTitle.setText((step + 1) + ". " + stepTitles[step]);
        stepText.setText(stepTexts[step]);
        stepProgress.setText((step + 1) + " / " + stepTitles.length);
        enable(stepPrev, step > 0);
        enable(stepNext, step < stepTitles.length - 1);
        Typeface bold = ResourcesCompat.getFont(activity, R.font.space_grotesk_bold);
        Typeface normal = ResourcesCompat.getFont(activity, R.font.space_grotesk_medium);
        for (int n = 0; n < stepList.getChildCount(); n++) {
            TextView t = (TextView) stepList.getChildAt(n);
            t.setTypeface(n == step ? bold : normal);
            t.setTextColor(color(n == step ? R.color.primary : R.color.text_secondary));
        }
    }

    // ======================================================================
    // Charts
    // ======================================================================

    /** Shows the tapped point's value, formatted with (x, y). */
    @SuppressLint("ViewConstructor")
    private static final class Readout extends MarkerView {
        private final TextView text;
        private final String format;

        Readout(Context context, String format) {
            super(context, R.layout.odmr_marker);
            this.text = findViewById(R.id.tv_marker);
            this.format = format;
        }

        @Override
        public void refreshContent(Entry e, Highlight h) {
            text.setText(String.format(Locale.US, format, e.getX(), e.getY()));
            super.refreshContent(e, h);
        }

        @Override
        public MPPointF getOffset() {
            return new MPPointF(-getWidth() / 2f, -getHeight() - 12f);
        }
    }

    /** {@code units} goes in the corner (and into exported PNGs); {@code readout} formats a tapped point. */
    private LineChart chart(int id, String units, String readout) {
        LineChart c = find(id);
        Typeface mono = ResourcesCompat.getFont(activity, R.font.ibm_plex_mono);
        c.getDescription().setText(units);
        c.getDescription().setTypeface(mono);
        c.getDescription().setTextColor(color(R.color.text_muted));
        c.getLegend().setEnabled(false);
        c.getAxisRight().setEnabled(false);
        c.setNoDataText(activity.getString(R.string.odmr_no_data));
        c.setNoDataTextColor(color(R.color.text_muted));
        c.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        for (AxisBase a : new AxisBase[]{c.getXAxis(), c.getAxisLeft()}) {
            a.setTextColor(color(R.color.text_secondary));
            a.setGridColor(color(R.color.divider));
            a.setAxisLineColor(color(R.color.divider));
            a.setTypeface(mono);
            a.setValueFormatter(PLAIN);
        }
        Readout r = new Readout(activity, readout);
        r.setChartView(c);
        c.setMarker(r);
        return c;
    }

    private LineDataSet line(List<Entry> pts, int color, boolean dots) {
        LineDataSet d = new LineDataSet(new ArrayList<>(pts), "");
        d.setColor(color);
        d.setLineWidth(1.8f);
        d.setDrawValues(false);
        d.setDrawCircles(dots);
        d.setCircleRadius(2.2f);
        d.setCircleColor(dots && color != Color.BLACK ? color(R.color.go) : color);
        d.setDrawCircleHole(false);
        d.setHighLightColor(color(R.color.accent));
        d.setDrawHorizontalHighlightIndicator(false);
        return d;
    }

    /** Time chart: the last {@code windowS} seconds (0 = everything) of {@code pts}. */
    private void window(LineChart c, List<Entry> pts, int windowS) {
        float now = pts.isEmpty() ? 0 : pts.get(pts.size() - 1).getX();
        float min = windowS == 0 ? 0 : Math.max(0, now - windowS);
        c.getXAxis().setAxisMinimum(min);
        c.getXAxis().setAxisMaximum(windowS == 0 ? Math.max(1, now) : min + windowS);
        List<Entry> visible = new ArrayList<>();
        for (Entry e : pts) if (e.getX() >= min) visible.add(e);
        show(c, visible.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(line(visible, color(R.color.primary), visible.size() <= 300)));
    }

    private static void show(LineChart c, List<ILineDataSet> sets) {
        // A highlight may point at a data set that is gone; drop it when the sets change.
        if (c.getData() == null || c.getData().getDataSetCount() != sets.size()) c.highlightValues(null);
        if (sets.isEmpty()) {
            c.clear();
            return;
        }
        c.setData(new LineData(sets));
        c.invalidate();
    }

    // ======================================================================
    // Saving and sharing
    // ======================================================================

    private void saveCsv(String prefix, String csv) {
        if (csv == null) { toast(R.string.odmr_no_data); return; }
        save(fileName(prefix, "csv"), csv.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    private static String fileName(String prefix, String ext) {
        return prefix + "_" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date()) + "." + ext;
    }

    private void save(String name, byte[] data, String mime) {
        String where;
        Uri uri;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uri = toDownloads(name, data, mime);
                where = "Downloads/UC2 ODMR/" + name;
            } else {
                uri = toAppFolder(name, data);
                where = "Android/data/" + activity.getPackageName() + "/files/odmr/" + name;
            }
        } catch (IOException e) {
            Snackbar.make(root, activity.getString(R.string.odmr_error, e.getMessage()), Snackbar.LENGTH_LONG).show();
            return;
        }
        Snackbar.make(root, activity.getString(R.string.odmr_saved, where), Snackbar.LENGTH_LONG)
                .setAction(R.string.share, v -> share(uri, mime, name))
                .show();
    }

    /** Android 10+: the shared Downloads folder, where the Files app shows it. No permission needed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private Uri toDownloads(String name, byte[] data, String mime) throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UC2 ODMR");
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Downloads refused " + name);
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("cannot write " + uri);
            out.write(data);
        }
        return uri;
    }

    /** Before Android 10: the app's own folder (shared Downloads would need a storage permission). */
    private Uri toAppFolder(String name, byte[] data) throws IOException {
        File base = activity.getExternalFilesDir(null);
        File dir = new File(base != null ? base : activity.getFilesDir(), "odmr");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
    }

    private void share(Uri uri, String mime, String name) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "ODMR " + name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(name, uri));
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share)));
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private void setupPages() {
        TabLayout tabs = find(R.id.odmr_tabs);
        int[] titles = {R.string.odmr_tab_sweep, R.string.odmr_tab_live, R.string.odmr_tab_ratio,
                R.string.odmr_tab_align, R.string.odmr_tab_info};
        int[] pages = {R.id.odmr_page_sweep, R.id.odmr_page_live, R.id.odmr_page_ratio,
                R.id.odmr_page_align, R.id.odmr_page_info};
        for (int t : titles) tabs.addTab(tabs.newTab().setText(t));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                for (int i = 0; i < pages.length; i++) {
                    find(pages[i]).setVisibility(i == tab.getPosition() ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    private Spinner spinner(int id, int items, int selected, Runnable onChange) {
        Spinner s = find(id);
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(activity, items,
                android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected, false);
        if (onChange != null) s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onChange.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        return s;
    }

    private static void onChange(EditText e, Runnable r) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { r.run(); }
        });
    }

    /** The buttons' brand tints have no disabled shade; fade them instead. */
    private static void enable(View b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private void running(MaterialButton b, boolean running, int start, int stop) {
        b.setText(running ? stop : start);
        b.setBackgroundTintList(ColorStateList.valueOf(color(running ? R.color.danger : R.color.go)));
    }

    private static double number(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 2820.0 → "2820", 2820.2 → "2820.2" (float digits, not 2820.199951…). */
    private static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : Float.toString(v);
    }

    private void click(int id, Runnable r) {
        find(id).setOnClickListener(v -> r.run());
    }

    private void toast(int text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    private int color(int res) {
        return ContextCompat.getColor(activity, res);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return (T) root.findViewById(id);
    }
}
