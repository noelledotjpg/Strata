package com.nikohalfkino.rubydung.audio;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import com.nikohalfkino.rubydung.util.GameSettings;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

public final class MusicPlayer {

    private static final String TRACK_PATH = "/assets/rubydung/ost/wind96/";

    private static final String[] TRACKS = {"arcadia_town", "desolate", "dream_port", "feather_lakefront", "foggy_jungle", "highwind_prairie", "homesick_coast", "inaction", "kylin_forest", "mirror_lakefront", "tranquility_path", "yujia_forest"};

    /** Length of the fade-in at the very start and of every crossfade. */
    private static final float FADE_IN_SECONDS = 2.0F;
    private static final float CROSSFADE_SECONDS = 2.0F;

    /** Size of the audio line buffer; smaller = volume slider reacts faster. */
    private static final float LINE_BUFFER_SECONDS = 0.2F;

    /** Fixed loudness trim applied before the options slider (1.0 = original loudness). */
    private static final float BASE_GAIN = 0.35F;

    private static final int READ_CHUNK = 4096;
    private static final int CHUNK_FRAMES = 4096;

    private final Random random = new Random();
    private final List<String> bag = new ArrayList<>();

    private String lastPlayed;
    private volatile boolean running;
    private Thread thread;

    public synchronized void start() {
        if (this.thread != null) {
            return;
        }

        this.running = true;
        this.thread = new Thread(this::loop, "Music");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public synchronized void stop() {
        this.running = false;

        if (this.thread != null) {
            this.thread.interrupt();
            this.thread = null;
        }
    }

    private void loop() {
        if (!sleepSeconds(2)) {
            return;
        }

        Output out = null;
        Decoder cur = null;
        boolean fadeIn = true;
        int failures = 0;

        try {
            while (this.running) {
                try {
                    if (cur == null) {
                        cur = openNext();

                        if (cur == null) {
                            System.err.println("MusicPlayer: all tracks failed to play, disabling music.");
                            break;
                        }

                        fadeIn = true;
                    }

                    if (out != null && !out.matches(cur)) {
                        out.close();
                        out = null;
                    }

                    if (out == null) {
                        out = new Output(cur.sampleRate, cur.channels);
                    }

                    // Plays the track, holding back the last seconds as "tail".
                    float[] tail = streamTrack(cur, out, fadeIn);

                    if (tail == null) {
                        break; // stopped
                    }

                    Decoder finished = cur;
                    Decoder next = openNext();
                    boolean compatible = next != null && out.matches(next);

                    cur = next;
                    fadeIn = !compatible;
                    finished.close();

                    // Mix tail of the old track with the start of the new one.
                    crossfade(tail, out, compatible ? next : null);

                    failures = 0;
                } catch (Exception e) {
                    if (!this.running) {
                        break;
                    }

                    System.err.println("MusicPlayer: playback error: " + e);
                    closeQuietly(cur);
                    cur = null;

                    if (++failures >= TRACKS.length) {
                        System.err.println("MusicPlayer: too many failures, disabling music.");
                        break;
                    }
                }
            }
        } finally {
            closeQuietly(cur);

            if (out != null) {
                out.close();
            }

            synchronized (this) {
                if (Thread.currentThread() == this.thread) {
                    this.thread = null;
                }
            }
        }
    }

    private String nextTrack() {
        if (this.bag.isEmpty()) {
            Collections.addAll(this.bag, TRACKS);
            Collections.shuffle(this.bag, this.random);

            if (this.bag.size() > 1 && this.bag.get(this.bag.size() - 1).equals(this.lastPlayed)) {
                Collections.swap(this.bag, 0, this.bag.size() - 1);
            }
        }

        this.lastPlayed = this.bag.remove(this.bag.size() - 1);
        return this.lastPlayed;
    }

    /** Opens the next track that works, or returns null if none do. */
    private Decoder openNext() {
        for (int i = 0; i < TRACKS.length && this.running; i++) {
            String resource = TRACK_PATH + nextTrack() + ".ogg";
            InputStream in = MusicPlayer.class.getResourceAsStream(resource);

            if (in == null) {
                System.err.println("MusicPlayer: missing resource " + resource);
                continue;
            }

            try {
                return new Decoder(in);
            } catch (Exception e) {
                System.err.println("MusicPlayer: failed to open " + resource + ": " + e);
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }

        return null;
    }

    /**
     * Streams a track to the output, retaining the final CROSSFADE_SECONDS.
     * Returns the retained tail (interleaved floats), or null if stopped.
     */
    private float[] streamTrack(Decoder d, Output out, boolean fadeIn) throws IOException {
        int ch = d.channels;
        int tailFrames = Math.max(1, (int) (d.sampleRate * CROSSFADE_SECONDS));
        long fadeInFrames = Math.max(1L, (long) (d.sampleRate * FADE_IN_SECONDS));

        Deque<float[]> queue = new ArrayDeque<>();
        int queued = 0;
        long played = 0;

        while (this.running) {
            float[] chunk = new float[CHUNK_FRAMES * ch];
            int frames = d.read(chunk, CHUNK_FRAMES);

            if (frames == 0) {
                break;
            }

            if (frames < CHUNK_FRAMES) {
                chunk = Arrays.copyOf(chunk, frames * ch);
            }

            if (fadeIn && played < fadeInFrames) {
                for (int i = 0; i < frames; i++) {
                    float g = Math.min(1.0F, (float) (played + i) / fadeInFrames);

                    for (int c = 0; c < ch; c++) {
                        chunk[i * ch + c] *= g;
                    }
                }
            }

            played += frames;
            queue.addLast(chunk);
            queued += frames;

            // Write everything older than the retained tail.
            while (queued > tailFrames) {
                float[] first = queue.removeFirst();
                int firstFrames = first.length / ch;
                int write = Math.min(firstFrames, queued - tailFrames);

                out.write(first, write);
                queued -= write;

                if (write < firstFrames) {
                    queue.addFirst(Arrays.copyOfRange(first, write * ch, first.length));
                }
            }
        }

        if (!this.running) {
            return null;
        }

        float[] tail = new float[queued * ch];
        int pos = 0;

        for (float[] chunk : queue) {
            System.arraycopy(chunk, 0, tail, pos, chunk.length);
            pos += chunk.length;
        }

        return tail;
    }

    /** Fades the tail out while fading the start of "next" in (next may be null). */
    private void crossfade(float[] tail, Output out, Decoder next) throws IOException {
        int ch = out.channels;
        int tailFrames = tail.length / ch;

        float[] nextBuf = new float[CHUNK_FRAMES * ch];
        float[] mixed = new float[CHUNK_FRAMES * ch];

        int pos = 0;

        while (this.running && pos < tailFrames) {
            int n = Math.min(CHUNK_FRAMES, tailFrames - pos);
            int got = next != null ? next.read(nextBuf, n) : 0;

            for (int i = 0; i < n; i++) {
                double t = (double) (pos + i + 1) / tailFrames;
                float gOut = (float) Math.cos(t * Math.PI / 2.0);
                float gIn = (float) Math.sin(t * Math.PI / 2.0);

                for (int c = 0; c < ch; c++) {
                    int idx = i * ch + c;
                    float incoming = i < got ? nextBuf[idx] : 0.0F;

                    mixed[idx] = tail[(pos + i) * ch + c] * gOut + incoming * gIn;
                }
            }

            out.write(mixed, n);
            pos += n;
        }
    }

    private static void closeQuietly(Decoder d) {
        if (d != null) {
            d.close();
        }
    }

    private boolean sleepSeconds(int seconds) {
        try {
            for (int i = 0; i < seconds * 10 && this.running; i++) {
                Thread.sleep(100L);
            }
        } catch (InterruptedException e) {
            return false;
        }

        return this.running;
    }

    private static int readChunk(InputStream in, SyncState oy) throws IOException {
        int index = oy.buffer(READ_CHUNK);
        int n = in.read(oy.data, index, READ_CHUNK);

        if (n <= 0) {
            return 0;
        }

        oy.wrote(n);
        return n;
    }

    /**
     * Audio output. Volume is applied in software on every write, so the
     * options slider takes effect immediately, mid-track.
     */
    private static final class Output {

        final int sampleRate;
        final int channels;

        private final SourceDataLine line;
        private byte[] bytes = new byte[0];
        private float lastVolume = -1.0F;

        Output(int sampleRate, int channels) throws LineUnavailableException {
            this.sampleRate = sampleRate;
            this.channels = channels;

            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            int bufferBytes = Math.max(4096, (int) (sampleRate * LINE_BUFFER_SECONDS)) * channels * 2;

            this.line = AudioSystem.getSourceDataLine(format);
            this.line.open(format, bufferBytes);
            this.line.start();
        }

        boolean matches(Decoder d) {
            return d.sampleRate == this.sampleRate && d.channels == this.channels;
        }

        void write(float[] data, int frames) {
            if (frames <= 0) {
                return;
            }

            float target = Math.max(0.0F, Math.min(1.0F, GameSettings.getMusicVolume()));

            if (this.lastVolume < 0.0F) {
                this.lastVolume = target;
            }

            int needed = frames * this.channels * 2;

            if (this.bytes.length < needed) {
                this.bytes = new byte[needed];
            }

            int p = 0;

            for (int i = 0; i < frames; i++) {
                // Ramp volume across the chunk to avoid zipper noise.
                float gain = this.lastVolume + (target - this.lastVolume) * (i + 1) / frames;

                for (int c = 0; c < this.channels; c++) {
                    int val = (int) (data[i * this.channels + c] * 32767.0F * gain * BASE_GAIN);
                    val = Math.max(-32768, Math.min(32767, val));

                    this.bytes[p++] = (byte) val;
                    this.bytes[p++] = (byte) (val >>> 8);
                }
            }

            this.lastVolume = target;
            this.line.write(this.bytes, 0, needed);
        }

        void close() {
            this.line.stop();
            this.line.close();
        }
    }

    /** Pull-based Ogg Vorbis decoder producing interleaved float PCM. */
    private static final class Decoder {

        int channels;
        int sampleRate;

        private final InputStream in;
        private final SyncState oy = new SyncState();
        private final StreamState os = new StreamState();
        private final Page og = new Page();
        private final Packet op = new Packet();
        private final Info vi = new Info();
        private final Comment vc = new Comment();
        private final DspState vd = new DspState();
        private final Block vb = new Block(this.vd);

        private final float[][][] pcmHolder = new float[1][][];
        private int[] pcmIndex;

        private float[] pending = new float[0];
        private int pendingPos;
        private int pendingLen;

        private boolean eos;

        Decoder(InputStream in) throws IOException {
            this.in = in;

            try {
                this.oy.init();

                if (readChunk(in, this.oy) == 0) {
                    throw new IOException("empty file");
                }

                if (this.oy.pageout(this.og) != 1) {
                    throw new IOException("not an Ogg stream");
                }

                this.os.init(this.og.serialno());
                this.vi.init();
                this.vc.init();

                if (this.os.pagein(this.og) < 0 || this.os.packetout(this.op) != 1) {
                    throw new IOException("bad first Ogg page");
                }

                if (this.vi.synthesis_headerin(this.vc, this.op) < 0) {
                    throw new IOException("not a Vorbis stream (Opus or FLAC inside an .ogg?)");
                }

                int headersRead = 1;

                while (headersRead < 3) {
                    int result;

                    while (headersRead < 3 && (result = this.oy.pageout(this.og)) != 0) {
                        if (result == 1) {
                            this.os.pagein(this.og);

                            while (headersRead < 3) {
                                result = this.os.packetout(this.op);

                                if (result == 0) {
                                    break;
                                }

                                if (result == -1) {
                                    throw new IOException("corrupt Vorbis headers");
                                }

                                this.vi.synthesis_headerin(this.vc, this.op);
                                headersRead++;
                            }
                        }
                    }

                    if (headersRead < 3 && readChunk(in, this.oy) == 0) {
                        throw new IOException("file ended inside the Vorbis headers");
                    }
                }

                this.channels = this.vi.channels;
                this.sampleRate = this.vi.rate;
                this.pcmIndex = new int[this.channels];

                this.vd.synthesis_init(this.vi);
                this.vb.init(this.vd);
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        /**
         * Reads up to "frames" interleaved frames into dst.
         * Returns fewer than requested only at the end of the stream.
         */
        int read(float[] dst, int frames) throws IOException {
            int got = 0;

            while (got < frames) {
                if (this.pendingPos >= this.pendingLen && !decodeMore()) {
                    break;
                }

                int n = Math.min(frames - got, this.pendingLen - this.pendingPos);

                System.arraycopy(this.pending, this.pendingPos * this.channels,
                        dst, got * this.channels, n * this.channels);

                this.pendingPos += n;
                got += n;
            }

            return got;
        }

        /** Decodes more audio into "pending". Returns false at end of stream. */
        private boolean decodeMore() throws IOException {
            while (true) {
                int samples = this.vd.synthesis_pcmout(this.pcmHolder, this.pcmIndex);

                if (samples > 0) {
                    float[][] pcm = this.pcmHolder[0];

                    if (this.pending.length < samples * this.channels) {
                        this.pending = new float[samples * this.channels];
                    }

                    for (int ch = 0; ch < this.channels; ch++) {
                        int src = this.pcmIndex[ch];

                        for (int j = 0; j < samples; j++) {
                            this.pending[j * this.channels + ch] = pcm[ch][src + j];
                        }
                    }

                    this.vd.synthesis_read(samples);
                    this.pendingPos = 0;
                    this.pendingLen = samples;
                    return true;
                }

                int result = this.os.packetout(this.op);

                if (result == 1) {
                    if (this.vb.synthesis(this.op) == 0) {
                        this.vd.synthesis_blockin(this.vb);
                    }
                    continue;
                }

                if (result == -1) {
                    continue;
                }

                if (this.eos) {
                    return false;
                }

                int page = this.oy.pageout(this.og);

                if (page == 1) {
                    this.os.pagein(this.og);

                    if (this.og.eos() != 0) {
                        this.eos = true;
                    }
                } else if (page == 0 && readChunk(this.in, this.oy) == 0) {
                    this.eos = true;
                }
            }
        }

        void close() {
            try {
                this.in.close();
            } catch (IOException ignored) {
            }

            this.os.clear();
            this.vb.clear();
            this.vd.clear();
            this.vi.clear();
            this.oy.clear();
        }
    }
}