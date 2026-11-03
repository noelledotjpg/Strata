package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.interfaces.screen.CreateWorldScreen;
import com.nikohalfkino.rubydung.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LevelGenerator {

    private LevelGenerator() {
    }

    public enum WorldType {
        // saved by ordinal (type.dat, level.dat), so only ever append new types
        FLAT,
        NATURAL,    // inland terrain: no ocean, lakes, beaches or sea stacks
        ISLAND      // natural terrain surrounded by ocean, with lakes, beaches, sea stacks and arches
    }

    public enum WorldSize {
        // about a third of a natural world is island and the rest ocean, so these are bigger than the land they hold.
        // all multiples of Chunk.SIZE. memory grows with width * height * depth (the block, data, liquid and light arrays), so XL stops at 768 (about 470 MB of arrays, give the JVM at least -Xmx2g)
        TINY(192, 192, 96),
        DEFAULT(384, 384, 128),
        LARGE(576, 576, 144),
        INSANE(768, 768, 160);

        public final int width;
        public final int height;
        public final int depth;

        WorldSize(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }

    /**
     * a generated world: the block array plus the origin cell {x, y, z} of every multi-tile object stamped into it
     *
     * all their cells are already in the block array, the origins let Level register them as objects so they break and save as one
     */
    public static final class Result {

        public final byte[] blocks;
        public final List<int[]> multiTileOrigins;

        /**
         * water, per column (index z * width + x): the y of the water surface, or -1 for a dry column. null = no water at all
         *
         * water isn't in the block array (it lives in the level's LiquidLayer), so it is reported separately.
         * Level floods each of these columns from that y down until it hits a solid block
         */
        public final int[] waterTops;

        public Result(byte[] blocks, List<int[]> multiTileOrigins) {
            this(blocks, multiTileOrigins, null);
        }

        public Result(byte[] blocks, List<int[]> multiTileOrigins, int[] waterTops) {
            this.blocks = blocks;
            this.multiTileOrigins = multiTileOrigins;
            this.waterTops = waterTops;
        }
    }

    public static Result generateWorld(WorldType type, int width, int height, int depth, long seed) {
        if (type == WorldType.NATURAL) {
            return NaturalLevelGenerator.generate(width, height, depth, seed, false);
        }
        if (type == WorldType.ISLAND) {
            return NaturalLevelGenerator.generate(width, height, depth, seed, true);
        }
        return new Result(FlatLevelGenerator.generate(width, height, depth), new ArrayList<>());
    }

    /** blocks only, prefer generateWorld which also reports multi-tile objects */
    public static byte[] generate(WorldType type, int width, int height, int depth, long seed) {
        return generateWorld(type, width, height, depth, seed).blocks;
    }

    /** prints how long a world creation phase took (ms since mark) and returns the new mark, to see where the time goes */
    public static long lap(String phase, long mark) {
        long now = System.nanoTime();
        System.out.println("[worldgen] " + phase + ": " + (now - mark) / 1_000_000L + " ms");
        return now;
    }

    public static long parseSeed(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }
}