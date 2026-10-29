package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.Tiles.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class NaturalLevelGenerator {

    private NaturalLevelGenerator() {
    }

    static byte[] generate(int width, int height, int depth, long seed) {
        byte[] blocks = new byte[width * height * depth];

        Random random = new Random(seed);
        int[][] heightMap = buildHeightMap(random, width, height);
        boolean[][] monolithMask = stampMonoliths(random, heightMap, width, height, depth);
        Archway archway = findArchwaySite(random, heightMap, width, height);

        int chunkSize = Chunk.SIZE;
        int chunksX = (width + chunkSize - 1) / chunkSize;
        int chunksY = (depth + chunkSize - 1) / chunkSize;
        int chunksZ = (height + chunkSize - 1) / chunkSize;

        for (int chunkX = 0; chunkX < chunksX; chunkX++) {
            for (int chunkY = 0; chunkY < chunksY; chunkY++) {
                for (int chunkZ = 0; chunkZ < chunksZ; chunkZ++) {
                    generateChunk(blocks, heightMap, width, height, chunkSize, chunkX, chunkY, chunkZ, depth, archway);
                }
            }
        }

        scatterTrees(random, blocks, heightMap, monolithMask, width, height, depth);

        return blocks;
    }

    private static void generateChunk(byte[] blocks, int[][] heightMap, int width, int height, int chunkSize,
                                      int chunkX, int chunkY, int chunkZ, int depth, Archway archway) {
        int minX = chunkX * chunkSize;
        int minY = chunkY * chunkSize;
        int minZ = chunkZ * chunkSize;

        int maxX = Math.min(width, minX + chunkSize);
        int maxY = Math.min(depth, minY + chunkSize);
        int maxZ = Math.min(height, minZ + chunkSize);

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                int groundY = heightMap[x][z];
                for (int y = minY; y < maxY; y++) {
                    int index = (y * height + z) * width + x;
                    byte tile = tileAt(y, groundY);
                    if (tile != 0 && isInsideArchway(archway, x, y, z)) {
                        tile = 0;
                    }
                    blocks[index] = tile;
                }
            }
        }
    }

    private static byte tileAt(int y, int groundY) {
        if (y < groundY) {
            return (byte) Tile.COBBLESTONE.id;
        }
        if (y == groundY) {
            return (byte) Tile.GRASS.id;
        }
        return 0;
    }

    private static final int MONOLITH_MAX_COUNT = 2;

    private static final int HUB_RADIUS_MIN = 20;
    private static final int HUB_RADIUS_MAX = 34;
    private static final int ARM_COUNT_MIN = 2;
    private static final int ARM_COUNT_MAX = 4;
    private static final int ARM_SEGMENTS_MIN = 2;
    private static final int ARM_SEGMENTS_MAX = 4;
    private static final float ARM_SEGMENT_SHRINK_MIN = 0.55f;
    private static final float ARM_SEGMENT_SHRINK_MAX = 0.80f;
    private static final int ARM_SEGMENT_MIN_RADIUS = 9;
    private static final float ARM_STEP_OVERLAP = 0.55f;
    private static final float ARM_DRIFT = (float) (Math.PI / 5);

    private static boolean[][] stampMonoliths(Random random, int[][] heightMap, int width, int height, int depth) {
        boolean[][] mask = new boolean[width][height];

        if (random.nextFloat() < 0.60f) {
            return mask;
        }

        int[][] baseHeightMap = new int[width][height];
        for (int x = 0; x < width; x++) {
            baseHeightMap[x] = heightMap[x].clone();
        }

        int hubHeightAboveGround = 42;
        int hubHeightVariance = 18;
        int armHeightTaper = 5;
        int armHeightJitter = 3;
        int minHeightAboveGround = 18;
        int topClearance = 4;

        float maxReach = HUB_RADIUS_MAX;
        float reachRadius = HUB_RADIUS_MAX;
        for (int s = 0; s < ARM_SEGMENTS_MAX; s++) {
            float segRadius = Math.max(ARM_SEGMENT_MIN_RADIUS, reachRadius * ARM_SEGMENT_SHRINK_MAX);
            maxReach += (reachRadius + segRadius) * ARM_STEP_OVERLAP;
            reachRadius = segRadius;
        }
        int formationExtent = Math.round(maxReach) + LOBE_EDGE_JITTER;

        int formationCount = random.nextInt(MONOLITH_MAX_COUNT + 1);
        List<int[]> placedFormations = new ArrayList<>();

        for (int i = 0; i < formationCount; i++) {
            int margin = formationExtent + LOBE_BASE_BLEND_WIDTH + 10;
            if (width <= margin * 2 || height <= margin * 2) {
                continue;
            }

            int hubCx = margin + random.nextInt(width - margin * 2);
            int hubCz = margin + random.nextInt(height - margin * 2);

            boolean tooClose = false;
            for (int[] other : placedFormations) {
                int dx = hubCx - other[0];
                int dz = hubCz - other[1];
                int minGap = formationExtent * 2 + 20;
                if (dx * dx + dz * dz < minGap * minGap) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            placedFormations.add(new int[]{hubCx, hubCz});

            int hubRadius = HUB_RADIUS_MIN + random.nextInt(HUB_RADIUS_MAX - HUB_RADIUS_MIN + 1);
            int hubHeight = Level.GROUND_HEIGHT + hubHeightAboveGround + random.nextInt(hubHeightVariance);
            hubHeight = Math.min(hubHeight, depth - topClearance);
            if (hubHeight <= Level.GROUND_HEIGHT) {
                continue;
            }

            stampLobe(random, heightMap, baseHeightMap, mask, width, height, hubCx, hubCz, hubRadius, hubHeight,
                    depth, topClearance);

            int armCount = ARM_COUNT_MIN + random.nextInt(ARM_COUNT_MAX - ARM_COUNT_MIN + 1);
            for (int a = 0; a < armCount; a++) {
                float angle = random.nextFloat() * 2.0f * (float) Math.PI;
                float cx = hubCx;
                float cz = hubCz;
                float prevRadius = hubRadius;
                int prevHeight = hubHeight;

                int segmentCount = ARM_SEGMENTS_MIN + random.nextInt(ARM_SEGMENTS_MAX - ARM_SEGMENTS_MIN + 1);
                for (int s = 0; s < segmentCount; s++) {
                    float shrink = ARM_SEGMENT_SHRINK_MIN
                            + random.nextFloat() * (ARM_SEGMENT_SHRINK_MAX - ARM_SEGMENT_SHRINK_MIN);
                    int segRadius = Math.max(ARM_SEGMENT_MIN_RADIUS, Math.round(prevRadius * shrink));

                    angle += (random.nextFloat() - 0.5f) * 2 * ARM_DRIFT;
                    float stepDist = (prevRadius + segRadius) * ARM_STEP_OVERLAP;
                    cx += (float) Math.cos(angle) * stepDist;
                    cz += (float) Math.sin(angle) * stepDist;

                    int segHeight = prevHeight - armHeightTaper
                            + Math.round((random.nextFloat() - 0.5f) * 2 * armHeightJitter);
                    segHeight = Math.max(Level.GROUND_HEIGHT + minHeightAboveGround, segHeight);
                    segHeight = Math.min(segHeight, depth - topClearance);

                    stampLobe(random, heightMap, baseHeightMap, mask, width, height, Math.round(cx), Math.round(cz),
                            segRadius, segHeight, depth, topClearance);

                    prevRadius = segRadius;
                    prevHeight = segHeight;
                }
            }
        }

        return mask;
    }

    private static final int LOBE_EDGE_JITTER = 6;
    private static final int LOBE_MIN_FACETS = 5;
    private static final int LOBE_MAX_FACETS = 9;
    private static final int LOBE_TOP_JITTER = 2;
    private static final int LOBE_TOP_CELL_SIZE = 20;
    private static final int LOBE_BASE_BLEND_WIDTH = 4;
    private static final int LOBE_BASE_BLEND_BUMP = 3;

    private static void stampLobe(Random random, int[][] heightMap, int[][] baseHeightMap, boolean[][] mask,
                                  int width, int height, int cx, int cz, int coreRadius, int baseHeight,
                                  int depth, int topClearance) {
        int facetCount = LOBE_MIN_FACETS + random.nextInt(LOBE_MAX_FACETS - LOBE_MIN_FACETS + 1);
        float[] facetRadius = new float[facetCount];
        for (int f = 0; f < facetCount; f++) {
            facetRadius[f] = coreRadius + (random.nextFloat() - 0.5f) * 2 * LOBE_EDGE_JITTER;
        }

        int footprintRadius = coreRadius + LOBE_EDGE_JITTER;
        float[][] topLattice = buildLattice(random, footprintRadius * 2 + 1, footprintRadius * 2 + 1,
                LOBE_TOP_CELL_SIZE);

        int blendRadius = footprintRadius + LOBE_BASE_BLEND_WIDTH;
        for (int lx = -blendRadius; lx <= blendRadius; lx++) {
            for (int lz = -blendRadius; lz <= blendRadius; lz++) {
                int x = cx + lx;
                int z = cz + lz;
                if (x < 0 || z < 0 || x >= width || z >= height) {
                    continue;
                }

                float dist = (float) Math.sqrt((float) (lx * lx + lz * lz));
                float angle = (float) Math.atan2(lz, lx);
                if (angle < 0) {
                    angle += (float) (2 * Math.PI);
                }
                int facet = (int) (angle / (2 * Math.PI) * facetCount) % facetCount;
                float effectiveRadius = facetRadius[facet];

                if (dist <= effectiveRadius) {
                    float topNoise = sampleLattice(topLattice, LOBE_TOP_CELL_SIZE,
                            lx + footprintRadius, lz + footprintRadius);
                    int columnHeight = baseHeight + Math.round((topNoise - 0.5f) * 2 * LOBE_TOP_JITTER);
                    columnHeight = Math.min(columnHeight, depth - topClearance);
                    if (columnHeight <= Level.GROUND_HEIGHT) {
                        continue;
                    }

                    heightMap[x][z] = Math.max(heightMap[x][z], columnHeight);
                    mask[x][z] = true;
                } else if (dist <= effectiveRadius + LOBE_BASE_BLEND_WIDTH && !mask[x][z]) {
                    float blendT = (dist - effectiveRadius) / LOBE_BASE_BLEND_WIDTH;
                    int bump = Math.round(LOBE_BASE_BLEND_BUMP * (1.0f - blendT));
                    heightMap[x][z] = Math.max(heightMap[x][z], baseHeightMap[x][z] + bump);
                }
            }
        }
    }

    private static final float ARCHWAY_CHANCE = 0.35f;
    private static final int ARCHWAY_MIN_MOUNTAIN_HEIGHT_ABOVE_GROUND = 24;
    private static final int ARCHWAY_HALF_LENGTH_MIN = 6;
    private static final int ARCHWAY_HALF_LENGTH_VARIANCE = 3;
    private static final int ARCHWAY_RADIUS_MIN = 3;
    private static final int ARCHWAY_RADIUS_VARIANCE = 2;
    private static final int ARCHWAY_ROOF_CLEARANCE = 2;

    private static final class Archway {
        final int cx, cz;
        final boolean alongX;
        final int centerY;
        final int radiusY;
        final int radiusAcross;
        final int halfLength;

        Archway(int cx, int cz, boolean alongX, int centerY, int radiusY, int radiusAcross, int halfLength) {
            this.cx = cx;
            this.cz = cz;
            this.alongX = alongX;
            this.centerY = centerY;
            this.radiusY = radiusY;
            this.radiusAcross = radiusAcross;
            this.halfLength = halfLength;
        }
    }

    private static Archway findArchwaySite(Random random, int[][] heightMap, int width, int height) {
        if (random.nextFloat() > ARCHWAY_CHANCE) {
            return null;
        }

        int minMountainHeight = Level.GROUND_HEIGHT + ARCHWAY_MIN_MOUNTAIN_HEIGHT_ABOVE_GROUND;
        int halfLength = ARCHWAY_HALF_LENGTH_MIN + random.nextInt(ARCHWAY_HALF_LENGTH_VARIANCE + 1);
        int radiusAcross = ARCHWAY_RADIUS_MIN + random.nextInt(ARCHWAY_RADIUS_VARIANCE + 1);
        int radiusY = ARCHWAY_RADIUS_MIN + random.nextInt(ARCHWAY_RADIUS_VARIANCE + 1);
        int margin = halfLength + radiusAcross + 4;

        if (width <= margin * 2 || height <= margin * 2) {
            return null;
        }

        List<int[]> candidates = new ArrayList<>();
        for (int x = margin; x < width - margin; x += 4) {
            for (int z = margin; z < height - margin; z += 4) {
                if (heightMap[x][z] >= minMountainHeight) {
                    candidates.add(new int[]{x, z});
                }
            }
        }
        Collections.shuffle(candidates, random);

        for (int[] site : candidates) {
            int cx = site[0];
            int cz = site[1];
            int groundY = heightMap[cx][cz];

            int centerY = Level.GROUND_HEIGHT + (groundY - Level.GROUND_HEIGHT) / 2;
            int neededHeight = centerY + radiusY + ARCHWAY_ROOF_CLEARANCE;

            boolean alongX = random.nextBoolean();
            if (boreClear(heightMap, cx, cz, alongX, halfLength, radiusAcross, neededHeight)) {
                return new Archway(cx, cz, alongX, centerY, radiusY, radiusAcross, halfLength);
            }
            if (boreClear(heightMap, cx, cz, !alongX, halfLength, radiusAcross, neededHeight)) {
                return new Archway(cx, cz, !alongX, centerY, radiusY, radiusAcross, halfLength);
            }
        }

        return null;
    }

    private static boolean boreClear(int[][] heightMap, int cx, int cz, boolean alongX, int halfLength,
                                     int radiusAcross, int neededHeight) {
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -radiusAcross; across <= radiusAcross; across++) {
                int x = alongX ? cx + along : cx + across;
                int z = alongX ? cz + across : cz + along;
                if (heightMap[x][z] < neededHeight) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isInsideArchway(Archway archway, int x, int y, int z) {
        if (archway == null) {
            return false;
        }

        int along = archway.alongX ? x - archway.cx : z - archway.cz;
        if (Math.abs(along) > archway.halfLength) {
            return false;
        }

        int across = archway.alongX ? z - archway.cz : x - archway.cx;
        float dy = (y - archway.centerY) / (float) archway.radiusY;
        float da = across / (float) archway.radiusAcross;
        return dy * dy + da * da <= 1.0f;
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

        int[][] heightMap = new int[width][height];
        for (int x = 0; x < width; x++) {
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
        }

        return heightMap;
    }

    private static void scatterTrees(Random random, byte[] blocks, int[][] heightMap, boolean[][] monolithMask,
                                     int width, int height, int depth) {
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
                int treeIndex = (treeY * height + z) * width + x;
                blocks[treeIndex] = (byte) tree.id;
            }
        }
    }

    private static float[][] buildLattice(Random random, int width, int height, int cellSize) {
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

    private static float sampleLattice(float[][] lattice, int cellSize, int x, int z) {
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