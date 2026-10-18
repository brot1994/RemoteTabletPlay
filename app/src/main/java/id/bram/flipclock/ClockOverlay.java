package id.bram.flipclock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * Jam mengambang: jendela di atas semua aplikasi, menempel di KANAN, KIRI, ATAS atau BAWAH layar
 * dengan ukuran persen tertentu. Aplikasi musik berjalan layar penuh di belakangnya.
 */
class ClockOverlay {

    static final String P_MODE = "ov_mode";   // "off" | "right" | "left" | "bottom" | "top"
    static final String P_PCT = "ov_pct";     // 15..80

    private static ClockOverlay instance;

    static synchronized ClockOverlay get(Context c) {
        if (instance == null) instance = new ClockOverlay(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean attached = false;
    private boolean suppressed = false;   // disembunyikan selama aplikasi utama tampil
    private WindowManager.LayoutParams lp;

    private ClockOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    String mode() { return prefs().getString(P_MODE, "off"); }

    int pct() { return Math.max(15, Math.min(80, prefs().getInt(P_PCT, 25))); }

    boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    boolean isShowing() { return attached; }

    static String label(String m) {
        switch (m) { case "left": return "kiri"; case "top": return "atas"; case "bottom": return "bawah"; case "right": return "kanan"; default: return "layar penuh"; }
    }

    /** Simpan mode lalu terapkan. */
    String setMode(String mode, int pct) {
        if (!"right".equals(mode) && !"left".equals(mode) && !"bottom".equals(mode) && !"top".equals(mode)) mode = "off";
        prefs().edit().putString(P_MODE, mode).putInt(P_PCT, pct <= 0 ? pct() : Math.max(15, Math.min(80, pct))).apply();
        ui.post(this::apply);
        if (!"off".equals(mode) && !canDraw()) return "butuh izin tampil di atas aplikasi lain";
        return "ok";
    }

    void setSuppressed(boolean s) {
        suppressed = s;
        ui.post(this::apply);
    }

    /** Tampilkan / sembunyikan / ubah ukuran sesuai pengaturan. */
    void apply() {
        String m = mode();
        boolean want = !"off".equals(m) && !suppressed && canDraw();
        if (!want) { detach(); return; }
        if (web == null) createWebView();
        WindowManager.LayoutParams p = layoutParams(m, pct());
        try {
            if (!attached) {
                wm.addView(web, p);
                attached = true;
            } else {
                wm.updateViewLayout(web, p);
            }
            lp = p;
        } catch (Exception e) {
            attached = false;
        }
    }

    /** Dipanggil saat orientasi layar berubah. */
    void onConfigChanged() {
        ui.postDelayed(this::apply, 300);
    }

    private void detach() {
        if (attached && web != null) {
            try { wm.removeView(web); } catch (Exception ignored) { }
        }
        attached = false;
    }

    private Point screenSize() {
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            p.set(b.width(), b.height());
        } else {
            wm.getDefaultDisplay().getRealSize(p);
        }
        return p;
    }

    @SuppressWarnings("deprecation")
    private WindowManager.LayoutParams layoutParams(String m, int pct) {
        Point s = screenSize();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int w, h, gravity;
        if ("bottom".equals(m) || "top".equals(m)) {
            w = WindowManager.LayoutParams.MATCH_PARENT;
            h = Math.round(s.y * pct / 100f);
            gravity = ("top".equals(m) ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START;
        } else {
            w = Math.round(s.x * pct / 100f);
            h = WindowManager.LayoutParams.MATCH_PARENT;
            gravity = ("left".equals(m) ? Gravity.START : Gravity.END) | Gravity.TOP;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        p.gravity = gravity;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (lp != null) p.screenBrightness = lp.screenBrightness;
        return p;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(ctx))
                .build();
        web = new WebView(ctx);
        web.setBackgroundColor(0xFF0E0E0F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
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
                return !"appassets.androidplatform.net".equals(req.getUrl().getHost());
            }
        });
        web.addJavascriptInterface(new OverlayBridge(), "Android");
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html?overlay=1");
    }

    /** Teruskan status "sedang dibacakan" ke jam mengambang. */
    void notifySpeaking(boolean speaking) {
        if (web != null && attached) {
            ui.post(() -> { if (web != null) web.evaluateJavascript("window.onSpeakState && window.onSpeakState(" + speaking + ")", null); });
        }
    }

    /** Fungsi yang bisa dipanggil JavaScript di jam mengambang (window.Android). */
    class OverlayBridge {
        @JavascriptInterface public String getVersion() { return RemoteService.VER; }
        @JavascriptInterface public boolean isOverlay() { return true; }
        @JavascriptInterface public String overlayMode() { return mode(); }
        @JavascriptInterface public void setKeepScreenOn(boolean on) { }
        @JavascriptInterface public boolean isSplit() { return false; }
        @JavascriptInterface public boolean hasMediaAccess() { return MediaHelper.get(ctx).hasMediaAccess(); }
        @JavascriptInterface public String ttsStatus() { return MediaHelper.get(ctx).ttsStatus(); }
        @JavascriptInterface public boolean isSpeaking() { return MediaHelper.get(ctx).speaking; }

        /** Info radio untuk tampilan di jam (JSON). */
        @JavascriptInterface
        public String radioInfo() { return RadioPlayer.get(ctx).infoJson(); }

        /** Tinggi batang animasi radio (32 angka 0–100, dipisah koma) atau "" kalau tidak ada suara. */
        @JavascriptInterface
        public String radioViz(int delayMs) { return RadioViz.get().levels(Math.max(0, Math.min(1500, delayMs))); }

        @JavascriptInterface
        public void setBrightness(final float level) {
            ui.post(() -> {
                if (!attached || lp == null) return;
                lp.screenBrightness = level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        : Math.max(0.01f, Math.min(1f, level));
                try { wm.updateViewLayout(web, lp); } catch (Exception ignored) { }
            });
        }

        /** Remote sudah diatur oleh aplikasi utama; jam mengambang tidak mengubahnya. */
        @JavascriptInterface
        public void setRemote(boolean enabled, String db, String key, String name) { }

        /** Ketuk jam mengambang → buka aplikasi (pengaturan). */
        @JavascriptInterface
        public void openApp() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(ctx, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    ctx.startActivity(i);
                } catch (Exception ignored) { }
            });
        }
    }

    static Intent overlayPermissionIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
