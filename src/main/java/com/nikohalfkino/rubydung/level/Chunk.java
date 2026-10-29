package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
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
        }

        TOTAL_ATLAS_SLOTS = Math.max(1, textureList.size());
        TEXTURE = Textures.buildAtlas(textureList.toArray(new String[0]), GL_NEAREST);
    }

    public static int rebuiltThisFrame;
    public static int updates;

    private final Level level;

    public AABB boundingBox;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    private final int lists;
    private boolean dirty = true;

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

        this.lists = glGenLists(2);

        this.boundingBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void rebuild(int layer) {
        if (rebuiltThisFrame == 2) {
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
    }

    public boolean isDirty() {
        return this.dirty;
    }

    private void buildList(int layer) {
        glNewList(this.lists + layer, GL_COMPILE);
        glEnable(GL_TEXTURE_2D);
        Textures.bind(TEXTURE);
        TESSELLATOR.init();

        if (layer == 0) {
            this.billboardPositions.clear();
        }

        for (int x = this.minX; x < this.maxX; ++x) {
            for (int y = this.minY; y < this.maxY; ++y) {
                for (int z = this.minZ; z < this.maxZ; ++z) {
                    int blockId = this.level.getTile(x, y, z);
                    if (blockId != 0) {
                        Tile tile = Tile.byId(blockId);
                        if (tile != null) {
                            if (tile.isBillboard()) {
                                if (layer == 0) {
                                    this.billboardPositions.add(new int[]{x, y, z});
                                }
                                continue;
                            }
                            int direction = this.level.getData(x, y, z);
                            tile.render(TESSELLATOR, this.level, layer, x, y, z, TOTAL_ATLAS_SLOTS, direction);
                        }
                    }
                }
            }
        }

        TESSELLATOR.flush();
        glDisable(GL_TEXTURE_2D);
        glEndList();
    }

    public void render(int layer) {
        if (this.dirty) {
            rebuild(0);
            rebuild(1);
        }

        glCallList(this.lists + layer);
    }

    public void setDirty() {
        this.dirty = true;
    }

    public void dispose() {
        glDeleteLists(this.lists, 2);
    }
}