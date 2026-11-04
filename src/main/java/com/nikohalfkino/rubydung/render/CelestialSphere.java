package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL14;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;

/**
 * The part of the sky beyond the sun and moon, laid out like Aristotle's nested spheres around a fixed Earth:
 * (Moon), Mercury, Venus, (Sun), Mars, Jupiter, Saturn, and finally the sphere of fixed stars.
 * The sun and moon stay in SkyboxRenderer; this class draws everything else.
 *
 * Everything is placed in the same tilted frame the sun orbits in (see SkyboxRenderer.ORBIT_TILT):
 *   e1 = (1,0,0) = sunrise direction, e2 = (0,cos,sin) = where the sun climbs to, pole = e1 x e2 = the axis the sky turns about.
 * The star sphere is a rigid shell rotating about that pole, so stars and constellation lines are static display lists
 * that are just rotated each frame.
 *
 * Stars are only ever two generated pixel sprites (no star texture is loaded): a dot and a four-point star.
 * The dim stars are dots, the bright ones and every constellation star are four-point stars.
 *
 * Textures (optional, missing ones fall back to generated pixel sprites):
 *   mercury.png, venus.png, mars.png, jupiter.png, saturn.png : one sprite each, black background (blended additively)
 */
public class CelestialSphere {

    private static final double TWO_PI = Math.PI * 2.0;

    // OpenGL's GL_CONSTANT_COLOR blend factor (0x8001). Not exposed by GL14 in this LWJGL version, so it is spelled out here.
    private static final int GL_CONSTANT_COLOR = 0x8001;

    private static final String TEXTURE_DIR = "/assets/rubydung/textures/enviroment/";

    // time: planets are placed from the number of game days passed. A year is this many game days, so the planets
    // actually visibly move (the real periods are scaled by YEAR_DAYS / 365.25)
    private static final double YEAR_DAYS = 48.0;
    private static final double TIME_SCALE = YEAR_DAYS / 365.25;

    // stars
    private static final float STAR_DISTANCE = 1900.0F;
    // the sprites are about 2.5x wider than they were (see STAR_PIXEL), so far fewer are needed to keep the sky from crowding
    private static final int RANDOM_STARS = 220;

    // star sprite layout: two generated square cells side by side
    private static final int STAR_VARIANTS = 2;
    private static final int VARIANT_DOT = 0;
    private static final int VARIANT_FLARE = 1;   // the four-point star
    private static final int FALLBACK_CELL = 13;

    // random stars brighter than this (0..1) are four-point stars, the rest are dots
    private static final float FLARE_MAGNITUDE = 0.35F;

    // The sun and moon (SkyboxRenderer) are 6 world units per texel at 1500 units from the eye. Stars sit farther out, so
    // their texels are scaled by distance to cover the same angle on screen: same pixel density as the sun and moon.
    private static final float BODY_TEXEL_AT_REFERENCE = 6.0F;   // = SkyboxRenderer.BODY_PIXEL_SCALE
    private static final float BODY_REFERENCE_DISTANCE = 1500.0F; // = SkyboxRenderer.BODY_REFERENCE_DISTANCE
    private static final float STAR_TEXEL_ANGLE = BODY_TEXEL_AT_REFERENCE / BODY_REFERENCE_DISTANCE;

    // world units per texel of a star sprite (at STAR_DISTANCE)
    private static final float STAR_PIXEL = STAR_TEXEL_ANGLE * STAR_DISTANCE;
    // stars are fully visible at or below ambient (this - 0.4) and gone at this ambient. Tune to your cycle's ambient range
    private static final float STAR_VISIBLE_BELOW = 0.65F;

    // star colours: white, blue-white, orange-red
    private static final float[][] TINTS = {
            {1.00F, 1.00F, 1.00F},
            {0.80F, 0.90F, 1.00F},
            {1.00F, 0.65F, 0.50F},
    };

    // constellation lines: each "pixel" is a LINE_PIXEL sized square, one every LINE_PIXEL * LINE_STEP units
    // (a line "pixel" is one star texel, so the lines match the stars and the sun and moon)
    private static final float LINE_PIXEL = STAR_PIXEL;
    private static final float LINE_STEP = 3.0F;   // a dot, then two dots' worth of gap
    private static final float[] LINE_COLOR = {0.06F, 0.08F, 0.15F}; // very faint, it is added to the sky

    // planets: same texel angle as the sun, moon and stars, so each sprite's world texel size grows with its distance
    // (a 32x32 planet covers 32 sun/moon texels of sky, whatever sphere it sits on)

    // ---------------------------------------------------------------- data

    private static final class Planet {
        final String file;
        final float distance;
        final float visibleBelow;   // like STAR_VISIBLE_BELOW, higher = survives brighter skies (Venus, Jupiter)
        final float[] tint;         // only used by the generated fallback sprite
        final double period;        // real days per lap round the zodiac (outer planets)
        final double elongation;    // max angle from the sun in radians (inner planets, 0 for outer)
        final double synodic;       // real days for one swing to and fro (inner planets)
        final double phase;
        final double inclination;   // max height above the ecliptic, radians

        int texture, width, height;
        boolean custom;

        private Planet(String file, float distance, float visibleBelow, float[] tint, double period,
                       double elongation, double synodic, double phase, double inclination) {
            this.file = file;
            this.distance = distance;
            this.visibleBelow = visibleBelow;
            this.tint = tint;
            this.period = period;
            this.elongation = elongation;
            this.synodic = synodic;
            this.phase = phase;
            this.inclination = inclination;
        }

        static Planet outer(String file, float distance, float visibleBelow, float[] tint,
                            double period, double phase, double inclination) {
            return new Planet(file, distance, visibleBelow, tint, period, 0.0, 0.0, phase, inclination);
        }

        static Planet inner(String file, float distance, float visibleBelow, float[] tint,
                            double elongation, double synodic, double phase, double inclination) {
            return new Planet(file, distance, visibleBelow, tint, 0.0, elongation, synodic, phase, inclination);
        }

        // ecliptic longitude (radians, grows eastward)
        double longitude(double days, double sunLongitude) {
            if (this.elongation > 0.0) {
                return sunLongitude + this.elongation * Math.sin(TWO_PI * days / (this.synodic * TIME_SCALE) + this.phase);
            }
            return this.phase + TWO_PI * days / (this.period * TIME_SCALE);
        }
    }

    // farthest first. Aristotle's order outwards: Moon (SkyboxRenderer 1400), Mercury, Venus, Sun (SkyboxRenderer 1550), Mars, Jupiter, Saturn
    private static final Planet[] PLANETS = {
            Planet.outer("saturn.png", 1760.0F, 0.65F, new float[]{0.95F, 0.85F, 0.55F}, 10759.0, 4.2, 0.04),
            Planet.outer("jupiter.png", 1680.0F, 0.80F, new float[]{0.90F, 0.75F, 0.60F}, 4332.6, 1.1, 0.02),
            Planet.outer("mars.png", 1600.0F, 0.65F, new float[]{1.00F, 0.45F, 0.30F}, 686.98, 2.5, 0.03),
            Planet.inner("venus.png", 1500.0F, 0.95F, new float[]{1.00F, 0.95F, 0.80F}, Math.toRadians(46.0), 583.9, 0.7, 0.04),
            Planet.inner("mercury.png", 1450.0F, 0.70F, new float[]{0.75F, 0.75F, 0.78F}, Math.toRadians(22.0), 115.9, 2.0, 0.05),
    };

    private static final class Constellation {
        // each star: {right ascension in hours, declination in degrees [, unused (was the sprite variant) [, tint index]]}
        // every constellation star is drawn as a four-point star; the tint index is optional (default white)
        final float[][] stars;
        final int[] edges;     // pairs of indices into stars

        Constellation(float[][] stars, int[] edges) {
            this.stars = stars;
            this.edges = edges;
        }
    }

    // Add your own: look up RA/Dec of the stars and list which ones are joined. Give the famous ones a tint (the third number is ignored now, keep it as a placeholder when you add a tint).
    private static final Constellation[] CONSTELLATIONS = {
            // Orion: Betelgeuse (red), Bellatrix, Alnitak, Alnilam, Mintaka, Saiph, Rigel (blue)
            new Constellation(new float[][]{
                    {5.92F, 7.41F, 4, 2}, {5.42F, 6.35F, 2, 1}, {5.68F, -1.94F, 2, 1}, {5.60F, -1.20F, 3, 1},
                    {5.53F, -0.30F, 2, 1}, {5.80F, -9.67F, 2, 1}, {5.24F, -8.20F, 5, 1}
            }, new int[]{0, 1, 0, 2, 1, 4, 2, 3, 3, 4, 2, 5, 4, 6}),
            // Big Dipper: Dubhe, Merak, Phecda, Megrez, Alioth, Mizar, Alkaid
            new Constellation(new float[][]{
                    {11.06F, 61.75F, 3, 2}, {11.03F, 56.38F}, {11.90F, 53.69F}, {12.26F, 57.03F}, {12.90F, 55.96F, 3, 0},
                    {13.40F, 54.93F}, {13.79F, 49.31F, 3, 1}
            }, new int[]{0, 1, 1, 2, 2, 3, 3, 0, 3, 4, 4, 5, 5, 6}),
            // Cassiopeia: Caph, Schedar, Gamma, Ruchbah, Segin
            new Constellation(new float[][]{
                    {0.15F, 59.15F}, {0.68F, 56.54F, 4, 2}, {0.94F, 60.72F, 3, 1}, {1.43F, 60.24F}, {1.91F, 63.67F}
            }, new int[]{0, 1, 1, 2, 2, 3, 3, 4}),
            // Scorpius: Acrab, Dschubba, Sigma, Antares (red), Tau, Epsilon, Zeta, Eta, Theta, Iota, Kappa, Shaula
            new Constellation(new float[][]{
                    {16.09F, -19.81F}, {16.01F, -22.62F}, {16.35F, -25.59F}, {16.49F, -26.43F, 6, 2}, {16.60F, -28.22F},
                    {16.83F, -34.29F}, {16.91F, -42.36F}, {17.20F, -43.24F}, {17.62F, -42.99F}, {17.79F, -40.13F},
                    {17.71F, -39.03F}, {17.56F, -37.10F, 5, 1}
            }, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11}),
    };

    // ---------------------------------------------------------------- state

    private final Tessellator tessellator = new Tessellator();

    private boolean loaded = false;
    private int starTexture = 0;
    private int discTexture = 0;
    private int starList = 0;
    private int lineList = 0;

    // game days elapsed. DayNightCycle only exposes the time of day, so full days are counted when it wraps. If you add a
    // day counter to DayNightCycle (and save it), use that here instead: it survives world loads and time commands.
    private int dayCount = 0;
    private float lastTime = -1.0F;

    // ---------------------------------------------------------------- render

    /** Call with the sky's matrix already translated to the sky centre. Draws behind clouds, sun and moon. */
    public void render(DayNightCycle cycle) {
        ensureLoaded();

        float time = cycle.getTime();
        if (this.lastTime >= 0.0F && time < this.lastTime - 0.5F) {
            this.dayCount++;
        }
        this.lastTime = time;

        double days = this.dayCount + time;
        double sunLongitude = TWO_PI * days / YEAR_DAYS;
        // the sun sits at phi = diurnal angle; the stars turn that much plus the year's drift (a sidereal day is shorter)
        double theta = (TWO_PI * (time - 0.25) + sunLongitude) % TWO_PI;

        float ambient = cycle.getAmbient();

        glEnable(GL_BLEND);
        glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        // stars and constellation lines: one blend with a constant fades the whole shell in and out
        float starVisibility = visibility(ambient, STAR_VISIBLE_BELOW);
        if (starVisibility > 0.0F) {
            GL14.glBlendColor(starVisibility, starVisibility, starVisibility, starVisibility);
            glBlendFunc(GL_CONSTANT_COLOR, GL_ONE);

            glPushMatrix();
            glRotatef((float) Math.toDegrees(theta), 0.0F, -SkyboxRenderer.sinTilt(), SkyboxRenderer.cosTilt());

            glDisable(GL_TEXTURE_2D);
            glCallList(this.lineList);

            glEnable(GL_TEXTURE_2D);
            Textures.bind(this.starTexture);
            glCallList(this.starList);
            glPopMatrix();
        }

        // planets, farthest first
        glEnable(GL_TEXTURE_2D);
        glBlendFunc(GL_ONE, GL_ONE);
        for (Planet planet : PLANETS) {
            float vis = visibility(ambient, planet.visibleBelow);
            if (vis <= 0.0F) {
                continue;
            }

            double longitude = planet.longitude(days, sunLongitude);
            double phi = theta - longitude;
            double inc = planet.inclination * Math.sin(longitude);
            float[] dir = toWorld(Math.cos(phi) * Math.cos(inc), Math.sin(phi) * Math.cos(inc), Math.sin(inc));

            float r = planet.custom ? 1.0F : planet.tint[0];
            float g = planet.custom ? 1.0F : planet.tint[1];
            float b = planet.custom ? 1.0F : planet.tint[2];
            float texel = STAR_TEXEL_ANGLE * planet.distance;
            drawSprite(planet.texture, dir, planet.distance,
                    planet.width * 0.5F * texel, planet.height * 0.5F * texel,
                    r * vis, g * vis, b * vis);
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
    }

    private static float visibility(float ambient, float visibleBelow) {
        float t = (visibleBelow - ambient) / 0.4F;
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    private void drawSprite(int texture, float[] d, float distance, float halfW, float halfH, float r, float g, float b) {
        float[][] basis = tangent(d);
        float[] right = basis[0];
        float[] up = basis[1];

        float cx = d[0] * distance;
        float cy = d[1] * distance;
        float cz = d[2] * distance;

        Textures.bind(texture);

        this.tessellator.init(GL_QUADS);
        this.tessellator.color(r, g, b, 1.0F);
        this.tessellator.texture(0.0F, 0.0F);
        this.tessellator.vertex(cx - right[0] * halfW + up[0] * halfH, cy - right[1] * halfW + up[1] * halfH, cz - right[2] * halfW + up[2] * halfH);
        this.tessellator.texture(1.0F, 0.0F);
        this.tessellator.vertex(cx + right[0] * halfW + up[0] * halfH, cy + right[1] * halfW + up[1] * halfH, cz + right[2] * halfW + up[2] * halfH);
        this.tessellator.texture(1.0F, 1.0F);
        this.tessellator.vertex(cx + right[0] * halfW - up[0] * halfH, cy + right[1] * halfW - up[1] * halfH, cz + right[2] * halfW - up[2] * halfH);
        this.tessellator.texture(0.0F, 1.0F);
        this.tessellator.vertex(cx - right[0] * halfW - up[0] * halfH, cy - right[1] * halfW - up[1] * halfH, cz - right[2] * halfW - up[2] * halfH);
        this.tessellator.flush();
    }

    // ---------------------------------------------------------------- geometry helpers

    // frame vector (e1, e2, pole components) -> world. e1 = x, e2 = (0,cos,sin), pole = (0,-sin,cos)
    private static float[] toWorld(double lx, double ly, double lz) {
        float c = SkyboxRenderer.cosTilt();
        float s = SkyboxRenderer.sinTilt();
        return new float[]{(float) lx, (float) (c * ly - s * lz), (float) (s * ly + c * lz)};
    }

    // right/up vectors of a sprite facing the origin, same convention as SkyboxRenderer.drawBody
    private static float[][] tangent(float[] d) {
        float rx = -d[2];
        float rz = d[0];
        float len = (float) Math.sqrt(rx * rx + rz * rz);
        if (len < 1.0E-4F) {
            rx = 1.0F;
            rz = 0.0F;
        } else {
            rx /= len;
            rz /= len;
        }
        float[] right = {rx, 0.0F, rz};
        float[] up = {-rz * d[1], rz * d[0] - rx * d[2], rx * d[1]};
        return new float[][]{right, up};
    }

    // celestial coordinates -> frame vector. The sky turns the way the sun does (positive about the pole), which makes the
    // pole the SOUTH celestial pole, so RA and Dec are mirrored into the frame to keep the real constellations the right way round
    private static float[] celestial(float raHours, float decDegrees) {
        double ra = Math.toRadians(raHours * 15.0);
        double dec = Math.toRadians(decDegrees);
        return toWorld(Math.cos(ra) * Math.cos(dec), -Math.sin(ra) * Math.cos(dec), -Math.sin(dec));
    }

    // ---------------------------------------------------------------- one-time setup

    private void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;

        this.starTexture = createFallbackStars();

        for (Planet planet : PLANETS) {
            int[] tex = SkyboxRenderer.loadTexture(TEXTURE_DIR + planet.file, true);
            if (tex[0] != 0) {
                planet.texture = tex[0];
                planet.width = tex[1];
                planet.height = tex[2];
                planet.custom = true;
            } else {
                if (this.discTexture == 0) {
                    this.discTexture = createFallbackDisc();
                }
                planet.texture = this.discTexture;
                planet.width = 8;
                planet.height = 8;
            }
        }

        buildLists();
    }

    private void buildLists() {
        float half = FALLBACK_CELL * 0.5F * STAR_PIXEL;

        this.starList = glGenLists(1);
        glNewList(this.starList, GL_COMPILE);
        glBegin(GL_QUADS);

        // the same number of random draws per star whatever it turns into, so tweaking one rule doesn't reshuffle the whole sky
        Random random = new Random(1337L);
        for (int i = 0; i < RANDOM_STARS; i++) {
            double z = random.nextDouble() * 2.0 - 1.0;
            double phi = random.nextDouble() * TWO_PI;
            double ring = Math.sqrt(1.0 - z * z);
            float[] dir = toWorld(ring * Math.cos(phi), ring * Math.sin(phi), z);

            // most stars are dim, a few are bright
            float magnitude = (float) Math.pow(random.nextDouble(), 3.5);
            // the dim ones are dots, the brighter ones four-point stars
            int variant = magnitude > FLARE_MAGNITUDE ? VARIANT_FLARE : VARIANT_DOT;
            float brightness = 0.45F + 0.55F * magnitude;

            float tint = random.nextFloat();

            float[] c = tint < 0.4F ? TINTS[1] : tint < 0.8F ? TINTS[0] : new float[]{1.00F, 0.85F, 0.65F};
            glColor3f(c[0] * brightness, c[1] * brightness, c[2] * brightness);
            emitStar(dir, half, variant);
        }

        // the constellation stars are always bright four-point stars, and the famous ones have their own colour
        for (Constellation constellation : CONSTELLATIONS) {
            for (float[] star : constellation.stars) {
                float[] tint = TINTS[star.length > 3 ? (int) star[3] : 0];
                glColor3f(tint[0], tint[1], tint[2]);
                emitStar(celestial(star[0], star[1]), half, VARIANT_FLARE);
            }
        }

        glEnd();
        glEndList();

        this.lineList = glGenLists(1);
        glNewList(this.lineList, GL_COMPILE);
        glColor3f(LINE_COLOR[0], LINE_COLOR[1], LINE_COLOR[2]);
        glBegin(GL_QUADS);
        for (Constellation constellation : CONSTELLATIONS) {
            for (int e = 0; e + 1 < constellation.edges.length; e += 2) {
                float[] sa = constellation.stars[constellation.edges[e]];
                float[] sb = constellation.stars[constellation.edges[e + 1]];
                emitLine(celestial(sa[0], sa[1]), celestial(sb[0], sb[1]));
            }
        }
        glEnd();
        glEndList();
    }

    private static void emitStar(float[] d, float half, int variant) {
        float[][] basis = tangent(d);
        float[] r = basis[0];
        float[] u = basis[1];
        float cx = d[0] * STAR_DISTANCE;
        float cy = d[1] * STAR_DISTANCE;
        float cz = d[2] * STAR_DISTANCE;
        float u0 = variant / (float) STAR_VARIANTS;
        float u1 = (variant + 1) / (float) STAR_VARIANTS;

        glTexCoord2f(u0, 0.0F);
        glVertex3f(cx - r[0] * half + u[0] * half, cy - r[1] * half + u[1] * half, cz - r[2] * half + u[2] * half);
        glTexCoord2f(u1, 0.0F);
        glVertex3f(cx + r[0] * half + u[0] * half, cy + r[1] * half + u[1] * half, cz + r[2] * half + u[2] * half);
        glTexCoord2f(u1, 1.0F);
        glVertex3f(cx + r[0] * half - u[0] * half, cy + r[1] * half - u[1] * half, cz + r[2] * half - u[2] * half);
        glTexCoord2f(u0, 1.0F);
        glVertex3f(cx - r[0] * half - u[0] * half, cy - r[1] * half - u[1] * half, cz - r[2] * half - u[2] * half);
    }

    // a dotted run of small squares along the great circle from a to b
    private static void emitLine(float[] a, float[] b) {
        double dot = Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        double omega = Math.acos(dot);
        if (omega < 1.0E-4) {
            return;
        }
        double sinOmega = Math.sin(omega);
        double length = omega * STAR_DISTANCE;
        double step = LINE_PIXEL * LINE_STEP;
        float half = LINE_PIXEL * 0.5F;

        // starts one step in so the dots don't sit on top of the star sprites
        for (double s = step; s < length - step * 0.5; s += step) {
            double t = s / length;
            double wa = Math.sin((1.0 - t) * omega) / sinOmega;
            double wb = Math.sin(t * omega) / sinOmega;
            float[] d = {
                    (float) (a[0] * wa + b[0] * wb),
                    (float) (a[1] * wa + b[1] * wb),
                    (float) (a[2] * wa + b[2] * wb)
            };

            float[][] basis = tangent(d);
            float[] r = basis[0];
            float[] u = basis[1];
            float cx = d[0] * STAR_DISTANCE;
            float cy = d[1] * STAR_DISTANCE;
            float cz = d[2] * STAR_DISTANCE;

            glVertex3f(cx - r[0] * half + u[0] * half, cy - r[1] * half + u[1] * half, cz - r[2] * half + u[2] * half);
            glVertex3f(cx + r[0] * half + u[0] * half, cy + r[1] * half + u[1] * half, cz + r[2] * half + u[2] * half);
            glVertex3f(cx + r[0] * half - u[0] * half, cy + r[1] * half - u[1] * half, cz + r[2] * half - u[2] * half);
            glVertex3f(cx - r[0] * half - u[0] * half, cy - r[1] * half - u[1] * half, cz - r[2] * half - u[2] * half);
        }
    }

    // ---------------------------------------------------------------- generated fallback sprites

    // the two star sprites side by side: a single-texel dot, and a four-point star (long arms that fade out, faint diagonals)
    private static int createFallbackStars() {
        int cell = FALLBACK_CELL;
        int width = cell * STAR_VARIANTS;
        int[][] lum = new int[cell][width];

        drawCross(lum, VARIANT_DOT, 0, false);
        drawCross(lum, VARIANT_FLARE, 5, true);

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * cell * 4);
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < width; x++) {
                byte l = (byte) lum[y][x];
                buffer.put(l).put(l).put(l).put((byte) 255);
            }
        }
        buffer.flip();
        return upload(buffer, width, cell);
    }

    // a bright centre with four arms that fade out, optionally with a faint diagonal at each corner
    private static void drawCross(int[][] lum, int variant, int arm, boolean diagonals) {
        int c = FALLBACK_CELL / 2;
        int x0 = variant * FALLBACK_CELL;
        lum[c][x0 + c] = 255;
        for (int k = 1; k <= arm; k++) {
            int value = 255 * (arm + 1 - k) / (arm + 1);
            lum[c][x0 + c - k] = value;
            lum[c][x0 + c + k] = value;
            lum[c - k][x0 + c] = value;
            lum[c + k][x0 + c] = value;
        }
        if (diagonals) {
            lum[c - 1][x0 + c - 1] = 90;
            lum[c - 1][x0 + c + 1] = 90;
            lum[c + 1][x0 + c - 1] = 90;
            lum[c + 1][x0 + c + 1] = 90;
        }
    }

    // a small shaded disc, tinted per planet when drawn
    private static int createFallbackDisc() {
        int size = 8;
        ByteBuffer buffer = BufferUtils.createByteBuffer(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - 3.5;
                double dy = y - 3.5;
                int l = 0;
                if (Math.sqrt(dx * dx + dy * dy) <= 3.6) {
                    double shade = 0.75 - (dx + dy) * 0.04;
                    l = (int) Math.max(0, Math.min(255, 255 * shade));
                }
                byte value = (byte) l;
                buffer.put(value).put(value).put(value).put((byte) 255);
            }
        }
        buffer.flip();
        return upload(buffer, size, size);
    }

    private static int upload(ByteBuffer buffer, int width, int height) {
        int id = glGenTextures();
        Textures.bind(id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        return id;
    }
}