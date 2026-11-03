package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;

import java.util.BitSet;

/**
 * moves the liquid of a LiquidLayer around. every liquid cell holds a fill (1..MAX_FILL) and the amount of liquid
 * is kept: nothing is created or destroyed by flowing.
 *
 * one tick, for every active cell, bottom to top:
 *   1. fall: as much as fits goes into the cell below
 *   2. spread: into each side neighbour that has at least 2 units less, half of the difference moves. so a
 *      pile levels out until neighbours differ by 1 or less, and liquid runs over an edge into any hole it meets
 *   3. a cell that received liquid is not processed again in the same tick, so it flows at a steady speed
 *
 * only cells that changed (and their neighbours) are active, still liquid costs nothing. because the simulator
 * can't hear about blocks being placed or broken, a sweep re-checks every liquid cell about every half second,
 * and LiquidLayer.wake(x, y, z) can be called for an instant reaction
 *
 * thick liquids (LiquidTile.getViscosity) just act less often: every round(viscosity) ticks
 * liquids never mix: a liquid doesn't flow into a cell holding another liquid
 */
public final class LiquidSimulator {

    private static volatile boolean enabled = true;
    private static volatile float ticksPerSecond = 10.0F;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; }
    public static float getTicksPerSecond() { return ticksPerSecond; }
    public static void setTicksPerSecond(float tps) { ticksPerSecond = tps; }

    private static final int MAX_TICKS_PER_UPDATE = 5;
    private static final int SWEEP_PER_TICK = 1 << 20;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final LiquidLayer layer;
    private final byte[] cells;
    private final byte[] fills;
    private final int width, depth, height;
    private final int layerSize;       // cells in one horizontal layer (the index step for one block up)

    private final BitSet active = new BitSet();
    private final BitSet touched = new BitSet();
    private float accumulator;
    private long tickCount;
    private int sweepCursor;
    private int rotation;

    LiquidSimulator(LiquidLayer layer, byte[] cells, byte[] fills, int width, int depth, int height) {
        this.layer = layer;
        this.cells = cells;
        this.fills = fills;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.layerSize = width * height;
    }

    int activeCount() {
        return this.active.cardinality();
    }

    /** marks the cell and its 6 neighbours to be looked at on the next tick */
    void wake(int x, int y, int z) {
        wakeIndex((y * this.height + z) * this.width + x);
    }

    private void wakeIndex(int i) {
        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;

        this.active.set(i);
        if (x > 0) this.active.set(i - 1);
        if (x < this.width - 1) this.active.set(i + 1);
        if (z > 0) this.active.set(i - this.width);
        if (z < this.height - 1) this.active.set(i + this.width);
        if (y > 0) this.active.set(i - this.layerSize);
        if (y < this.depth - 1) this.active.set(i + this.layerSize);
    }

    void update(float seconds) {
        if (!enabled || ticksPerSecond <= 0.0F) {
            this.accumulator = 0.0F;
            return;
        }
        float interval = 1.0F / ticksPerSecond;
        this.accumulator += seconds;
        int ran = 0;
        while (this.accumulator >= interval && ran < MAX_TICKS_PER_UPDATE) {
            tick();
            this.accumulator -= interval;
            ran++;
        }
        if (ran == MAX_TICKS_PER_UPDATE) {
            this.accumulator = 0.0F;     // too slow to keep up, don't build up a backlog
        }
    }

    void stepNow(int ticks) {
        for (int k = 0; k < ticks; k++) {
            tick();
        }
    }

    private void tick() {
        this.tickCount++;
        sweep();
        this.touched.clear();

        int count = this.active.cardinality();
        if (count == 0) {
            return;
        }
        int[] list = new int[count];
        int n = 0;
        for (int i = this.active.nextSetBit(0); i >= 0; i = this.active.nextSetBit(i + 1)) {
            list[n++] = i;
        }
        this.active.clear();

        this.rotation = (this.rotation + 1) & 3;
        for (int k = 0; k < n; k++) {
            step(list[k]);
        }
    }

    // looks at the next slice of the grid for liquid that should be moving but isn't being watched
    private void sweep() {
        int total = this.cells.length;
        int end = Math.min(total, this.sweepCursor + SWEEP_PER_TICK);
        for (int i = this.sweepCursor; i < end; i++) {
            byte type = this.cells[i];
            if (type == 0 || this.active.get(i)) {
                continue;
            }
            if (unstable(i, type)) {
                this.active.set(i);
            }
        }
        this.sweepCursor = end >= total ? 0 : end;
    }

    private boolean unstable(int i, byte type) {
        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;

        if (!this.layer.passable(x, y, z)) {
            return true;                                   // a block now fills this cell, step() removes the liquid
        }
        if (y > 0 && room(i - this.layerSize, x, y - 1, z, type) > 0) {
            return true;
        }
        int f = this.fills[i] & 0xFF;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                continue;
            }
            int j = i + d[0] + d[1] * this.width;
            if (room(j, nx, y, nz, type) > 0 && f - fill(j) >= 2) {
                return true;
            }
        }
        return false;
    }

    private void step(int i) {
        byte type = this.cells[i];
        if (type == 0) {
            return;
        }
        if (this.touched.get(i)) {
            this.active.set(i);
            return;
        }
        LiquidTile liquid = LiquidTile.byIndex(type - 1);
        int interval = Math.max(1, Math.round(liquid.getViscosity()));
        if (this.tickCount % interval != 0) {
            this.active.set(i);
            return;
        }

        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;

        if (!this.layer.passable(x, y, z)) {
            this.layer.applyRaw(i, (byte) 0, (byte) 0);    // a block was put where this liquid is
            wakeIndex(i);
            return;
        }

        int f = this.fills[i] & 0xFF;

        // 1. fall
        if (y > 0) {
            int j = i - this.layerSize;
            int room = room(j, x, y - 1, z, type);
            if (room > 0) {
                int m = Math.min(f, room);
                transfer(i, j, m);
                f -= m;
            }
        }

        // 2. spread to the sides
        for (int s = 0; s < 4 && f > 0; s++) {
            int[] d = SIDES[(s + this.rotation) & 3];
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                continue;
            }
            int j = i + d[0] + d[1] * this.width;
            int room = room(j, nx, y, nz, type);
            if (room <= 0) {
                continue;
            }
            int diff = f - fill(j);
            if (diff >= 2) {
                int m = Math.min(diff / 2, room);
                transfer(i, j, m);
                f -= m;
            }
        }
    }

    private int fill(int i) {
        return this.cells[i] == 0 ? 0 : this.fills[i] & 0xFF;
    }

    // how many units the cell can still take of this liquid (0 if a block or another liquid is there)
    private int room(int j, int x, int y, int z, byte type) {
        if (!this.layer.passable(x, y, z)) {
            return 0;
        }
        byte c = this.cells[j];
        if (c == 0) {
            return LiquidLayer.MAX_FILL;
        }
        if (c != type) {
            return 0;
        }
        return LiquidLayer.MAX_FILL - (this.fills[j] & 0xFF);
    }

    private void transfer(int from, int to, int amount) {
        byte type = this.cells[from];
        int left = (this.fills[from] & 0xFF) - amount;
        int gained = fill(to) + amount;

        this.layer.applyRaw(from, left <= 0 ? 0 : type, (byte) Math.max(left, 0));
        this.layer.applyRaw(to, type, (byte) gained);

        this.touched.set(to);
        wakeIndex(from);
        wakeIndex(to);
    }
}