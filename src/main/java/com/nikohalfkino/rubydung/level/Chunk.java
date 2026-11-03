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
        buildList(0);
        buildList(1);
        buildList(LAYER_LIQUID);
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public boolean hasLiquid() {
        return this.hasLiquid;
    }

    private void buildList(int layer) {
        boolean liquidLayer = layer == LAYER_LIQUID;
        boolean liquidFound = false;

        glNewList(this.lists + layer, GL_COMPILE);
        if (!liquidLayer) {
            // liquids don't use the atlas, LiquidShader makes their look
            glEnable(GL_TEXTURE_2D);
            Textures.bind(TEXTURE);
        }
        TESSELLATOR.init();

        if (layer == 0) {
            this.billboardPositions.clear();
        }

        for (int x = this.minX; x < this.maxX; ++x) {
            for (int y = this.minY; y < this.maxY; ++y) {
                for (int z = this.minZ; z < this.maxZ; ++z) {
                    int blockId = this.level.getTile(x, y, z);

                    if (liquidLayer) {
                        // this list only holds liquids (from the level's liquid layer, so a block may share the cell),
                        // everything else (sub-tiles included) belongs to layers 0 and 1
                        LiquidTile liquid = this.level.getLiquids().get(x, y, z);
                        if (liquid != null) {
                            liquid.renderLiquid(TESSELLATOR, this.level, x, y, z);
                            liquidFound = true;
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
                                    this.billboardPositions.add(new int[]{x, y, z});
                                }
                                continue;
                            }
                            int direction = this.level.getData(x, y, z);
                            tile.render(TESSELLATOR, this.level, layer, x, y, z, TOTAL_ATLAS_SLOTS, direction);
                        }
                    }

                    // Tiles sharing this cell with its normal tile, each drawn shifted by its own offset.
                    for (SubTile subTile : this.level.getSubTiles().at(x, y, z)) {
                        Tile shared = Tile.byId(subTile.tileId);
                        if (shared == null || shared.isBillboard()) {
                            continue;
                        }
                        TESSELLATOR.setOffset(subTile.ox, subTile.oy, subTile.oz);
                        int clip = SubTile.clipOf(subTile.data);
                        if (clip != 0) {
                            // A block sharing the cell with a slab: only its lower or upper half is drawn.
                            float lo = clip == SubTile.CLIP_BOTTOM ? y : y + 0.5F;
                            TESSELLATOR.setClipY(y, y + 1.0F, lo + subTile.oy, lo + 0.5F + subTile.oy);
                        }
                        shared.render(TESSELLATOR, this.level, layer, x, y, z, TOTAL_ATLAS_SLOTS,
                                SubTile.baseData(subTile.data));
                        TESSELLATOR.clearClip();
                        TESSELLATOR.clearOffset();
                    }
                }
            }
        }

        TESSELLATOR.flush();
        if (!liquidLayer) {
            glDisable(GL_TEXTURE_2D);
        }
        glEndList();

        if (liquidLayer) {
            this.hasLiquid = liquidFound;
        }
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