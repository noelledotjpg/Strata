package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.generation.noise.Fbm;

import java.util.Random;
import java.util.stream.IntStream;

/**
 * The archipelago preset: many islands of different sizes, scattered over the whole map with open water
 * (straits) between them.
 *
 * Instead of planning islands out of circles like Shoreline does, this builds one smooth "elevation above
 * sea" field out of big, slow noise and lets the sea flood everything below zero. That is the same thing
 * you get by raising the water level on the stepped terrain: islands are whatever pokes out, and their
 * coasts follow the contours of the land. On top of that the land above the beach is cut into 4-block
 * terraces, so the islands get the same stepped look as the terrain in the flooded screenshot.
 *
 * Everything is sized as a share of the map's smaller side, so a bigger world gets bigger islands (and
 * more of them) rather than the same islands further apart.
 *
 * It only writes the height map. Surface blocks, water columns and sea stacks still come from Shoreline,
 * so beaches, gravel, sea stacks and arches all work as before. It has its own Random, so nothing else
 * in the generator moves.
 */
final class Archipelago {

    // ---- where the land is ----

    /** Size of one big landmass, as a share of the map's smaller side. Smaller = more, smaller islands. */
    private static final float ISLAND_WAVELENGTH = 0.30f;
    /** Size of the bumps that break up the big shapes and make separate islets. */
    private static final float DETAIL_WAVELENGTH = 0.085f;
    private static final float DETAIL_STRENGTH = 0.40f;
    /** Raise for less land and more sea, lower for fatter islands that run together. */
    private static final float LAND_THRESHOLD = 0.05f;
    /** Blocks of height per unit of the field. Bigger = taller, steeper islands. */
    private static final float HEIGHT_SCALE = 48.0f;
    /** How much the coast is bent, as a share of the map's smaller side. */
    private static final float WARP = 0.03f;

    // ---- the map's middle, where the player starts ----

    private static final float SPAWN_CLEAR = 0.04f;   // share of the map's smaller side that is guaranteed land
    private static final float SPAWN_FADE = 0.09f;
    private static final float SPAWN_IRREGULAR = 0.35f;   // how much the noise bends that distance (0 = a round blob)
    private static final float SPAWN_LIFT = 0.14f;    // how far above the waterline the field is held there

    // ---- the map's edge ----

    // Measured with a rounded metric (see shape), so islands near a corner are not cut off in straight lines.
    // The fall-off is wide and gradual, so islands shrink toward the edge instead of being sliced.
    private static final float EDGE_START = 0.72f;
    private static final float EDGE_WIDTH = 0.28f;
    private static final float EDGE_PUSH = 2.2f;

    // ---- the shore and the terraces ----

    private static final float BEACH_MIN = 1.0f;      // sand flat width, in blocks of height (not of distance)
    private static final float BEACH_MAX = 4.0f;
    private static final int GRASS_START = 2;         // the first grass terrace, in blocks above the sea
    private static final int TERRACE_STEP = 4;
    private static final int TOP_CLEARANCE = 4;

    private Archipelago() {
    }

    /** Fills the whole height map: islands, shelves, sea floor. Shoreline's sea level is the waterline. */
    static void shape(int[][] heightMap, int width, int height, int depth, long seed) {
        Random random = new Random(seed ^ 0xA2C41DE1A60L);

        float size = Math.min(width, height);

        Fbm warpA = new Fbm(random, 3, 0.010f, 0.45f);
        Fbm warpB = new Fbm(random, 3, 0.010f, 0.45f);
        Fbm broad = new Fbm(random, 3, 1.0f / (size * ISLAND_WAVELENGTH), 0.45f);
        Fbm detail = new Fbm(random, 3, 1.0f / (size * DETAIL_WAVELENGTH), 0.5f);
        Fbm terraceNoise = new Fbm(random, 2, 1.0f / (size * 0.12f), 0.5f);
        Fbm beachNoise = new Fbm(random, 3, 0.012f, 0.5f);
        Fbm ripple = new Fbm(random, 3, 0.09f, 0.5f);

        float warpAmount = WARP * size;
        float middleX = (width - 1) * 0.5f;
        float middleZ = (height - 1) * 0.5f;
        int topLimit = depth - TOP_CLEARANCE;

        IntStream.range(0, width).parallel().forEach(x -> {
            float distX = Math.abs(x / (float) (width - 1) - 0.5f) * 2.0f;

            for (int z = 0; z < height; z++) {
                float distZ = Math.abs(z / (float) (height - 1) - 0.5f) * 2.0f;
                float edgeX = Math.max(0.0f, distX - EDGE_START);
                float edgeZ = Math.max(0.0f, distZ - EDGE_START);
                float edge = (float) Math.sqrt(edgeX * edgeX + edgeZ * edgeZ);

                // Bend the position so the contours are not as smooth as the noise.
                float px = x + warpA.get(x, z) * warpAmount;
                float pz = z + warpB.get(x, z) * warpAmount;

                float field = broad.get(px, pz) + detail.get(px, pz) * DETAIL_STRENGTH;

                // Open sea at the edge of the map.
                field -= smoothstep(clamp01(edge / EDGE_WIDTH)) * EDGE_PUSH;

                // The middle of the map is always land.
                float dx = x - middleX;
                float dz = z - middleZ;
                // The noise stretches and squeezes the distance, so the land around the spawn is not a round blob.
                float fromMiddle = (float) Math.sqrt(dx * dx + dz * dz)
                        * (1.0f + SPAWN_IRREGULAR * detail.get(px, pz));
                float spawn = 1.0f - smoothstep(clamp01((fromMiddle - SPAWN_CLEAR * size) / (SPAWN_FADE * size)));
                field = lerp(field, Math.max(field, LAND_THRESHOLD + SPAWN_LIFT), spawn);

                // Blocks above (+) or below (-) the waterline, before terracing.
                float above = (field - LAND_THRESHOLD) * HEIGHT_SCALE;
                float wave = ripple.get(x, z);
                float ground;

                if (above <= 0.0f) {
                    // Steep next to the coast, then a shallow shelf, then the deep floor.
                    float away = -above;
                    float below = 1.0f
                            + smoothstep(clamp01(away / 4.0f))
                            + clamp01(away / 12.0f) * 3.0f
                            + smoothstep(clamp01((away - 12.0f) / 14.0f)) * 4.0f;

                    ground = Shoreline.SEA_LEVEL - below + wave * 0.4f;
                } else {
                    float flat = lerp(BEACH_MIN, BEACH_MAX, clamp01(beachNoise.get(x, z) * 0.5f + 0.5f));

                    if (above < flat) {
                        // The beach: a flat of sand right at the waterline.
                        ground = Shoreline.SEA_LEVEL + 0.5f + wave * 0.6f;
                    } else {
                        // Inland: grass terraces. Some areas are cut into clean steps, some stay as slopes.
                        float inland = above - flat;
                        float smooth = GRASS_START + inland;
                        float stepped = GRASS_START + TERRACE_STEP * Math.round(inland / TERRACE_STEP);
                        float terraced = smoothstep(clamp01((terraceNoise.get(x, z) + 0.35f) / 0.5f));

                        ground = Shoreline.SEA_LEVEL + lerp(smooth, stepped, terraced);
                    }
                }

                heightMap[x][z] = Math.max(1, Math.min(topLimit, Math.round(ground)));
            }
        });
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