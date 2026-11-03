package com.nikohalfkino.rubydung.level.generation.noise;

import java.util.Random;

/**
 * several ImprovedNoise layers added up, each one twice as wide and twice as strong as the last
 * (ported from net.minecraft.a.a.c.a.c). more octaves = bigger, stronger, lower-frequency features:
 * 2 octaves is a gentle wobble, 6 gives features a few dozen blocks wide, 8 is dominated by one huge blob
 */
public final class OctaveNoise implements Noise {

    private final ImprovedNoise[] octaves;

    public OctaveNoise(Random random, int octaveCount) {
        this.octaves = new ImprovedNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            this.octaves[i] = new ImprovedNoise(random);
        }
    }

    @Override
    public double get(double x, double z) {
        double total = 0.0;
        double scale = 1.0;
        for (ImprovedNoise octave : this.octaves) {
            total += octave.get(x / scale, z / scale) * scale;
            scale *= 2.0;
        }
        return total;
    }
}
