package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

public class Chunk {

    public static final int SIZE = 16;

    // display lists per chunk: 0 = fully lit terrain, 1 = shadowed terrain, 2 = liquids (own shader, blended)
    public static final int LAYER_LIQUID = 2;
    private static final int LAYERS = 3;

    private static final int TEXTURE;
    private static final Textures.AtlasLayout ATLAS_LAYOUT;
    private static final int TOTAL_ATLAS_SLOTS;
    private static final Tessellator TESSELLATOR = new Tessellator();

    static {
        List<String> textureList = new ArrayList<>();
        Map<String, Integer> textureToSlotMap = new LinkedHashMap<>();

        for (Tile tile : Tile.getTiles()) {
            for (int face = 0; face < 6; face++) {
                String texPath = tile.getTexture(face);
                if (texPath != null) {
                    if (!textureToSlotMap.containsKey(texPath)) {
                        textureToSlotMap.put(texPath, textureList.size());
                        textureList.add(texPath);
                    }
                    int slot = textureToSlotMap.get(texPath);
                    tile.setAtlasSlot(face, slot);
                }
            }

            for (String texPath : tile.getExtraTextures()) {
                if (!textureToSlotMap.containsKey(texPath)) {
                    textureToSlotMap.put(texPath, textureList.size());
                    textureList.add(texPath);
                }
            }
        }

        TOTAL_ATLAS_SLOTS = Math.max(1, textureList.size());
        TEXTURE = Textures.buildAtlas(textureList.toArray(new String[0]), GL_NEAREST);
        ATLAS_LAYOUT = Textures.getAtlasLayout(TEXTURE);

        for (Tile tile : Tile.getTiles()) {
            tile.onAtlasBuilt(textureToSlotMap);
        }
    }

    public static int rebuiltThisFrame;
    public static int updates;

    private final Level level;

    public AABB boundingBox;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    private final int lists;
    private boolean dirty = true;

    private boolean priority = false;

    // Fade-in tracking for dither shader
    private long fadeStartNanos = 0;
    private static final long FADE_DURATION_NANOS = 400_000_000L; // 0.4 seconds

    // whether the liquid list has anything in it, so chunks without liquid cost nothing in that pass
    private boolean hasLiquid = false;

    // per layer: whether its display list has any geometry. an empty layer is never called, most chunks of a big world are air or buried
    private final boolean[] layerHasGeometry = new boolean[LAYERS];

    // bumped by every setDirty. a build remembers the value it started from, so a result that finishes after the chunk was changed again is recognised as out of date
    private volatile int version = 0;

    private final List<int[]> billboardPositions = new ArrayList<>();

    public List<int[]> getBillboardPositions() {
        return this.billboardPositions;
    }

    public static int getAtlasTexture() { return TEXTURE; }
    public static int getTotalAtlasSlots() { return TOTAL_ATLAS_SLOTS; }

    /** texture coordinates inside a slot of the block atlas: local 0..1 is the slot's own cell, left to right and top to bottom */
    public static float atlasU(int slot, float local) { return ATLAS_LAYOUT.u(slot, local); }
    public static float atlasV(int slot, float local) { return ATLAS_LAYOUT.v(slot, local); }

    public Chunk(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.level = level;

        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        this.lists = glGenLists(LAYERS);

        this.boundingBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // chunks rebuilt (all layers together) per LevelRenderer.render pass, urgent (priority) chunks ignore it
    private static final int MAX_REBUILDS_PER_PASS = 1;

    /**
     * rebuilds every layer of the chunk in one go, if the frame's budget allows it (or the chunk is urgent).
     * the layers must never be rebuilt separately: the "lit" and "shadowed" lists are split from the same light values,
     * so a chunk with one new and one stale list shows blocks lit that should be shadowed, until the next block update nearby
     */
    private void rebuildIfAllowed() {
        if (rebuiltThisFrame >= MAX_REBUILDS_PER_PASS && !this.priority) {
            return;
        }

        updates++;
        rebuiltThisFrame++;

        rebuildNow();
        this.priority = this.dirty;
    }

    void rebuildNow() {
        this.dirty = false;
        upload(computeAll(TESSELLATOR));
    }

    /**
     * the CPU half of a chunk build: everything that reads the level and makes vertices, nothing that touches GL,
     * so it can run on a worker thread (with that thread's own Tessellator) as long as the level isn't being changed
     */
    public static final class Built {
        private final Chunk chunk;
        private final int version;   // the chunk's version when this build started
        private final List<Tessellator.Mesh>[] meshes;
        private final boolean[] empty;
        private List<int[]> billboards = new ArrayList<>();
        private boolean liquidFound = false;

        @SuppressWarnings("unchecked")
        private Built(Chunk chunk) {
            this.chunk = chunk;
            this.version = chunk.version;
            this.meshes = new List[LAYERS];
            this.empty = new boolean[LAYERS];
        }

        public Chunk chunk() { return this.chunk; }
    }

    /** thread safe as long as the level isn't modified meanwhile; the tessellator must belong to the calling thread */
    public Built computeAll(Tessellator tessellator) {
        Built built = new Built(this);
        boolean nothing = drawsNothing();   // once per chunk, not once per layer
        for (int layer = 0; layer < LAYERS; layer++) {
            if (nothing) {
                built.empty[layer] = true;
            } else {
                computeLayer(built, layer, tessellator);
            }
        }
        return built;
    }

    /** GL thread only: compiles the display lists from a finished build */
    public void upload(Built built) {
        for (int layer = 0; layer < LAYERS; layer++) {
            glNewList(this.lists + layer, GL_COMPILE);
            if (!built.empty[layer]) {
                if (layer != LAYER_LIQUID) {
                    glEnable(GL_TEXTURE_2D);
                    Textures.bind(TEXTURE);
                }
                for (Tessellator.Mesh mesh : built.meshes[layer]) {
                    Tessellator.draw(mesh);
                }
                if (layer != LAYER_LIQUID) {
                    glDisable(GL_TEXTURE_2D);
                }
            }
            glEndList();
        }
        for (int layer = 0; layer < LAYERS; layer++) {
            this.layerHasGeometry[layer] = hasMeshes(built, layer);
        }
        this.billboardPositions.clear();
        this.billboardPositions.addAll(built.billboards);
        this.hasLiquid = built.liquidFound;

        // Mark fade-in start time upon successful upload
        this.fadeStartNanos = System.nanoTime();

        // changed while this was being built on a worker? then it is already out of date: stay dirty so it is built again,
        // otherwise the stale mesh (wrong light on some blocks) would stay until the next block update nearby
        this.dirty = built.version != this.version;
    }

    public float getFadeProgress() {
        if (this.fadeStartNanos == 0) {
            return 1.0F;
        }
        long elapsed = System.nanoTime() - this.fadeStartNanos;
        if (elapsed >= FADE_DURATION_NANOS) {
            return 1.0F;
        }
        return (float) elapsed / (float) FADE_DURATION_NANOS;
    }

    private static boolean hasMeshes(Built built, int layer) {
        return !built.empty[layer] && built.meshes[layer] != null && !built.meshes[layer].isEmpty();
    }

    /** whether any layer has something to draw. a dirty chunk hasn't been built yet, so this says nothing about it */
    public boolean hasGeometry() {
        for (boolean has : this.layerHasGeometry) {
            if (has) {
                return true;
            }
        }
        return false;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public boolean hasLiquid() {
        return this.hasLiquid;
    }

    private boolean drawsNothing() {
        boolean checkSubTiles = !this.level.getSubTiles().isEmpty();
        LiquidLayer liquids = this.level.getLiquids();

        boolean allAir = true;
        scan:
        for (int y = this.minY; y < this.maxY; y++) {
            for (int z = this.minZ; z < this.maxZ; z++) {
                for (int x = this.minX; x < this.maxX; x++) {
                    if (this.level.getTile(x, y, z) != 0 || liquids.has(x, y, z)
                            || (checkSubTiles && this.level.getSubTiles().has(x, y, z))) {
                        allAir = false;
                        break scan;
                    }
                }
            }
        }
        if (allAir) {
            return true;
        }

        if (this.minX == 0 || this.minY == 0 || this.minZ == 0
                || this.maxX >= this.level.width || this.maxY >= this.level.depth || this.maxZ >= this.level.height
                || !this.level.getSubTiles().isEmpty()) {
            return false;
        }
        byte[] opaque = new byte[256];
        for (int y = this.minY - 1; y <= this.maxY; y++) {
            for (int z = this.minZ - 1; z <= this.maxZ; z++) {
                for (int x = this.minX - 1; x <= this.maxX; x++) {
                    int id = this.level.getTile(x, y, z);
                    if (opaque[id] == 0) {
                        Tile tile = Tile.byId(id);
                        boolean plain = id != 0 && tile != null && tile.getClass() == Tile.class
                                && tile.isSolid() && !tile.isTransparent() && tile.occludesNeighbours();
                        opaque[id] = (byte) (plain ? 1 : 2);
                    }
                    if (opaque[id] != 1 || liquids.has(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void computeLayer(Built built, int layer, Tessellator tessellator) {
        boolean liquidLayer = layer == LAYER_LIQUID;

        tessellator.beginCapture();
        tessellator.init();

        for (int x = this.minX; x < this.maxX; ++x) {
            for (int y = this.minY; y < this.maxY; ++y) {
                for (int z = this.minZ; z < this.maxZ; ++z) {
                    int blockId = this.level.getTile(x, y, z);

                    if (liquidLayer) {
                        LiquidTile liquid = this.level.getLiquids().get(x, y, z);
                        if (liquid != null) {
                            liquid.renderLiquid(tessellator, this.level, x, y, z);
                            built.liquidFound = true;
                        }
                        continue;
                    }

                    if (blockId != 0) {
                        Tile tile = Tile.byId(blockId);
                        if (tile instanceof LiquidTile) {
                            tile = null;
                        }
                        if (tile != null) {
                            if (tile.isBillboard()) {
                                if (layer == 0 && !this.level.isMultiTilePart(x, y, z)) {
                                    built.billboards.add(new int[]{x, y, z});
                                }
                                continue;
                            }
                            int direction = this.level.getData(x, y, z);
                            tile.render(tessellator, this.level, layer, x, y, z, TOTAL_ATLAS_SLOTS, direction);
                        }
                    }

                    for (SubTile subTile : this.level.getSubTiles().at(x, y, z)) {
                        Tile shared = Tile.byId(subTile.tileId);
                        if (shared == null || shared.isBillboard()) {
                            continue;
                        }
                        tessellator.setOffset(subTile.ox, subTile.oy, subTile.oz);
                        int clip = SubTile.clipOf(subTile.data);
                        if (clip != 0) {
                            float lo = clip == SubTile.CLIP_BOTTOM ? y : y + 0.5F;
                            tessellator.setClipY(y, y + 1.0F, lo + subTile.oy, lo + 0.5F + subTile.oy);
                        }
                        shared.render(tessellator, this.level, layer, x, y, z, TOTAL_ATLAS_SLOTS,
                                SubTile.baseData(subTile.data));
                        tessellator.clearClip();
                        tessellator.clearOffset();
                    }
                }
            }
        }

        tessellator.flush();
        built.meshes[layer] = tessellator.endCapture();
    }

    public void render(int layer) {
        if (this.dirty) {
            rebuildIfAllowed();
        }

        if (!this.layerHasGeometry[layer]) {
            return;
        }
        glCallList(this.lists + layer);
    }

    public void setDirty() {
        this.dirty = true;
        this.version++;
    }

    public void setDirtyNow() {
        this.dirty = true;
        this.version++;
        this.priority = true;
    }

    public void dispose() {
        glDeleteLists(this.lists, LAYERS);
    }
}