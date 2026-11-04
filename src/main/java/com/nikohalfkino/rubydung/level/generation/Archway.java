package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A tunnel through a tall mountain. Moved out of NaturalLevelGenerator: find picks the site (or null),
 * contains is asked for every block while the chunks are filled in.
 */
final class Archway {

    private static final float ARCHWAY_CHANCE = 0.35f;
    private static final int ARCHWAY_MIN_MOUNTAIN_HEIGHT_ABOVE_GROUND = 24;
    private static final int ARCHWAY_HALF_LENGTH_MIN = 6;
    private static final int ARCHWAY_HALF_LENGTH_VARIANCE = 3;
    private static final int ARCHWAY_RADIUS_MIN = 3;
    private static final int ARCHWAY_RADIUS_VARIANCE = 2;
    private static final int ARCHWAY_ROOF_CLEARANCE = 2;

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

    static Archway find(Random random, int[][] heightMap, int width, int height) {
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

    static boolean contains(Archway archway, int x, int y, int z) {
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
}