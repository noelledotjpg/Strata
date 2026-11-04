package com.nikohalfkino.rubydung.audio;

import com.nikohalfkino.rubydung.util.GameSettings;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * fire-and-forget sound effects from /assets/rubydung/sfx/
 *
 * "door_open" is sfx/tiles/door_open, "ui/menu_open" is relative to sfx/
 *
 * each file is decoded once and kept as pcm. instead of opening a Clip per sound, every
 * sound is a "voice" that a single mixer thread adds together and writes to ONE line,
 * so any number of sounds overlap and nothing depends on how many lines the OS allows
 */
public final class SoundEffects {

    private static final String PATH = "/assets/rubydung/sfx/";
    private static final String DEFAULT_FOLDER = "tiles/";

    // same sound closer together than this is dropped (breaking 30 blocks in one tick)
    private static final long MIN_GAP_MS = 45L;

    // how far playRandom shifts the pitch either way
    public static final float DEFAULT_PITCH_VARIATION = 0.12F;

    // mixer output format, samples of any rate/channel count are converted on the fly
    private static final int OUT_RATE = 44100;
    private static final int OUT_CHANNELS = 2;

    // ~11 ms per mix block, ~46 ms of line buffer. smaller = lower latency, more risk of crackle
    private static final int BLOCK_FRAMES = 512;
    private static final int LINE_BUFFER_FRAMES = 2048;

    // when more than this are playing the oldest ones get cut
    private static final int MAX_VOICES = 48;

    private static final Sample MISSING = new Sample(null, 0, 0, 0);

    private static final Map<String, Sample> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_PLAYED = new HashMap<>();
    private static final Random RANDOM = new Random();

    // decoding only, never touches the mixer so a slow first load can't delay playing sounds
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SoundEffects-Loader");
        thread.setDaemon(true);
        return thread;
    });

    // new voices wait here until the mixer picks them up
    private static final BlockingQueue<Voice> INCOMING = new LinkedBlockingQueue<>();
    private static volatile boolean mixerFailed;

    static {
        Thread mixer = new Thread(SoundEffects::mixLoop, "SoundEffects-Mixer");
        mixer.setDaemon(true);
        mixer.start();
    }

    private SoundEffects() {}

    // the SFX slider in options, 1.0 = as recorded
    public static void setMasterVolume(float volume) {
        GameSettings.setSfxVolume(volume);
    }

    public static float getMasterVolume() {
        return GameSettings.getSfxVolume();
    }

    public static void play(String name) {
        play(name, 1.0F, 1.0F);
    }

    public static void play(String name, float volume) {
        play(name, volume, 1.0F);
    }

    /** pitch 1.0 = as recorded, higher = higher and shorter */
    public static void play(String name, float volume, float pitch) {
        enqueue(name, name, volume, pitch);
    }

    /** picks prefix_1 .. prefix_variants at random, with a slight pitch change */
    public static void playRandom(String prefix, int variants) {
        playRandom(prefix, variants, DEFAULT_PITCH_VARIATION);
    }

    public static void playRandom(String prefix, int variants, float pitchVariation) {
        float pitch = 1.0F + (RANDOM.nextFloat() * 2.0F - 1.0F) * pitchVariation;
        // The gap applies to the whole group, whichever variant was picked.
        enqueue(prefix, prefix + "_" + (1 + RANDOM.nextInt(variants)), 1.0F, pitch);
    }

    /** like playRandom, for a single file */
    public static void playVaried(String name) {
        float pitch = 1.0F + (RANDOM.nextFloat() * 2.0F - 1.0F) * DEFAULT_PITCH_VARIATION;
        enqueue(name, name, 1.0F, pitch);
    }

    /** decodes ahead of time so the first play has no delay */
    public static void preload(String... names) {
        LOADER.execute(() -> {
            for (String name : names) {
                load(name);
            }
        });
    }

    private static void enqueue(String gapKey, String name, float volume, float pitch) {
        if (mixerFailed || !passesGap(gapKey)) {
            return;
        }

        Sample cached = CACHE.get(name);
        if (cached != null) {
            // already decoded: hand straight to the mixer, no thread hop
            startVoice(cached, volume, pitch);
        } else {
            LOADER.execute(() -> startVoice(load(name), volume, pitch));
        }
    }

    // only counts a sound as "played" when it actually gets through. the old version
    // refreshed the timestamp on dropped calls too, so anything retriggered faster than
    // the gap (holding a key, mining) was muted for as long as it kept being triggered
    private static boolean passesGap(String key) {
        long now = System.currentTimeMillis();
        synchronized (LAST_PLAYED) {
            Long last = LAST_PLAYED.get(key);
            if (last != null && now - last < MIN_GAP_MS) {
                return false;
            }
            LAST_PLAYED.put(key, now);
            return true;
        }
    }

    private static void startVoice(Sample sample, float volume, float pitch) {
        if (sample == MISSING || mixerFailed) {
            return;
        }

        // master volume is applied by the mixer, so the slider also affects sounds already playing
        if (getMasterVolume() * volume <= 0.0001F) {
            return;
        }

        double step = Math.max(0.01D, pitch) * sample.sampleRate / OUT_RATE;
        INCOMING.offer(new Voice(sample, volume, step));
    }

    // ---- mixer ----

    private static void mixLoop() {
        SourceDataLine line;
        try {
            AudioFormat format = new AudioFormat(OUT_RATE, 16, OUT_CHANNELS, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, LINE_BUFFER_FRAMES * OUT_CHANNELS * 2);
            line.start();
        } catch (Exception e) {
            System.err.println("SoundEffects: could not open audio line, sfx disabled: " + e);
            mixerFailed = true;
            INCOMING.clear();
            return;
        }

        List<Voice> active = new ArrayList<>();
        float[] mix = new float[BLOCK_FRAMES * OUT_CHANNELS];
        byte[] out = new byte[BLOCK_FRAMES * OUT_CHANNELS * 2];

        try {
            while (true) {
                if (active.isEmpty()) {
                    // nothing playing: sleep until a sound shows up instead of mixing silence
                    active.add(INCOMING.take());
                }
                INCOMING.drainTo(active);

                while (active.size() > MAX_VOICES) {
                    active.remove(0);
                }

                Arrays.fill(mix, 0.0F);
                for (int i = active.size() - 1; i >= 0; i--) {
                    if (active.get(i).mixInto(mix)) {
                        active.remove(i);
                    }
                }

                for (int i = 0; i < mix.length; i++) {
                    int value = Math.round(mix[i] * 32767.0F);
                    value = Math.max(-32768, Math.min(32767, value));
                    out[i * 2] = (byte) value;
                    out[i * 2 + 1] = (byte) (value >> 8);
                }

                // blocks while the line buffer is full, which is what paces this loop
                line.write(out, 0, out.length);
            }
        } catch (InterruptedException e) {
            // daemon thread, nothing to clean up beyond the line
        } catch (Exception e) {
            System.err.println("SoundEffects: mixer stopped: " + e);
            mixerFailed = true;
        } finally {
            line.stop();
            line.close();
        }
    }

    // ---- decoding ----

    private static Sample load(String name) {
        Sample cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }

        Sample sample = decode(name);
        CACHE.put(name, sample);
        return sample;
    }

    private static Sample decode(String name) {
        String resource = PATH + (name.indexOf('/') >= 0 ? "" : DEFAULT_FOLDER) + name + ".ogg";
        InputStream in = SoundEffects.class.getResourceAsStream(resource);
        if (in == null) {
            System.err.println("SoundEffects: missing resource " + resource);
            return MISSING;
        }

        MusicPlayer.Decoder decoder = null;
        try {
            decoder = new MusicPlayer.Decoder(in);
            int channels = decoder.channels;
            float[] chunk = new float[4096 * channels];
            short[] data = new short[1 << 16];
            int length = 0;

            int frames;
            while ((frames = decoder.read(chunk, 4096)) > 0) {
                int count = frames * channels;
                if (length + count > data.length) {
                    data = Arrays.copyOf(data, Math.max(data.length * 2, length + count));
                }
                for (int i = 0; i < count; i++) {
                    data[length++] = (short) Math.max(-32768, Math.min(32767, (int) (chunk[i] * 32767.0F)));
                }
            }

            if (length == 0) {
                System.err.println("SoundEffects: empty sound " + resource);
                return MISSING;
            }
            return new Sample(Arrays.copyOf(data, length), decoder.sampleRate, channels, length / channels);
        } catch (Exception e) {
            System.err.println("SoundEffects: failed to decode " + resource + ": " + e);
            return MISSING;
        } finally {
            if (decoder != null) {
                decoder.close();
            }
        }
    }

    private static final class Sample {
        final short[] pcm;
        final int sampleRate;
        final int channels;
        final int frames;

        Sample(short[] pcm, int sampleRate, int channels, int frames) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frames = frames;
        }
    }

    // one playing instance of a sample. only the mixer thread touches position
    private static final class Voice {
        final Sample sample;
        final float gain;
        final double step; // source frames per output frame (pitch * rate conversion)
        double position;

        Voice(Sample sample, float gain, double step) {
            this.sample = sample;
            this.gain = gain;
            this.step = step;
        }

        /** adds one block into the buffer, true once the sound has finished */
        boolean mixInto(float[] mix) {
            short[] pcm = sample.pcm;
            int channels = sample.channels;
            int last = sample.frames - 1;
            float scale = gain * Math.max(0.0F, getMasterVolume()) / 32768.0F;
            double pos = position;

            for (int n = 0; n < BLOCK_FRAMES && pos <= last; n++, pos += step) {
                int i0 = (int) pos;
                int i1 = Math.min(i0 + 1, last);
                float fraction = (float) (pos - i0);

                int a = i0 * channels;
                int b = i1 * channels;
                float left = pcm[a] + (pcm[b] - pcm[a]) * fraction;
                float right = left;
                if (channels > 1) {
                    right = pcm[a + 1] + (pcm[b + 1] - pcm[a + 1]) * fraction;
                }

                mix[n * 2] += left * scale;
                mix[n * 2 + 1] += right * scale;
            }

            position = pos;
            return pos > last;
        }
    }
}