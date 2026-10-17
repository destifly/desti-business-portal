package com.destifly.rani;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private WebViewAssetLoader assetLoader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ur", "PK"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(new Locale("hi", "IN"));
                ttsReady = true;
            }
        });
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.addJavascriptInterface(new RaniBridge(), "RaniNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) request.deny();
                    else request.grant(request.getResources());
                });
            }
        });
        setContentView(webView);
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private class RaniBridge {
        @JavascriptInterface public void startListening() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
                    callback("raniOnSpeechError", "Microphone permission chahiye.");
                    return;
                }
                if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    callback("raniOnSpeechError", "Is phone par speech recognition service available nahi.");
                    return;
                }
                if (recognizer != null) recognizer.destroy();
                recognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle params) { callback("raniOnSpeechStatus", "Listening…"); }
                    public void onBeginningOfSpeech() {}
                    public void onRmsChanged(float rmsdB) {}
                    public void onBufferReceived(byte[] buffer) {}
                    public void onEndOfSpeech() { callback("raniOnSpeechStatus", "Processing…"); }
                    public void onError(int error) {
                        String message = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            ? "Microphone permission allow karein."
                            : error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                                ? "Speech service ke liye internet check karein."
                                : error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                    ? "Awaaz samajh nahi aayi. Dobara bol kar dekhein."
                                    : "Voice input mein masla hua. Dobara koshish karein.";
                        callback("raniOnSpeechError", message);
                    }
                    public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) callback("raniOnSpeechResult", matches.get(0));
                        else callback("raniOnSpeechError", "Koi alfaaz nahi mile.");
                        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                    }
                    public void onPartialResults(Bundle partialResults) {}
                    public void onEvent(int eventType, Bundle params) {}
                });
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                recognizer.startListening(intent);
            });
        }
        @JavascriptInterface public void speak(String text, float rate, float pitch) {
            runOnUiThread(() -> {
                if (!ttsReady || tts == null) { callback("raniOnSpeechStatus", "Voice service loading…"); return; }
                tts.setSpeechRate(Math.max(0.7f, Math.min(1.1f, rate)));
                tts.setPitch(Math.max(0.8f, Math.min(1.3f, pitch)));
                int result = tts.setLanguage(new Locale("ur", "PK"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("hi", "IN"));
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rani-reply");
            });
        }
        @JavascriptInterface public void stopSpeaking() { runOnUiThread(() -> { if (tts != null) tts.stop(); }); }
    }

    private void callback(String function, String value) {
        if (webView == null) return;
        String quoted = JSONObject.quote(value == null ? "" : value);
        webView.post(() -> webView.evaluateJavascript("window." + function + " && window." + function + "(" + quoted + ")", null));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED))
            callback("raniOnSpeechError", "Microphone permission allow karein.");
    }
    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onPause() {
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (webView != null) { webView.removeJavascriptInterface("RaniNative"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}