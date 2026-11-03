package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * the liquid of every cell, in its own grid next to the level's block array
 *
 * a cell holds a normal tile (the host, in the block array) AND a liquid at the same time, so a slab, a stair, a pane or a half block can sit in water without pushing it out. the only thing that removes a liquid is a tile that fills the whole cell (see displacesLiquid), and a liquid can't be put into such a cell
 *
 * two bytes per cell: liquid index + 1 (0 = no liquid) and how full the cell is (1..MAX_FILL, 0 when empty).
 * the fill is what the LiquidSimulator moves around: a full cell is a whole block of liquid, a cell with 1 is a thin puddle
 */
public final class LiquidLayer {

    /** a full cell of liquid. the simulator works in these units (1 unit = 1/8 of a block of liquid) */
    public static final int MAX_FILL = 8;

    private final Level level;
    private final int width, depth, height;
    private final byte[] cells;
    private final byte[] fills;
    private final LiquidSimulator simulator;

    LiquidLayer(Level level, int width, int depth, int height) {
        this.level = level;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.cells = new byte[width * depth * height];
        this.fills = new byte[width * depth * height];
        this.simulator = new LiquidSimulator(this, this.cells, this.fills, width, depth, height);
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.depth && z < this.height;
    }

    private int index(int x, int y, int z) {
        return (y * this.height + z) * this.width + x;
    }

    /** whether a tile fills its cell, so a liquid can't share it (stone, glass, a full log...). slabs, stairs, panes, doors and sprites leave room for liquid */
    public static boolean displacesLiquid(Tile tile) {
        return tile != null && tile != Tile.AIR && tile.isSolid() && tile.occludesNeighbours()
                && !tile.isBillboard() && !(tile instanceof LiquidTile);
    }

    /** the liquid in the cell, null if none (or outside the level) */
    public LiquidTile get(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return null;
        }
        int value = this.cells[index(x, y, z)];
        return value == 0 ? null : LiquidTile.byIndex(value - 1);
    }

    public boolean has(int x, int y, int z) {
        return inside(x, y, z) && this.cells[index(x, y, z)] != 0;
    }

    /** how full the cell is: 0 = no liquid, MAX_FILL = a whole block */
    public int getFill(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return 0;
        }
        return this.fills[index(x, y, z)] & 0xFF;
    }

    /** puts a full cell of liquid in the cell (replacing another one). false if nothing changed: outside the level, same liquid already there and full, or a full block in the way */
    public boolean set(int x, int y, int z, LiquidTile liquid) {
        return set(x, y, z, liquid, MAX_FILL);
    }

    /** same, with a given amount (1..MAX_FILL). the amount is what then spreads out when the simulation is on */
    public boolean set(int x, int y, int z, LiquidTile liquid, int fill) {
        if (liquid == null || fill <= 0) {
            return clear(x, y, z);
        }
        if (!inside(x, y, z) || displacesLiquid(Tile.byId(this.level.getTile(x, y, z)))) {
            return false;
        }
        byte value = (byte) (liquid.getLiquidIndex() + 1);
        byte amount = (byte) Math.min(fill, MAX_FILL);
        int i = index(x, y, z);
        if (this.cells[i] == value && this.fills[i] == amount) {
            return false;
        }
        applyRaw(i, value, amount);
        this.simulator.wake(x, y, z);
        return true;
    }

    public boolean clear(int x, int y, int z) {
        if (!has(x, y, z)) {
            return false;
        }
        applyRaw(index(x, y, z), (byte) 0, (byte) 0);
        this.simulator.wake(x, y, z);
        return true;
    }

    // -------------------------------------------------------------------------
    // simulation

    /** makes the simulation look at this cell and its neighbours again. call it when a block next to liquid is placed or removed (the simulation also notices on its own a little later) */
    public void wake(int x, int y, int z) {
        if (inside(x, y, z)) {
            this.simulator.wake(x, y, z);
        }
    }

    /** advances the simulation by real time, called every frame. does nothing while the simulation is off */
    public void update(float seconds) {
        this.simulator.update(seconds);
    }

    /** runs this many simulation ticks right now, even while the simulation is off */
    public void step(int ticks) {
        this.simulator.stepNow(ticks);
    }

    /** number of cells the simulation is currently keeping an eye on */
    public int getActiveCells() {
        return this.simulator.activeCount();
    }

    // package-private, for the simulator

    boolean passable(int x, int y, int z) {
        return inside(x, y, z) && !displacesLiquid(Tile.byId(this.level.getTile(x, y, z)));
    }

    /** writes a cell and tells the level, so the mesh is rebuilt. the same liquid with another fill counts as a change too */
    void applyRaw(int i, byte type, byte fill) {
        byte oldType = this.cells[i];
        if (oldType == type && this.fills[i] == fill) {
            return;
        }
        this.cells[i] = type;
        this.fills[i] = fill;

        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;
        this.level.liquidChanged(x, y, z,
                oldType == 0 ? null : LiquidTile.byIndex(oldType - 1),
                type == 0 ? null : LiquidTile.byIndex(type - 1));
    }

    // -------------------------------------------------------------------------
    // saving: a gzip file next to the level file. liquids are saved by tile NAME (a name table, then
    // runs of equal cells), so reordering the tile registry can't corrupt a save. after the cell runs
    // comes a second set of runs with the fill of every cell (older files stop before it: every cell is
    // then full)

    void save(File file) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            List<LiquidTile> all = LiquidTile.all();
            out.writeInt(this.width);
            out.writeInt(this.depth);
            out.writeInt(this.height);
            out.writeInt(all.size());
            for (LiquidTile liquid : all) {
                out.writeUTF(liquid.name);
            }

            writeRuns(out, this.cells);
            writeRuns(out, this.fills);
        } catch (IOException e) {
            System.err.println("[LiquidLayer] could not save " + file + ": " + e);
        }
    }

    private static void writeRuns(DataOutputStream out, byte[] data) throws IOException {
        int n = data.length;
        int i = 0;
        while (i < n) {
            byte value = data[i];
            int j = i + 1;
            while (j < n && data[j] == value) {
                j++;
            }
            out.writeByte(value);
            out.writeInt(j - i);
            i = j;
        }
    }

    /**
     * fills the grid with the water of a freshly generated world: per column (index z * width + x) the y of the water surface, -1 for a dry column.
     * each column is filled from there down until a block that fills its cell. cells are written straight into the arrays like load does: no notifications (the level isn't listened to yet) and no waking, so the simulation starts quiet
     */
    void fillGenerated(int[] surfaceY, LiquidTile liquid) {
        byte value = (byte) (liquid.getLiquidIndex() + 1);
        // every row writes its own cells, so the rows run on all cores
        java.util.stream.IntStream.range(0, this.height).parallel().forEach(z -> {
            for (int x = 0; x < this.width; x++) {
                int top = surfaceY[z * this.width + x];
                for (int y = Math.min(top, this.depth - 1); y >= 0; y--) {
                    if (displacesLiquid(Tile.byId(this.level.getTile(x, y, z)))) {
                        break;
                    }
                    int i = index(x, y, z);
                    this.cells[i] = value;
                    this.fills[i] = (byte) MAX_FILL;
                }
            }
        });
    }

    /** replaces everything with the saved liquids, false if there is no usable file (nothing is changed then). names that no longer exist are skipped, no notifications */
    boolean load(File file) {
        if (!file.isFile()) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != this.width || in.readInt() != this.depth || in.readInt() != this.height) {
                System.err.println("[LiquidLayer] " + file + " is for a different world size, ignored");
                return false;
            }

            int names = in.readInt();
            byte[] remap = new byte[names + 1];          // saved value -> current value
            for (int k = 0; k < names; k++) {
                Tile tile = Tile.byName(in.readUTF());
                remap[k + 1] = tile instanceof LiquidTile ? (byte) (((LiquidTile) tile).getLiquidIndex() + 1) : 0;
            }

            byte[] loaded = new byte[this.cells.length];
            int i = 0;
            while (i < loaded.length) {
                int value = in.readByte();
                int run = in.readInt();
                if (run < 1 || i + run > loaded.length || value < 0 || value > names) {
                    throw new IOException("corrupt run at cell " + i);
                }
                byte mapped = remap[value];
                if (mapped != 0) {
                    java.util.Arrays.fill(loaded, i, i + run, mapped);
                }
                i += run;
            }

            // fills: missing in older files, then every liquid cell is a full block
            byte[] loadedFills = new byte[this.cells.length];
            try {
                i = 0;
                while (i < loadedFills.length) {
                    int value = in.readByte();
                    int run = in.readInt();
                    if (run < 1 || i + run > loadedFills.length || value < 0 || value > MAX_FILL) {
                        throw new IOException("corrupt fill run at cell " + i);
                    }
                    java.util.Arrays.fill(loadedFills, i, i + run, (byte) value);
                    i += run;
                }
            } catch (EOFException e) {
                java.util.Arrays.fill(loadedFills, (byte) MAX_FILL);
            }
            for (int k = 0; k < loaded.length; k++) {
                if (loaded[k] == 0) {
                    loadedFills[k] = 0;
                } else if (loadedFills[k] == 0) {
                    loadedFills[k] = (byte) MAX_FILL;
                }
            }

            System.arraycopy(loaded, 0, this.cells, 0, loaded.length);
            System.arraycopy(loadedFills, 0, this.fills, 0, loadedFills.length);
            return true;
        } catch (IOException e) {
            System.err.println("[LiquidLayer] could not load " + file + ": " + e);
            return false;
        }
    }
}