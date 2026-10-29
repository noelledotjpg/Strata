package com.nikohalfkino.rubydung;

public class Timer {

    private static final long NS_PER_SECOND = 1000000000L;
    private static final long MAX_NS_PER_UPDATE = 1000000000L;
    private static final int MAX_TICKS_PER_UPDATE = 100;

    private final float ticksPerSecond;

    private long lastTime = System.nanoTime();

    public float timeScale = 1.0F;

    public float fps = 0.0F;

    private static final float FPS_UPDATE_INTERVAL = 0.4F;

    private int framesSinceLastFpsUpdate = 0;
    private float secondsSinceLastFpsUpdate = 0.0F;

    public float passedTime = 0.0F;

    public int ticks;

    public float partialTicks;

    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    public void advanceTime() {
        long now = System.nanoTime();
        long passedNs = now - this.lastTime;

        this.lastTime = now;

        passedNs = Math.max(0, passedNs);
        passedNs = Math.min(MAX_NS_PER_UPDATE, passedNs);

        this.framesSinceLastFpsUpdate++;
        this.secondsSinceLastFpsUpdate += (float) passedNs / NS_PER_SECOND;

        if (this.secondsSinceLastFpsUpdate >= FPS_UPDATE_INTERVAL) {
            this.fps = this.framesSinceLastFpsUpdate / this.secondsSinceLastFpsUpdate;
            this.framesSinceLastFpsUpdate = 0;
            this.secondsSinceLastFpsUpdate = 0.0F;
        }

        this.passedTime += passedNs * this.timeScale * this.ticksPerSecond / NS_PER_SECOND;
        this.ticks = (int) this.passedTime;

        this.ticks = Math.min(MAX_TICKS_PER_UPDATE, this.ticks);

        this.passedTime -= this.ticks;
        this.partialTicks = this.passedTime;
    }
}
