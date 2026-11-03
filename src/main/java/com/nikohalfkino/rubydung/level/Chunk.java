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

    // whether the liquid list has anything in it, so chunks without liquid cost nothing in that pass
    private boolean hasLiquid = false;

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

    public void rebuild(int layer) {
        if (rebuiltThisFrame >= LAYERS && !this.priority) {
            return;
        }

        updates++;
        rebuiltThisFrame++;

        this.dirty = false;

        buildList(layer);
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
        private final List<Tessellator.Mesh>[] meshes;
        private final boolean[] empty;
        private List<int[]> billboards = new ArrayList<>();
        private boolean liquidFound = false;

        @SuppressWarnings("unchecked")
        private Built(Chunk chunk) {
            this.chunk = chunk;
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
        this.billboardPositions.clear();
        this.billboardPositions.addAll(built.billboards);
        this.hasLiquid = built.liquidFound;
        this.dirty = false;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public boolean hasLiquid() {
        return this.hasLiquid;
    }

    /**
     * true if nothing in this chunk can be seen: it is all air, or it is buried in plain opaque blocks (a chunk deep in the rock).
     * most chunks of a big world are one of those, and skipping their cell-by-cell mesh pass is what makes loading fast.
     * only called for chunks that really are empty or buried, anything else (liquid, sub-tiles, odd shaped tiles, the level's edge) gets the normal pass
     */
    private boolean drawsNothing() {
        boolean checkSubTiles = !this.level.getSubTiles().isEmpty();
        LiquidLayer liquids = this.level.getLiquids();

        // all air, no liquid, no sub-tiles
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

        // buried: this chunk and the layer of cells around it are all plain opaque cubes, so no face of this chunk is exposed.
        // chunks on the edge of the level are always built, their outer faces can be seen from outside
        if (this.minX == 0 || this.minY == 0 || this.minZ == 0
                || this.maxX >= this.level.width || this.maxY >= this.level.depth || this.maxZ >= this.level.height
                || !this.level.getSubTiles().isEmpty()) {
            return false;
        }
        byte[] opaque = new byte[256];       // per tile id: 0 = not looked at yet, 1 = plain opaque cube, 2 = anything else
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

    private void buildList(int layer) {
        // single layer rebuild, same two steps as a full build
        Built built = new Built(this);
        boolean nothing = drawsNothing();
        if (nothing) {
            built.empty[layer] = true;
        } else {
            computeLayer(built, layer, TESSELLATOR);
        }

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

        if (layer == 0) {
            this.billboardPositions.clear();
            this.billboardPositions.addAll(built.billboards);
        }
        if (layer == LAYER_LIQUID) {
            this.hasLiquid = built.liquidFound;
        }
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
                        // this list only holds liquids (from the level's liquid layer, so a block may share the cell),
                        // everything else (sub-tiles included) belongs to layers 0 and 1
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
                            tile = null;   // liquids live in the liquid layer, never draw one as a block
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

                    // Tiles sharing this cell with its normal tile, each drawn shifted by its own offset.
                    for (SubTile subTile : this.level.getSubTiles().at(x, y, z)) {
                        Tile shared = Tile.byId(subTile.tileId);
                        if (shared == null || shared.isBillboard()) {
                            continue;
                        }
                        tessellator.setOffset(subTile.ox, subTile.oy, subTile.oz);
                        int clip = SubTile.clipOf(subTile.data);
                        if (clip != 0) {
                            // A block sharing the cell with a slab: only its lower or upper half is drawn.
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
            rebuild(0);
            rebuild(1);
            rebuild(LAYER_LIQUID);
            if (!this.dirty) {
                this.priority = false;
            }
        }

        glCallList(this.lists + layer);
    }

    public void setDirty() {
        this.dirty = true;
    }

    public void setDirtyNow() {
        this.dirty = true;
        this.priority = true;
    }

    public void dispose() {
        glDeleteLists(this.lists, LAYERS);
    }
}