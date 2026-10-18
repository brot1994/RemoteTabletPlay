package id.bram.flipclock;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Menganalisis suara radio yang sedang diputar (FFT) menjadi tinggi batang-batang
 * untuk animasi di layar. Tidak butuh izin mikrofon: data diambil langsung dari pemutar.
 * Hasil disimpan beserta waktunya, lalu diambil sedikit terlambat supaya pas dengan
 * suara yang benar-benar keluar dari speaker.
 */
class RadioViz implements TeeAudioProcessor.AudioBufferSink {

    static final int BANDS = 32;
    private static final int N = 1024;          // ukuran FFT
    private static final int HOP = 512;         // geser setengah jendela → ±86 frame/detik pada 44,1 kHz

    private static RadioViz instance;

    static synchronized RadioViz get() {
        if (instance == null) instance = new RadioViz();
        return instance;
    }

    private int channels = 2, rate = 44100, encoding = C.ENCODING_PCM_16BIT;
    private final float[] ring = new float[N];
    private int fill = 0;
    private final float[] re = new float[N], im = new float[N], win = new float[N];
    private int[] edges;
    private int edgesRate = 0;

    private static final class Frame { long t; byte[] v; }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    private RadioViz() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
    }

    @Override
    public synchronized void flush(int sampleRateHz, int channelCount, int enc) {
        if (sampleRateHz > 0) rate = sampleRateHz;
        if (channelCount > 0) channels = channelCount;
        encoding = enc;
        fill = 0;
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int ch, enc;
        synchronized (this) { ch = Math.max(1, channels); enc = encoding; }
        boolean isFloat = enc == C.ENCODING_PCM_FLOAT;
        int bytesPer = isFloat ? 4 : 2;
        if (enc != C.ENCODING_PCM_16BIT && !isFloat) return;
        while (b.remaining() >= bytesPer * ch) {
            float s = 0;
            for (int c = 0; c < ch; c++) s += isFloat ? b.getFloat() : b.getShort() / 32768f;
            ring[fill++] = s / ch;
            if (fill == N) {
                analyze();
                System.arraycopy(ring, HOP, ring, 0, N - HOP);
                fill = N - HOP;
            }
        }
    }

    private void buildEdges() {
        edges = new int[BANDS + 1];
        double lo = 45, hi = Math.min(15000, rate / 2.0 - 100);
        for (int i = 0; i <= BANDS; i++) {
            double f = lo * Math.pow(hi / lo, i / (double) BANDS);
            edges[i] = (int) Math.round(f * N / rate);
        }
        for (int i = 1; i <= BANDS; i++) if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1;
        edgesRate = rate;
    }

    private void analyze() {
        if (edges == null || edgesRate != rate) buildEdges();
        for (int i = 0; i < N; i++) { re[i] = ring[i] * win[i]; im[i] = 0; }
        fft(re, im);
        byte[] out = new byte[BANDS];
        float norm = N / 4f;
        for (int bnd = 0; bnd < BANDS; bnd++) {
            int a = edges[bnd], z = Math.min(N / 2, edges[bnd + 1]);
            float m = 0;
            for (int k = a; k < z; k++) {
                float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (mag > m) m = mag;
            }
            double db = 20 * Math.log10(m / norm + 1e-9);
            db += bnd * 0.45;                          // musik makin lemah di nada tinggi → diimbangi
            double v = (db + 62) / 56.0;
            int iv = (int) Math.round(Math.max(0, Math.min(1, v)) * 100);
            out[bnd] = (byte) iv;
        }
        Frame f = new Frame();
        f.t = System.nanoTime() / 1000000L;
        f.v = out;
        synchronized (frames) {
            frames.addLast(f);
            while (frames.size() > 400) frames.removeFirst();
        }
    }

    /** Batang untuk saat ini (data diambil {@code delayMs} lebih awal), "" kalau tidak ada suara. */
    String levels(int delayMs) {
        long target = System.nanoTime() / 1000000L - delayMs;
        Frame pick = null;
        synchronized (frames) {
            while (frames.size() > 1 && frames.peekFirst().t < target - 2000) frames.removeFirst();
            for (Frame f : frames) {
                if (f.t <= target) pick = f; else break;
            }
        }
        if (pick == null || target - pick.t > 600) return "";
        StringBuilder sb = new StringBuilder(BANDS * 3);
        for (int i = 0; i < BANDS; i++) {
            if (i > 0) sb.append(',');
            sb.append(pick.v[i]);
        }
        return sb.toString();
    }

    void clear() {
        synchronized (frames) { frames.clear(); }
    }

    /** FFT radix-2 di tempat. */
    private static void fft(float[] x, float[] y) {
        int n = x.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = x[i]; x[i] = x[j]; x[j] = t;
                t = y[i]; y[i] = y[j]; y[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int p = i + k, q = i + k + len / 2;
                    float ur = x[p], ui = y[p];
                    float vr = x[q] * cr - y[q] * ci, vi = x[q] * ci + y[q] * cr;
                    x[p] = ur + vr; y[p] = ui + vi;
                    x[q] = ur - vr; y[q] = ui - vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
