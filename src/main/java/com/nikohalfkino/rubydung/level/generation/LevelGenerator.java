package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.interfaces.screen.CreateWorldScreen;
import com.nikohalfkino.rubydung.level.Level;

public final class LevelGenerator {

    private LevelGenerator() {
    }

    public enum WorldType {
        FLAT,
        NATURAL
    }

    public enum WorldSize {
        TINY(128, 128, 96),
        DEFAULT(256, 256, 128),
        LARGE(384, 384, 144),
        INSANE(512, 512, 160);

        public final int width;
        public final int height;
        public final int depth;

        WorldSize(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }

    public static byte[] generate(WorldType type, int width, int height, int depth, long seed) {
        return type == WorldType.NATURAL
                ? NaturalLevelGenerator.generate(width, height, depth, seed)
                : FlatLevelGenerator.generate(width, height, depth);
    }

    public static long parseSeed(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }
}