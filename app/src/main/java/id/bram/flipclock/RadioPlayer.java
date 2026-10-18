package id.bram.flipclock;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.metadata.icy.IcyHeaders;
import androidx.media3.extractor.metadata.icy.IcyInfo;

/**
 * Pemutar radio online di dalam aplikasi (tanpa aplikasi lain).
 * Mendukung siaran MP3 / AAC (Icecast/Shoutcast) dan HLS (.m3u8).
 * Saat pengumuman dibacakan, suara radio otomatis mengecil (audio focus).
 */
class RadioPlayer {

    private static RadioPlayer instance;

    static synchronized RadioPlayer get(Context c) {
        if (instance == null) instance = new RadioPlayer(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;

    private String name = "", url = "";
    private String songTitle = "";
    private String error = "";
    private int retries = 0;
    private boolean wantPlay = false;

    private RadioPlayer(Context ctx) { this.ctx = ctx; }

    private ExoPlayer player() {
        if (player != null) return player;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android) TabletPlay/1.7")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000);
        // hanya renderer audio + metadata (judul lagu); audio juga dikirim ke penganalisis animasi
        RenderersFactory rf = (handler, videoListener, audioListener, textOutput, metadataOutput) -> new Renderer[]{
                new MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audioListener,
                        new DefaultAudioSink.Builder(ctx)
                                .setAudioProcessors(new AudioProcessor[]{new TeeAudioProcessor(RadioViz.get())})
                                .build()),
                new MetadataRenderer(metadataOutput, handler.getLooper())
        };
        player = new ExoPlayer.Builder(ctx, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(false)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { error = ""; retries = 0; }
                if (state == Player.STATE_ENDED && wantPlay) retryLater();
            }

            @Override public void onPlayerError(PlaybackException e) {
                error = friendly(e);
                if (wantPlay) retryLater();
            }

            @Override public void onMetadata(Metadata md) {
                for (int i = 0; i < md.length(); i++) {
                    Metadata.Entry en = md.get(i);
                    if (en instanceof IcyInfo) {
                        String t = ((IcyInfo) en).title;
                        if (t != null) songTitle = clean(t);
                    } else if (en instanceof IcyHeaders) {
                        // nama dari server tidak dipakai — nama stasiun dari daftar lebih rapi
                    }
                }
            }

            @Override public void onMediaMetadataChanged(MediaMetadata m) {
                if (m.title != null && m.title.length() > 0) songTitle = clean(m.title.toString());
            }
        });
        return player;
    }

    private static String clean(String t) {
        t = t.trim();
        if (t.equals("-") || t.equalsIgnoreCase("unknown") || t.equalsIgnoreCase("live")) return "";
        return t;
    }

    private static String friendly(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Tidak bisa terhubung ke server radio";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "Link radio tidak aktif lagi";
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
                return "Link http diblokir";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "Format link tidak didukung (pakai link siaran langsung, bukan halaman web)";
            default:
                return "Gagal memutar (" + e.getErrorCodeName() + ")";
        }
    }

    private void retryLater() {
        if (retries >= 5) { wantPlay = false; return; }
        retries++;
        ui.postDelayed(() -> {
            if (!wantPlay || player == null) return;
            player.prepare();
            player.play();
        }, 3000L * retries);
    }

    /** Putar stasiun. Dipanggil di thread utama. */
    void play(String stationName, String link) {
        name = stationName == null ? "" : stationName;
        url = link;
        songTitle = "";
        error = "";
        retries = 0;
        wantPlay = true;
        RadioViz.get().clear();
        startInfo();
        ExoPlayer p = player();
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(link));
        String low = link.toLowerCase();
        if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        p.setMediaItem(b.build());
        p.prepare();
        p.play();
    }

    void resume() {
        if (url.isEmpty()) return;
        wantPlay = true;
        ExoPlayer p = player();
        if (p.getPlaybackState() == Player.STATE_IDLE || p.getPlaybackState() == Player.STATE_ENDED) {
            play(name, url);
            return;
        }
        // siaran langsung: lompat ke posisi terbaru setelah jeda
        if (p.isCurrentMediaItemLive()) p.seekToDefaultPosition();
        p.play();
    }

    void pause() {
        wantPlay = false;
        if (player != null) player.pause();
    }

    /** Hentikan total (saat pindah ke YouTube / Spotify). */
    void stop() {
        wantPlay = false;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    boolean active() { return wantPlay || (player != null && player.isPlaying()); }

    String toggle() {
        if (player != null && (player.isPlaying() || wantPlay)) { pause(); return "ok-pause"; }
        if (url.isEmpty()) return "Belum ada stasiun dipilih";
        resume();
        return "ok-play";
    }

    String command(String cmd) {
        switch (cmd) {
            case "play": if (url.isEmpty()) return "Belum ada stasiun dipilih"; resume(); return "ok";
            case "pause": pause(); return "ok";
            case "toggle": return toggle();
            default: return "Pilih stasiun berikutnya dari daftar";
        }
    }

    String state() {
        if (player == null || url.isEmpty()) return "none";
        if (!error.isEmpty() && !player.isPlaying()) return wantPlay ? "buffering" : "error";
        if (player.isPlaying()) return "playing";
        if (wantPlay) return "buffering";
        return "paused";
    }

    /** Untuk tampilan di jam. Dipanggil dari thread JavaScript → nilai disalin dari thread utama. */
    private volatile String info = "{\"active\":false}";

    String infoJson() { return info; }

    private final Runnable infoTick = new Runnable() {
        @Override public void run() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                String st = state();
                o.put("active", "playing".equals(st) || "buffering".equals(st));
                o.put("state", st);
                o.put("name", name);
                o.put("title", songTitle);
                o.put("error", error);
                info = o.toString();
            } catch (Exception ignored) { }
            ui.postDelayed(this, 700);
        }
    };

    private boolean ticking = false;

    private void startInfo() {
        if (ticking) return;
        ticking = true;
        ui.post(infoTick);
    }

    String name() { return name; }
    String url() { return url; }
    String songTitle() { return songTitle; }
    String error() { return error; }
}
