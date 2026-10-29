package com.nikohalfkino.rubydung.level.Tiles;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;

public abstract class BillboardTile extends Tile {

    protected static final float BILLBOARD_DISTANCE_THRESHOLD = 2.0F;
    protected static final float MAX_CLAMP_ANGLE = 40.0F;

    private final float height;

    public BillboardTile(String name, String textureName, float height) {
        super(name, textureName);
        this.height = height;
        setTransparent(true);
    }

    public float getHeight() {
        return this.height;
    }

    @Override
    public boolean isBillboard() {
        return true;
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

    @Override
    public AABB getPickAABB(int x, int y, int z) {
        return new AABB(x, y, z, x + 1.0F, y + this.height, z + 1.0F);
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

    @Override
    public void renderBillboard(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots) {
        float brightness = level.getBrightness(x, y, z);
        tessellator.color(brightness, brightness, brightness, 1.0F);

        int slot = getAtlasSlot(FACE_NORTH);
        float minU = slot / (float) totalAtlasSlots;
        float maxU = minU + 1.0F / totalAtlasSlots;
        float minV = 0.0F;
        float maxV = 1.0F;

        int hash = x * 31220 + z * 452389 + y * 93901;
        if ((hash & 1) == 0) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = this.height;
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
        float brightness = level.getBrightness(x, y, z);

        int slot = getAtlasSlot(FACE_NORTH);
        float minU = slot / (float) totalAtlasSlots;
        float maxU = minU + 1.0F / totalAtlasSlots;
        float minV = 0.0F;
        float maxV = 1.0F;

        int hash = x * 31220 + z * 452389 + y * 93901;
        if ((hash & 1) == 0) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = this.height;
        float half = quadHeight / 2.0F;

        putVertex(tessellator, -half, quadHeight, minU, minV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, half, quadHeight, maxU, minV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, half, 0.0F, maxU, maxV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, -half, 0.0F, minU, maxV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);

        putVertex(tessellator, half, quadHeight, minU, minV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, -half, quadHeight, maxU, minV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, -half, 0.0F, maxU, maxV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        putVertex(tessellator, half, 0.0F, minU, maxV, brightness, worldX, worldY, worldZ, cosYaw, sinYaw, scale);
    }

    private static void putVertex(Tessellator tessellator, float localX, float localY, float u, float v,
                                   float brightness, float worldX, float worldY, float worldZ,
                                   float cosYaw, float sinYaw, float scale) {
        tessellator.color(brightness, brightness, brightness, 1.0F);
        tessellator.texture(u, v);

        float sx = localX * scale;
        float sy = localY * scale;
        tessellator.vertex(
                worldX + sx * cosYaw,
                worldY + sy,
                worldZ - sx * sinYaw
        );
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