package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

public class SkyboxRenderer {

    private static final float SKY_RADIUS = 2000.0F;

    private static final int LATITUDE_SEGMENTS = 48;
    private static final int LONGITUDE_SEGMENTS = 96;

    private static final String SUN_TEXTURE = "/assets/rubydung/textures/enviroment/sun.png";
    private static final String MOON_TEXTURE = "/assets/rubydung/textures/enviroment/moon.png";

    // distance of the sun/moon quads from the eye (inside SKY_RADIUS)
    private static final float BODY_DISTANCE = 1500.0F;

    // world units per texture pixel: 1.0 = the quad is exactly as many units wide/tall as the image is pixels
    private static final float BODY_PIXEL_SCALE = 6.0F;

    // tilt of the sun/moon orbit away from the east-west plane (radians), so noon isn't exactly overhead
    private static final float ORBIT_TILT = 0.4F;

    private final Tessellator tessellator = new Tessellator();

    private boolean bodyTexturesLoaded = false;
    private int sunTexture = 0, sunWidth = 0, sunHeight = 0;
    private int moonTexture = 0, moonWidth = 0, moonHeight = 0;

    // The current cycle reference, updated each frame before rendering.
    private DayNightCycle cycle;

    // When true the sky writes and tests depth, so it can be looked at from outside (freecam) without its far side
    // painting over its near side. Off normally: the camera is inside, and the sky is just a backdrop drawn first.
    private boolean solid = false;

    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    /**
     * Draws only the sky dome. Sky colours come from the day-night cycle, so the mesh is rendered directly every frame
     * instead of cached in a display list.
     * The sun and moon are NOT drawn here: call {@link #renderSunAndMoon} between the two cloud layers
     * (see the betweenLayers callback of CloudRenderer.render).
     */
    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        this.cycle = cycle;

        setupRenderState(true);

        glPushMatrix();
        glTranslated(eyeX, eyeY, eyeZ);
        renderMesh();
        glPopMatrix();

        cleanupRenderState();
    }

    /** Draws the sun and moon on top of whatever is already in the sky (call between the white and coloured cloud layers). */
    public void renderSunAndMoon(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        this.cycle = cycle;

        setupRenderState(false);

        glPushMatrix();
        glTranslated(eyeX, eyeY, eyeZ);
        drawSunAndMoon();
        glPopMatrix();

        cleanupRenderState();
    }

    // sun and moon

    // camera-facing quads on a tilted circular orbit driven by the cycle's time (0.25 = sunrise, 0.5 = highest, 0.75 = sunset, the moon is opposite)
    // the textures have a black background, so they're blended additively and the black vanishes
    private void drawSunAndMoon() {
        if (!this.bodyTexturesLoaded) {
            this.bodyTexturesLoaded = true;
            int[] sun = loadTexture(SUN_TEXTURE);
            this.sunTexture = sun[0];
            this.sunWidth = sun[1];
            this.sunHeight = sun[2];
            int[] moon = loadTexture(MOON_TEXTURE);
            this.moonTexture = moon[0];
            this.moonWidth = moon[1];
            this.moonHeight = moon[2];
        }

        float angle = (this.cycle.getTime() - 0.25F) * (float) (2.0 * Math.PI);
        float sunX = (float) Math.cos(angle);
        float sunY = (float) (Math.sin(angle) * Math.cos(ORBIT_TILT));
        float sunZ = (float) (Math.sin(angle) * Math.sin(ORBIT_TILT));

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        if (this.sunTexture != 0) {
            // Fade out as it drops below the horizon, and turn warm while it is low.
            float visibility = clamp01((sunY + 0.1F) / 0.25F);
            float warm = clamp01(1.0F - sunY / 0.45F);
            drawBody(this.sunTexture, sunX, sunY, sunZ,
                    this.sunWidth * 0.5F * BODY_PIXEL_SCALE,
                    this.sunHeight * 0.5F * BODY_PIXEL_SCALE,
                    lerp(1.0F, 1.0F, warm) * visibility,
                    lerp(1.0F, 0.6F, warm) * visibility,
                    lerp(1.0F, 0.3F, warm) * visibility);
        }

        if (this.moonTexture != 0) {
            float moonY = -sunY;
            float visibility = clamp01((moonY + 0.1F) / 0.25F);
            // A bright daytime sky washes the moon out, but it stays faintly visible.
            float dim = 1.0F - 0.65F * this.cycle.getAmbient();
            float v = visibility * dim;
            drawBody(this.moonTexture, -sunX, moonY, -sunZ,
                    this.moonWidth * 0.5F * BODY_PIXEL_SCALE,
                    this.moonHeight * 0.5F * BODY_PIXEL_SCALE,
                    0.85F * v, 0.9F * v, 1.0F * v);
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_BLEND);
    }

    private void drawBody(int texture, float dx, float dy, float dz, float halfW, float halfH, float r, float g, float b) {
        if (r <= 0.0F && g <= 0.0F && b <= 0.0F) {
            return;
        }

        // Camera-facing basis: right = dir x worldUp, up = right x dir.
        float rx = -dz;
        float rz = dx;
        float len = (float) Math.sqrt(rx * rx + rz * rz);
        if (len < 1.0E-4F) {
            rx = 1.0F;
            rz = 0.0F;
        } else {
            rx /= len;
            rz /= len;
        }
        float ux = -rz * dy;
        float uy = rz * dx - rx * dz;
        float uz = rx * dy;

        float cx = dx * BODY_DISTANCE;
        float cy = dy * BODY_DISTANCE;
        float cz = dz * BODY_DISTANCE;

        Textures.bind(texture);

        this.tessellator.init(GL_QUADS);
        this.tessellator.color(r, g, b, 1.0F);

        this.tessellator.texture(0.0F, 0.0F);
        this.tessellator.vertex(cx - rx * halfW + ux * halfH, cy + uy * halfH, cz - rz * halfW + uz * halfH);
        this.tessellator.texture(1.0F, 0.0F);
        this.tessellator.vertex(cx + rx * halfW + ux * halfH, cy + uy * halfH, cz + rz * halfW + uz * halfH);
        this.tessellator.texture(1.0F, 1.0F);
        this.tessellator.vertex(cx + rx * halfW - ux * halfH, cy - uy * halfH, cz + rz * halfW - uz * halfH);
        this.tessellator.texture(0.0F, 1.0F);
        this.tessellator.vertex(cx - rx * halfW - ux * halfH, cy - uy * halfH, cz - rz * halfW - uz * halfH);

        this.tessellator.flush();
    }

    // loads a PNG from the classpath into a clamped, nearest-filtered RGBA texture
    // returns {id, width, height}, or {0, 0, 0} on failure
    private static int[] loadTexture(String path) {
        try (InputStream in = SkyboxRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                System.out.println("Missing sky texture: " + path);
                return new int[]{0, 0, 0};
            }

            BufferedImage image = ImageIO.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

            // Premultiply by alpha so any transparent pixels contribute nothing when added.
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int pixel : pixels) {
                int a = pixel >>> 24;
                buffer.put((byte) (((pixel >> 16) & 0xFF) * a / 255));
                buffer.put((byte) (((pixel >> 8) & 0xFF) * a / 255));
                buffer.put((byte) ((pixel & 0xFF) * a / 255));
                buffer.put((byte) a);
            }
            buffer.flip();

            int id = glGenTextures();
            Textures.bind(id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            return new int[]{id, width, height};
        } catch (IOException e) {
            System.out.println("Failed to load sky texture " + path + ": " + e.getMessage());
            return new int[]{0, 0, 0};
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private void renderMesh() {
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
    }

    private void addVertex(float theta, float phi) {

        float cosTheta = (float) Math.cos(theta);
        float sinTheta = (float) Math.sin(theta);

        float cosPhi = (float) Math.cos(phi);
        float sinPhi = (float) Math.sin(phi);

        float x = cosTheta * cosPhi * SKY_RADIUS;
        float y = sinTheta * SKY_RADIUS;
        float z = cosTheta * sinPhi * SKY_RADIUS;

        float[] zenith  = cycle.getZenithColor();
        float[] horizon = cycle.getHorizonColor();
        float[] nadir   = cycle.getNadirColor();

        float r;
        float g;
        float b;

        if (y >= 0.0F) {

            float t = (y / SKY_RADIUS + 0.35F) / 1.35F;

            t = Math.max(0.0F, Math.min(1.0F, t));

            t = t * t * (3.0F - 2.0F * t);

            r = lerp(horizon[0], zenith[0], t);
            g = lerp(horizon[1], zenith[1], t);
            b = lerp(horizon[2], zenith[2], t);

        } else {

            float t = -y / SKY_RADIUS;

            t = (float) Math.pow(t, 0.75);

            r = lerp(horizon[0], nadir[0], t);
            g = lerp(horizon[1], nadir[1], t);
            b = lerp(horizon[2], nadir[2], t);
        }

        tessellator.color(r, g, b);
        tessellator.vertex(x, y, z);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // writeDepth: whether this pass writes depth when the sky is solid (the dome does, the sun and moon only test)
    private void setupRenderState(boolean writeDepth) {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        if (this.solid) {
            glEnable(GL_DEPTH_TEST);
            glDepthMask(writeDepth);
        } else {
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
        }

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