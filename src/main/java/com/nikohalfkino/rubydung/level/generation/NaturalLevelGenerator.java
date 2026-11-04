package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

final class NaturalLevelGenerator {

    private NaturalLevelGenerator() {
    }

    /** Which kind of world to make. */
    enum Style {
        /** plain inland terrain: grass everywhere, no water, mountains and archways only */
        INLAND,
        /** one main island (sometimes with satellites): the ocean, lakes, beaches, sea stacks and sea arches (Shoreline) */
        ISLAND,
        /** many islands scattered across the whole map, with stepped terraces (Archipelago) */
        ARCHIPELAGO
    }

    static LevelGenerator.Result generate(int width, int height, int depth, long seed, boolean island) {
        return generate(width, height, depth, seed, island ? Style.ISLAND : Style.INLAND);
    }

    static LevelGenerator.Result generate(int width, int height, int depth, long seed, Style style) {
        boolean island = style != Style.INLAND;
        boolean archipelago = style == Style.ARCHIPELAGO;

        long mark = System.nanoTime();
        byte[] blocks = new byte[width * height * depth];

        WorldGenStatus.set("Raising the land...");
        Random random = new Random(seed);
        // the archipelago builds its own terrain, so it skips the plateau and mountain height map
        int[][] heightMap = archipelago ? new int[width][height] : buildHeightMap(random, width, height);
        mark = LevelGenerator.lap("height map", mark);

        Shoreline shoreline = null;
        int seaLevel = Integer.MIN_VALUE;   // no sea, so nothing counts as under water

        if (island) {
            // ocean, lakes and beaches. this has its own Random, so the land below is the same for a given seed
            WorldGenStatus.set("Carving coastlines...");
            shoreline = new Shoreline(seed);
            if (archipelago) {
                WorldGenStatus.set("Scattering islands...");
                Archipelago.shape(heightMap, width, height, depth, seed);
            } else {
                shoreline.carve(heightMap, width, height);
            }
            mark = LevelGenerator.lap("shoreline", mark);

            WorldGenStatus.set("Eroding sea stacks...");
            shoreline.stampSeaStacks(heightMap, width, height);
            mark = LevelGenerator.lap("sea stacks", mark);

            seaLevel = Shoreline.SEA_LEVEL;
        }

        WorldGenStatus.set("Pushing up mountains...");

        // the archipelago has its own mountains in the terrain, so no extra monoliths on top
        boolean[][] monolithMask = archipelago
                ? new boolean[width][height]
                : Monoliths.stamp(random, heightMap, width, height, depth, seaLevel);
        Archway archway = Archway.find(random, heightMap, width, height);
        // assigned once in each branch so the chunk lambda below can capture them
        final List<SeaStacks.Bore> bores;
        final boolean[][] boreColumns;
        final byte[][] surface;
        if (island) {
            bores = shoreline.bores();
            boreColumns = shoreline.boreColumns(width, height);
            surface = shoreline.buildSurface(heightMap, monolithMask, width, height);
        } else {
            bores = new ArrayList<>();
            boreColumns = new boolean[width][height];
            surface = new byte[width][height];
            for (int x = 0; x < width; x++) {
                java.util.Arrays.fill(surface[x], (byte) Tile.GRASS.id);
            }
        }
        mark = LevelGenerator.lap("mountains + surface", mark);

        WorldGenStatus.set("Filling in the ground...");
        int chunkSize = Chunk.SIZE;
        int chunksX = (width + chunkSize - 1) / chunkSize;
        int chunksY = (depth + chunkSize - 1) / chunkSize;
        int chunksZ = (height + chunkSize - 1) / chunkSize;

        // every chunk column (all the chunks stacked at one x, z) writes its own part of the block array, so they run on all cores
        IntStream.range(0, chunksX * chunksZ).parallel().forEach(column -> {
            int chunkX = column / chunksZ;
            int chunkZ = column % chunksZ;
            for (int chunkY = 0; chunkY < chunksY; chunkY++) {
                generateChunk(blocks, heightMap, surface, width, height, chunkSize, chunkX, chunkY, chunkZ, depth, archway, bores, boreColumns);
            }
        });
        mark = LevelGenerator.lap("blocks", mark);

        WorldGenStatus.set("Planting trees...");
        List<int[]> multiTileOrigins = new ArrayList<>();
        scatterTrees(random, blocks, heightMap, monolithMask, width, height, depth, multiTileOrigins);
        mark = LevelGenerator.lap("trees", mark);

        int[] waterTops = island ? shoreline.waterTops(heightMap, width, height) : null;
        return new LevelGenerator.Result(blocks, multiTileOrigins, waterTops);
    }

    private static void generateChunk(byte[] blocks, int[][] heightMap, byte[][] surface, int width, int height, int chunkSize,
                                      int chunkX, int chunkY, int chunkZ, int depth, Archway archway,
                                      List<SeaStacks.Bore> bores, boolean[][] boreColumns) {
        int minX = chunkX * chunkSize;
        int minY = chunkY * chunkSize;
        int minZ = chunkZ * chunkSize;

        int maxX = Math.min(width, minX + chunkSize);
        int maxY = Math.min(depth, minY + chunkSize);
        int maxZ = Math.min(height, minZ + chunkSize);

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                int groundY = heightMap[x][z];
                byte surfaceTile = surface[x][z];
                for (int y = minY; y < maxY; y++) {
                    int index = (y * height + z) * width + x;
                    byte tile = tileAt(y, groundY, surfaceTile);
                    if (tile != 0 && Archway.contains(archway, x, y, z)) {
                        tile = 0;
                    }
                    if (tile != 0 && boreColumns[x][z] && isInsideAnyBore(bores, x, y, z)) {
                        tile = 0;   // a sea arch
                    }
                    blocks[index] = tile;
                }
            }
        }
    }

    private static boolean isInsideAnyBore(List<SeaStacks.Bore> bores, int x, int y, int z) {
        for (SeaStacks.Bore bore : bores) {
            if (bore.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static byte tileAt(int y, int groundY, byte surfaceTile) {
        if (y > groundY) {
            return 0;
        }
        if (y == groundY) {
            return surfaceTile;
        }
        // sand and gravel go a few blocks deep so a shore doesn't turn to cobblestone the moment you dig it
        if (surfaceTile != (byte) Tile.GRASS.id && y > groundY - Shoreline.SURFACE_DEPTH) {
            return surfaceTile;
        }
        return (byte) Tile.COBBLESTONE.id;
    }

    private static int[][] buildHeightMap(Random random, int width, int height) {
        int primaryCellSize = 56;
        int primaryAmplitude = 8;

        int secondaryCellSize = 32;
        int secondaryAmplitude = 5;

        int stepHeight = 4;

        int regionCellSize = 80;
        float flatBias = 0.5f;
        float transitionWidth = 0.12f;

        int mountainCellSize = 90;
        float mountainBaseThreshold = 0.75f;
        float mountainCornerBoost = 0.55f;
        float mountainTransitionWidth = 0.15f;
        float mountainAmplitudeScale = 2.2f;

        float mountainBaseBoost = 26.0f;

        float[][] primaryLattice = buildLattice(random, width, height, primaryCellSize);
        float[][] secondaryLattice = buildLattice(random, width, height, secondaryCellSize);
        float[][] regionLattice = buildLattice(random, width, height, regionCellSize);
        float[][] mountainLattice = buildLattice(random, width, height, mountainCellSize);

        int plainDetailCellSize = 16;
        float plainDetailAmplitude = 1.5f;

        float[][] plainDetailLattice = buildLattice(random, width, height, plainDetailCellSize);

        // every column only reads the lattices and writes its own cell, so the rows run on all cores
        int[][] heightMap = new int[width][height];
        IntStream.range(0, width).parallel().forEach(x -> {
            for (int z = 0; z < height; z++) {
                float plainDetail = sampleLattice(plainDetailLattice, plainDetailCellSize, x, z);
                float flatHeight = Level.GROUND_HEIGHT + (plainDetail - 0.5f) * 2 * plainDetailAmplitude;

                float mountainNoise = sampleLattice(mountainLattice, mountainCellSize, x, z);
                float cornerBias = cornerCloseness(x, z, width, height);
                float mountainThreshold = mountainBaseThreshold - cornerBias * mountainCornerBoost;
                float mountainAmount = smoothstep(clamp01(
                        (mountainNoise - mountainThreshold) / mountainTransitionWidth));
                float elevationScale = lerp(1.0f, mountainAmplitudeScale, mountainAmount);

                float primary = sampleLattice(primaryLattice, primaryCellSize, x, z);
                float secondary = sampleLattice(secondaryLattice, secondaryCellSize, x, z);
                float rawHeight = Level.GROUND_HEIGHT
                        + (primary - 0.5f) * 2 * primaryAmplitude * elevationScale
                        + (secondary - 0.5f) * 2 * secondaryAmplitude * elevationScale
                        + mountainAmount * mountainBaseBoost;
                int plateauHeight = Math.round(rawHeight / stepHeight) * stepHeight;

                float region = sampleLattice(regionLattice, regionCellSize, x, z);
                float plateauAmount = smoothstep(clamp01((region - flatBias) / transitionWidth));

                heightMap[x][z] = Math.round(lerp(flatHeight, plateauHeight, plateauAmount));
            }
        });

        return heightMap;
    }

    private static void scatterTrees(Random random, byte[] blocks, int[][] heightMap, boolean[][] monolithMask,
                                     int width, int height, int depth, List<int[]> multiTileOrigins) {
        int cellSize = 6;
        float treeChance = 0.35f;
        float largeChance = 0.35f;

        for (int cellX = 0; cellX < width; cellX += cellSize) {
            for (int cellZ = 0; cellZ < height; cellZ += cellSize) {
                if (random.nextFloat() > treeChance) {
                    continue;
                }

                int cellWidth = Math.min(cellSize, width - cellX);
                int cellHeight = Math.min(cellSize, height - cellZ);
                int x = cellX + random.nextInt(cellWidth);
                int z = cellZ + random.nextInt(cellHeight);

                int groundY = heightMap[x][z];
                int treeY = groundY + 1;
                if (treeY <= 0 || treeY >= depth) {
                    continue;
                }

                int groundIndex = (groundY * height + z) * width + x;
                if (blocks[groundIndex] != (byte) Tile.GRASS.id) {
                    continue;
                }

                Tile tree = random.nextFloat() < largeChance ? Tile.TREE_LARGE : Tile.TREE_MEDIUM;

                // Trees occupy their whole height, so skip the spot if the tree doesn't fit.
                if (!footprintIsFree(blocks, tree, x, treeY, z, width, height, depth)) {
                    continue;
                }

                stampTile(blocks, tree, x, treeY, z, width, height);
                if (tree.isMultiTile()) {
                    multiTileOrigins.add(new int[]{x, treeY, z});
                }
            }
        }
    }

    // whole footprint (unrotated, from its minimum corner) is in bounds and empty
    private static boolean footprintIsFree(byte[] blocks, Tile tile, int x, int y, int z,
                                           int width, int height, int depth) {
        int sizeX = tile.getSizeX(0);
        int sizeY = tile.getSizeY();
        int sizeZ = tile.getSizeZ(0);

        if (x + sizeX > width || y + sizeY > depth || z + sizeZ > height) {
            return false;
        }

        for (int cy = y; cy < y + sizeY; cy++) {
            for (int cz = z; cz < z + sizeZ; cz++) {
                for (int cx = x; cx < x + sizeX; cx++) {
                    if (blocks[(cy * height + cz) * width + cx] != 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // tile id into every cell of its footprint (rotation 0, the data array's default)
    private static void stampTile(byte[] blocks, Tile tile, int x, int y, int z, int width, int height) {
        for (int cy = y; cy < y + tile.getSizeY(); cy++) {
            for (int cz = z; cz < z + tile.getSizeZ(0); cz++) {
                for (int cx = x; cx < x + tile.getSizeX(0); cx++) {
                    blocks[(cy * height + cz) * width + cx] = (byte) tile.id;
                }
            }
        }
    }

    static float[][] buildLattice(Random random, int width, int height, int cellSize) {
        int latticeWidth = width / cellSize + 2;
        int latticeHeight = height / cellSize + 2;

        float[][] lattice = new float[latticeWidth][latticeHeight];
        for (int i = 0; i < latticeWidth; i++) {
            for (int j = 0; j < latticeHeight; j++) {
                lattice[i][j] = random.nextFloat();
            }
        }
        return lattice;
    }

    static float sampleLattice(float[][] lattice, int cellSize, int x, int z) {
        float gridX = x / (float) cellSize;
        float gridZ = z / (float) cellSize;

        int latticeX = (int) gridX;
        int latticeZ = (int) gridZ;
        float fracX = smoothstep(gridX - latticeX);
        float fracZ = smoothstep(gridZ - latticeZ);

        float top = lerp(lattice[latticeX][latticeZ], lattice[latticeX + 1][latticeZ], fracX);
        float bottom = lerp(lattice[latticeX][latticeZ + 1], lattice[latticeX + 1][latticeZ + 1], fracX);
        return lerp(top, bottom, fracZ);
    }

    private static float cornerCloseness(int x, int z, int width, int height) {
        float distFromCenterX = Math.abs(x / (float) width - 0.5f) * 2.0f;
        float distFromCenterZ = Math.abs(z / (float) height - 0.5f) * 2.0f;
        return distFromCenterX * distFromCenterZ;
    }

    private static float clamp01(float t) {
        return Math.max(0.0f, Math.min(1.0f, t));
    }

    private static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}