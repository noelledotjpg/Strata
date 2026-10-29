package com.nikohalfkino.rubydung.level.Tiles;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.render.Tessellator;

public class RotatableTile extends Tile {

    public RotatableTile(String name) {
        super(name);
    }

    public RotatableTile(String name, String defaultTextureName) {
        super(name, defaultTextureName);
    }

    public boolean isDirectional() {
        return true;
    }

    private int rotateFace(int worldFace, int axis) {
        if (axis == 0) return worldFace;

        if (axis == 1) {
            if (worldFace == FACE_NORTH || worldFace == FACE_SOUTH) return FACE_TOP;
            if (worldFace == FACE_TOP || worldFace == FACE_BOTTOM) return FACE_NORTH;
            return FACE_WEST;
        }

        if (axis == 2) {
            if (worldFace == FACE_EAST || worldFace == FACE_WEST) return FACE_TOP;
            if (worldFace == FACE_TOP || worldFace == FACE_BOTTOM) return FACE_EAST;
            return FACE_NORTH;
        }

        return worldFace;
    }

    @Override
    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z, int totalAtlasSlots, int direction) {
        if (!this.isDirectional()) {
            direction = 0;
        }

        float shadeX = 0.6f;
        float shadeY = 1.0f;
        float shadeZ = 0.8f;

        float minX = x + 0.0F;
        float maxX = x + 1.0F;
        float minY = y + 0.0F;
        float maxY = y + 1.0F;
        float minZ = z + 0.0F;
        float maxZ = z + 1.0F;

        if (shouldRenderFace(level, x, y - 1, z)) {
            float brightness = level.getBrightness(x, y - 1, z) * shadeY;
            if (layer == 1 ^ brightness == shadeY) {
                int slot = getAtlasSlot(rotateFace(FACE_BOTTOM, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 2) {
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, minY, maxZ);
                } else {
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(minX, minY, minZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(maxX, minY, maxZ);
                }
            }
        }

        if (shouldRenderFace(level, x, y + 1, z)) {
            float brightness = level.getBrightness(x, y + 1, z) * shadeY;
            if (layer == 1 ^ brightness == shadeY) {
                int slot = getAtlasSlot(rotateFace(FACE_TOP, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 2) {
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, maxY, maxZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, maxY, maxZ);
                } else {
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(maxX, maxY, maxZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, maxY, maxZ);
                }
            }
        }

        if (shouldRenderFace(level, x, y, z - 1)) {
            float brightness = level.getBrightness(x, y, z - 1) * shadeZ;
            if (layer == 1 ^ brightness == shadeZ) {
                int slot = getAtlasSlot(rotateFace(FACE_NORTH, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 2) {
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, minZ);
                } else {
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, minY, minZ);
                }
            }
        }

        if (shouldRenderFace(level, x, y, z + 1)) {
            float brightness = level.getBrightness(x, y, z + 1) * shadeZ;
            if (layer == 1 ^ brightness == shadeZ) {
                int slot = getAtlasSlot(rotateFace(FACE_SOUTH, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 2) {
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, maxY, maxZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, minY, maxZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, maxY, maxZ);
                } else {
                    tessellator.texture(minU, minV);
                    tessellator.vertex(minX, maxY, maxZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(maxX, minY, maxZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, maxZ);
                }
            }
        }

        if (shouldRenderFace(level, x - 1, y, z)) {
            float brightness = level.getBrightness(x - 1, y, z) * shadeX;
            if (layer == 1 ^ brightness == shadeX) {
                int slot = getAtlasSlot(rotateFace(FACE_WEST, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 1) {
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, maxY, maxZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(minX, minY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                } else {
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(minX, maxY, maxZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(minX, maxY, minZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(minX, minY, minZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(minX, minY, maxZ);
                }
            }
        }

        if (shouldRenderFace(level, x + 1, y, z)) {
            float brightness = level.getBrightness(x + 1, y, z) * shadeX;
            if (layer == 1 ^ brightness == shadeX) {
                int slot = getAtlasSlot(rotateFace(FACE_EAST, direction));
                float minU = slot / (float) totalAtlasSlots;
                float maxU = minU + 1.0F / totalAtlasSlots;
                float minV = 0.0F;
                float maxV = 1.0F;

                tessellator.color(brightness, brightness, brightness);

                if (direction == 1) {
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, minY, maxZ);
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, maxZ);
                } else {
                    tessellator.texture(minU, maxV);
                    tessellator.vertex(maxX, minY, maxZ);
                    tessellator.texture(maxU, maxV);
                    tessellator.vertex(maxX, minY, minZ);
                    tessellator.texture(maxU, minV);
                    tessellator.vertex(maxX, maxY, minZ);
                    tessellator.texture(minU, minV);
                    tessellator.vertex(maxX, maxY, maxZ);
                }
            }
        }
    }
}