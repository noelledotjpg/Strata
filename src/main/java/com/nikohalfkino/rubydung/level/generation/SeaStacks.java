package com.nikohalfkino.rubydung.level.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sea stacks: trails of rock towers that start on the shore and lead out to sea, shrinking as they go, with
 * ridges between them and (sometimes) a tunnel through a ridge, a sea arch.
 *
 * Shoreline calls it after it has carved the coast. It has its own Random derived from the world seed.
 */
final class SeaStacks {

    private static final int SEA_LEVEL = Shoreline.SEA_LEVEL;

    // ---- sea stacks ----

    private static final int STACK_TRAIL_AREA = 140000;
    private static final float STACK_RADIUS_MIN = 4.0f;
    private static final float STACK_RADIUS_MAX = 7.5f;
    private static final float STACK_LAST_RADIUS = 2.4f;
    private static final int STACK_PEAK_MIN = 8;
    private static final int STACK_PEAK_MAX = 16;
    private static final int STACK_TRAIL_MIN = 2;
    private static final int STACK_TRAIL_MAX = 5;
    private static final int STACK_GAP_MIN = 3;
    private static final int STACK_GAP_MAX = 12;
    private static final float STACK_RIDGE_CHANCE = 0.75f;
    private static final float STACK_ARCH_CHANCE = 0.70f;
    private static final int STACK_ARCH_MIN_GAP = 9;
    private static final int STACK_STEP = 2;
    private static final int STACK_TRAIL_SPACING = 90;

    private final long seed;
    private final List<Bore> bores = new ArrayList<>();

    SeaStacks(long seed) {
        this.seed = seed;
    }

    /** A tunnel through a sea stack ridge. */
    static final class Bore {
        final float cx, cz, cy;
        final float dirX, dirZ;
        final float halfLength, radiusAcross, radiusY;

        Bore(float cx, float cz, float cy, float dirX, float dirZ,
             float halfLength, float radiusAcross, float radiusY) {
            this.cx = cx;
            this.cz = cz;
            this.cy = cy;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.halfLength = halfLength;
            this.radiusAcross = radiusAcross;
            this.radiusY = radiusY;
        }

        int reach() {
            return (int) Math.ceil(this.halfLength + this.radiusAcross) + 1;
        }

        boolean contains(int x, int y, int z) {
            if (y < SEA_LEVEL - 2) {
                return false;
            }

            float rx = x + 0.5f - this.cx;
            float rz = z + 0.5f - this.cz;

            float along = rx * this.dirX + rz * this.dirZ;
            if (Math.abs(along) > this.halfLength) {
                return false;
            }

            float across = (-rx * this.dirZ + rz * this.dirX) / this.radiusAcross;
            float up = (y + 0.5f - this.cy) / this.radiusY;

            return up * up + across * across <= 1.0f;
        }
    }

    /** Every tunnel, for the generator to carve out of the blocks. */
    List<Bore> bores() {
        return this.bores;
    }

    /** True for the columns a tunnel can touch. */
    boolean[][] boreColumns(int width, int height) {
        boolean[][] mask = new boolean[width][height];

        for (Bore bore : this.bores) {
            int reach = bore.reach();

            for (int x = Math.max(0, (int) bore.cx - reach);
                 x <= Math.min(width - 1, (int) bore.cx + reach); x++) {
                for (int z = Math.max(0, (int) bore.cz - reach);
                     z <= Math.min(height - 1, (int) bore.cz + reach); z++) {
                    mask[x][z] = true;
                }
            }
        }

        return mask;
    }

    /**
     * Sea stacks: trails of chunky rock towers that start on the shore and lead
     * out to sea, shrinking as they go.
     */
    void stamp(int[][] heightMap, int width, int height) {
        this.bores.clear();

        Random random = new Random(this.seed ^ 0x57ACC5L);
        List<int[]> used = new ArrayList<>();

        float expected = width * height / (float) STACK_TRAIL_AREA;
        int trails = (int) expected
                + (random.nextFloat() < expected - (int) expected ? 1 : 0);

        for (int attempt = 0;
             attempt < trails * 40 && used.size() < trails;
             attempt++) {

            int sx = 12 + random.nextInt(Math.max(1, width - 24));
            int sz = 12 + random.nextInt(Math.max(1, height - 24));

            int ground = heightMap[sx][sz];

            // A spot of shore: dry and low.
            if (ground < SEA_LEVEL || ground > SEA_LEVEL + 6) {
                continue;
            }

            boolean crowded = false;

            for (int[] other : used) {
                int dx = sx - other[0];
                int dz = sz - other[1];

                if (dx * dx + dz * dz < STACK_TRAIL_SPACING * STACK_TRAIL_SPACING) {
                    crowded = true;
                    break;
                }
            }

            if (crowded) {
                continue;
            }

            // Out to sea: the direction with the longest run of shallow water.
            float bestAngle = 0.0f;
            int bestRun = 0;

            for (int d = 0; d < 16; d++) {
                float angle = d * (float) Math.PI * 2.0f / 16.0f;
                int run = 0;

                for (int step = 3; step <= 48; step += 3) {
                    int x = Math.round(sx + (float) Math.cos(angle) * step);
                    int z = Math.round(sz + (float) Math.sin(angle) * step);

                    if (x < 6 || z < 6 || x >= width - 6 || z >= height - 6) {
                        break;
                    }

                    int g = heightMap[x][z];

                    if (g >= SEA_LEVEL || g < SEA_LEVEL - 11) {
                        break;
                    }

                    run = step;
                }

                if (run > bestRun) {
                    bestRun = run;
                    bestAngle = angle;
                }
            }

            if (bestRun < 24) {
                continue;
            }

            used.add(new int[]{sx, sz});
            growTrail(heightMap, width, height, random, sx, sz, bestAngle);
        }
    }

    private void growTrail(int[][] heightMap, int width, int height,
                           Random random, int startX, int startZ, float angle) {
        int count = STACK_TRAIL_MIN
                + random.nextInt(STACK_TRAIL_MAX - STACK_TRAIL_MIN + 1);

        float radius = STACK_RADIUS_MIN
                + random.nextFloat() * (STACK_RADIUS_MAX - STACK_RADIUS_MIN);

        float peak = STACK_PEAK_MIN
                + random.nextInt(STACK_PEAK_MAX - STACK_PEAK_MIN + 1);

        // The first tower sits half on the shore.
        float x = startX + (float) Math.cos(angle) * radius * 0.5f;
        float z = startZ + (float) Math.sin(angle) * radius * 0.5f;

        float prevX = 0.0f;
        float prevZ = 0.0f;
        float prevRadius = 0.0f;

        for (int i = 0; i < count; i++) {
            float gap = 0.0f;

            if (i > 0) {
                angle += (random.nextFloat() - 0.5f) * 0.7f;

                radius = Math.max(
                        STACK_LAST_RADIUS,
                        radius * (0.72f + random.nextFloat() * 0.20f)
                );

                peak = Math.max(
                        STACK_PEAK_MIN - 3,
                        peak * (0.82f + random.nextFloat() * 0.14f)
                );

                gap = STACK_GAP_MIN
                        + random.nextFloat() * (STACK_GAP_MAX - STACK_GAP_MIN);

                float step = prevRadius + gap + radius;

                x += (float) Math.cos(angle) * step;
                z += (float) Math.sin(angle) * step;
            }

            int cx = Math.round(x);
            int cz = Math.round(z);

            if (cx < 8 || cz < 8 || cx >= width - 8 || cz >= height - 8
                    || heightMap[cx][cz] < SEA_LEVEL - 12) {
                return;
            }

            stampStack(heightMap, width, height, random, cx, cz, radius, Math.round(peak));

            if (i > 0 && random.nextFloat() < STACK_RIDGE_CHANCE) {
                float dirX = x - prevX;
                float dirZ = z - prevZ;

                float length = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);

                dirX /= length;
                dirZ /= length;

                float halfWidth = 2.0f + random.nextFloat() * 0.8f;
                boolean arch = gap >= STACK_ARCH_MIN_GAP
                        && random.nextFloat() < STACK_ARCH_CHANCE;

                float radiusY = 3.0f + random.nextFloat() * 1.5f;
                float radiusAcross = Math.max(2.0f, Math.min(3.5f, gap / 2.0f - 1.5f));

                int ridgeTop = SEA_LEVEL
                        + (arch ? (int) radiusY + 6 : 5 + random.nextInt(3));

                stampRidge(heightMap, width, height,
                        prevX, prevZ, x, z, halfWidth, ridgeTop);

                if (arch) {
                    float middle = prevRadius + gap / 2.0f;

                    this.bores.add(new Bore(
                            prevX + dirX * middle,
                            prevZ + dirZ * middle,
                            SEA_LEVEL + 1.0f,
                            -dirZ,
                            dirX,
                            halfWidth + 2.5f,
                            radiusAcross,
                            radiusY
                    ));
                }
            }

            prevX = x;
            prevZ = z;
            prevRadius = radius;
        }
    }

    /** A flat-topped tower with an uneven outline and terraced sides. */
    private static void stampStack(int[][] heightMap, int width, int height,
                                   Random random, int cx, int cz, float radius, int peak) {
        float phase1 = random.nextFloat() * 6.28f;
        float phase2 = random.nextFloat() * 6.28f;
        float phase3 = random.nextFloat() * 6.28f;

        int reach = (int) Math.ceil(radius * 1.4f) + 1;

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                if (x < 0 || z < 0 || x >= width || z >= height) {
                    continue;
                }

                float angle = (float) Math.atan2(dz, dx);

                float outline = radius * (
                        1.0f
                                + 0.18f * (float) Math.sin(2.0f * angle + phase1)
                                + 0.12f * (float) Math.sin(3.0f * angle + phase2)
                                + 0.08f * (float) Math.sin(5.0f * angle + phase3)
                );

                float distance = (float) Math.sqrt(dx * dx + dz * dz);

                if (distance >= outline) {
                    continue;
                }

                float inside = 1.0f - distance / outline;
                float shape = smoothstep(clamp01(inside / 0.45f));

                float local = peak * (
                        0.92f
                                + 0.08f * (float) Math.sin(x * 0.6f + phase1)
                                * (float) Math.cos(z * 0.5f + phase2)
                );

                int above = Math.round(local * shape / STACK_STEP) * STACK_STEP;

                if (above <= 0) {
                    continue;
                }

                int top = SEA_LEVEL + above;

                if (top > heightMap[x][z]) {
                    heightMap[x][z] = top;
                }
            }
        }
    }

    /** A rounded ridge between two points. */
    private static void stampRidge(int[][] heightMap, int width, int height,
                                   float ax, float az, float bx, float bz,
                                   float halfWidth, int top) {
        float dx = bx - ax;
        float dz = bz - az;
        float lengthSq = dx * dx + dz * dz;

        int minX = (int) Math.floor(Math.min(ax, bx) - halfWidth - 1);
        int maxX = (int) Math.ceil(Math.max(ax, bx) + halfWidth + 1);
        int minZ = (int) Math.floor(Math.min(az, bz) - halfWidth - 1);
        int maxZ = (int) Math.ceil(Math.max(az, bz) + halfWidth + 1);

        for (int x = Math.max(0, minX); x <= Math.min(width - 1, maxX); x++) {
            for (int z = Math.max(0, minZ); z <= Math.min(height - 1, maxZ); z++) {
                float t = clamp01(((x - ax) * dx + (z - az) * dz) / lengthSq);

                float qx = ax + dx * t - x;
                float qz = az + dz * t - z;

                float across = (float) Math.sqrt(qx * qx + qz * qz);

                if (across >= halfWidth) {
                    continue;
                }

                float shape = smoothstep(clamp01((1.0f - across / halfWidth) / 0.5f));
                int above = Math.round((top - SEA_LEVEL) * shape);

                if (above <= 0) {
                    continue;
                }

                if (SEA_LEVEL + above > heightMap[x][z]) {
                    heightMap[x][z] = SEA_LEVEL + above;
                }
            }
        }
    }

    private static float clamp01(float t) {
        return Math.max(0.0f, Math.min(1.0f, t));
    }

    private static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }
}