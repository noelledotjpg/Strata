package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.Tiles.Tile;

final class FlatLevelGenerator {

    private FlatLevelGenerator() {
    }

    static byte[] generate(int width, int height, int depth) {
        byte[] blocks = new byte[width * height * depth];

        int chunkSize = Chunk.SIZE;
        int chunksX = (width + chunkSize - 1) / chunkSize;
        int chunksY = (depth + chunkSize - 1) / chunkSize;
        int chunksZ = (height + chunkSize - 1) / chunkSize;

        for (int chunkX = 0; chunkX < chunksX; chunkX++) {
            for (int chunkY = 0; chunkY < chunksY; chunkY++) {
                for (int chunkZ = 0; chunkZ < chunksZ; chunkZ++) {
                    generateChunk(blocks, width, height, chunkSize, chunkX, chunkY, chunkZ, depth);
                }
            }
        }

        return blocks;
    }

    private static void generateChunk(byte[] blocks, int width, int height, int chunkSize,
                                      int chunkX, int chunkY, int chunkZ, int depth) {
        int minX = chunkX * chunkSize;
        int minY = chunkY * chunkSize;
        int minZ = chunkZ * chunkSize;

        int maxX = Math.min(width, minX + chunkSize);
        int maxY = Math.min(depth, minY + chunkSize);
        int maxZ = Math.min(height, minZ + chunkSize);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    int index = (y * height + z) * width + x;
                    blocks[index] = tileAt(y);
                }
            }
        }
    }

    private static byte tileAt(int y) {
        if (y < Level.GROUND_HEIGHT) {
            return (byte) Tile.COBBLESTONE.id;
        }
        if (y == Level.GROUND_HEIGHT) {
            return (byte) Tile.GRASS.id;
        }
        return 0;
    }
}