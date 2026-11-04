package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;

import java.util.Arrays;
import java.util.BitSet;

/**
 * moves the liquid of a LiquidLayer around. every liquid cell holds a fill (1..MAX_FILL).
 * ordinary liquid is conserved: nothing is created or destroyed by flowing. the one exception are
 * "infinite" liquids (LiquidTile.isInfinite, e.g. water), see below.
 *
 * one tick, for every active cell, bottom to top:
 *   1. fall: as much as fits goes into the cell below
 *   2. spread: the cell levels out with ALL its lower side neighbours at once (it shares its liquid evenly
 *      with them), so a pile flattens quickly and without a preferred direction. neighbours end up
 *      differing by 1 or less, and liquid runs over an edge into any hole it meets
 *   3. a cell that received liquid is not processed again in the same tick, so it flows at a steady speed
 *   (between 2 and 3) level the body: see below
 *
 * LEVELLING A BODY
 * the local rules above leave slopes behind (they stop at a difference of 1 unit per cell) and know nothing
 * about pressure. so after the local rules every active cell also levels the whole body it belongs to:
 *   - a STACK is a vertical run of the same liquid in one column that rests on something. all its cells below
 *     the top one are full. a falling stream (partly filled cells, or nothing under it) is not a stack
 *   - stacks in neighbouring columns that have liquid on a common layer are connected (communicating vessels)
 *   - the liquid of all connected stacks is poured out again from the bottom up: one water level for the
 *     whole body, every stack filled up to it, limited by its floor and by the first block above it. so a
 *     tower of liquid on a pool sinks into it, a deep column and a shallow one end at the same height, and
 *     leftover units go to the stacks that already have the most. nothing is created or destroyed
 *   - bodies of more than LEVEL_MAX_STACKS columns are left to the local rules
 *
 * INFINITE BODIES
 * an infinite liquid that is connected to at least getInfiniteBodySize() cells of itself counts as a big
 * body (an ocean). in a big body:
 *   - the body has a sea level: the highest layer that is reasonably wide (see SURFACE_SHARE)
 *   - cells at sea level that rest on full liquid are kept full: holes next to the surface get filled
 *     from an endless supply, and the surface stays flat
 *   - liquid that ends up above sea level on top of the body is absorbed (deleted), so pouring more
 *     liquid in does not make a bulge. liquid resting on solid ground (a pond on a cliff, a thin film
 *     running over a beach) is never touched
 * a body that is smaller than the size limit behaves like normal conserved liquid
 *
 * only cells that changed (and their neighbours) are active, still liquid costs nothing. because the simulator
 * can't hear about blocks being placed or broken, a sweep re-checks the liquid cells, and
 * LiquidLayer.wake(x, y, z) can be called for an instant reaction
 *
 * NEAR THE PLAYER FIRST (setFocus)
 *   - the sweep works in chunks of 16x16 columns, ordered by distance to the focus (the player). the chunks
 *     within NEAR_CHUNK_RADIUS are re-checked every few ticks, the rest of the level slowly in the
 *     background, nearest first. so on a huge level the water around the player reacts at once and far away
 *     water is only looked at when there is time
 *   - if more than getMaxStepsPerTick() cells are active at once, the ones nearest to the focus are
 *     processed first and the others wait for a later tick
 *
 * thick liquids (LiquidTile.getViscosity) just act less often: every round(viscosity) ticks
 * liquids never mix: a liquid doesn't flow into a cell holding another liquid
 */
public final class LiquidSimulator {

    private static volatile boolean enabled = true;
    private static volatile float ticksPerSecond = 10.0F;
    private static volatile int infiniteBodySize = 256;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; }
    public static float getTicksPerSecond() { return ticksPerSecond; }
    public static void setTicksPerSecond(float tps) { ticksPerSecond = tps; }

    /** how many connected cells of an infinite liquid make a body "big" (infinite). */
    public static int getInfiniteBodySize() { return infiniteBodySize; }
    public static void setInfiniteBodySize(int cells) { infiniteBodySize = Math.max(2, cells); }

    private static final int MAX_TICKS_PER_UPDATE = 5;
    private static volatile int maxStepsPerTick = 40000;

    /** most active cells that are processed in one tick, the nearest to the focus go first */
    public static int getMaxStepsPerTick() { return maxStepsPerTick; }
    public static void setMaxStepsPerTick(int cells) { maxStepsPerTick = Math.max(1, cells); }

    private static final int CHUNK = 16;                    // sweep chunk: 16x16 columns, full height
    private static final int NEAR_CHUNK_RADIUS = 6;         // chunks (96 blocks) around the focus swept often
    private static final int NEAR_SWEEP_CELLS = 1 << 19;    // cells looked at per tick in the near zone
    private static final int FAR_SWEEP_CELLS = 1 << 18;     // ... and in the rest of the level
    private static final int DIST_BUCKETS = 8192;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // a layer of a big body counts as its sea level when it holds at least 1/SURFACE_SHARE of the cells of the
    // widest layer. a bulge on top of the water is far smaller than that, so it counts as "above sea level"
    private static final int SURFACE_SHARE = 4;

    private final LiquidLayer layer;
    private final byte[] cells;
    private final byte[] fills;
    private final int width, depth, height;
    private final int layerSize;       // cells in one horizontal layer (the index step for one block up)

    private final BitSet active = new BitSet();
    private final BitSet touched = new BitSet();
    private float accumulator;
    private long tickCount;

    // near-the-player sweep
    private final int chunksX, chunksZ;
    private int[] chunkOrder = new int[0];                   // chunk ids, nearest to the focus first
    private int nearChunks;                                  // how many of them are in the near zone
    private int nearCursor, farCursor;
    private int orderChunkX = Integer.MIN_VALUE, orderChunkZ = Integer.MIN_VALUE;
    private volatile int focusX, focusY, focusZ;
    private int rotation;

    // results of the big body analysis, valid for one tick only
    private final BitSet checked = new BitSet();   // cell was analysed this tick
    private final BitSet big = new BitSet();       // ... and belongs to a big body
    private final BitSet excess = new BitSet();    // ... and lies above the sea level of that body
    private final BitSet surface = new BitSet();   // ... and lies exactly at sea level
    private final BitSet visited = new BitSet();   // scratch for the flood fill
    private final int[] layerCount;
    private int[] queue = new int[0];

    // scratch for spreading, avoids allocating in the hot path
    private final int[] spreadTo = new int[4];
    private final int[] spreadRoom = new int[4];

    // body levelling: the most columns of one body that are levelled as one piece
    private static final int LEVEL_MAX_STACKS = 1024;
    private final BitSet leveled = new BitSet();      // cells of bodies already handled this tick
    private final BitSet levelSeen = new BitSet();    // scratch: bottom cells of the stacks found so far
    private int stackCount;
    private final int[] stCol = new int[LEVEL_MAX_STACKS];       // index of the column (z * width + x)
    private final int[] stBottomIdx = new int[LEVEL_MAX_STACKS]; // cell index of the lowest cell
    private final int[] stB = new int[LEVEL_MAX_STACKS];         // lowest layer
    private final int[] stT = new int[LEVEL_MAX_STACKS];         // top layer (the only one that may be partial)
    private final int[] stCap = new int[LEVEL_MAX_STACKS];       // first layer above that can't hold liquid
    private final int[] stAmount = new int[LEVEL_MAX_STACKS];    // units in the stack now
    private final int[] stTarget = new int[LEVEL_MAX_STACKS];    // units it gets

    LiquidSimulator(LiquidLayer layer, byte[] cells, byte[] fills, int width, int depth, int height) {
        this.layer = layer;
        this.cells = cells;
        this.fills = fills;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.layerSize = width * height;
        this.layerCount = new int[depth];
        this.chunksX = (width + CHUNK - 1) / CHUNK;
        this.chunksZ = (height + CHUNK - 1) / CHUNK;
        this.focusX = width / 2;
        this.focusY = depth / 2;
        this.focusZ = height / 2;
    }

    /**
     * where the player is (block coordinates). call it every frame before update(): the sweep and the
     * processing order start from here. without a call the centre of the level is used.
     */
    public void setFocus(int x, int y, int z) {
        this.focusX = x;
        this.focusY = y;
        this.focusZ = z;
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
        this.leveled.clear();
        this.checked.clear();
        this.big.clear();
        this.excess.clear();
        this.surface.clear();

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

        int limit = maxStepsPerTick;
        if (n > limit) {
            n = nearestFirst(list, n, limit);
        }

        this.rotation = (this.rotation + 1) & 3;
        for (int k = 0; k < n; k++) {
            step(list[k]);
        }
    }

    // keeps the `limit` active cells nearest to the focus (still in index order, so bottom to top) and puts
    // the rest back into the active set for a later tick. returns how many are left in the list
    private int nearestFirst(int[] list, int n, int limit) {
        int fx = this.focusX, fy = this.focusY, fz = this.focusZ;
        int[] bucketOf = new int[n];
        int[] hist = new int[DIST_BUCKETS];
        for (int k = 0; k < n; k++) {
            int i = list[k];
            double dx = i % this.width - fx;
            double dz = (i / this.width) % this.height - fz;
            double dy = i / this.layerSize - fy;
            int b = (int) Math.min(DIST_BUCKETS - 1, Math.sqrt(dx * dx + dy * dy + dz * dz));
            bucketOf[k] = b;
            hist[b]++;
        }
        int cutoff = 0, taken = 0;
        while (cutoff < DIST_BUCKETS && taken + hist[cutoff] <= limit) {
            taken += hist[cutoff++];
        }
        int spare = limit - taken;                           // cells taken from the cutoff distance
        int m = 0;
        for (int k = 0; k < n; k++) {
            int i = list[k];
            int b = bucketOf[k];
            if (b < cutoff || (b == cutoff && spare > 0 && spare-- > 0)) {
                list[m++] = i;
            } else {
                this.active.set(i);
            }
        }
        return m;
    }

    // looks at the chunks around the focus every few ticks, and the rest of the level slowly, for liquid that
    // should be moving but isn't being watched
    private void sweep() {
        int fcx = Math.max(0, Math.min(this.chunksX - 1, this.focusX / CHUNK));
        int fcz = Math.max(0, Math.min(this.chunksZ - 1, this.focusZ / CHUNK));
        if (fcx != this.orderChunkX || fcz != this.orderChunkZ) {
            rebuildChunkOrder(fcx, fcz);
        }

        int chunkCells = CHUNK * CHUNK * this.depth;
        int farChunks = this.chunkOrder.length - this.nearChunks;
        int nearPerTick = Math.min(this.nearChunks, Math.max(1, NEAR_SWEEP_CELLS / chunkCells));
        int farPerTick = Math.min(farChunks, Math.max(1, FAR_SWEEP_CELLS / chunkCells));

        for (int k = 0; k < nearPerTick && this.nearChunks > 0; k++) {
            sweepChunk(this.chunkOrder[this.nearCursor]);
            this.nearCursor = (this.nearCursor + 1) % this.nearChunks;
        }
        for (int k = 0; k < farPerTick && farChunks > 0; k++) {
            sweepChunk(this.chunkOrder[this.nearChunks + this.farCursor]);
            this.farCursor = (this.farCursor + 1) % farChunks;
        }
    }

    // sorts all chunks by distance to the chunk of the focus (only when the focus changes chunk)
    private void rebuildChunkOrder(int fcx, int fcz) {
        int total = this.chunksX * this.chunksZ;
        long[] keys = new long[total];
        for (int c = 0; c < total; c++) {
            long dx = c % this.chunksX - fcx;
            long dz = c / this.chunksX - fcz;
            keys[c] = ((dx * dx + dz * dz) << 32) | c;
        }
        Arrays.sort(keys);
        int[] order = new int[total];
        int near = 0;
        long nearLimit = (long) NEAR_CHUNK_RADIUS * NEAR_CHUNK_RADIUS;
        for (int k = 0; k < total; k++) {
            order[k] = (int) keys[k];
            if ((keys[k] >> 32) <= nearLimit) {
                near++;
            }
        }
        this.chunkOrder = order;
        this.nearChunks = near;
        this.nearCursor = near == 0 ? 0 : this.nearCursor % near;
        int far = total - near;
        this.farCursor = far == 0 ? 0 : this.farCursor % far;
        this.orderChunkX = fcx;
        this.orderChunkZ = fcz;
    }

    private void sweepChunk(int chunk) {
        int x0 = (chunk % this.chunksX) * CHUNK;
        int z0 = (chunk / this.chunksX) * CHUNK;
        int x1 = Math.min(this.width, x0 + CHUNK);
        int z1 = Math.min(this.height, z0 + CHUNK);
        for (int y = 0; y < this.depth; y++) {
            for (int z = z0; z < z1; z++) {
                int row = (y * this.height + z) * this.width;
                for (int x = x0; x < x1; x++) {
                    int i = row + x;
                    byte type = this.cells[i];
                    if (type == 0 || this.active.get(i)) {
                        continue;
                    }
                    if (unstable(i, type)) {
                        this.active.set(i);
                    }
                }
            }
        }
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
        if (f < LiquidLayer.MAX_FILL && y > 0 && this.cells[i - this.layerSize] == type
                && LiquidTile.byIndex(type - 1).isInfinite()) {
            return true;                                   // maybe a surface cell of a big body that needs topping up
        }
        boolean covered = y < this.depth - 1 && this.cells[i + this.layerSize] != 0;   // liquid above: under pressure
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                continue;
            }
            int j = i + d[0] + d[1] * this.width;
            if (room(j, nx, y, nz, type) > 0 && (covered || f - fill(j) >= 2)) {
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

        // big bodies of an infinite liquid: absorb what lies above sea level, remember if this is the surface
        boolean seaLevel = false;
        if (liquid.isInfinite() && y > 0 && this.cells[i - this.layerSize] == type && inBigBody(i, type)) {
            if (this.excess.get(i)) {
                this.layer.applyRaw(i, (byte) 0, (byte) 0);    // absorbed by the body
                wakeIndex(i);
                return;
            }
            seaLevel = this.surface.get(i);
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

        // 2. spread: level out with every lower side neighbour at once
        if (f >= 2) {
            int count = 0;
            int total = f;
            for (int s = 0; s < 4; s++) {
                int[] d = SIDES[(s + this.rotation) & 3];
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                    continue;
                }
                int j = i + d[0] + d[1] * this.width;
                int room = room(j, nx, y, nz, type);
                if (room <= 0 || f - fill(j) < 2) {
                    continue;
                }
                this.spreadTo[count] = j;
                this.spreadRoom[count] = room;
                total += fill(j);
                count++;
            }
            if (count > 0) {
                int share = total / (count + 1);
                boolean moved = false;
                for (int k = 0; k < count; k++) {
                    int j = this.spreadTo[k];
                    int m = Math.min(Math.min(share - fill(j), this.spreadRoom[k]), f);
                    if (m > 0) {
                        transfer(i, j, m);
                        f -= m;
                        moved = true;
                    }
                }
                if (!moved) {
                    // the even share rounded down to nothing (a few units next to many empty cells): still
                    // move one unit into the lowest neighbour, it has at least 2 less
                    int best = 0;
                    for (int k = 1; k < count; k++) {
                        if (fill(this.spreadTo[k]) < fill(this.spreadTo[best])) {
                            best = k;
                        }
                    }
                    transfer(i, this.spreadTo[best], 1);
                    f--;
                }
            }
        }

        // 2b. level the whole body this cell is part of
        if (this.cells[i] != 0) {
            levelBody(i, type);
        }
        f = fill(i);

        // 3. the surface of a big body is an endless supply: it is topped up again
        if (seaLevel && f > 0 && f < LiquidLayer.MAX_FILL
                && (this.fills[i - this.layerSize] & 0xFF) == LiquidLayer.MAX_FILL) {
            this.layer.applyRaw(i, type, (byte) LiquidLayer.MAX_FILL);
            wakeIndex(i);                                   // neighbours may want to take from the full cell
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

    // lowest cell of the run of this liquid that cell i is part of
    private int stackBottom(int i, byte type) {
        while (i >= this.layerSize && this.cells[i - this.layerSize] == type) {
            i -= this.layerSize;
        }
        return i;
    }

    // registers the stack that starts at bottomIdx. false (and nothing registered) when it is not a settled stack
    private boolean addStack(int bottomIdx, byte type) {
        int col = bottomIdx % this.layerSize;
        int x = col % this.width;
        int z = col / this.width;
        int b = bottomIdx / this.layerSize;

        if (b > 0 && this.cells[bottomIdx - this.layerSize] == 0 && this.layer.passable(x, b - 1, z)) {
            return false;                                   // nothing under it: it is still falling
        }
        int y = b;
        int amount = 0;
        while (true) {
            int idx = y * this.layerSize + col;
            int f = this.fills[idx] & 0xFF;
            amount += f;
            if (y == this.depth - 1 || this.cells[idx + this.layerSize] != type) {
                break;
            }
            if (f != LiquidLayer.MAX_FILL) {
                return false;                               // a stream, not a column of liquid
            }
            y++;
        }
        int top = y;
        int cap = top + 1;
        while (cap < this.depth) {
            int idx = cap * this.layerSize + col;
            if (this.cells[idx] != 0 || !this.layer.passable(x, cap, z)) {
                break;
            }
            cap++;
        }
        int s = this.stackCount++;
        this.stCol[s] = col;
        this.stBottomIdx[s] = bottomIdx;
        this.stB[s] = b;
        this.stT[s] = top;
        this.stCap[s] = cap;
        this.stAmount[s] = amount;
        return true;
    }

    // units that fit below water level h in all stacks
    private int poured(int h) {
        int sum = 0;
        for (int s = 0; s < this.stackCount; s++) {
            int floor = this.stB[s] * LiquidLayer.MAX_FILL;
            int room = (this.stCap[s] - this.stB[s]) * LiquidLayer.MAX_FILL;
            sum += Math.max(0, Math.min(h - floor, room));
        }
        return sum;
    }

    /**
     * levels the body of liquid that cell `start` belongs to (see the class comment): finds the connected
     * stacks, then pours all their liquid out again with one common water level. every body is handled once
     * per tick.
     */
    private void levelBody(int start, byte type) {
        if (this.leveled.get(start)) {
            return;
        }
        int first = stackBottom(start, type);
        this.stackCount = 0;
        if (!addStack(first, type)) {
            return;
        }
        this.levelSeen.set(first);

        boolean tooBig = false;
        for (int s = 0; s < this.stackCount && !tooBig; s++) {
            int col = this.stCol[s];
            int x = col % this.width;
            int z = col / this.width;
            int low = this.stB[s], high = this.stT[s];
            for (int[] d : SIDES) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                    continue;
                }
                int col2 = col + d[0] + d[1] * this.width;
                int y = low;
                while (y <= high) {
                    if (this.cells[y * this.layerSize + col2] != type) {
                        y++;
                        continue;
                    }
                    int bottom = stackBottom(y * this.layerSize + col2, type);
                    if (!this.levelSeen.get(bottom)) {
                        if (this.stackCount >= LEVEL_MAX_STACKS) {
                            tooBig = true;
                            break;
                        }
                        if (addStack(bottom, type)) {
                            this.levelSeen.set(bottom);
                        }
                    }
                    while (y <= high && this.cells[y * this.layerSize + col2] == type) {
                        y++;
                    }
                }
                if (tooBig) {
                    break;
                }
            }
        }

        int n = this.stackCount;
        int minHead = Integer.MAX_VALUE, maxHead = Integer.MIN_VALUE;
        int total = 0, lo = Integer.MAX_VALUE, hi = 0;
        for (int s = 0; s < n; s++) {
            this.levelSeen.clear(this.stBottomIdx[s]);
            for (int y = this.stB[s]; y <= this.stT[s]; y++) {
                this.leveled.set(y * this.layerSize + this.stCol[s]);
            }
            int head = this.stB[s] * LiquidLayer.MAX_FILL + this.stAmount[s];   // surface height in units
            minHead = Math.min(minHead, head);
            maxHead = Math.max(maxHead, head);
            total += this.stAmount[s];
            lo = Math.min(lo, this.stB[s] * LiquidLayer.MAX_FILL);
            hi = Math.max(hi, this.stCap[s] * LiquidLayer.MAX_FILL);
        }
        if (tooBig || n < 2 || maxHead - minHead <= 1) {
            return;                                         // one stack, too big, or already flat
        }

        // the highest water level that doesn't need more liquid than there is
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (poured(mid) <= total) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int level = lo;

        int remaining = total;
        for (int s = 0; s < n; s++) {
            int floor = this.stB[s] * LiquidLayer.MAX_FILL;
            int room = (this.stCap[s] - this.stB[s]) * LiquidLayer.MAX_FILL;
            this.stTarget[s] = Math.max(0, Math.min(level - floor, room));
            remaining -= this.stTarget[s];
        }
        // leftover units (less than the number of stacks the level could still rise in): the stacks that
        // already hold the most keep them first, so a settled body doesn't shuffle
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int s = 0; s < n && remaining > 0; s++) {
                int floor = this.stB[s] * LiquidLayer.MAX_FILL;
                int room = (this.stCap[s] - this.stB[s]) * LiquidLayer.MAX_FILL;
                int base = level - floor;
                if (base < 0 || base >= room || this.stTarget[s] != base) {
                    continue;
                }
                if (pass == 0 && this.stAmount[s] <= base) {
                    continue;
                }
                this.stTarget[s]++;
                remaining--;
            }
        }

        // pour: every stack is full from its floor up, with the remainder in the cell above
        for (int s = 0; s < n; s++) {
            int amount = this.stTarget[s];
            if (amount == this.stAmount[s]) {
                continue;
            }
            int full = amount / LiquidLayer.MAX_FILL;
            int rest = amount % LiquidLayer.MAX_FILL;
            int topLayer = rest > 0 ? this.stB[s] + full : this.stB[s] + full - 1;
            int end = Math.max(this.stT[s], topLayer);
            for (int y = this.stB[s]; y <= end; y++) {
                int idx = y * this.layerSize + this.stCol[s];
                int want = (y - this.stB[s]) < full ? LiquidLayer.MAX_FILL : ((y - this.stB[s]) == full ? rest : 0);
                int have = this.cells[idx] == type ? this.fills[idx] & 0xFF : 0;
                if (want == have) {
                    continue;
                }
                this.layer.applyRaw(idx, want == 0 ? (byte) 0 : type, (byte) want);
                this.touched.set(idx);
                wakeIndex(idx);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // big body analysis

    private int neighbour(int i, int x, int y, int z, int n) {
        switch (n) {
            case 0:  return x > 0 ? i - 1 : -1;
            case 1:  return x < this.width - 1 ? i + 1 : -1;
            case 2:  return z > 0 ? i - this.width : -1;
            case 3:  return z < this.height - 1 ? i + this.width : -1;
            case 4:  return y > 0 ? i - this.layerSize : -1;
            default: return y < this.depth - 1 ? i + this.layerSize : -1;
        }
    }

    /**
     * true when the cell is part of a body of at least infiniteBodySize connected cells of the same liquid.
     * for a big body this also fills `excess` (above sea level) and `surface` (at sea level) for the cells it
     * looked at. the flood fill stops as soon as the size limit is reached, so it never costs more than that
     * many cells, and the answer is remembered for the rest of the tick.
     */
    private boolean inBigBody(int start, byte type) {
        if (this.checked.get(start)) {
            return this.big.get(start);
        }
        int limit = Math.max(2, infiniteBodySize);
        if (this.queue.length != limit) {
            this.queue = new int[limit];
        }
        int[] q = this.queue;
        int head = 0, tail = 0;
        q[tail++] = start;
        this.visited.set(start);
        boolean isBig = false;

        while (head < tail && !isBig) {
            int i = q[head++];
            int x = i % this.width;
            int z = (i / this.width) % this.height;
            int y = i / this.layerSize;
            for (int n = 0; n < 6; n++) {
                int j = neighbour(i, x, y, z, n);
                if (j < 0 || this.cells[j] != type || this.visited.get(j)) {
                    continue;
                }
                this.visited.set(j);
                q[tail++] = j;
                if (tail >= limit) {
                    isBig = true;
                    break;
                }
            }
        }

        int level = -1;
        if (isBig) {
            int minY = this.depth, maxY = 0;
            for (int k = 0; k < tail; k++) {
                int cy = q[k] / this.layerSize;
                this.layerCount[cy]++;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
            }
            int widest = 0;
            for (int cy = minY; cy <= maxY; cy++) {
                widest = Math.max(widest, this.layerCount[cy]);
            }
            for (int cy = maxY; cy >= minY; cy--) {
                if (this.layerCount[cy] * SURFACE_SHARE >= widest) {
                    level = cy;
                    break;
                }
            }
            for (int cy = minY; cy <= maxY; cy++) {
                this.layerCount[cy] = 0;
            }
        }

        for (int k = 0; k < tail; k++) {
            int c = q[k];
            this.visited.clear(c);
            this.checked.set(c);
            if (isBig) {
                int cy = c / this.layerSize;
                this.big.set(c);
                this.excess.set(c, cy > level);
                this.surface.set(c, cy == level);
            }
        }
        return isBig;
    }
}