package id.bram.flipclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kamera tablet → video live ke halaman remote (WebRTC, langsung HP ↔ tablet).
 * Firebase hanya dipakai untuk bertukar "alamat sambungan" (SDP); videonya tidak lewat Firebase.
 * Semua operasi berjalan di satu thread sendiri supaya aman.
 */
class CamStreamer {

    interface AnswerCallback { void done(String sdp, String error); }
    interface StateListener { void changed(boolean active); }

    private static CamStreamer instance;

    static synchronized CamStreamer get(Context c) {
        if (instance == null) instance = new CamStreamer(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private EglBase egl;
    private PeerConnectionFactory factory;

    private PeerConnection pc;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper sth;
    private VideoSource source;
    private VideoTrack track;
    private String sid = "";
    private boolean front = true;
    private volatile boolean active = false;
    private volatile String status = "";
    private StateListener listener;
    private long disconnectedAt = 0;

    private CamStreamer(Context ctx) { this.ctx = ctx; }

    void setListener(StateListener l) { listener = l; }

    boolean active() { return active; }

    String status() { return status; }

    boolean front() { return front; }

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureFactory() {
        if (factory != null) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions());
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    /** Daftar kamera yang ada: "front", "back" atau keduanya. */
    String cameras() {
        try {
            Camera2Enumerator en = new Camera2Enumerator(ctx);
            boolean f = false, b = false;
            for (String n : en.getDeviceNames()) { if (en.isFrontFacing(n)) f = true; if (en.isBackFacing(n)) b = true; }
            return (f ? "front" : "") + (f && b ? "," : "") + (b ? "back" : "");
        } catch (Exception e) { return ""; }
    }

    private CameraVideoCapturer makeCapturer(boolean wantFront) {
        CameraEnumerator en = new Camera2Enumerator(ctx);
        String pick = null;
        for (String n : en.getDeviceNames()) {
            if (wantFront ? en.isFrontFacing(n) : en.isBackFacing(n)) { pick = n; break; }
        }
        if (pick == null && en.getDeviceNames().length > 0) pick = en.getDeviceNames()[0];
        if (pick == null) return null;
        front = en.isFrontFacing(pick);
        return en.createCapturer(pick, null);
    }

    /**
     * Terima penawaran dari HP, nyalakan kamera, dan kembalikan jawaban (SDP lengkap, tanpa trickle).
     */
    void start(final String newSid, final String offerSdp, final boolean wantFront, final List<PeerConnection.IceServer> ice,
               final int quality, final AnswerCallback cb) {
        exec.execute(() -> {
            try {
                stopInternal();
                if (!hasPermission(ctx)) { cb.done(null, "Izin kamera belum diberikan di tablet"); return; }
                ensureFactory();
                capturer = makeCapturer(wantFront);
                if (capturer == null) { cb.done(null, "Tablet tidak punya kamera"); return; }
                sth = SurfaceTextureHelper.create("CamThread", egl.getEglBaseContext());
                source = factory.createVideoSource(false);
                capturer.initialize(sth, ctx, source.getCapturerObserver());
                int w = quality >= 2 ? 1280 : quality == 1 ? 960 : 640;
                int h = quality >= 2 ? 720 : quality == 1 ? 540 : 360;
                capturer.startCapture(w, h, 15);
                track = factory.createVideoTrack("cam0", source);
                track.setEnabled(true);

                PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
                cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
                final AtomicBoolean answered = new AtomicBoolean(false);
                sid = newSid;
                final String mySid = newSid;
                pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
                    @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
                    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
                    @Override public void onIceConnectionReceivingChange(boolean b) { }
                    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                        if (s == PeerConnection.IceGatheringState.COMPLETE) exec.execute(() -> sendAnswer(mySid, answered, cb));
                    }
                    @Override public void onIceCandidate(IceCandidate c) { }
                    @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
                    @Override public void onAddStream(MediaStream m) { }
                    @Override public void onRemoveStream(MediaStream m) { }
                    @Override public void onDataChannel(DataChannel d) { }
                    @Override public void onRenegotiationNeeded() { }
                    @Override public void onAddTrack(RtpReceiver r, MediaStream[] m) { }
                    @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                        exec.execute(() -> onConn(mySid, s));
                    }
                });
                if (pc == null) { stopInternal(); cb.done(null, "Gagal membuat sambungan video"); return; }

                pc.setRemoteDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        exec.execute(() -> {
                            if (pc == null || !mySid.equals(sid)) return;
                            boolean attached = false;
                            for (RtpTransceiver t : pc.getTransceivers()) {
                                if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !attached) {
                                    t.getSender().setTrack(track, false);
                                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
                                    attached = true;
                                }
                            }
                            if (!attached) pc.addTrack(track, java.util.Collections.singletonList("s0"));
                            pc.createAnswer(new SdpAdapter() {
                                @Override public void onCreateSuccess(SessionDescription d) {
                                    exec.execute(() -> {
                                        if (pc == null) return;
                                        pc.setLocalDescription(new SdpAdapter() {
                                            @Override public void onSetSuccess() {
                                                exec.execute(() -> limitBitrate(quality));
                                                // jaga-jaga: kirim jawaban setelah 4 detik walau pengumpulan alamat belum selesai
                                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                                        () -> exec.execute(() -> sendAnswer(mySid, answered, cb)), 4000);
                                            }
                                            @Override public void onSetFailure(String e) { fail(cb, answered, "setLocal: " + e); }
                                        }, d);
                                    });
                                }
                                @Override public void onCreateFailure(String e) { fail(cb, answered, "answer: " + e); }
                            }, new MediaConstraints());
                        });
                    }
                    @Override public void onSetFailure(String e) { fail(cb, answered, "offer: " + e); }
                }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));
                active = true;
                status = "menyambung";
                notifyState();
            } catch (Throwable t) {
                stopInternal();
                cb.done(null, "Kamera error: " + t.getMessage());
            }
        });
    }

    private void fail(AnswerCallback cb, AtomicBoolean answered, String msg) {
        exec.execute(() -> {
            if (answered.getAndSet(true)) return;
            stopInternal();
            cb.done(null, "Gagal: " + msg);
        });
    }

    private void sendAnswer(String mySid, AtomicBoolean answered, AnswerCallback cb) {
        if (pc == null || !mySid.equals(sid) || answered.get()) return;
        SessionDescription d = pc.getLocalDescription();
        if (d == null) return;
        answered.set(true);
        cb.done(d.description, null);
    }

    private void limitBitrate(int quality) {
        try {
            if (pc == null) return;
            for (RtpTransceiver t : pc.getTransceivers()) {
                if (t.getSender() == null || t.getSender().track() == null) continue;
                RtpParameters p = t.getSender().getParameters();
                for (RtpParameters.Encoding e : p.encodings) {
                    e.maxBitrateBps = quality >= 2 ? 1_500_000 : quality == 1 ? 900_000 : 500_000;
                    e.maxFramerate = 15;
                }
                t.getSender().setParameters(p);
            }
        } catch (Throwable ignored) { }
    }

    private void onConn(String mySid, PeerConnection.PeerConnectionState s) {
        if (!mySid.equals(sid)) return;
        switch (s) {
            case CONNECTED:
                status = "live";
                disconnectedAt = 0;
                break;
            case DISCONNECTED:
                status = "terputus";
                disconnectedAt = System.currentTimeMillis();
                // beri waktu 10 detik untuk tersambung lagi
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> exec.execute(() -> {
                    if (mySid.equals(sid) && disconnectedAt > 0 && System.currentTimeMillis() - disconnectedAt >= 9500) stopInternal();
                }), 10000);
                break;
            case FAILED:
            case CLOSED:
                stopInternal();
                break;
            default:
                break;
        }
        notifyState();
    }

    /** Ganti kamera depan ↔ belakang saat sedang live. */
    void switchCamera() {
        exec.execute(() -> {
            if (capturer == null) return;
            capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override public void onCameraSwitchDone(boolean isFront) { front = isFront; }
                @Override public void onCameraSwitchError(String e) { }
            });
        });
    }

    void stop() { exec.execute(this::stopInternal); }

    /** Hentikan kalau sesi yang diminta masih yang sedang berjalan. */
    void stop(String whichSid) {
        exec.execute(() -> { if (whichSid == null || whichSid.isEmpty() || whichSid.equals(sid)) stopInternal(); });
    }

    private void stopInternal() {
        boolean was = active;
        active = false;
        sid = "";
        status = "";
        disconnectedAt = 0;
        try { if (pc != null) pc.close(); } catch (Throwable ignored) { }
        try { if (pc != null) pc.dispose(); } catch (Throwable ignored) { }
        pc = null;
        try { if (capturer != null) capturer.stopCapture(); } catch (Throwable ignored) { }
        try { if (capturer != null) capturer.dispose(); } catch (Throwable ignored) { }
        capturer = null;
        try { if (track != null) track.dispose(); } catch (Throwable ignored) { }
        track = null;
        try { if (source != null) source.dispose(); } catch (Throwable ignored) { }
        source = null;
        try { if (sth != null) sth.dispose(); } catch (Throwable ignored) { }
        sth = null;
        if (was) notifyState();
    }

    private void notifyState() {
        StateListener l = listener;
        if (l != null) l.changed(active);
    }

    static List<PeerConnection.IceServer> iceServers(org.json.JSONArray arr) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        out.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        out.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                List<String> urls = new ArrayList<>();
                Object u = o.opt("urls");
                if (u instanceof org.json.JSONArray) {
                    for (int k = 0; k < ((org.json.JSONArray) u).length(); k++) urls.add(((org.json.JSONArray) u).optString(k));
                } else if (u != null) urls.add(String.valueOf(u));
                if (urls.isEmpty()) continue;
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
                if (o.has("username")) b.setUsername(o.optString("username"));
                if (o.has("credential")) b.setPassword(o.optString("credential"));
                out.add(b.createIceServer());
            }
        }
        return out;
    }

    /** SdpObserver dengan semua metode kosong. */
    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { }
        @Override public void onSetFailure(String e) { }
    }
}
