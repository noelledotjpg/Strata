package com.nikohalfkino.rubydung.render;

import static org.lwjgl.opengl.GL11.*;

/**
 * geometry shared by everything drawn as an upright camera-facing sprite: billboard tiles (batched through the Tessellator) and the player sprite (drawn immediately)
 *
 * the quad stands on y, is centred on (cx, cz) and turns around Y only, so it never tilts when the camera looks up or down. the turn is the screen-right vector in the XZ plane, rightX / rightZ derive it from a camera yaw
 */
public final class Billboard {

    // scratch buffer: top-left, top-right, bottom-right, bottom-left (x, y, z each)
    private static final float[] CORNERS = new float[12];

    private Billboard() {
    }

    /** x of the screen-right vector for a camera yaw in degrees (same convention as Player's strafe) */
    public static double rightX(float cameraYaw) {
        return Math.cos(Math.toRadians(cameraYaw));
    }

    public static double rightZ(float cameraYaw) {
        return Math.sin(Math.toRadians(cameraYaw));
    }

    private static void corners(double cx, double y, double cz, float width, float height,
                                double rightX, double rightZ) {
        float half = width / 2.0F;
        float dx = (float) (rightX * half);
        float dz = (float) (rightZ * half);
        float x = (float) cx;
        float z = (float) cz;
        float bottom = (float) y;
        float top = bottom + height;

        CORNERS[0] = x - dx;  CORNERS[1] = top;     CORNERS[2] = z - dz;    // top-left
        CORNERS[3] = x + dx;  CORNERS[4] = top;     CORNERS[5] = z + dz;    // top-right
        CORNERS[6] = x + dx;  CORNERS[7] = bottom;  CORNERS[8] = z + dz;    // bottom-right
        CORNERS[9] = x - dx;  CORNERS[10] = bottom; CORNERS[11] = z - dz;   // bottom-left
    }

    /**
     * adds the sprite to a tessellator batch. doubleSided adds a second mirrored quad so it reads the right way round from behind, leave it off when back-face culling is disabled anyway
     *
     * (u0, v0) is the texture coordinate of the top-left corner, (u1, v1) of the bottom-right
     */
    public static void emit(Tessellator t, double cx, double y, double cz, float width, float height,
                            double rightX, double rightZ,
                            float u0, float v0, float u1, float v1,
                            float r, float g, float b, float a, boolean doubleSided) {
        corners(cx, y, cz, width, height, rightX, rightZ);
        float[] c = CORNERS;

        vertex(t, c, 0, u0, v0, r, g, b, a);
        vertex(t, c, 3, u1, v0, r, g, b, a);
        vertex(t, c, 6, u1, v1, r, g, b, a);
        vertex(t, c, 9, u0, v1, r, g, b, a);

        if (doubleSided) {
            vertex(t, c, 3, u0, v0, r, g, b, a);
            vertex(t, c, 0, u1, v0, r, g, b, a);
            vertex(t, c, 9, u1, v1, r, g, b, a);
            vertex(t, c, 6, u0, v1, r, g, b, a);
        }
    }

    /**
     * one quad instead of the two of emit(..., doubleSided): the mirrored one, which is the one that faces the camera
     * with back-face culling on. Half the vertices to build and draw, but the caller must turn face culling off so the
     * sprite is visible whichever way round the quad's winding ends up.
     */
    public static void emitSingle(Tessellator t, double cx, double y, double cz, float width, float height,
                                  double rightX, double rightZ,
                                  float u0, float v0, float u1, float v1,
                                  float r, float g, float b, float a) {
        corners(cx, y, cz, width, height, rightX, rightZ);
        float[] c = CORNERS;

        vertex(t, c, 3, u0, v0, r, g, b, a);
        vertex(t, c, 0, u1, v0, r, g, b, a);
        vertex(t, c, 9, u1, v1, r, g, b, a);
        vertex(t, c, 6, u0, v1, r, g, b, a);
    }

    private static void vertex(Tessellator t, float[] c, int i, float u, float v,
                               float r, float g, float b, float a) {
        t.color(r, g, b, a);
        t.texture(u, v);
        t.vertex(c[i], c[i + 1], c[i + 2]);
    }

    /** draws straight away in immediate mode with whatever texture is bound, for the odd sprite with its own texture that doesn't belong in a batch. one-sided, disable face culling around the call if it's on */
    public static void draw(double cx, double y, double cz, float width, float height,
                            double rightX, double rightZ,
                            float u0, float v0, float u1, float v1,
                            float r, float g, float b, float a) {
        corners(cx, y, cz, width, height, rightX, rightZ);
        float[] c = CORNERS;

        glColor4f(r, g, b, a);
        glBegin(GL_QUADS);
        glTexCoord2f(u0, v0); glVertex3f(c[0], c[1], c[2]);
        glTexCoord2f(u1, v0); glVertex3f(c[3], c[4], c[5]);
        glTexCoord2f(u1, v1); glVertex3f(c[6], c[7], c[8]);
        glTexCoord2f(u0, v1); glVertex3f(c[9], c[10], c[11]);
        glEnd();
    }
}