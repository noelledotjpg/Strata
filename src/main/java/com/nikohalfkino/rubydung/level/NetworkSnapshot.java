package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * a whole world as one gzip blob, what a server sends a joining client: the block and data arrays as they are in
 * memory (both sides run the same build, so the tile ids match), the multi-tile origins, the sub-tiles by tile name and
 * the liquid layer in its save-file format
 */
public final class NetworkSnapshot {

    private NetworkSnapshot() {
    }

    /** server side, on the thread that owns the level */
    public static byte[] write(Level level) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(bytes, 1 << 16), 1 << 16))) {
            out.write(level.blocksArray());
            out.write(level.dataArray());

            List<int[]> origins = level.collectMultiTileOrigins();
            out.writeInt(origins.size());
            for (int[] origin : origins) {
                out.writeInt(origin[0]);
                out.writeInt(origin[1]);
                out.writeInt(origin[2]);
            }

            List<SubTiles.Entry> subTiles = level.getSubTiles().snapshot();
            out.writeInt(subTiles.size());
            for (SubTiles.Entry e : subTiles) {
                out.writeInt(e.x);
                out.writeInt(e.y);
                out.writeInt(e.z);
                out.writeUTF(e.tileName);
                out.writeInt(e.data);
                out.writeFloat(e.ox);
                out.writeFloat(e.oy);
                out.writeFloat(e.oz);
            }

            byte[] liquids = liquidBytes(level);
            out.writeInt(liquids.length);
            out.write(liquids);
        }
        return bytes.toByteArray();
    }

    /** client side, may run on any thread: builds the level and lights it. the level is read only (never saved) */
    public static Level read(byte[] compressed, String worldName, int width, int height, int depth,
                             LevelGenerator.WorldType type, long seed) throws IOException {
        int cells = width * height * depth;
        Level level = new Level(worldName, width, height, depth, type, seed, new byte[cells], new byte[cells]);

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(compressed), 1 << 16), 1 << 16))) {
            in.readFully(level.blocksArray());
            in.readFully(level.dataArray());

            int originCount = in.readInt();
            List<int[]> origins = new ArrayList<>(originCount);
            for (int i = 0; i < originCount; i++) {
                origins.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
            }
            level.registerMultiTiles(origins);

            int subTileCount = in.readInt();
            List<SubTiles.Entry> subTiles = new ArrayList<>(subTileCount);
            for (int i = 0; i < subTileCount; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                String name = in.readUTF();
                int data = in.readInt();
                float ox = in.readFloat();
                float oy = in.readFloat();
                float oz = in.readFloat();
                subTiles.add(new SubTiles.Entry(x, y, z, name, data, ox, oy, oz));
            }
            level.getSubTiles().restore(subTiles);

            byte[] liquids = new byte[in.readInt()];
            in.readFully(liquids);
            loadLiquids(level, liquids);
        }

        level.lighting.calcLightDepths(0, 0, level.width, level.height);
        level.lighting.rebuildBlockLight();
        level.setReadOnly(true);
        return level;
    }

    // the liquid layer only knows how to save to and load from a file
    private static byte[] liquidBytes(Level level) throws IOException {
        File temp = File.createTempFile("strata-liquids", ".dat");
        try {
            level.getLiquids().save(temp);
            return Files.readAllBytes(temp.toPath());
        } finally {
            temp.delete();
        }
    }

    private static void loadLiquids(Level level, byte[] bytes) throws IOException {
        File temp = File.createTempFile("strata-liquids", ".dat");
        try {
            Files.write(temp.toPath(), bytes);
            level.getLiquids().load(temp);
        } finally {
            temp.delete();
        }
    }
}
