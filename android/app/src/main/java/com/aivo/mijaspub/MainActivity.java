package com.aivo.mijaspub;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooser;
    private ActivityResultLauncher<Intent> voiceLauncher;

    // Ladataan appi netista; service worker tallentaa sen laitteelle offline-kayttoon.
    private static final String START_URL = "https://aivo88.github.io/MijasPub/";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pida naytto paalla koko ajan (kioski).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);
        web.addJavascriptInterface(new MijasBridge(), "MijasBridge");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Salli service worker -> appi tallentaa itsensa laitteelle, jotta se toimii offline.
        try {
            ServiceWorkerController swc = ServiceWorkerController.getInstance();
            swc.getServiceWorkerWebSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            swc.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return null; // anna hakea normaalisti verkosta/valimuistista
                }
            });
        } catch (Exception ignored) {}

        web.setWebViewClient(new WebViewClient());

        fileChooser = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] out = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) out = new Uri[]{ uri };
                    }
                    filePathCallback.onReceiveValue(out);
                    filePathCallback = null;
                });

        voiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    String text = "";
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> r = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (r != null && !r.isEmpty()) text = r.get(0);
                    }
                    final String tt = text;
                    web.post(() -> web.evaluateJavascript(
                            "window.__onVoiceResult && window.__onVoiceResult(" + JSONObject.quote(tt) + ")", null));
                });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    fileChooser.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            web.loadUrl(START_URL);
        }

        hideSystemUI();
    }

    // Kioskitila: piilota Androidin ala- ja ylapalkit.
    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        web.restoreState(savedInstanceState);
    }

    // Silta jolla web-appi tallentaa tiedostot laitteen kansioihin.
    public class MijasBridge {
        @JavascriptInterface
        public void saveBase64(String b64, String filename, String mime, String subdir) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String folder = (subdir == null || subdir.isEmpty()) ? "SaunaMijas" : subdir;

                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
                    android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("no uri");
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.close();
                } else {
                    java.io.File dir = new java.io.File(
                            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), folder);
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, filename);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(data);
                    fos.close();
                }
                final String msg = "Tallennettu: Download/" + folder + "/" + filename;
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Tallennus epaonnistui", android.widget.Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void printPdf(String b64, String name) {
            runOnUiThread(() -> {
                try {
                    final byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    final String jobName = (name == null || name.isEmpty()) ? "Sauna Mijas" : name;
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                        @Override
                        public void onLayout(PrintAttributes oldAttr, PrintAttributes newAttr,
                                             CancellationSignal cancel, LayoutResultCallback cb, android.os.Bundle extras) {
                            if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
                            PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName)
                                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build();
                            cb.onLayoutFinished(info, true);
                        }
                        @Override
                        public void onWrite(PageRange[] pages, ParcelFileDescriptor dest,
                                            CancellationSignal cancel, WriteResultCallback cb) {
                            try {
                                java.io.InputStream in = new java.io.ByteArrayInputStream(data);
                                java.io.OutputStream out = new java.io.FileOutputStream(dest.getFileDescriptor());
                                byte[] buf = new byte[16384]; int r;
                                while ((r = in.read(buf)) >= 0) { out.write(buf, 0, r); }
                                in.close(); out.close();
                                cb.onWriteFinished(new PageRange[]{ PageRange.ALL_PAGES });
                            } catch (Exception ex) {
                                cb.onWriteFailed(ex.getMessage());
                            }
                        }
                    };
                    pm.print(jobName, adapter, null);
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this, "Tulostus ei kaytettavissa", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startVoice(String locale) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    if (locale != null && !locale.isEmpty()) {
                        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
                    }
                    voiceLauncher.launch(intent);
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this, "Puheentunnistus ei kaytettavissa", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
