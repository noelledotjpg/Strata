package com.nikohalfkino.rubydung.level.tiles;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Billboard;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

public abstract class BillboardTile extends Tile {

    protected static final float BILLBOARD_DISTANCE_THRESHOLD = 2.0F;
    protected static final float MAX_CLAMP_ANGLE = 40.0F;

    private final float height;

    public static final int PIXELS_PER_BLOCK = 16;

    private float spriteSize = -1.0F;

    private float atlasExtent = -1.0F;

    public BillboardTile(String name, String textureName, float height) {
        super(name, textureName);
        this.height = height;
        setTransparent(true);
    }

    public float getHeight() {
        return this.height;
    }

    public float getSpriteSize() {
        if (this.spriteSize < 0.0F) {
            int pixels = Textures.getSourceWidth(getTexture(FACE_NORTH));
            if (pixels == 0) {
                return this.height;
            }
            this.spriteSize = pixels / (float) PIXELS_PER_BLOCK;
        }
        return this.spriteSize;
    }

    private float getAtlasExtent() {
        if (this.atlasExtent < 0.0F) {
            this.atlasExtent = Textures.getAtlasExtent(getTexture(FACE_NORTH));
        }
        return this.atlasExtent;
    }

    @Override
    public boolean isBillboard() {
        return true;
    }

    public float getPivotOffsetX() {
        return (getSizeX(0) - 1) / 2.0F;
    }

    public float getPivotOffsetZ() {
        return (getSizeZ(0) - 1) / 2.0F;
    }

    @Override
    public AABB getAABB(int x, int y, int z) {
        return null;
    }

    public static float getDistanceScale(float distance) {
        if (distance < 20.0F) {
            return 1.0F;
        } else if (distance < 150.0F) {
            return 1.0F + (distance - 20.0F) / 260.0F;
        } else if (distance < 500.0F) {
            return 1.5F + (distance - 150.0F) / 700.0F;
        } else {
            return 2.0F;
        }
    }

    protected void occupyHeight() {
        setFootprint(1, (int) Math.ceil(this.height), 1);
    }

    @Override
    public AABB getPickAABB(int x, int y, int z) {
        return new AABB(x, y, z, x + getSizeX(0), y + this.height, z + getSizeZ(0));
    }

    @Override
    public AABB getOutlineAABB(Level level, int x, int y, int z) {
        AABB box = getPickAABB(x, y, z);
        float drop = level.getBillboardDrop(x, y, z);
        if (drop == 0.0F) {
            return box;
        }
        return new AABB(box.minX, box.minY - drop, box.minZ, box.maxX, box.maxY - drop, box.maxZ);
    }

    @Override
    public java.util.List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        return java.util.Collections.singletonList(getOutlineAABB(level, x, y, z));
    }

    public float getEffectiveYaw(float tileX, float tileZ, float playerX, float playerZ, float currentCameraYaw) {
        float dx = playerX - tileX;
        float dz = playerZ - tileZ;
        float distanceSquared = (dx * dx) + (dz * dz);

        if (distanceSquared > (BILLBOARD_DISTANCE_THRESHOLD * BILLBOARD_DISTANCE_THRESHOLD)) {
            return Math.max(-MAX_CLAMP_ANGLE, Math.min(currentCameraYaw, MAX_CLAMP_ANGLE));
        }

        return currentCameraYaw;
    }

    protected float spriteBrightness(Level level, int x, int y, int z) {
        if (emitsLight()) {
            return 1.0F;
        }
        float base = level.getBrightness(x, y, z);
        float block = level.getBlockLight(x, y, z);
        return base + (1.0F - base) * block;
    }

    @Override
    public void renderBillboard(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots) {
        float brightness = spriteBrightness(level, x, y, z);
        tessellator.color(brightness, brightness, brightness, 1.0F);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        int hash = x * 31220 + z * 452389 + y * 93901;
        if ((hash & 1) == 0) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = getSpriteSize();
        float half = quadHeight / 2.0F;

        tessellator.texture(minU, minV);
        tessellator.vertex(-half, quadHeight, 0.0F);
        tessellator.texture(maxU, minV);
        tessellator.vertex(half, quadHeight, 0.0F);
        tessellator.texture(maxU, maxV);
        tessellator.vertex(half, 0.0F, 0.0F);
        tessellator.texture(minU, maxV);
        tessellator.vertex(-half, 0.0F, 0.0F);

        tessellator.texture(minU, minV);
        tessellator.vertex(half, quadHeight, 0.0F);
        tessellator.texture(maxU, minV);
        tessellator.vertex(-half, quadHeight, 0.0F);
        tessellator.texture(maxU, maxV);
        tessellator.vertex(-half, 0.0F, 0.0F);
        tessellator.texture(minU, maxV);
        tessellator.vertex(half, 0.0F, 0.0F);
    }

    public void renderBillboardBatched(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots,
                                       float worldX, float worldY, float worldZ,
                                       float cosYaw, float sinYaw, float scale) {
        renderBillboardBatched(tessellator, level, x, y, z, totalAtlasSlots,
                worldX, worldY, worldZ, cosYaw, sinYaw, scale, -1.0F, 1.0F);
    }

    public void renderBillboardBatched(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots,
                                       float worldX, float worldY, float worldZ,
                                       float cosYaw, float sinYaw, float scale,
                                       float brightnessOverride, float alpha) {
        float brightness = brightnessOverride >= 0.0F ? brightnessOverride : spriteBrightness(level, x, y, z);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        int hash = x * 31220 + z * 452389 + y * 93901;
        if ((hash & 1) == 0) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = getSpriteSize();

        Billboard.emit(tessellator, worldX, worldY, worldZ, quadHeight * scale, quadHeight * scale,
                cosYaw, -sinYaw, minU, minV, maxU, maxV,
                brightness, brightness, brightness, alpha, true);
    }

    @Override
    public void renderFace(Tessellator tessellator, int x, int y, int z, int face) {
        float minX = x + 0.0F;
        float maxX = x + 1.0F;
        float minY = y + 0.0F;
        float maxY = y + this.height;
        float minZ = z + 0.0F;
        float maxZ = z + 1.0F;

        if (face == FACE_BOTTOM) {
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, minY, maxZ);
        }
        if (face == FACE_TOP) {
            tessellator.vertex(maxX, maxY, maxZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, maxY, maxZ);
        }
        if (face == FACE_NORTH) {
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(minX, minY, minZ);
        }
        if (face == FACE_SOUTH) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
        if (face == FACE_WEST) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(minX, minY, maxZ);
        }
        if (face == FACE_EAST) {
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
    }
}