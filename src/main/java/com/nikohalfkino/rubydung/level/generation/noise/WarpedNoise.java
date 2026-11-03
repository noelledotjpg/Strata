package com.nikohalfkino.rubydung.level.generation.noise;

/**
 * noise sampled at an x pushed around by another noise, which makes the swirly, folded shapes of the Indev terrain
 * (ported from net.minecraft.a.a.c.a.d)
 */
public final class WarpedNoise implements Noise {

    private final Noise base;
    private final Noise warp;

    public WarpedNoise(Noise base, Noise warp) {
        this.base = base;
        this.warp = warp;
    }

    @Override
    public double get(double x, double z) {
        return this.base.get(x + this.warp.get(x, z), z);
    }
}
