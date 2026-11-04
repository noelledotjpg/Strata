package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public class CloudRenderer {

    private static final float TILE_WORLD_SIZE = 128.0F;
    private static final float PLANE_HALF_SIZE = 800.0F;

    private static final int GRID_SEGMENTS = 144;
    private static final float CELL_SIZE = (PLANE_HALF_SIZE * 2.0F) / GRID_SEGMENTS;

    // world units covered by one repeat of the cloud texture
    private static final float UV_SCALE = TILE_WORLD_SIZE * 4.0F;

    private static final float FADE_START_FRACTION = 0.3F;

    // how many texture repeats the upper layer scrolls per millisecond (one full repeat every 100 s)
    private static final double DRIFT_TILES_PER_MS = 0.00002 / 2;

    // the lower layer scrolls slower than the upper one
    private static final double LOWER_SPEED_FACTOR = 0.7;

    public static final float HEIGHT_OFFSET = 180.0F;

    private static final float LAYER_GAP = 15.0F;

    // Per-layer base tint and alpha. The day-night cloud colour is multiplied into the tint at draw time.
    private static final float[] LOWER_TINT = {0.9F, 0.9F, 1.0F};
    private static final float LOWER_ALPHA = 1.0F;
    private static final float[] UPPER_TINT = {0.3F, 0.6F, 1.0F};
    private static final float UPPER_ALPHA = 0.8F;

    private final Tessellator tessellator = new Tessellator();
    private final FloatBuffer tintBuffer = BufferUtils.createFloatBuffer(4);

    private final long driftStartNanos = System.nanoTime();

    // radial edge fade (1 = opaque, 0 = invisible) for every grid vertex; the grid is centred on the eye, so this never changes
    private final float[][] edgeAlpha = new float[GRID_SEGMENTS + 1][GRID_SEGMENTS + 1];

    private int textureId = -1;

    public CloudRenderer() {
        for (int gx = 0; gx <= GRID_SEGMENTS; gx++) {
            for (int gz = 0; gz <= GRID_SEGMENTS; gz++) {
                float x = -PLANE_HALF_SIZE + gx * CELL_SIZE;
                float z = -PLANE_HALF_SIZE + gz * CELL_SIZE;
                float radiusFraction = (float) Math.sqrt(x * x + z * z) / PLANE_HALF_SIZE;
                float fade = ease(clamp01((radiusFraction - FADE_START_FRACTION) / (1.0F - FADE_START_FRACTION)));
                this.edgeAlpha[gx][gz] = 1.0F - fade;
            }
        }
    }

    private static float ease(float t) {
        float smooth = t * t * (3.0F - 2.0F * t);
        return smooth * smooth * (3.0F - 2.0F * smooth);
    }

    private static float clamp01(float value) {
        if (value < 0.0F) return 0.0F;
        if (value > 1.0F) return 1.0F;
        return value;
    }

    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        renderLayers(eyeX, eyeY, eyeZ, cycle, null);
    }

    /**
     * @param betweenLayers run after the lower (white) layer and before the upper (coloured) one, with the GL state
     *                      restored to normal; use it to draw the sun and moon between the two layers. May be null.
     */
    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle, Runnable betweenLayers) {
        renderLayers(eyeX, eyeY, eyeZ, cycle, betweenLayers);
    }

    public void renderLayers(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle, Runnable betweenLayers) {
        if (textureId == -1) {
            textureId = Textures.loadCloudTexture(CloudTextureGenerator.generate(1024, 42L));
        }

        // Each layer wraps on its own full texture repeat, so both loop seamlessly.
        // (Wrapping one shared value and scaling it per layer made the slower layer jump back when the shared value wrapped.)
        double elapsedMs = (System.nanoTime() - driftStartNanos) / 1_000_000.0;
        float upperShift = (float) ((elapsedMs * DRIFT_TILES_PER_MS) % 1.0);
        float lowerShift = (float) ((elapsedMs * DRIFT_TILES_PER_MS * LOWER_SPEED_FACTOR) % 1.0);

        float[] cloudColor = cycle.getCloudColor();

        setupRenderState();
        Textures.bind(textureId);

        drawLayer(eyeX, eyeY + HEIGHT_OFFSET - LAYER_GAP, eyeZ, LOWER_TINT, cloudColor, LOWER_ALPHA, lowerShift);

        if (betweenLayers != null) {
            // The callback draws with its own GL state, so hand it a clean one and set the cloud state up again afterwards.
            cleanupRenderState();
            betweenLayers.run();
            setupRenderState();
            Textures.bind(textureId);
        }

        drawLayer(eyeX, eyeY + HEIGHT_OFFSET + LAYER_GAP, eyeZ, UPPER_TINT, cloudColor, UPPER_ALPHA, upperShift);

        cleanupRenderState();
    }

    // constant colour the texture is multiplied by (layer tint * time-of-day cloud colour)
    private void setLayerTint(float[] layerTint, float[] cloudColor) {
        this.tintBuffer.clear();
        this.tintBuffer.put(layerTint[0] * cloudColor[0])
                .put(layerTint[1] * cloudColor[1])
                .put(layerTint[2] * cloudColor[2])
                .put(1.0F)
                .flip();
        glTexEnv(GL_TEXTURE_ENV, GL_TEXTURE_ENV_COLOR, this.tintBuffer);
    }

    // The drift lives in the mesh: the plane stays centred on the eye (so the edge fade never moves),
    // and the texture coordinates of its vertices are shifted every frame.
    private void drawLayer(double x, double y, double z, float[] layerTint, float[] cloudColor, float alphaMul, float shift) {
        glPushMatrix();
        glTranslated(x, y, z);

        setLayerTint(layerTint, cloudColor);

        for (int gx = 0; gx < GRID_SEGMENTS; gx++) {
            float x0 = -PLANE_HALF_SIZE + gx * CELL_SIZE;
            float x1 = x0 + CELL_SIZE;

            tessellator.init(GL_TRIANGLE_STRIP);

            for (int gz = 0; gz <= GRID_SEGMENTS; gz++) {
                float vz = -PLANE_HALF_SIZE + gz * CELL_SIZE;

                addVertex(x0, vz, this.edgeAlpha[gx][gz] * alphaMul, shift);
                addVertex(x1, vz, this.edgeAlpha[gx + 1][gz] * alphaMul, shift);
            }

            tessellator.flush();
        }

        glPopMatrix();
    }

    private void addVertex(float x, float z, float alpha, float shift) {
        // RGB is white here: the tint is applied at draw time via the texture environment colour.
        tessellator.color(1.0F, 1.0F, 1.0F, alpha);
        tessellator.texture(x / UV_SCALE + shift, z / UV_SCALE);
        tessellator.vertex(x, 0, z);
    }

    private void setupRenderState() {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        Textures.enableAlphaBlending();
        glDepthMask(false);
        glEnable(GL_TEXTURE_2D);

        // RGB = texture * constant tint colour; alpha = texture alpha * vertex alpha (edge fade).
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL_MODULATE);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL_TEXTURE);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL_SRC_COLOR);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL13.GL_CONSTANT);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_OPERAND1_RGB, GL_SRC_COLOR);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL_MODULATE);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL_TEXTURE);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL_SRC_ALPHA);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_SOURCE1_ALPHA, GL13.GL_PRIMARY_COLOR);
        glTexEnvi(GL_TEXTURE_ENV, GL13.GL_OPERAND1_ALPHA, GL_SRC_ALPHA);
    }

    private void cleanupRenderState() {
        // Restore the default texture environment for everything drawn after the clouds.
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);

        glDisable(GL_BLEND);
        glDepthMask(true);

        Textures.enableAlphaTest();
        glEnable(GL_FOG);
        glEnable(GL_CULL_FACE);
    }
}