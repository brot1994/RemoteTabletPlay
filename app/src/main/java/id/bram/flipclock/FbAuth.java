package id.bram.flipclock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Login anonim tablet ke Firebase (tanpa akun Google). Tablet mendapat "uid" sendiri,
 * yang dicatat di acl/&lt;kode&gt;/tablet. Setelah tablet dikunci ke akun Google pemilik,
 * hanya tablet ini, pemilik, dan email yang diizinkan yang bisa membaca/menulis datanya.
 * Hanya dipakai untuk server Firebase yang kunci API-nya dikenal (lihat {@link #apiKeyFor}).
 */
class FbAuth {

    private static final String P_REFRESH = "fb_refresh";
    private static final String P_UID = "fb_uid";

    private static FbAuth instance;

    static synchronized FbAuth get(Context c) {
        if (instance == null) instance = new FbAuth(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private volatile String idToken = "";
    private volatile long expiresAt = 0;
    private volatile String uid = "";
    private volatile long lastFail = 0;
    private String registeredFor = "";

    private FbAuth(Context ctx) {
        this.ctx = ctx;
        uid = prefs().getString(P_UID, "");
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** Kunci API web per server Firebase yang mendukung login. */
    static String apiKeyFor(String dbUrl) {
        if (dbUrl == null) return null;
        if (dbUrl.contains("agi-flipclock-default-rtdb.asia-southeast1.firebasedatabase.app")) {
            return "AIzaSyDgknK6q-X6vLqtuBD6mgiTIEAArP8D1WE";
        }
        return null;
    }

    String uid() { return uid; }

    /** Token yang sudah ada saja (tanpa jaringan); kosong kalau belum ada / hampir habis. */
    String cachedToken() {
        return System.currentTimeMillis() < expiresAt - 60000 ? idToken : "";
    }

    /** Token yang masih berlaku; memanggil jaringan kalau perlu. Jangan dipanggil dari thread utama. */
    synchronized String token(String apiKey) {
        if (apiKey == null) return "";
        if (System.currentTimeMillis() < expiresAt - 5 * 60000 && !idToken.isEmpty()) return idToken;
        if (System.currentTimeMillis() - lastFail < 20000) return idToken;   // jangan membanjiri server saat offline
        try {
            String refresh = prefs().getString(P_REFRESH, "");
            JSONObject r = null;
            if (refresh != null && !refresh.isEmpty()) {
                r = post("https://securetoken.googleapis.com/v1/token?key=" + apiKey,
                        "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                        "application/x-www-form-urlencoded");
                if (r != null && r.has("id_token")) {
                    idToken = r.getString("id_token");
                    expiresAt = System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000L;
                    uid = r.optString("user_id", uid);
                    prefs().edit().putString(P_REFRESH, r.optString("refresh_token", refresh)).putString(P_UID, uid).apply();
                    return idToken;
                }
            }
            // belum pernah login (atau akun anonim hilang) → buat akun anonim baru
            r = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + apiKey,
                    "{\"returnSecureToken\":true}", "application/json");
            if (r != null && r.has("idToken")) {
                idToken = r.getString("idToken");
                expiresAt = System.currentTimeMillis() + r.optLong("expiresIn", 3600) * 1000L;
                uid = r.optString("localId", "");
                prefs().edit().putString(P_REFRESH, r.optString("refreshToken", "")).putString(P_UID, uid).apply();
                registeredFor = "";
                return idToken;
            }
        } catch (Exception ignored) { }
        lastFail = System.currentTimeMillis();
        return cachedToken();
    }

    /** Tambahkan ?auth=… ke alamat Firebase kalau server ini mendukung login. Jangan dari thread utama. */
    String withAuth(String url) {
        String key = apiKeyFor(url);
        if (key == null) return url;
        String t = token(key);
        if (t.isEmpty()) return url;
        return url + (url.contains("?") ? "&" : "?") + "auth=" + t;
    }

    /** Catat uid tablet ini di acl/&lt;kode&gt;/tablet (sekali per kode). Jangan dari thread utama. */
    void registerTablet(String dbBase, String clockKey) {
        String apiKey = apiKeyFor(dbBase);
        if (apiKey == null || clockKey == null || clockKey.length() < 20) return;
        String t = token(apiKey);
        if (t.isEmpty() || uid.isEmpty()) return;
        String mark = clockKey + "|" + uid;
        if (mark.equals(registeredFor)) return;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(dbBase + "/acl/" + clockKey + "/tablet.json?auth=" + t).openConnection();
            con.setRequestMethod("PUT");
            con.setDoOutput(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            OutputStream os = con.getOutputStream();
            os.write(JSONObject.quote(uid).getBytes(StandardCharsets.UTF_8));
            os.close();
            if (con.getResponseCode() < 300) registeredFor = mark;
        } catch (Exception ignored) {
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static JSONObject post(String url, String body, String type) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Content-Type", type);
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = con.getResponseCode();
            InputStream in = code < 300 ? con.getInputStream() : con.getErrorStream();
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            return code < 300 ? o : null;
        } finally {
            con.disconnect();
        }
    }
}
