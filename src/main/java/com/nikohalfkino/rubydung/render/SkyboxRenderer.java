package com.nikohalfkino.rubydung.render;

import static org.lwjgl.opengl.GL11.*;

public class SkyboxRenderer {

    private static final float SKY_RADIUS = 2000.0F;

    private static final int LATITUDE_SEGMENTS = 48;
    private static final int LONGITUDE_SEGMENTS = 96;

    private static final float ZENITH_R = 0.2F;
    private static final float ZENITH_G = 0.5F;
    private static final float ZENITH_B = 0.8F;

    private static final float HORIZON_R = 0.4F;
    private static final float HORIZON_G = 0.7F;
    private static final float HORIZON_B = 1F;

    private static final float NADIR_R = 0.70F;
    private static final float NADIR_G = 0.87F;
    private static final float NADIR_B = 1.00F;;

    private final Tessellator tessellator = new Tessellator();

    private int displayList = -1;

    public void render(double eyeX, double eyeY, double eyeZ) {
        if (displayList == -1) {
            displayList = buildMesh();
        }

        setupRenderState();

        glPushMatrix();
        glTranslated(eyeX, eyeY, eyeZ);
        glCallList(displayList);
        glPopMatrix();

        cleanupRenderState();
    }

    private int buildMesh() {
        int list = glGenLists(1);

        glNewList(list, GL_COMPILE);

        for (int lat = 0; lat < LATITUDE_SEGMENTS; lat++) {

            float theta0 = (float) (-Math.PI / 2.0 + Math.PI * lat / LATITUDE_SEGMENTS);
            float theta1 = (float) (-Math.PI / 2.0 + Math.PI * (lat + 1) / LATITUDE_SEGMENTS);

            tessellator.init(GL_TRIANGLE_STRIP);

            for (int lon = 0; lon <= LONGITUDE_SEGMENTS; lon++) {

                float phi = (float) (2.0 * Math.PI * lon / LONGITUDE_SEGMENTS);

                addVertex(theta0, phi);
                addVertex(theta1, phi);
            }

            tessellator.flush();
        }

        glEndList();

        return list;
    }

    private void addVertex(float theta, float phi) {

        float cosTheta = (float) Math.cos(theta);
        float sinTheta = (float) Math.sin(theta);

        float cosPhi = (float) Math.cos(phi);
        float sinPhi = (float) Math.sin(phi);

        float x = cosTheta * cosPhi * SKY_RADIUS;
        float y = sinTheta * SKY_RADIUS;
        float z = cosTheta * sinPhi * SKY_RADIUS;

        float r;
        float g;
        float b;

        if (y >= 0.0F) {

            float t = (y / SKY_RADIUS + 0.35F) / 1.35F;

            t = Math.max(0.0F, Math.min(1.0F, t));

            t = t * t * (3.0F - 2.0F * t);

            r = lerp(HORIZON_R, ZENITH_R, t);
            g = lerp(HORIZON_G, ZENITH_G, t);
            b = lerp(HORIZON_B, ZENITH_B, t);

        } else {

            float t = -y / SKY_RADIUS;

            t = (float) Math.pow(t, 0.75);

            r = lerp(HORIZON_R, NADIR_R, t);
            g = lerp(HORIZON_G, NADIR_G, t);
            b = lerp(HORIZON_B, NADIR_B, t);
        }

        tessellator.color(r, g, b);
        tessellator.vertex(x, y, z);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void setupRenderState() {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);

        glShadeModel(GL_SMOOTH);
    }

    private void cleanupRenderState() {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);

        glEnable(GL_ALPHA_TEST);
        glEnable(GL_FOG);
        glEnable(GL_CULL_FACE);
        glEnable(GL_TEXTURE_2D);
    }
}