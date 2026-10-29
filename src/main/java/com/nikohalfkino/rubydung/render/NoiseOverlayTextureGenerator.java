package com.nikohalfkino.rubydung.render;

import java.awt.image.BufferedImage;
import java.util.Random;

public final class NoiseOverlayTextureGenerator {

    private static final float MIN_BRIGHTNESS = 0.75f;

    private NoiseOverlayTextureGenerator() {
    }

    public static BufferedImage generate(int size, long seed) {
        float[][] noise = fractalNoise(size, seed, 4, 0.5f);

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float n = noise[x][y];
                float brightness = MIN_BRIGHTNESS + (1.0f - MIN_BRIGHTNESS) * n;

                int v = clamp255((int) (brightness * 255));
                int argb = (255 << 24) | (v << 16) | (v << 8) | v;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static float[][] fractalNoise(int size, long seed, int octaves, float persistence) {
        float[][] result = new float[size][size];
        Random random = new Random(seed);

        float amplitude = 1.0f;
        float totalAmplitude = 0.0f;
        int latticeSize = 4;

        for (int octave = 0; octave < octaves; octave++) {
            float[][] lattice = buildLattice(random, latticeSize);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    result[x][y] += sampleLattice(lattice, latticeSize, size, x, y) * amplitude;
                }
            }
            totalAmplitude += amplitude;
            amplitude *= persistence;
            latticeSize *= 2;
        }

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                result[x][y] /= totalAmplitude;
            }
        }
        return result;
    }

    private static float[][] buildLattice(Random random, int latticeSize) {
        float[][] lattice = new float[latticeSize][latticeSize];
        for (int i = 0; i < latticeSize; i++) {
            for (int j = 0; j < latticeSize; j++) {
                lattice[i][j] = random.nextFloat();
            }
        }
        return lattice;
    }

    private static float sampleLattice(float[][] lattice, int latticeSize, int size, int x, int y) {
        float gridX = (x * (float) latticeSize) / size;
        float gridY = (y * (float) latticeSize) / size;

        int x0 = ((int) gridX) % latticeSize;
        int y0 = ((int) gridY) % latticeSize;
        int x1 = (x0 + 1) % latticeSize;
        int y1 = (y0 + 1) % latticeSize;

        float fracX = smoothstep(gridX - (int) gridX);
        float fracY = smoothstep(gridY - (int) gridY);

        float top = lerp(lattice[x0][y0], lattice[x1][y0], fracX);
        float bottom = lerp(lattice[x0][y1], lattice[x1][y1], fracX);
        return lerp(top, bottom, fracY);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}