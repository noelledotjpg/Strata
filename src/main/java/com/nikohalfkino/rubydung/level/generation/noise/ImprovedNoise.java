package com.nikohalfkino.rubydung.level.generation.noise;

import java.util.Random;

/**
 * Perlin's "improved noise", ported from the Indev worldgen (net.minecraft.a.a.c.a.a)
 *
 * the original is the 3D version always sampled with z = 0. with z = 0 the z fade curve is 0, so the
 * z interpolation collapses to the lower layer, which is what this 2D version does directly.
 * output is roughly -1..1 and is exactly 0 on whole-number coordinates, so callers sample it with scaled coordinates
 */
public final class ImprovedNoise implements Noise {

    private final int[] permutation = new int[512];

    public ImprovedNoise(Random random) {
        for (int i = 0; i < 256; i++) {
            this.permutation[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i) + i;
            int swap = this.permutation[i];
            this.permutation[i] = this.permutation[j];
            this.permutation[j] = swap;
            this.permutation[i + 256] = this.permutation[i];
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h != 12 && h != 14 ? 0.0 : x);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    @Override
    public double get(double x, double z) {
        int cellX = (int) Math.floor(x) & 255;
        int cellZ = (int) Math.floor(z) & 255;
        double fx = x - Math.floor(x);
        double fz = z - Math.floor(z);
        double u = fade(fx);
        double v = fade(fz);

        int[] p = this.permutation;
        int a = p[cellX] + cellZ;
        int b = p[cellX + 1] + cellZ;

        return lerp(v,
                lerp(u, grad(p[a], fx, fz), grad(p[b], fx - 1.0, fz)),
                lerp(u, grad(p[a + 1], fx, fz - 1.0), grad(p[b + 1], fx - 1.0, fz - 1.0)));
    }
}
