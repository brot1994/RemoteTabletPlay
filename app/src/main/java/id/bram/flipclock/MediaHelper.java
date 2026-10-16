package id.bram.flipclock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/** Kontrol musik aplikasi lain, layar terbagi, volume, dan suara pengumuman (TTS). */
class MediaHelper {

    static final String PKG_YT = "com.google.android.youtube";
    static final String PKG_YTM = "com.google.android.apps.youtube.music";
    static final String PKG_SPOTIFY = "com.spotify.music";
    static final String PKG_GOOGLE_TTS = "com.google.android.tts";

    interface SpeakListener { void onSpeakState(boolean speaking); }

    private final Activity act;
    private final AudioManager audio;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private int ttsLangResult = -99;
    private String ttsEngine = "";
    private AudioFocusRequest focusReq;
    private SpeakListener speakListener;

    private String lastArtKey = null;
    private String lastArtB64 = "";

    MediaHelper(Activity act) {
        this.act = act;
        this.audio = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        initTts();
    }

    void setSpeakListener(SpeakListener l) { this.speakListener = l; }

    void shutdown() {
        if (tts != null) tts.shutdown();
    }

    // ------------------------------------------------------------------ permissions
    boolean hasMediaAccess() {
        String s = Settings.Secure.getString(act.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(act.getPackageName());
    }

    void openMediaAccessSettings() {
        try {
            act.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception ignored) { }
    }

    boolean installed(String pkg) {
        try { act.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    static String pkgFor(String source) {
        if ("ytmusic".equals(source)) return PKG_YTM;
        if ("spotify".equals(source)) return PKG_SPOTIFY;
        return PKG_YT;
    }

    static String sourceFor(String pkg) {
        if (PKG_YTM.equals(pkg)) return "ytmusic";
        if (PKG_SPOTIFY.equals(pkg)) return "spotify";
        if (PKG_YT.equals(pkg)) return "youtube";
        return "";
    }

    // ------------------------------------------------------------------ open in split screen
    /** Buka link di aplikasi sumber, di sisi sebelah kalau layar sedang terbagi. */
    String openAdjacent(String source, String link) {
        String pkg = pkgFor(source);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        if (installed(pkg)) i.setPackage(pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (act.isInMultiWindowMode()) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try {
            act.startActivity(i);
            return "ok";
        } catch (Exception e) {
            // aplikasi tidak bisa membuka link ini: coba tanpa paket tertentu
            try {
                i.setPackage(null);
                act.startActivity(i);
                return "ok-generic";
            } catch (Exception e2) {
                return "gagal: " + e2.getClass().getSimpleName();
            }
        }
    }

    // ------------------------------------------------------------------ media sessions
    private List<MediaController> sessions() {
        try {
            MediaSessionManager msm = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return msm.getActiveSessions(new ComponentName(act, MediaListener.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** Pilih pemutar: yang cocok dengan sumber, lalu yang sedang main, lalu yang pertama. */
    private MediaController pick(String source) {
        List<MediaController> list = sessions();
        if (list == null || list.isEmpty()) return null;
        String want = source == null || source.isEmpty() ? null : pkgFor(source);
        if (want != null) {
            for (MediaController c : list) if (want.equals(c.getPackageName())) return c;
        }
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return want == null ? list.get(0) : null;
    }

    String command(String source, String cmd) {
        MediaController c = pick(source);
        if (c == null) {
            // tanpa izin / tanpa sesi: pakai tombol media global
            int code = 0;
            if ("play".equals(cmd)) code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
            else if ("pause".equals(cmd)) code = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
            else if ("toggle".equals(cmd)) code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            else if ("next".equals(cmd)) code = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
            else if ("prev".equals(cmd)) code = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            if (code == 0) return "perintah tidak dikenal";
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
            return "ok-global";
        }
        MediaController.TransportControls t = c.getTransportControls();
        switch (cmd) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "toggle": {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                break;
            }
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            default: return "perintah tidak dikenal";
        }
        return "ok";
    }

    /**
     * Putar link di sumber. Kalau aplikasi sumber sudah aktif dan mendukung "putar dari link",
     * kirim langsung tanpa membuka ulang; kalau tidak, buka di sisi sebelah.
     * Spotify memakai cara bertingkat karena tidak selalu menuruti perintah langsung.
     */
    String playLink(String source, String link, String spotifyUri) {
        if ("spotify".equals(source)) return playSpotify(link, spotifyUri);
        MediaController c = pick(source);
        String pkg = pkgFor(source);
        if (c != null && pkg.equals(c.getPackageName()) && c.getPlaybackState() != null) {
            long a = c.getPlaybackState().getActions();
            try {
                if ((a & PlaybackState.ACTION_PLAY_FROM_URI) != 0) {
                    c.getTransportControls().playFromUri(Uri.parse(link), new Bundle());
                    return "ok-uri";
                }
            } catch (Exception ignored) { }
        }
        return openAdjacent(source, link);
    }

    private String lastSpotifyResult = "";

    String lastSpotifyResult() { return lastSpotifyResult; }

    private MediaController spotifySession() {
        List<MediaController> list = sessions();
        if (list == null) return null;
        for (MediaController c : list) if (PKG_SPOTIFY.equals(c.getPackageName())) return c;
        return null;
    }

    private static String titleOf(MediaController c) {
        if (c == null || c.getMetadata() == null) return "";
        String t = c.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE);
        return t == null ? "" : t;
    }

    private static boolean isPlaying(MediaController c) {
        return c != null && c.getPlaybackState() != null && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
    }

    /**
     * 1) Sesi Spotify ada → playFromUri / playFromMediaId, cek 3,5 detik kemudian.
     * 2) Gagal / tidak ada sesi → buka link playlist di sisi sebelah, lalu coba lagi playFromUri,
     *    lalu tekan play. Hasil akhir dicatat di lastSpotifyResult.
     */
    private String playSpotify(final String link, final String uri) {
        final String target = (uri != null && !uri.isEmpty()) ? uri : link;
        final MediaController c = spotifySession();
        final String before = titleOf(c);
        lastSpotifyResult = "mencoba";
        if (c != null && c.getPlaybackState() != null) {
            long a = c.getPlaybackState().getActions();
            boolean sent = false;
            try {
                if ((a & PlaybackState.ACTION_PLAY_FROM_URI) != 0) {
                    c.getTransportControls().playFromUri(Uri.parse(target), new Bundle());
                    sent = true;
                } else if ((a & PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) != 0) {
                    c.getTransportControls().playFromMediaId(target, new Bundle());
                    sent = true;
                }
            } catch (Exception ignored) { }
            if (sent) {
                ui.postDelayed(() -> {
                    MediaController c2 = spotifySession();
                    if (isPlaying(c2) && !titleOf(c2).equals(before)) {
                        lastSpotifyResult = "ok-langsung";
                    } else {
                        spotifyFallback(link, target, before);
                    }
                }, 3500);
                return "ok-mencoba";
            }
        }
        spotifyFallback(link, target, before);
        return "ok-membuka";
    }

    private void spotifyFallback(String link, final String target, final String before) {
        lastSpotifyResult = "membuka-playlist";
        // buka playlist-nya di aplikasi Spotify (sisi sebelah kalau layar terbagi)
        String r = openAdjacent("spotify", target.startsWith("spotify:") ? target : link);
        if (!r.startsWith("ok")) r = openAdjacent("spotify", link);
        ui.postDelayed(() -> {
            MediaController c = spotifySession();
            if (c == null) { command("spotify", "play"); lastSpotifyResult = "tombol-media"; return; }
            if (isPlaying(c) && !titleOf(c).equals(before)) { lastSpotifyResult = "ok-buka"; return; }
            try {
                long a = c.getPlaybackState() != null ? c.getPlaybackState().getActions() : 0;
                if ((a & PlaybackState.ACTION_PLAY_FROM_URI) != 0) c.getTransportControls().playFromUri(Uri.parse(target), new Bundle());
                else c.getTransportControls().play();
            } catch (Exception e) {
                c.getTransportControls().play();
            }
            lastSpotifyResult = "buka+play";
        }, 4000);
        ui.postDelayed(() -> {
            MediaController c = spotifySession();
            if (c != null && !isPlaying(c)) {
                c.getTransportControls().play();
                lastSpotifyResult = "buka+play2";
            }
        }, 8000);
    }

    /** Info pemutar saat ini dalam bentuk JSON. */
    String info(String source) {
        JSONObject o = new JSONObject();
        try {
            o.put("access", hasMediaAccess());
            o.put("split", act.isInMultiWindowMode());
            o.put("vol", volumePercent());
            MediaController c = pick(source);
            if (c == null) c = pick(null);
            if (c != null) {
                PlaybackState s = c.getPlaybackState();
                MediaMetadata md = c.getMetadata();
                o.put("pkg", c.getPackageName());
                o.put("src", sourceFor(c.getPackageName()));
                o.put("state", s == null ? "none" : stateName(s.getState()));
                if (md != null) {
                    String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    o.put("title", title == null ? "" : title);
                    o.put("artist", artist == null ? "" : artist);
                    o.put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                    String key = c.getPackageName() + "|" + title;
                    if (!key.equals(lastArtKey)) {
                        lastArtKey = key;
                        lastArtB64 = artBase64(md);
                    }
                    o.put("art", lastArtB64);
                }
                if (s != null) {
                    o.put("pos", s.getPosition());
                    long a = s.getActions();
                    StringBuilder sb = new StringBuilder();
                    if ((a & PlaybackState.ACTION_PLAY_FROM_URI) != 0) sb.append("uri ");
                    if ((a & PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) != 0) sb.append("mediaId ");
                    if ((a & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0) sb.append("search ");
                    if ((a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) sb.append("next ");
                    o.put("acts", sb.toString().trim());
                }
                o.put("spres", lastSpotifyResult);
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    private String artBase64(MediaMetadata md) {
        try {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b == null) return "";
            int w = b.getWidth(), h = b.getHeight();
            float k = 120f / Math.max(w, h);
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 70, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stateName(int st) {
        switch (st) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING: return "loading";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_ERROR: return "error";
            default: return "none";
        }
    }

    // ------------------------------------------------------------------ volume
    int volumePercent() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(cur * 100f / max);
    }

    void setVolumePercent(int pct) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int idx = Math.round(Math.max(0, Math.min(100, pct)) * max / 100f);
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ TTS
    private void initTts() {
        TextToSpeech.OnInitListener onInit = status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsLangResult = tts.setLanguage(new Locale("id", "ID"));
                ttsReady = true;
                try { ttsEngine = tts.getDefaultEngine(); } catch (Exception ignored) { }
            }
        };
        // utamakan mesin suara Google (punya bahasa Indonesia)
        if (installed(PKG_GOOGLE_TTS)) {
            tts = new TextToSpeech(act, onInit, PKG_GOOGLE_TTS);
            ttsEngine = PKG_GOOGLE_TTS;
        } else {
            tts = new TextToSpeech(act, onInit);
        }
    }

    /** "ok", "no-lang" (suara Indonesia belum terpasang) atau "not-ready". */
    String ttsStatus() {
        if (!ttsReady) return "not-ready";
        return ttsLangResult >= 0 ? "ok" : "no-lang";
    }

    void speak(final String text, final boolean bell, final int times) {
        if (!ttsReady || text == null || text.trim().isEmpty()) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        if (Build.VERSION.SDK_INT >= 26) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build();
            audio.requestAudioFocus(focusReq);
        }
        tts.setAudioAttributes(attrs);
        tts.setSpeechRate(0.95f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { if ("last".equals(id)) finishSpeak(); }
            @Override public void onError(String id) { finishSpeak(); }
        });
        if (speakListener != null) ui.post(() -> speakListener.onSpeakState(true));
        new Thread(() -> {
            if (bell) playBell();
            ui.post(() -> {
                int n = Math.max(1, Math.min(3, times));
                for (int k = 1; k <= n; k++) {
                    tts.speak(text, k == 1 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), k == n ? "last" : "u" + k);
                    if (k < n) tts.playSilentUtterance(800, TextToSpeech.QUEUE_ADD, "gap" + k);
                }
            });
        }).start();
    }

    private void finishSpeak() {
        if (Build.VERSION.SDK_INT >= 26 && focusReq != null) audio.abandonAudioFocusRequest(focusReq);
        if (speakListener != null) ui.post(() -> speakListener.onSpeakState(false));
    }

    /** "Ding-dong" dari dua nada. Dijalankan di thread latar. */
    private void playBell() {
        int sr = 44100;
        double[][] notes = {{880, 0.55}, {659.25, 0.95}};
        int total = 0;
        for (double[] n : notes) total += (int) (sr * n[1]);
        short[] buf = new short[total];
        int pos = 0;
        for (double[] n : notes) {
            int len = (int) (sr * n[1]);
            for (int i = 0; i < len; i++) {
                double env = Math.exp(-3.2 * i / (double) len);
                double v = Math.sin(2 * Math.PI * n[0] * i / sr) * 0.7 + Math.sin(2 * Math.PI * n[0] * 2 * i / sr) * 0.15;
                buf[pos++] = (short) (v * env * 0.55 * Short.MAX_VALUE);
            }
        }
        try {
            AudioTrack at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(buf.length * 2).build();
            at.write(buf, 0, buf.length);
            at.play();
            Thread.sleep((long) (1000.0 * total / sr) + 200);
            at.release();
        } catch (Exception ignored) { }
    }
}
