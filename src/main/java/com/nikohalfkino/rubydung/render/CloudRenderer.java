package com.nikohalfkino.rubydung.render;

import static org.lwjgl.opengl.GL11.*;

public class CloudRenderer {

    private static final float TILE_WORLD_SIZE = 128.0F;
    private static final float PLANE_HALF_SIZE = 800.0F;

    private static final int GRID_SEGMENTS = 144;
    private static final float CELL_SIZE = (PLANE_HALF_SIZE * 2.0F) / GRID_SEGMENTS;

    private static final float FADE_START_FRACTION = 0.3F;
    private static final float DRIFT_UNITS_PER_MS = 0.00002F / 2;

    public static final float HEIGHT_OFFSET = 180.0F;

    private static final float LAYER_GAP = 15.0F;

    private final Tessellator tessellator = new Tessellator();

    private final long driftStartNanos = System.nanoTime();

    private int textureId = -1;
    private int cloudListLower = -1;
    private int cloudListUpper = -1;

    private static float ease(float t) {
        float smooth = t * t * (3.0F - 2.0F * t);
        return smooth * smooth * (3.0F - 2.0F * smooth);
    }

    private static float clamp01(float value) {
        if (value < 0.0F) return 0.0F;
        if (value > 1.0F) return 1.0F;
        return value;
    }

    public void render(double eyeX, double eyeY, double eyeZ) {
        renderLayers(eyeX, eyeY, eyeZ);
    }

    public void renderLayers(double eyeX, double eyeY, double eyeZ) {
        if (textureId == -1) {
            textureId = Textures.loadCloudTexture(CloudTextureGenerator.generate(1024, 42L));
        }

        if (cloudListLower == -1) {
            cloudListLower = buildMeshCustomTint(0.9F, 0.9F, 1.0F, 1.0F);
        }
        if (cloudListUpper == -1) {
            cloudListUpper = buildMeshCustomTint(0.3F, 0.6F, 1.0F, 0.8F);
        }

        double elapsedMs = (System.nanoTime() - driftStartNanos) / 1_000_000.0;
        float drift = (float) (elapsedMs * (double) DRIFT_UNITS_PER_MS % 1.0);

        setupRenderState();
        Textures.bind(textureId);

        glPushMatrix();
        glTranslated(eyeX, eyeY + HEIGHT_OFFSET - LAYER_GAP, eyeZ);

        glMatrixMode(GL_TEXTURE);
        glPushMatrix();
        glTranslatef(drift * 0.7F, 0.0F, 0.0F);
        glMatrixMode(GL_MODELVIEW);

        glCallList(cloudListLower);

        glMatrixMode(GL_TEXTURE);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();

        glPushMatrix();
        glTranslated(eyeX, eyeY + HEIGHT_OFFSET + LAYER_GAP, eyeZ);

        glMatrixMode(GL_TEXTURE);
        glPushMatrix();
        glTranslatef(drift, 0.0F, 0.0F);
        glMatrixMode(GL_MODELVIEW);

        glCallList(cloudListUpper);

        glMatrixMode(GL_TEXTURE);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();

        cleanupRenderState();
    }

    private int buildMeshCustomTint(float tintR, float tintG, float tintB, float alphaMul) {
        int list = glGenLists(1);
        glNewList(list, GL_COMPILE);
        tessellator.init();

        for (int gx = 0; gx < GRID_SEGMENTS; gx++) {
            for (int gz = 0; gz < GRID_SEGMENTS; gz++) {
                float minX = -PLANE_HALF_SIZE + gx * CELL_SIZE;
                float maxX = minX + CELL_SIZE;
                float minZ = -PLANE_HALF_SIZE + gz * CELL_SIZE;
                float maxZ = minZ + CELL_SIZE;

                addCellCustomTint(minX, maxX, minZ, maxZ, tintR, tintG, tintB, alphaMul);
            }
        }

        tessellator.flush();
        glEndList();
        return list;
    }

    private void addCellCustomTint(float minX, float maxX, float minZ, float maxZ, float rMul, float gMul, float bMul, float alphaMul) {
        addVertexCustomTint(minX, minZ, rMul, gMul, bMul, alphaMul);
        addVertexCustomTint(minX, maxZ, rMul, gMul, bMul, alphaMul);
        addVertexCustomTint(maxX, maxZ, rMul, gMul, bMul, alphaMul);
        addVertexCustomTint(maxX, minZ, rMul, gMul, bMul, alphaMul);
    }

    private void addVertexCustomTint(float x, float z, float rMul, float gMul, float bMul, float alphaMul) {
        float radiusFraction = (float) Math.sqrt(x * x + z * z) / PLANE_HALF_SIZE;

        float fade = ease(clamp01((radiusFraction - FADE_START_FRACTION) / (1.0F - FADE_START_FRACTION)));
        float alpha = (1.0F - fade) * alphaMul;

        float r = rMul;
        float g = gMul;
        float b = bMul;

        float scale = TILE_WORLD_SIZE * 4.0F;

        tessellator.color(r, g, b, alpha);
        tessellator.texture(x / scale, z / scale);
        tessellator.vertex(x, 0, z);
    }

    private void setupRenderState() {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        Textures.enableAlphaBlending();
        glDepthMask(false);
        glEnable(GL_TEXTURE_2D);
    }

    private void cleanupRenderState() {
        glDisable(GL_BLEND);
        glDepthMask(true);

        Textures.enableAlphaTest();
        glEnable(GL_FOG);
        glEnable(GL_CULL_FACE);
    }
}