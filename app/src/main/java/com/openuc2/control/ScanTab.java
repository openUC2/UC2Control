package com.openuc2.control;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.serenegiant.widget.AspectRatioTextureView;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Scan tab: live view, stage overview with jog and tap-to-move, tile
 * scan, share. Every blocking stage or camera call runs on one hardware
 * worker thread, one at a time, so jogs and scans never interleave on the wire.
 */
final class ScanTab implements Camera.Listener {

    private static final String[] STAGE_KEYS = {"uc2", "pcb", "none"};
    /**
     * Per protocol: µm/step X, Y, Z, speed (steps/s), approach overshoot (µsteps).
     * UC2: 0.3125 µm/step is a 1 mm lead screw at 3200 µsteps/rev. PCB: the
     * calibration in ImSwitchConfig shitscope_hik.json.
     */
    private static final String[][] STAGE_DEFAULTS = {
            {"0.3125", "0.3125", "0.3125", "15000", "0"},
            {"174.9542", "86.6589", "1", "100", "6"},
            {"1", "1", "1", "1000", "0"},
    };
    private static final int[] STAGE_FIELDS =
            {R.id.et_ums_x, R.id.et_ums_y, R.id.et_ums_z, R.id.et_stage_speed, R.id.et_overshoot};
    private static final int[] SCAN_FIELDS = {R.id.et_px_size, R.id.et_img_w, R.id.et_img_h,
            R.id.et_nx, R.id.et_ny, R.id.et_overlap, R.id.et_step_x, R.id.et_step_y, R.id.et_settle,
            R.id.et_jog_step, R.id.et_exposure, R.id.et_gain};
    private static final int[] CHECKS = {R.id.cb_snake, R.id.cb_centred, R.id.cb_return,
            R.id.cb_gallery, R.id.cb_flip_x, R.id.cb_flip_y};
    /** Camera choices, in the order of R.array.camera_types; Daheng only when its SDK was built in. */
    private static final int CAM_UVC = 0, CAM_PHONE = 1, CAM_DAHENG = 2;
    private static final int[] JOG_BUTTONS = {R.id.btn_scan_x_minus, R.id.btn_scan_x_plus,
            R.id.btn_scan_y_minus, R.id.btn_scan_y_plus, R.id.btn_scan_z_minus, R.id.btn_scan_z_plus};
    private static final int MAX_TILES_PER_AXIS = 200;

    private interface StageOp {
        void run(Stage stage) throws Exception;
    }

    private final AppCompatActivity activity;
    private final UsbSerialManager serial;
    private final SerialLink link;
    private final SharedPreferences prefs;
    private final ExecutorService hw = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> cameraPermission;

    private final AspectRatioTextureView preview;
    private final StageMapView map;
    private final TextView camStatus, position, scanInfo, scanStatus;
    private final ProgressBar progress;
    private final Spinner stageSpinner, cameraSpinner;

    private Camera camera;
    private int cameraType = -1;
    private String cameraStatus = "";
    private Stage stage;
    private String stageKey = "";
    private int stageType = -1;            // protocol whose values the stage fields hold
    private TileScan scan;                 // non-null while one runs (UI thread)
    private File lastScanDir;
    private long scanStartMs;

    // Plan for the current fields, kept in step by updatePlan().
    private List<TileScan.Tile> tiles;
    private double fovW, fovH, stepX, stepY, pixel, overlap;
    private int imgW, imgH;

    ScanTab(AppCompatActivity activity, UsbSerialManager serial) {
        this.activity = activity;
        this.serial = serial;
        link = new SerialLink(serial);
        serial.setRawListener(link);
        prefs = activity.getSharedPreferences("scan", Context.MODE_PRIVATE);

        preview = activity.findViewById(R.id.tex_preview);
        map = activity.findViewById(R.id.map);
        camStatus = activity.findViewById(R.id.tv_cam_status);
        position = activity.findViewById(R.id.tv_position);
        scanInfo = activity.findViewById(R.id.tv_scan_info);
        scanStatus = activity.findViewById(R.id.tv_scan_status);
        progress = activity.findViewById(R.id.pb_scan);
        stageSpinner = activity.findViewById(R.id.sp_stage);
        cameraSpinner = activity.findViewById(R.id.sp_camera);

        cameraPermission = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) openCamera();
                    else camStatus.setText("Cameras need the camera permission.");
                });

        List<CharSequence> cameras = new ArrayList<>(Arrays.asList(
                activity.getResources().getTextArray(R.array.camera_types)));
        if (!BuildConfig.DAHENG) cameras.remove(CAM_DAHENG);
        ArrayAdapter<CharSequence> cameraItems = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, cameras);
        cameraItems.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraItems);

        ArrayAdapter<CharSequence> protocols = ArrayAdapter.createFromResource(
                activity, R.array.stage_protocols, android.R.layout.simple_spinner_item);
        protocols.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stageSpinner.setAdapter(protocols);

        load();
        wire();
        updatePlan();
        showPosition();
    }

    // ======================================================================
    // Wiring
    // ======================================================================

    private void wire() {
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (camera != null) camera.setPreview(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (camera != null) camera.setPreview(null);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        activity.findViewById(R.id.btn_camera).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA);
            }
        });
        activity.findViewById(R.id.btn_exposure).setOnClickListener(v -> applyExposure());

        jog(R.id.btn_scan_x_minus, 'X', -1);
        jog(R.id.btn_scan_x_plus, 'X', 1);
        jog(R.id.btn_scan_y_minus, 'Y', -1);
        jog(R.id.btn_scan_y_plus, 'Y', 1);
        jog(R.id.btn_scan_z_minus, 'Z', -1);
        jog(R.id.btn_scan_z_plus, 'Z', 1);
        activity.findViewById(R.id.btn_scan_stop).setOnClickListener(v -> stopNow());
        map.setOnTapListener((x, y) -> onStage(s -> s.moveTo(x, y)));

        activity.findViewById(R.id.btn_scan_start).setOnClickListener(v -> startScan());
        activity.findViewById(R.id.btn_scan_end).setOnClickListener(v -> {
            if (scan != null) {
                scan.requestStop();
                scanStatus.setText("Stopping after this tile…");
            }
        });
        activity.findViewById(R.id.btn_share).setOnClickListener(v -> shareLastScan());

        TextWatcher replan = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updatePlan(); }
        };
        for (int id : SCAN_FIELDS) field(id).addTextChangedListener(replan);
        for (int id : CHECKS) check(id).setOnCheckedChangeListener((b, on) -> updatePlan());

        stageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == stageType) return;   // the initial callback, or no change
                save();                          // keeps the old protocol's values
                loadStageFields(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void jog(int id, char axis, int sign) {
        activity.findViewById(id).setOnClickListener(v -> {
            double step = sign * num(R.id.et_jog_step, 100);
            onStage(s -> s.moveBy(axis, step));
        });
    }

    /** Opens the camera chosen in the spinner, closing another kind first. */
    private void openCamera() {
        int type = cameraSpinner.getSelectedItemPosition();
        if (camera != null && type != cameraType) {
            camera.close();
            camera = null;
        }
        if (camera == null) {
            if (type == CAM_PHONE) {
                camera = new PhoneCamera(activity, this);
            } else if (type == CAM_DAHENG) {
                camera = dahengCamera();
                if (camera == null) return;
            } else {
                camera = new UvcCamera(activity.getApplication(), this);
            }
            cameraType = type;
            camera.setPreview(preview.isAvailable() ? preview.getSurfaceTexture() : null);
        }
        camera.open();
    }

    /** DahengCamera is compiled only with Daheng's SDK in app/libs/galaxy, hence by name. */
    private Camera dahengCamera() {
        try {
            return (Camera) Class.forName("com.openuc2.control.DahengCamera")
                    .getDeclaredConstructor(android.app.Application.class, Camera.Listener.class)
                    .newInstance(activity.getApplication(), this);
        } catch (Exception e) {
            camStatus.setText("This build has no Daheng support: " + message(e));
            return null;
        }
    }

    /** Fixes exposure and gain now, so the live view shows what the tiles will look like. */
    private void applyExposure() {
        Camera c = camera;
        if (c == null || !c.isOpen()) {
            toast("Connect the camera first");
            return;
        }
        double ms = num(R.id.et_exposure, 10), gain = num(R.id.et_gain, 0);
        hw.execute(() -> {
            try {
                String set = c.setExposure(ms, gain);
                main.post(() -> camStatus.setText(cameraStatus + "\nFixed: " + set));
            } catch (Exception e) {
                main.post(() -> toast("Exposure not set: " + message(e)));
            }
        });
    }

    /** Queue a stage operation on the hardware thread; reports errors, then the new position. */
    private void onStage(StageOp op) {
        if (scan != null) {
            toast("A scan is running");
            return;
        }
        Stage s = stage();
        if (s == null) return;
        hw.execute(() -> {
            try {
                op.run(s);
                s.idle();
            } catch (Exception e) {
                main.post(() -> toast(message(e)));
            }
            main.post(this::showPosition);
        });
    }

    private void stopNow() {
        if (scan != null) scan.requestStop();
        if (stage != null) stage.stop();
    }

    /**
     * The stage for the current settings, rebuilt with the old position when
     * they change. Null, after saying why, when it cannot be used.
     */
    private Stage stage() {
        int type = stageSpinner.getSelectedItemPosition();
        if (type < 2 && !serial.isConnected()) {
            toast("Connect the stage first (Connect at the top)");
            return null;
        }
        double[] ums = {num(R.id.et_ums_x, 0), num(R.id.et_ums_y, 0), num(R.id.et_ums_z, 0)};
        if (ums[0] == 0 || ums[1] == 0 || (type == 0 && ums[2] == 0)) {
            toast("µm/step must not be 0");
            return null;
        }
        int speed = (int) num(R.id.et_stage_speed, 1000);
        int overshoot = (int) num(R.id.et_overshoot, 0);
        String key = type + " " + Arrays.toString(ums) + " " + speed + " " + overshoot;
        if (!key.equals(stageKey)) {
            double[] pos = stage == null ? new double[3] : stage.position();
            if (type == 0) stage = new Uc2Stage(link, ums, speed, pos);
            else if (type == 1) stage = new PcbStage(link, ums[0], ums[1], speed, overshoot, pos);
            else stage = new Stage.Virtual(pos);
            stageKey = key;
        }
        return stage;
    }

    // ======================================================================
    // Plan, position
    // ======================================================================

    /** Recompute field of view, steps and tiles from the fields; refresh the info line and the map. */
    private void updatePlan() {
        pixel = num(R.id.et_px_size, 1);
        imgW = (int) num(R.id.et_img_w, 1920);
        imgH = (int) num(R.id.et_img_h, 1080);
        overlap = Math.max(0, Math.min(90, num(R.id.et_overlap, 25))) / 100;
        fovW = imgW * pixel;
        fovH = imgH * pixel;
        double autoX = fovW * (1 - overlap), autoY = fovH * (1 - overlap);
        field(R.id.et_step_x).setHint(fmt(autoX));
        field(R.id.et_step_y).setHint(fmt(autoY));
        stepX = num(R.id.et_step_x, autoX);
        stepY = num(R.id.et_step_y, autoY);
        int nx = clampTiles(num(R.id.et_nx, 3)), ny = clampTiles(num(R.id.et_ny, 3));

        double[] at = stage == null ? new double[3] : stage.position();
        tiles = TileScan.grid(at[0], at[1], nx, ny, stepX, stepY,
                check(R.id.cb_snake).isChecked(), check(R.id.cb_centred).isChecked());

        scanInfo.setText(String.format(Locale.US,
                "FOV %.2f × %.2f mm · step %s × %s µm\narea %.2f × %.2f mm · %d tiles",
                fovW / 1000, fovH / 1000, fmt(stepX), fmt(stepY),
                ((nx - 1) * stepX + fovW) / 1000, ((ny - 1) * stepY + fovH) / 1000, tiles.size()));
        map.setFov(fovW, fovH);
        map.setFlip(check(R.id.cb_flip_x).isChecked(), check(R.id.cb_flip_y).isChecked());
        if (scan == null) map.setPlanned(tiles);
    }

    private static int clampTiles(double n) {
        return (int) Math.max(1, Math.min(MAX_TILES_PER_AXIS, Math.round(n)));
    }

    private void showPosition() {
        if (stage == null) {
            position.setText(R.string.position_unknown);
            return;
        }
        double[] p = stage.position();
        position.setText(String.format(Locale.US, "X %.1f µm · Y %.1f µm%s", p[0], p[1],
                stage.hasZ() ? String.format(Locale.US, " · Z %.1f µm", p[2]) : ""));
        map.setPosition(p[0], p[1]);
        if (scan == null) updatePlan();   // a centred grid follows the stage
    }

    // ======================================================================
    // Scan
    // ======================================================================

    private void startScan() {
        if (scan != null) return;
        Camera c = camera;
        if (c == null || !c.isOpen()) {
            toast("Connect the camera first");
            return;
        }
        Stage s = stage();
        if (s == null) return;
        updatePlan();   // grid around where the stage is now
        if (!(stepX > 0 && stepY > 0)) {
            toast("Set the pixel size and image size first");
            return;
        }
        List<TileScan.Tile> plan = tiles;

        File base = activity.getExternalFilesDir(null);
        if (base == null) base = activity.getFilesDir();
        File dir = new File(base, "scans/"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        JSONObject meta;
        try {
            meta = new JSONObject()
                    .put("um_per_px", pixel)
                    .put("image_width", imgW).put("image_height", imgH)
                    .put("step_x_um", stepX).put("step_y_um", stepY).put("overlap", overlap)
                    .put("pattern", check(R.id.cb_snake).isChecked() ? "snake" : "raster")
                    .put("stage", String.valueOf(stageSpinner.getSelectedItem()))
                    .put("camera", String.valueOf(cameraSpinner.getSelectedItem()))
                    .put("app", "UC2 Control " + BuildConfig.VERSION_NAME);
        } catch (org.json.JSONException e) {
            toast(message(e));
            return;
        }

        int sample = thumbSample(imgW, plan.size());
        TileScan.Listener listener = new TileScan.Listener() {
            @Override
            public void onTile(int k, TileScan.Tile t, byte[] jpeg) {
                Bitmap thumb = thumbnail(jpeg, sample);   // decoded here, off the UI thread
                main.post(() -> {
                    if (thumb != null) map.addShot(t.x, t.y, thumb);
                    showProgress(k + 1, plan.size());
                    showPosition();
                });
            }

            @Override
            public void onFinished(String state, String error) {
                main.post(() -> finished(state, error, plan.size()));
            }
        };
        scan = new TileScan(s, c, plan, dir, meta, num(R.id.et_exposure, 10), num(R.id.et_gain, 0),
                (long) num(R.id.et_settle, 200), check(R.id.cb_return).isChecked(), listener);
        lastScanDir = dir;
        scanStartMs = SystemClock.elapsedRealtime();
        map.clearShots();
        progress.setMax(plan.size());
        progress.setProgress(0);
        scanStatus.setText("Scanning " + plan.size() + " tiles…");
        setScanning(true);
        hw.execute(scan);
    }

    private void showProgress(int done, int total) {
        progress.setProgress(done);
        long elapsed = SystemClock.elapsedRealtime() - scanStartMs;
        long eta = elapsed / done * (total - done);
        scanStatus.setText(String.format(Locale.US, "Tile %d / %d · %s · ETA %s",
                done, total, clock(elapsed), clock(eta)));
    }

    private void finished(String state, String error, int total) {
        int done = progress.getProgress();
        String settings = scan.cameraSettings();
        scan = null;
        setScanning(false);
        String where = "Android/data/" + activity.getPackageName() + "/files/scans/" + lastScanDir.getName();
        String report;
        if ("error".equals(state)) {
            report = "Error after " + done + " / " + total + " tiles: " + error
                    + "\nTiles so far are kept in " + where;
        } else {
            report = ("done".equals(state) ? "Done: " : "Stopped: ") + done + " / " + total
                    + " tiles in " + clock(SystemClock.elapsedRealtime() - scanStartMs)
                    + (settings.isEmpty() ? "" : " · " + settings) + "\n" + where;
        }
        scanStatus.setText(report);
        showPosition();
        if (done > 0 && check(R.id.cb_gallery).isChecked()) {
            File dir = lastScanDir;
            hw.execute(() -> {
                String note;
                try {
                    int n = copyToGallery(dir);
                    note = n < 0 ? "Gallery copy needs Android 10 or newer."
                            : n + " tiles in the gallery: Pictures/UC2 Scans/" + dir.getName();
                } catch (Exception e) {
                    note = "Gallery copy failed: " + message(e);
                }
                String line = note;
                main.post(() -> scanStatus.setText(report + "\n" + line));
            });
        }
    }

    /**
     * Copies the scan's tiles into the gallery, album Pictures/UC2 Scans/<scan>.
     * Returns how many, or -1 below Android 10.
     */
    private int copyToGallery(File dir) throws IOException {
        // ponytail: MediaStore without any permission is Android 10+; older phones would need
        // WRITE_EXTERNAL_STORAGE and a media scan.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        File[] files = new File(dir, "tiles").listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return 0;
        Arrays.sort(files);
        ContentResolver resolver = activity.getContentResolver();
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String album = Environment.DIRECTORY_PICTURES + "/UC2 Scans/" + dir.getName();
        for (File f : files) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, dir.getName() + "_" + f.getName());
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, album);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);   // hidden until complete
            Uri uri = resolver.insert(images, v);
            if (uri == null) throw new IOException("the gallery refused " + f.getName());
            try (OutputStream out = resolver.openOutputStream(uri);
                 InputStream in = new FileInputStream(f)) {
                if (out == null) throw new IOException("cannot write " + uri);
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
        }
        return files.length;
    }

    private void setScanning(boolean on) {
        for (int id : SCAN_FIELDS) field(id).setEnabled(!on);
        for (int id : STAGE_FIELDS) field(id).setEnabled(!on);
        for (int id : CHECKS) check(id).setEnabled(!on);
        for (int id : JOG_BUTTONS) activity.findViewById(id).setEnabled(!on);
        stageSpinner.setEnabled(!on);
        cameraSpinner.setEnabled(!on);
        activity.findViewById(R.id.btn_exposure).setEnabled(!on);
        map.setEnabled(!on);
        activity.findViewById(R.id.btn_scan_start).setEnabled(!on);
        activity.findViewById(R.id.btn_share).setEnabled(!on);
        activity.findViewById(R.id.btn_camera).setEnabled(!on);
        // A long scan must not stop because the screen went dark.
        if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Power of two that brings a frame down to map-thumbnail size: 160 px wide
     * up to 400 tiles, smaller beyond, so all thumbnails together stay ~12 MB.
     */
    private static int thumbSample(int width, int tiles) {
        double target = Math.max(16, 160 / Math.sqrt(Math.max(1, tiles / 400.0)));
        int s = 1;
        while (width / (s * 2) >= target) s *= 2;
        return s;
    }

    private static Bitmap thumbnail(byte[] jpeg, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    // ======================================================================
    // Share
    // ======================================================================

    /**
     * Zips the last scan (next to it, reused while current) and hands it to
     * the share sheet: Drive, Dropbox, Nextcloud, mail, whatever is installed.
     */
    private void shareLastScan() {
        File dir = lastScanDir != null ? lastScanDir : newestScan();
        if (dir == null || !dir.isDirectory()) {
            toast("No scan yet");
            return;
        }
        scanStatus.setText("Packing " + dir.getName() + ".zip…");
        hw.execute(() -> {
            try {
                File zip = new File(dir.getParentFile(), dir.getName() + ".zip");
                File index = new File(dir, "tiles.json");
                if (!zip.exists() || zip.lastModified() < index.lastModified()) zipDir(dir, zip);
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", zip);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "UC2 scan " + dir.getName())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(dir.getName(), uri));   // carries the grant through the chooser
                long mb = zip.length() >> 20;
                main.post(() -> {
                    scanStatus.setText(dir.getName() + ".zip · " + mb + " MB");
                    activity.startActivity(Intent.createChooser(send, "Upload scan"));
                });
            } catch (Exception e) {
                main.post(() -> scanStatus.setText("Packing failed: " + message(e)));
            }
        });
    }

    private File newestScan() {
        File base = activity.getExternalFilesDir(null);
        File[] scans = base == null ? null : new File(base, "scans").listFiles(File::isDirectory);
        if (scans == null || scans.length == 0) return null;
        Arrays.sort(scans);   // names are timestamps
        return scans[scans.length - 1];
    }

    private static void zipDir(File dir, File zip) throws IOException {
        File partial = new File(zip.getPath() + ".part");
        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(partial)))) {
            out.setLevel(Deflater.NO_COMPRESSION);   // JPEGs do not shrink; the JSON is tiny
            addToZip(out, dir, dir.getName());
        }
        if (!partial.renameTo(zip)) throw new IOException("Cannot write " + zip);
    }

    private static void addToZip(ZipOutputStream out, File file, String path) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) addToZip(out, c, path + "/" + c.getName());
            return;
        }
        out.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(file)) {
            copy(in, out);
        }
        out.closeEntry();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    // ======================================================================
    // Camera.Listener
    // ======================================================================

    @Override
    public void onCameraState(String status, int width, int height) {
        cameraStatus = status;
        camStatus.setText(status);
        if (width > 0) {
            preview.setAspectRatio(width, height);
            // Field of view follows the frames actually delivered.
            field(R.id.et_img_w).setText(String.valueOf(width));
            field(R.id.et_img_h).setText(String.valueOf(height));
        }
    }

    // ======================================================================
    // Settings
    // ======================================================================

    private void load() {
        for (int id : SCAN_FIELDS) {
            String v = prefs.getString(key(id), null);
            if (v != null) field(id).setText(v);
        }
        for (int id : CHECKS) check(id).setChecked(prefs.getBoolean(key(id), check(id).isChecked()));
        cameraSpinner.setSelection(Math.min(prefs.getInt("camera", CAM_UVC), cameraSpinner.getCount() - 1));
        int type = Math.min(prefs.getInt("stage", 0), STAGE_KEYS.length - 1);
        stageSpinner.setSelection(type);
        loadStageFields(type);
    }

    private void loadStageFields(int type) {
        for (int i = 0; i < STAGE_FIELDS.length; i++) {
            field(STAGE_FIELDS[i]).setText(prefs.getString(
                    STAGE_KEYS[type] + "." + key(STAGE_FIELDS[i]), STAGE_DEFAULTS[type][i]));
        }
        stageType = type;
        boolean pcb = type == 1;
        activity.findViewById(R.id.col_ums_z).setVisibility(pcb ? View.INVISIBLE : View.VISIBLE);
        activity.findViewById(R.id.col_overshoot).setVisibility(pcb ? View.VISIBLE : View.INVISIBLE);
        activity.findViewById(R.id.btn_scan_z_minus).setVisibility(pcb ? View.INVISIBLE : View.VISIBLE);
        activity.findViewById(R.id.btn_scan_z_plus).setVisibility(pcb ? View.INVISIBLE : View.VISIBLE);
    }

    void save() {
        SharedPreferences.Editor e = prefs.edit();
        for (int id : SCAN_FIELDS) e.putString(key(id), field(id).getText().toString());
        for (int id : CHECKS) e.putBoolean(key(id), check(id).isChecked());
        e.putInt("stage", stageSpinner.getSelectedItemPosition());
        e.putInt("camera", cameraSpinner.getSelectedItemPosition());
        if (stageType >= 0) {
            for (int id : STAGE_FIELDS) {
                e.putString(STAGE_KEYS[stageType] + "." + key(id), field(id).getText().toString());
            }
        }
        e.apply();
    }

    void release() {
        save();
        if (scan != null) scan.requestStop();
        hw.shutdown();
        if (camera != null) camera.close();
        serial.setRawListener(null);
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private String key(int id) {
        return activity.getResources().getResourceEntryName(id);
    }

    private EditText field(int id) {
        return activity.findViewById(id);
    }

    private CheckBox check(int id) {
        return activity.findViewById(id);
    }

    /** Field as a number; accepts a decimal comma, falls back when empty or garbled. */
    private double num(int id, double fallback) {
        String s = field(id).getText().toString().trim().replace(',', '.');
        try {
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, v >= 100 ? "%.0f" : "%.1f", v);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
