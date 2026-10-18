package id.bram.flipclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layanan latar belakang: menerima perintah remote (musik, volume, pengumuman suara)
 * lewat Firebase, dan mengirim status musik + heartbeat — tetap jalan walau jam tidak tampil.
 */
public class RemoteService extends Service {

    static final String CHANNEL = "tabletplay_remote";
    static final String VER = "2.7";

    static final String P_ENABLED = "r_enabled";
    static final String P_DB = "r_db";
    static final String P_KEY = "r_key";
    static final String P_NAME = "r_name";
    static final String P_SRC = "r_src";
    static final String P_PLNAME = "r_plname";
    static final String P_LASTCMD = "r_lastcmd";
    static final String P_LASTCMD_L = "r_lastcmd_l";
    static final String P_LKEY = "r_lkey";
    static final String P_KEEPFRONT = "r_keepfront";

    private volatile boolean running = false;
    private volatile HttpURLConnection sseConn = null;
    private volatile int generation = 0;
    private Handler ui;
    private MediaHelper media;
    private RadioPlayer radio;
    private String lastNowKey = "";
    private String lastArt = "";

    static void start(Context c) {
        Intent i = new Intent(c, RemoteService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    /** Dipanggil saat pengaturan remote berubah: sambung ulang. */
    static void restart(Context c) {
        Intent i = new Intent(c, RemoteService.class);
        i.putExtra("restart", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        media = MediaHelper.get(this);
        radio = RadioPlayer.get(this);
        startForeground(71, buildNotification());
        running = true;
        ClockOverlay.get(this).apply();
        startSse();
        ui.postDelayed(nowTick, 3000);
        ui.postDelayed(hbTick, 2000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("restart", false)) {
            lastNowKey = "";
            startSse();
            ui.removeCallbacks(hbTick);
            ui.postDelayed(hbTick, 500);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        generation++;
        closeSse();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ClockOverlay.get(this).onConfigChanged();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Remote Tablet Play", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Tablet Play aktif")
                .setContentText("Siap menerima musik & pengumuman dari remote")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    // ------------------------------------------------------------------ config
    private String base() {
        String db = prefs().getString(P_DB, "");
        if (db == null) return "";
        db = db.trim();
        while (db.endsWith("/")) db = db.substring(0, db.length() - 1);
        return db;
    }

    private String key() { return prefs().getString(P_KEY, ""); }

    private boolean enabled() {
        return prefs().getBoolean(P_ENABLED, false) && !base().isEmpty() && key().length() >= 20;
    }

    private String path(String part) { return base() + "/clocks/" + key() + "/" + part + ".json"; }

    // ------------------------------------------------------------------ kode akses terbatas
    /** Kode kedua: pemegangnya hanya boleh kirim pesan & pengumuman suara. */
    String limitedKey() {
        String k = prefs().getString(P_LKEY, "");
        if (k == null || k.length() < 20) {
            k = newKey();
            prefs().edit().putString(P_LKEY, k).apply();
        }
        return k;
    }

    static String newKey() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private String pathFor(String k, String part) { return base() + "/clocks/" + k + "/" + part + ".json"; }

    // ------------------------------------------------------------------ SSE (perintah)
    private void closeSse() {
        HttpURLConnection c = sseConn;
        sseConn = null;
        if (c != null) new Thread(() -> { try { c.disconnect(); } catch (Exception ignored) { } }).start();
        HttpURLConnection c2 = sseConnL;
        sseConnL = null;
        if (c2 != null) new Thread(() -> { try { c2.disconnect(); } catch (Exception ignored) { } }).start();
    }

    private volatile HttpURLConnection sseConnL = null;

    private void startSse() {
        final int gen = ++generation;
        closeSse();
        startSseLoop(gen, false);
        startSseLoop(gen, true);
    }

    /** limited=false → kode penuh, limited=true → kode pesan saja. */
    private void startSseLoop(final int gen, final boolean limited) {
        new Thread(() -> {
            while (running && gen == generation) {
                if (!enabled()) { sleep(5000); continue; }
                HttpURLConnection con = null;
                try {
                    String k = limited ? limitedKey() : key();
                    URL u = new URL(pathFor(k, "cmd"));
                    con = (HttpURLConnection) u.openConnection();
                    con.setRequestProperty("Accept", "text/event-stream");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(90000);   // Firebase kirim keep-alive tiap ±30 detik
                    con.setInstanceFollowRedirects(true);
                    if (limited) sseConnL = con; else sseConn = con;
                    BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                    String event = null;
                    StringBuilder data = new StringBuilder();
                    boolean first = true;
                    String line;
                    while (running && gen == generation && (line = r.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                        } else if (line.isEmpty()) {
                            if ("put".equals(event)) {
                                handleSse(data.toString(), first, limited, k);
                                first = false;
                            } else if ("cancel".equals(event) || "auth_revoked".equals(event)) {
                                break;
                            }
                            event = null;
                            data.setLength(0);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
                }
                if (running && gen == generation) sleep(5000);
            }
        }).start();
    }

    private void handleSse(String json, boolean first, final boolean limited, final String k) {
        try {
            JSONObject msg = new JSONObject(json);
            if (!"/".equals(msg.optString("path"))) return;
            JSONObject c = msg.optJSONObject("data");
            if (c == null) return;
            final String id = c.optString("id", "");
            String lastKey = limited ? P_LASTCMD_L : P_LASTCMD;
            if (id.isEmpty() || id.equals(prefs().getString(lastKey, ""))) return;
            long ts = c.optLong("ts", 0);
            long age = ts > 0 ? Math.abs(System.currentTimeMillis() - ts) : 0;
            prefs().edit().putString(lastKey, id).apply();
            // perintah lama (tablet sempat offline) jangan dijalankan
            if ((first && age > 120000) || age > 600000) return;
            final JSONObject cmd = c;
            ui.post(() -> {
                String type = cmd.optString("type");
                String[] res;
                if (limited && !"msg".equals(type) && !"speak".equals(type)) {
                    res = new String[]{"fail", "Akses terbatas: hanya pesan & pengumuman"};
                } else {
                    res = runCmd(cmd);
                }
                ack(k, id, "ok".equals(res[0]), res[1]);
                ui.postDelayed(nowTick, 1200);
            });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ perintah
    /** @return {ok|fail, pesan} */
    private String[] runCmd(JSONObject c) {
        String type = c.optString("type");
        SharedPreferences p = prefs();
        try {
            switch (type) {
                case "speak": {
                    String text = c.optString("text", "").trim();
                    if (text.length() > 200) text = text.substring(0, 200);
                    if (text.isEmpty()) return new String[]{"fail", "Teks kosong"};
                    String st = media.ttsStatus();
                    if ("not-ready".equals(st)) return new String[]{"fail", "Mesin suara tablet belum siap"};
                    media.speak(text, c.optBoolean("bell", true), c.optInt("times", 1) == 2 ? 2 : 1);
                    return new String[]{"ok", "no-lang".equals(st) ? "Dibacakan, tapi suara Indonesia belum terpasang" : "Dibacakan"};
                }
                case "msg": {
                    String text = c.optString("text", "").trim();
                    if (text.length() > 150) text = text.substring(0, 150);
                    boolean show = c.optBoolean("show", true) && !text.isEmpty();
                    JSONObject patch = new JSONObject();
                    patch.put("customText", text);
                    patch.put("custom", show);
                    JSONObject sv = new JSONObject();
                    sv.put(".sv", "timestamp");
                    patch.put("updatedAt", sv);
                    patch.put("by", "remote");
                    patchJson(path("config"), patch.toString());
                    String extra = "";
                    if (c.optBoolean("speak", false) && !text.isEmpty()) {
                        String st = media.ttsStatus();
                        media.speak(text, c.optBoolean("bell", true), c.optInt("times", 1) == 2 ? 2 : 1);
                        extra = "no-lang".equals(st) ? " dan dibacakan (suara Indonesia belum terpasang)" : " dan dibacakan";
                    }
                    return new String[]{"ok", (show ? "Pesan tampil" : "Pesan disembunyikan") + extra};
                }
                case "newLimitedKey": {
                    prefs().edit().putString(P_LKEY, newKey()).apply();
                    heartbeat();
                    ui.postDelayed(this::startSse, 1500);
                    return new String[]{"ok", "Kode akses pesan dibuat ulang"};
                }
                case "media": {
                    if ("radio".equals(p.getString(P_SRC, ""))) {
                        String r = radio.command(c.optString("cmd"));
                        return new String[]{r.startsWith("ok") ? "ok" : "fail", r};
                    }
                    String r = media.command(p.getString(P_SRC, "youtube"), c.optString("cmd"));
                    return new String[]{r.startsWith("ok") ? "ok" : "fail", r};
                }
                case "volume": {
                    int pct = Math.max(0, Math.min(100, c.optInt("pct", 50)));
                    media.setVolumePercent(pct);
                    return new String[]{"ok", "Volume " + pct + "%"};
                }
                case "play": {
                    String src = srcOf(c.optString("src"));
                    if ("radio".equals(src)) {
                        String link = c.optString("link", "").trim();
                        if (!link.matches("(?i)^https?://.+")) return new String[]{"fail", "Link radio harus diawali http:// atau https://"};
                        String nm = c.optString("name", "Radio");
                        p.edit().putString(P_SRC, "radio").putString(P_PLNAME, nm).apply();
                        radio.play(nm, link);
                        return new String[]{"ok", "Memutar " + nm};
                    }
                    radio.stop();
                    String[] n = normalize(src, c.optString("link"));
                    if (n[0].isEmpty()) return new String[]{"fail", "Link kosong"};
                    p.edit().putString(P_SRC, src).putString(P_PLNAME, c.optString("name", "")).apply();
                    String r = "spotify".equals(src)
                            ? media.playSpotifyMethod(n[0], n[1], c.optString("name", ""), c.optString("method", "auto"))
                            : media.playLink(src, n[0], n[1]);
                    keepClockVisible();
                    String warn = media.isSplit() ? "" : " (layar tablet belum terbagi)";
                    return new String[]{r.startsWith("ok") ? "ok" : "fail", r + warn};
                }
                case "open": {
                    String src = srcOf(c.optString("src"));
                    if ("radio".equals(src)) {
                        p.edit().putString(P_SRC, "radio").apply();
                        if (radio.url().isEmpty()) return new String[]{"ok", "Pilih stasiun dari daftar"};
                        radio.resume();
                        return new String[]{"ok", "Melanjutkan " + radio.name()};
                    }
                    radio.stop();
                    p.edit().putString(P_SRC, src).putString(P_PLNAME, "").apply();
                    String home = "spotify".equals(src) ? "https://open.spotify.com/"
                            : "ytmusic".equals(src) ? "https://music.youtube.com/" : "https://m.youtube.com/";
                    String r = media.openAdjacent(src, home);
                    keepClockVisible();
                    return new String[]{r.startsWith("ok") ? "ok" : "fail", r};
                }
                case "clock": {
                    showClock();
                    return new String[]{"ok", "Jam dimunculkan"};
                }
                case "layout": {
                    String mode = c.optString("mode", "off");
                    ClockOverlay ov = ClockOverlay.get(this);
                    String r = ov.setMode(mode, c.optInt("pct", 0));
                    if ("off".equals(ov.mode())) {
                        showClock();
                        return new String[]{"ok", "Jam layar penuh"};
                    }
                    // aplikasi utama sedang tampil → pindahkan ke belakang agar jam mengambang terlihat
                    android.app.Activity a = media.activity;
                    if (a != null && MainActivity.visible) {
                        a.runOnUiThread(() -> a.moveTaskToBack(true));
                    }
                    return new String[]{"ok".equals(r) ? "ok" : "fail",
                            "ok".equals(r) ? ("Jam mengambang " + (ClockOverlay.label(ov.mode()) + " ") + ov.pct() + "%") : r};
                }
                default:
                    return new String[]{"fail", "Perintah tidak dikenal"};
            }
        } catch (Exception e) {
            return new String[]{"fail", "Error: " + e.getMessage()};
        }
    }

    private static String srcOf(String s) {
        return "ytmusic".equals(s) || "spotify".equals(s) || "radio".equals(s) ? s : "youtube";
    }

    /** Setelah membuka aplikasi musik, pastikan jam tetap tampil di sisi sebelah. */
    private void keepClockVisible() {
        if (!"off".equals(ClockOverlay.get(this).mode())) return;   // jam mengambang selalu tampil
        if (!prefs().getBoolean(P_KEEPFRONT, true)) return;
        ui.postDelayed(() -> { if (!MainActivity.visible) showClock(); }, 3000);
        ui.postDelayed(() -> { if (!MainActivity.visible) showClock(); }, 7000);
    }

    private void showClock() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if (media.lastSplit) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ link
    private static String qp(String url, String name) {
        Matcher m = Pattern.compile("[?&#]" + name + "=([^&#]+)").matcher(url);
        if (!m.find()) return "";
        try { return URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception e) { return m.group(1); }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** @return {link, spotifyUri} */
    static String[] normalize(String src, String link) {
        link = link == null ? "" : link.trim();
        if (link.isEmpty()) return new String[]{"", ""};
        if ("spotify".equals(src)) {
            Matcher m = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(playlist|album|track|artist|show|episode)/([A-Za-z0-9]+)").matcher(link);
            if (m.find()) return new String[]{"https://open.spotify.com/" + m.group(1) + "/" + m.group(2), "spotify:" + m.group(1) + ":" + m.group(2)};
            Matcher u = Pattern.compile("^spotify:(playlist|album|track|artist|show|episode):([A-Za-z0-9]+)").matcher(link);
            if (u.find()) return new String[]{"https://open.spotify.com/" + u.group(1) + "/" + u.group(2), u.group(0)};
            return new String[]{link, ""};
        }
        String list = qp(link, "list");
        String v = qp(link, "v");
        if (v.isEmpty()) {
            Matcher yb = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{6,})").matcher(link);
            if (yb.find()) v = yb.group(1);
        }
        if (list.isEmpty()) {
            Matcher br = Pattern.compile("music\\.youtube\\.com/browse/VL([A-Za-z0-9_-]+)").matcher(link);
            if (br.find()) list = br.group(1);
        }
        String host = "ytmusic".equals(src) ? "https://music.youtube.com" : "https://m.youtube.com";
        if (!list.isEmpty()) return new String[]{host + "/watch?list=" + enc(list) + (v.isEmpty() ? "" : "&v=" + enc(v)), ""};
        if (!v.isEmpty()) return new String[]{host + "/watch?v=" + enc(v), ""};
        return new String[]{link, ""};
    }

    // ------------------------------------------------------------------ status ke Firebase
    private final Runnable nowTick = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (running) {
                try { pushNow(); } catch (Exception ignored) { }
                ui.postDelayed(this, 2500);
            }
        }
    };

    private final Runnable hbTick = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (running) {
                heartbeat();
                ui.postDelayed(this, 60000);
            }
        }
    };

    private void pushNow() throws Exception {
        if (!enabled()) return;
        SharedPreferences p = prefs();
        String want = p.getString(P_SRC, "youtube");
        JSONObject info = new JSONObject(media.info(want));
        if ("radio".equals(want)) {
            JSONObject r = new JSONObject();
            r.put("vol", info.optInt("vol", -1));
            r.put("access", info.optBoolean("access", false));
            r.put("src", "radio");
            r.put("state", radio.state());
            String song = radio.songTitle();
            r.put("title", song.isEmpty() ? radio.name() : song);
            r.put("artist", song.isEmpty() ? "Radio" : radio.name());
            r.put("acts", "radio");
            r.put("spres", radio.error());
            info = r;
        }
        JSONObject now = new JSONObject();
        now.put("src", info.optString("src", want).isEmpty() ? want : info.optString("src", want));
        now.put("want", want);
        now.put("playlist", p.getString(P_PLNAME, ""));
        now.put("state", info.optString("state", "none"));
        now.put("title", info.optString("title", ""));
        now.put("artist", info.optString("artist", ""));
        now.put("dur", info.optLong("dur", 0));
        now.put("pos", info.optLong("pos", 0));
        now.put("access", info.optBoolean("access", false));
        now.put("split", media.isSplit());
        now.put("vol", info.optInt("vol", -1));
        now.put("tts", media.ttsStatus());
        now.put("speaking", media.speaking);
        now.put("acts", info.optString("acts", ""));
        now.put("spres", info.optString("spres", ""));
        now.put("queue", info.optString("queue", ""));
        ClockOverlay ov = ClockOverlay.get(this);
        now.put("clock", MainActivity.visible || ov.isShowing());
        now.put("layout", ov.mode());
        now.put("pct", ov.pct());
        now.put("overlayPerm", ov.canDraw());
        now.put("ver", VER);
        String art = info.optString("art", "");
        String k = now.toString();
        if (k.equals(lastNowKey) && art.equals(lastArt)) return;
        lastNowKey = k;
        lastArt = art;
        now.put("art", art);
        JSONObject sv = new JSONObject();
        sv.put(".sv", "timestamp");
        now.put("at", sv);
        put(path("now"), now.toString(), () -> lastNowKey = "");
    }

    private void heartbeat() {
        if (!enabled()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("name", prefs().getString(P_NAME, "Tablet"));
            JSONObject sv = new JSONObject();
            sv.put(".sv", "timestamp");
            o.put("lastSeen", sv);
            o.put("ver", VER);
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            o.put("batt", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            o.put("charging", bm.isCharging());
            put(path("status"), o.toString(), null);
            // status untuk pemegang akses terbatas + kode terbatas untuk pemegang akses penuh
            JSONObject ol = new JSONObject(o.toString());
            ol.put("lastSeen", sv);
            ol.put("limited", true);
            put(pathFor(limitedKey(), "status"), ol.toString(), null);
            JSONObject share = new JSONObject();
            share.put("limitedKey", limitedKey());
            put(path("share"), share.toString(), null);
        } catch (Exception ignored) { }
    }

    private void ack(String k, String id, boolean ok, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("ok", ok);
            o.put("msg", msg == null ? "" : msg);
            JSONObject sv = new JSONObject();
            sv.put(".sv", "timestamp");
            o.put("at", sv);
            put(pathFor(k, "cmdAck"), o.toString(), null);
        } catch (Exception ignored) { }
    }

    private void put(final String url, final String body, final Runnable onFail) {
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(15000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = con.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
                int code = con.getResponseCode();
                if (code >= 300 && onFail != null) ui.post(onFail);
            } catch (Exception e) {
                if (onFail != null) ui.post(onFail);
            } finally {
                if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
            }
        }).start();
    }

    private void patchJson(final String url, final String body) {
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                con.setDoOutput(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(15000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = con.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
                con.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
            }
        }).start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
