package id.bram.flipclock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {

    static final String PREFS = "flipclock";
    static final String KEY_AUTOSTART = "autostart";

    private WebView web;
    private MediaHelper media;
    /** true selama jam terlihat di layar (dipakai layanan untuk memunculkan jam kembali). */
    static volatile boolean visible = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        w.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0E0F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        media = MediaHelper.get(this);
        media.activity = this;
        media.setSpeakListener(speaking -> {
            if (web != null) web.evaluateJavascript("window.onSpeakState && window.onSpeakState(" + speaking + ")", null);
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        RemoteService.start(this);
        // dinyalakan otomatis setelah tablet hidup + mode jam mengambang → langsung ke belakang
        if (getIntent() != null && getIntent().getBooleanExtra("fromBoot", false)
                && !"off".equals(ClockOverlay.get(this).mode())) {
            web.postDelayed(() -> moveTaskToBack(true), 2500);
        }
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
        }
    }

    @Override
    protected void onPause() {
        // di layar terbagi aplikasi tetap terlihat: jangan jeda tampilan jam
        if (web != null && !isInMultiWindowMode()) web.onPause();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        ClockOverlay.get(this).setSuppressed(true);
        if (media != null) {
            media.activity = this;
            try { media.lastSplit = isInMultiWindowMode(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        ClockOverlay.get(this).setSuppressed(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (media != null && media.activity == this) {
            media.activity = null;
            media.setSpeakListener(null);
        }
        super.onDestroy();
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMulti, Configuration newConfig) {
        super.onMultiWindowModeChanged(inMulti, newConfig);
        if (media != null) media.lastSplit = inMulti;
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onSplitChange && window.onSplitChange(" + inMulti + ")", null);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) {
                super.onBackPressed();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /** Methods callable from JavaScript as window.Android.xxx() */
    class Bridge {
        @JavascriptInterface
        public void setKeepScreenOn(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setAutostart(boolean on) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART, on).apply();
        }

        @JavascriptInterface
        public boolean getAutostart() {
            SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getBoolean(KEY_AUTOSTART, true);
        }

        @JavascriptInterface
        public boolean canAutostartFromBoot() {
            if (Build.VERSION.SDK_INT < 29) return true;
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void setBrightness(final float level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        : Math.max(0.01f, Math.min(1f, level));
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public String getVersion() {
            return "2.3";
        }

        // ---------------- musik / layar terbagi / suara (versi 2)
        @JavascriptInterface
        public boolean hasMediaAccess() { return media.hasMediaAccess(); }

        @JavascriptInterface
        public void openMediaAccess() { runOnUiThread(() -> media.openMediaAccessSettings()); }

        @JavascriptInterface
        public boolean isSplit() { return isInMultiWindowMode(); }

        /** Pengaturan remote untuk layanan latar belakang. */
        @JavascriptInterface
        public void setRemote(boolean enabled, String db, String key, String name) {
            SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean changed = p.getBoolean(RemoteService.P_ENABLED, false) != enabled
                    || !db.equals(p.getString(RemoteService.P_DB, ""))
                    || !key.equals(p.getString(RemoteService.P_KEY, ""))
                    || !name.equals(p.getString(RemoteService.P_NAME, ""));
            if (!changed) return;
            p.edit().putBoolean(RemoteService.P_ENABLED, enabled).putString(RemoteService.P_DB, db)
                    .putString(RemoteService.P_KEY, key).putString(RemoteService.P_NAME, name).apply();
            RemoteService.restart(MainActivity.this);
        }

        @JavascriptInterface
        public void setKeepFront(boolean on) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RemoteService.P_KEEPFRONT, on).apply();
        }

        @JavascriptInterface
        public boolean getKeepFront() {
            return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RemoteService.P_KEEPFRONT, true);
        }

        @JavascriptInterface
        public boolean isSpeaking() { return media.speaking; }

        /** Info radio untuk tampilan di jam (JSON). */
        @JavascriptInterface
        public String radioInfo() { return RadioPlayer.get(MainActivity.this).infoJson(); }

        /** Tinggi batang animasi radio (32 angka 0–100, dipisah koma) atau "" kalau tidak ada suara. */
        @JavascriptInterface
        public String radioViz(int delayMs) { return RadioViz.get().levels(Math.max(0, Math.min(1500, delayMs))); }

        // ---------------- jam mengambang
        @JavascriptInterface
        public boolean isOverlay() { return false; }

        @JavascriptInterface
        public String overlayMode() { return ClockOverlay.get(MainActivity.this).mode(); }

        @JavascriptInterface
        public int overlayPct() { return ClockOverlay.get(MainActivity.this).pct(); }

        @JavascriptInterface
        public boolean canOverlay() { return ClockOverlay.get(MainActivity.this).canDraw(); }

        @JavascriptInterface
        public String setOverlay(String mode, int pct) {
            ClockOverlay ov = ClockOverlay.get(MainActivity.this);
            String r = ov.setMode(mode, pct);
            if (!"off".equals(ov.mode()) && ov.canDraw()) {
                runOnUiThread(() -> web.postDelayed(() -> moveTaskToBack(true), 400));
            }
            return r;
        }

        @JavascriptInterface
        public boolean isInstalled(String source) { return media.installed(MediaHelper.pkgFor(source)); }

        @JavascriptInterface
        public String playLink(String source, String link, String spotifyUri) {
            return media.playLink(source, link, spotifyUri);
        }

        @JavascriptInterface
        public String playSpotify(String link, String uri, String name, String method) {
            return media.playSpotifyMethod(link, uri, name, method);
        }

        @JavascriptInterface
        public String openSource(String source, String link) {
            return media.openAdjacent(source, link);
        }

        @JavascriptInterface
        public String mediaCommand(String source, String cmd) {
            return media.command(source, cmd);
        }

        @JavascriptInterface
        public String mediaInfo(String source) { return media.info(source); }

        @JavascriptInterface
        public void setVolume(int pct) { media.setVolumePercent(pct); }

        @JavascriptInterface
        public void speak(String text, boolean bell, int times) { media.speak(text, bell, times); }

        @JavascriptInterface
        public String ttsStatus() { return media.ttsStatus(); }

        @JavascriptInterface
        public void openTtsSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public int battery() {
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            } catch (Exception e) { return -1; }
        }

        @JavascriptInterface
        public boolean charging() {
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                return bm.isCharging();
            } catch (Exception e) { return false; }
        }
    }
}
