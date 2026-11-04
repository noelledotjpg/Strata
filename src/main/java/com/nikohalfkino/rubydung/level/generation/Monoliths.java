package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.nikohalfkino.rubydung.level.generation.NaturalLevelGenerator.buildLattice;
import static com.nikohalfkino.rubydung.level.generation.NaturalLevelGenerator.sampleLattice;

/**
 * Rock monoliths: flat-topped mountain formations (a wide hub with a few arms) stamped onto dry land.
 * Moved out of NaturalLevelGenerator, which calls stamp.
 */
final class Monoliths {

    private Monoliths() {
    }

    private static final int MONOLITH_MAX_COUNT = 3;

    private static final int HUB_RADIUS_MIN = 72;
    private static final int HUB_RADIUS_MAX = 90;
    private static final int ARM_COUNT_MIN = 1;
    private static final int ARM_COUNT_MAX = 3;
    private static final int ARM_SEGMENTS_MIN = 1;
    private static final int ARM_SEGMENTS_MAX = 2;
    private static final float ARM_SEGMENT_SHRINK_MIN = 0.2f;
    private static final float ARM_SEGMENT_SHRINK_MAX = 0.80f;
    private static final int ARM_SEGMENT_MIN_RADIUS = 5;
    private static final float ARM_STEP_OVERLAP = 0.55f;
    private static final float ARM_DRIFT = (float) (Math.PI / 5);

    static boolean[][] stamp(Random random, int[][] heightMap, int width, int height, int depth, int seaLevel) {
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
            // mountains rise from the island, not from the sea: the hub needs dry land in the middle and all around it
            if (!isDryLand(heightMap, width, height, hubCx, hubCz, Math.round(hubRadius * 0.9f), seaLevel)) {
                continue;
            }

            int hubHeight = Level.GROUND_HEIGHT + hubHeightAboveGround + random.nextInt(hubHeightVariance);
            hubHeight = Math.min(hubHeight, depth - topClearance);
            if (hubHeight <= Level.GROUND_HEIGHT) {
                continue;
            }

            stampLobe(random, heightMap, baseHeightMap, mask, width, height, hubCx, hubCz, hubRadius, hubHeight,
                    depth, topClearance, seaLevel);

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
                            segRadius, segHeight, depth, topClearance, seaLevel);

                    prevRadius = segRadius;
                    prevHeight = segHeight;
                }
            }
        }

        return mask;
    }

    // the centre and 8 points around it are all above the sea
    private static boolean isDryLand(int[][] heightMap, int width, int height, int cx, int cz, int radius, int seaLevel) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = Math.max(0, Math.min(width - 1, cx + dx * radius));
                int z = Math.max(0, Math.min(height - 1, cz + dz * radius));
                if (heightMap[x][z] < seaLevel) {
                    return false;
                }
            }
        }
        return true;
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
                                  int depth, int topClearance, int seaLevel) {
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
                // arms that reach the shore stop there instead of building rock towers in the water
                if (baseHeightMap[x][z] < seaLevel) {
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
}