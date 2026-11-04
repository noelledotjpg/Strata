package com.nikohalfkino.rubydung.level.generation.noise;

import java.util.Random;

/** Layered noise, -1..1: every octave has twice the frequency and persistence times the strength of the one before. */
public final class Fbm {
    private final ImprovedNoise[] layers;
    private final float baseFrequency;
    private final float persistence;

    public Fbm(Random random, int octaves, float baseFrequency, float persistence) {
        this.layers = new ImprovedNoise[octaves];

        for (int i = 0; i < octaves; i++) {
            this.layers[i] = new ImprovedNoise(random);
        }

        this.baseFrequency = baseFrequency;
        this.persistence = persistence;
    }

    public float get(double x, double z) {
        double total = 0.0;
        double strength = 1.0;
        double sum = 0.0;
        double frequency = this.baseFrequency;

        for (ImprovedNoise layer : this.layers) {
            total += layer.get(x * frequency, z * frequency) * strength;
            sum += strength;
            strength *= this.persistence;
            frequency *= 2.0;
        }

        return Math.max(-1.0f, Math.min(1.0f, (float) (total / sum * 1.6)));
    }
}