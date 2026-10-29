package com.openuc2.control;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The ODMR tab: the openUC2 ODMR WebSerial app (TechnicalDocs-openUC2-QBox,
 * ODMR_Server/webserial-app), bundled unchanged in assets/odmr and shown in a
 * WebView, so the phone gets exactly the desktop interface. uc2-bridge.js,
 * loaded before it, provides navigator.serial on top of this app's USB
 * connection and passes downloads to {@link #save}. Refresh the copy with
 * tools/sync_odmr_webapp.sh.
 */
final class OdmrTab {

    /** Any https origin works; ES modules refuse file://. Requests to it are served from assets. */
    private static final String ORIGIN = "https://appassets.androidplatform.net/";

    private final AppCompatActivity activity;
    private final UsbSerialManager serial;
    private final Runnable connect;   // MainActivity's Connect, at the chosen baud rate
    private final FrameLayout container;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;              // created the first time the tab is shown

    OdmrTab(AppCompatActivity activity, UsbSerialManager serial, Runnable connect) {
        this.activity = activity;
        this.serial = serial;
        this.connect = connect;
        this.container = activity.findViewById(R.id.tab_content_odmr);
    }

    /** Loads the webapp the first time the tab is shown. */
    @SuppressLint("SetJavaScriptEnabled")   // only our own bundled pages are loaded
    void show() {
        if (web != null) return;
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);   // chrome://inspect
        web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // language and measurement series persist
        settings.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "UC2Android");
        web.setWebChromeClient(new WebChromeClient());   // shows the webapp's alert() dialogs
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(ORIGIN)) return false;
                activity.startActivity(new Intent(Intent.ACTION_VIEW, url));   // e.g. the docs: browser
                return true;
            }
        });
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(ORIGIN + "index.html");
    }

    /** Text from the board (main thread, batched by UsbSerialManager). */
    void onSerialData(String text) {
        js("window.__uc2Serial && __uc2Serial.receive(" + JSONObject.quote(text) + ")");
    }

    void onConnected(boolean connected) {
        js("window.__uc2Serial && __uc2Serial.state(" + connected + ")");
    }

    void release() {
        if (web != null) web.destroy();
        web = null;
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    /** Webapp files from assets/odmr; index.html gets uc2-bridge.js in front of its scripts. */
    private WebResourceResponse serve(Uri url) {
        if (!url.toString().startsWith(ORIGIN)) return null;
        String path = url.getPath() == null || url.getPath().equals("/") ? "/index.html" : url.getPath();
        try {
            if (path.equals("/uc2-bridge.js")) return response(path, activity.getAssets().open("uc2-bridge.js"));
            InputStream in = activity.getAssets().open("odmr" + path);
            if (!path.equals("/index.html")) return response(path, in);
            String html = new String(readAll(in), StandardCharsets.UTF_8)
                    .replaceFirst("<head>", "<head><script src=\"uc2-bridge.js\"></script>");
            return response(path, new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                    new ByteArrayInputStream(new byte[0]));
        }
    }

    private static WebResourceResponse response(String path, InputStream body) {
        String ext = path.substring(path.lastIndexOf('.') + 1);
        String type;
        switch (ext) {
            case "html": type = "text/html"; break;
            case "js":   type = "text/javascript"; break;   // module scripts need a JS type
            case "css":  type = "text/css"; break;
            case "png":  type = "image/png"; break;
            case "svg":  type = "image/svg+xml"; break;
            default:     type = "application/octet-stream";
        }
        return new WebResourceResponse(type, "utf-8", body);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Called by the webapp through uc2-bridge.js, on the WebView's JavaBridge thread. */
    private final class Bridge {
        @JavascriptInterface
        public boolean isConnected() {
            return serial.isConnected();
        }

        @JavascriptInterface
        public void connect() {
            main.post(() -> {
                connect.run();
                // No device or driver fails at once, without a state change: say so, or the
                // webapp waits forever. Otherwise CONNECTED/DISCONNECTED follow via onConnected.
                if (serial.getState() == UsbSerialManager.State.DISCONNECTED) onConnected(false);
            });
        }

        @JavascriptInterface
        public void disconnect() {
            main.post(serial::disconnect);
        }

        @JavascriptInterface
        public void write(String text) {
            if (serial.isConnected()) serial.send(text, false);   // the webapp keeps its own log
        }

        @JavascriptInterface
        public void save(String name, String base64, String type) {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            String file = name.replaceAll("[^A-Za-z0-9._-]", "_");
            String mime = type == null || type.isEmpty() ? "application/octet-stream" : type.split(";")[0].trim();
            main.post(() -> saved(file, data, mime));
        }
    }

    private void saved(String name, byte[] data, String mime) {
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
            Snackbar.make(container, "Not saved: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
            return;
        }
        Snackbar.make(container, activity.getString(R.string.odmr_saved, where), Snackbar.LENGTH_LONG)
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
}
