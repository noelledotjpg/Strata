package com.nikohalfkino.rubydung.level.generation;

import java.util.Random;

/**
 * A bay bitten into the coast of the main island: a rounded inlet with a head inside the island and a
 * mouth that opens onto the sea. The mouth is a little narrower than the head, so it reads as a sheltered
 * bay rather than a plain notch. Shoreline bends the outline with noise, so it is not a perfect circle.
 *
 * Shoreline subtracts it from the land (smoothMax) and gives the shore around it a sand beach.
 */
final class Bay {

    private static final float CHANCE = 0.55f;

    // Sizes are shares of the main island's body radius.
    private static final float RADIUS_MIN = 0.36f;
    private static final float RADIUS_MAX = 0.48f;
    private static final float MOUTH_MIN = 0.65f;     // mouth radius as a share of the head radius
    private static final float MOUTH_MAX = 0.8f;
    private static final float DEPTH_MIN = 0.55f;     // how far inside the rim the head's centre lies
    private static final float DEPTH_MAX = 0.70f;
    private static final int   DIRECTION_TRIES = 8;
    private static final int   FIT_TRIES = 8;

    // The head sits at (ax, az), the mouth at (bx, bz).
    private final float ax, az, bx, bz, ra, rb;

    private Bay(float ax, float az, float bx, float bz, float ra, float rb) {
        this.ax = ax;
        this.az = az;
        this.bx = bx;
        this.bz = bz;
        this.ra = ra;
        this.rb = rb;
    }

    /**
     * Maybe plans a bay for the main island, or returns null.
     * All randomness comes from the given Random, which Shoreline only uses after the layout is done, so
     * adding bays does not move any island.
     *
     * @param pieces       the island's pieces {dx, dz, radius}, relative to its centre at full size
     * @param avoidAngles  directions of the neighbouring islands: the bay faces away from them
     * @param mapX         the map's middle, where the player starts: the bay stays {@code clearance} blocks away
     */
    static Bay plan(Random random, float[][] pieces, float cx, float cz, float scale,
                    float[] avoidAngles, float mapX, float mapZ, float clearance) {
        if (random.nextFloat() >= CHANCE) {
            return null;
        }

        // Face away from the other islands, if there are any.
        float angle = 0.0f;
        float bestGap = -1.0f;

        for (int i = 0; i < DIRECTION_TRIES; i++) {
            float candidate = random.nextFloat() * 2.0f * (float) Math.PI;
            float gap = (float) Math.PI;

            for (float other : avoidAngles) {
                gap = Math.min(gap, angularGap(candidate, other));
            }

            if (gap > bestGap) {
                bestGap = gap;
                angle = candidate;
            }
        }

        float dirX = (float) Math.cos(angle);
        float dirZ = (float) Math.sin(angle);

        float farthest = 0.0f;
        for (float[] piece : pieces) {
            farthest = Math.max(farthest,
                    (float) Math.sqrt(piece[0] * piece[0] + piece[1] * piece[1]) + piece[2]);
        }
        farthest *= scale;

        // Where the island's rim lies in that direction: walk in from the far side until we are on land.
        float rim = 0.0f;

        for (float t = farthest; t > 0.0f; t -= 1.0f) {
            float px = cx + dirX * t;
            float pz = cz + dirZ * t;
            boolean inside = false;

            for (float[] piece : pieces) {
                float dx = px - (cx + piece[0] * scale);
                float dz = pz - (cz + piece[1] * scale);
                float r = piece[2] * scale;

                if (dx * dx + dz * dz < r * r) {
                    inside = true;
                    break;
                }
            }

            if (inside) {
                rim = t;
                break;
            }
        }

        float hub = pieces[0][2] * scale;
        float headRadius = hub * range(random, RADIUS_MIN, RADIUS_MAX);
        float mouthRadius = headRadius * range(random, MOUTH_MIN, MOUTH_MAX);
        float depth = hub * range(random, DEPTH_MIN, DEPTH_MAX);
        float mouthOut = mouthRadius * 0.3f;   // the mouth's centre lies a little out to sea

        // Make it shallower until it keeps well away from the middle of the map.
        for (int attempt = 0; attempt < FIT_TRIES; attempt++) {
            Bay bay = new Bay(
                    cx + dirX * (rim - depth), cz + dirZ * (rim - depth),
                    cx + dirX * (rim + mouthOut), cz + dirZ * (rim + mouthOut),
                    headRadius, mouthRadius);

            if (bay.signedDistance(mapX, mapZ) >= clearance) {
                return bay;
            }

            depth *= 0.88f;
        }

        return null;
    }

    /** Distance from the point to the bay's rim in blocks: negative in the water, 0 on the rim. */
    float signedDistance(float px, float pz) {
        float dx = this.bx - this.ax;
        float dz = this.bz - this.az;
        float lengthSq = dx * dx + dz * dz;

        float t = lengthSq < 1.0E-6f
                ? 0.0f
                : Math.max(0.0f, Math.min(1.0f, ((px - this.ax) * dx + (pz - this.az) * dz) / lengthSq));

        float qx = this.ax + dx * t - px;
        float qz = this.az + dz * t - pz;

        return (float) Math.sqrt(qx * qx + qz * qz) - (this.ra + (this.rb - this.ra) * t);
    }

    /** The larger of two distances, with a rounded join where they cross (k = how wide the rounding is). */
    static float smoothMax(float a, float b, float k) {
        float diff = Math.abs(a - b);

        if (diff >= k) {
            return Math.max(a, b);
        }

        float h = k - diff;
        return Math.max(a, b) + h * h / (4.0f * k);
    }

    private static float angularGap(float a, float b) {
        float twoPi = 2.0f * (float) Math.PI;
        float d = Math.abs(a - b) % twoPi;

        return d > (float) Math.PI ? twoPi - d : d;
    }

    private static float range(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }
}