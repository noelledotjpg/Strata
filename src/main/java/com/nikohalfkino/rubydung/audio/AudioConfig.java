package com.nikohalfkino.rubydung.audio;

import com.nikohalfkino.rubydung.level.tiles.model.Toml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * everything audio.toml defines, parsed once. immutable, so the audio threads can read it without locking
 *
 * lives at /assets/rubydung/audio.toml. a missing or broken file never crashes the game: the problem is
 * printed and the built-in defaults are used (music with no tracks, no sounds)
 */
final class AudioConfig {

    static final String RESOURCE = "/assets/rubydung/audio.toml";

    /** value of a tile event that means "play nothing" (written as "none" in the toml) */
    static final String SILENT = "";

    /** one named sound: which files it picks from and how it is played */
    static final class SoundSpec {
        final String name;
        final String[] files;       // relative to sfx/, no extension
        final float volume;
        final float pitch;
        final float pitchVariation;
        final boolean hasPitchRange; // pitch_min / pitch_max given: a random pitch inside the range
        final float pitchMin;
        final float pitchMax;
        final long gapNanos;        // same sound closer together than this is dropped
        final boolean preload;

        SoundSpec(String name, String[] files, float volume, float pitch, float pitchVariation,
                  boolean hasPitchRange, float pitchMin, float pitchMax, long gapMs, boolean preload) {
            this.name = name;
            this.files = files;
            this.volume = volume;
            this.pitch = pitch;
            this.pitchVariation = pitchVariation;
            this.hasPitchRange = hasPitchRange;
            this.pitchMin = Math.min(pitchMin, pitchMax);
            this.pitchMax = Math.max(pitchMin, pitchMax);
            this.gapNanos = gapMs * 1_000_000L;
            this.preload = preload;
        }
    }

    // [mixer]
    final int outputRate;
    final int bufferMs;
    final int blockFrames;
    final int maxVoices;
    final long defaultGapMs;
    final float defaultPitchVariation;
    final boolean threadPriorityHigh;

    // [music]
    final boolean musicEnabled;
    final String musicPath;
    final List<String> musicTracks;
    final float musicFadeIn;
    final float musicCrossfade;
    final float musicGain;
    final int musicBufferMs;
    final int musicStartDelaySeconds;

    // [sounds.*]
    final Map<String, SoundSpec> sounds;

    // [tile_sounds.*]: event -> (tile name -> sound name), plus the per event fallback
    private final Map<String, Map<String, String>> tileEvents;
    private final Map<String, String> tileDefaults;

    private AudioConfig(Map<String, Object> root) {
        Map<String, Object> mixer = table(root, "mixer");
        this.outputRate = clamp(integer(mixer, "output_rate", 44100), 8000, 192000);
        this.bufferMs = clamp(integer(mixer, "buffer_ms", 80), 10, 1000);
        this.blockFrames = clamp(integer(mixer, "block_frames", 512), 64, 8192);
        this.maxVoices = clamp(integer(mixer, "max_voices", 48), 1, 512);
        this.defaultGapMs = Math.max(0, integer(mixer, "min_gap_ms", 45));
        this.defaultPitchVariation = (float) Math.max(0.0, number(mixer, "pitch_variation", 0.12));
        this.threadPriorityHigh = bool(mixer, "high_priority_thread", true);

        Map<String, Object> music = table(root, "music");
        this.musicEnabled = bool(music, "enabled", true);
        this.musicPath = normalizeFolder(string(music, "path", "ost/"));
        this.musicTracks = Collections.unmodifiableList(strings(music, "tracks"));
        this.musicFadeIn = (float) Math.max(0.0, number(music, "fade_in_seconds", 2.0));
        this.musicCrossfade = (float) Math.max(0.0, number(music, "crossfade_seconds", 2.0));
        this.musicGain = (float) Math.max(0.0, number(music, "gain", 0.35));
        this.musicBufferMs = clamp(integer(music, "buffer_ms", 200), 20, 2000);
        this.musicStartDelaySeconds = Math.max(0, integer(music, "start_delay_seconds", 2));

        this.sounds = readSounds(table(root, "sounds"));

        Map<String, Map<String, String>> events = new HashMap<>();
        Map<String, String> defaults = new HashMap<>();
        readTileSounds(table(root, "tile_sounds"), events, defaults);
        this.tileEvents = events;
        this.tileDefaults = defaults;

        warnAboutUnknownSounds();
    }

    private Map<String, SoundSpec> readSounds(Map<String, Object> table) {
        Map<String, SoundSpec> result = new LinkedHashMap<>();
        if (table == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Map<String, Object> def = table(table, entry.getKey());
            if (def == null) {
                System.err.println("audio.toml: [sounds] entry '" + entry.getKey() + "' must be a table");
                continue;
            }

            List<String> files = strings(def, "files");
            if (files.isEmpty()) {
                System.err.println("audio.toml: sound '" + entry.getKey() + "' has no files");
                continue;
            }

            // pitch_min and/or pitch_max: the pitch is picked inside that range (a missing one uses "pitch")
            boolean ranged = def.containsKey("pitch_min") || def.containsKey("pitch_max");
            float base = (float) Math.max(0.01, number(def, "pitch", 1.0));
            float pitchMin = (float) Math.max(0.01, number(def, "pitch_min", base));
            float pitchMax = (float) Math.max(0.01, number(def, "pitch_max", base));

            result.put(entry.getKey(), new SoundSpec(
                    entry.getKey(),
                    files.toArray(new String[0]),
                    (float) Math.max(0.0, number(def, "volume", 1.0)),
                    base,
                    (float) Math.max(0.0, number(def, "pitch_variation", this.defaultPitchVariation)),
                    ranged, pitchMin, pitchMax,
                    Math.max(0, integer(def, "gap_ms", (int) this.defaultGapMs)),
                    bool(def, "preload", true)));
        }
        return result;
    }

    private static void readTileSounds(Map<String, Object> table, Map<String, Map<String, String>> events,
                                       Map<String, String> defaults) {
        if (table == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Map<String, Object> group = table(table, entry.getKey());
            if (group == null) {
                System.err.println("audio.toml: [tile_sounds] entry '" + entry.getKey() + "' must be a table");
                continue;
            }

            boolean isDefault = entry.getKey().equals("default");
            List<String> tiles = strings(group, "tiles");

            for (Map.Entry<String, Object> field : group.entrySet()) {
                if (field.getKey().equals("tiles") || !(field.getValue() instanceof String)) {
                    continue;
                }

                String event = field.getKey();
                String sound = (String) field.getValue();
                if (sound.equalsIgnoreCase("none")) {
                    sound = SILENT;
                }

                if (isDefault) {
                    defaults.put(event, sound);
                } else {
                    Map<String, String> perTile = events.computeIfAbsent(event, k -> new HashMap<>());
                    for (String tile : tiles) {
                        perTile.put(tile, sound);
                    }
                }
            }

            if (!isDefault && tiles.isEmpty()) {
                System.err.println("audio.toml: tile_sounds." + entry.getKey() + " lists no tiles");
            }
        }
    }

    private void warnAboutUnknownSounds() {
        List<String> referenced = new ArrayList<>(this.tileDefaults.values());
        for (Map<String, String> perTile : this.tileEvents.values()) {
            referenced.addAll(perTile.values());
        }
        for (String name : referenced) {
            if (!name.isEmpty() && !this.sounds.containsKey(name)) {
                System.err.println("audio.toml: tile sound '" + name + "' is not defined under [sounds], "
                        + "treating it as the file sfx/tiles/" + name + ".ogg");
            }
        }
    }

    /**
     * the sound name a tile plays for an event ("break", "place", ...): its own group, else the default group.
     * null = nothing should play
     */
    String tileSound(String tileName, String event) {
        Map<String, String> perTile = this.tileEvents.get(event);
        String sound = perTile != null ? perTile.get(tileName) : null;
        if (sound == null) {
            sound = this.tileDefaults.get(event);
        }
        return sound == null || sound.isEmpty() ? null : sound;
    }

    // ---- loading ----

    static AudioConfig load() {
        try (InputStream in = AudioConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("audio: " + RESOURCE + " not found, using built-in defaults");
                return defaults();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return new AudioConfig(Toml.parse(text));
        } catch (IOException e) {
            System.err.println("audio: could not read audio.toml, using built-in defaults: " + e);
        } catch (RuntimeException e) {
            // the shared Toml reader throws ModelException (unchecked) with the line and column
            System.err.println("audio: could not parse audio.toml, using built-in defaults: " + e.getMessage());
        }
        return defaults();
    }

    static AudioConfig defaults() {
        return new AudioConfig(new LinkedHashMap<>());
    }

    // ---- typed access to the parsed toml ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String string(Map<String, Object> table, String key, String fallback) {
        Object value = table == null ? null : table.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static double number(Map<String, Object> table, String key, double fallback) {
        Object value = table == null ? null : table.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static int integer(Map<String, Object> table, String key, int fallback) {
        return (int) Math.round(number(table, key, fallback));
    }

    private static boolean bool(Map<String, Object> table, String key, boolean fallback) {
        Object value = table == null ? null : table.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** a string or a list of strings, always returned as a list (empty if missing) */
    private static List<String> strings(Map<String, Object> table, String key) {
        List<String> result = new ArrayList<>();
        Object value = table == null ? null : table.get(key);
        if (value instanceof String) {
            result.add((String) value);
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
        }
        return result;
    }

    private static String normalizeFolder(String folder) {
        String f = folder.replace('\\', '/');
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        return f.isEmpty() || f.endsWith("/") ? f : f + "/";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}