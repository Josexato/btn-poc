package com.josexato.mirador3d;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Envoltorio mínimo: toda la app vive en assets/index.html (three.js local).
 * Lo único nativo es el selector de archivos que el WebView no trae solo.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Los gestores de archivos no siempre conocen los MIME de STL/OBJ,
                // así que se acepta todo y el visor valida por extensión.
                intent.setType("*/*");
                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "Elige un archivo STL u OBJ"),
                            REQ_FILE_CHOOSER);
                } catch (android.content.ActivityNotFoundException e) {
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER && pendingFileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
