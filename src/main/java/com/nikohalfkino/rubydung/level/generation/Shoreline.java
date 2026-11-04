package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.generation.noise.Noise;
import com.nikohalfkino.rubydung.level.generation.noise.OctaveNoise;
import com.nikohalfkino.rubydung.level.generation.noise.ImprovedNoise;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The water side of the natural world: one main island made from a rounded,
 * irregular body, sometimes with smaller islands nearby, in an ocean.
 *
 * Islands have irregular, lobed coastlines and sit on a wide, shallow sand
 * shelf that drops away into deeper water further out. Lakes, wide sand
 * beaches, terraced highlands, and small rock columns (sea stacks) add detail
 * to the terrain.
 *
 * The sand / gravel noise is the Indev idea, the rest is built around the
 * stepped terrain of NaturalLevelGenerator. Much of the map is ocean, so
 * the world sizes are bigger than the land they hold.
 *
 * It works in these steps, all driven from NaturalLevelGenerator:
 *  1. carve (then stampSeaStacks): pushes the height map down into the ocean
 *     and lake basins. Land that isn't near either is left alone.
 *  2. buildSurface: picks the top block of every column (grass, sand or gravel)
 *     from the final heights.
 *  3. waterTops: reports which columns hold water.
 *
 * It has its own Random derived from the world seed, so it doesn't take numbers
 * from the generator's Random.
 *
 * carve and buildSurface work column by column and only read the noise,
 * so they run on all cores.
 */
final class Shoreline {

    /** The highest cell that holds water. */
    static final int SEA_LEVEL = Level.GROUND_HEIGHT - 4;

    /** How many blocks deep the sand / gravel goes. */
    static final int SURFACE_DEPTH = 3;

    // ---- the landmasses ----

    private static final float OCEAN_START = -3.0f;
    private static final float OCEAN_WIDTH = 13.0f;
    private static final float DOMAIN_WARP = 0.018f;
    private static final float COAST_AMPLITUDE = 5.0f;
    private static final float COAST_FINE = 1.2f;
    private static final float SMOOTH_UNION = 7.0f;

    private static final float SEA_WALL_START = 0.80f;
    private static final float SEA_WALL_WIDTH = 0.15f;
    private static final float SEA_WALL_PUSH = 40.0f;

    // The seabed: next to the coast the floor drops quickly to a shallow shelf, the shelf then slopes gently
    // for SHELF_WIDTH blocks (sand flats you can walk or swim over), and only after that does it fall away to the deep floor.
    private static final int OCEAN_FLOOR_BELOW_SEA = 2;
    private static final float SHELF_BASE_DEPTH = 1.0f;     // extra depth where the steep part ends, below OCEAN_FLOOR_BELOW_SEA
    private static final float SHELF_WIDTH = 34.0f;
    private static final float SHELF_SLOPE_DEPTH = 3.0f;    // how much deeper the shelf gets across its width
    private static final float DROP_WIDTH = 16.0f;
    private static final float DROP_DEPTH = 4.0f;           // the deep floor is this much below the end of the shelf
    private static final float SHELF_BUMP_SHARE = 0.25f;    // how much of the seabed noise the shelf gets (the deep floor gets all of it)
    private static final float SEABED_BUMP = 4.0f;

    private static final float EXTRA_ISLAND_CHANCE = 0.40f;
    private static final float THIRD_ISLAND_CHANCE = 0.12f;

    // Island body sizes, as a share of the map's smaller dimension.
    private static final float HUB_SINGLE_MIN = 0.20f;
    private static final float HUB_SINGLE_MAX = 0.27f;
    private static final float HUB_SATELLITE_SCALE = 0.55f;

    // Several islands: open water kept between neighbours (as a share of the map's smaller dimension, on top of
    // the room the coast noise, the warp and the beaches need), and how far the islands may be shrunk to fit
    // inside the map before a satellite is dropped instead.
    private static final float ISLAND_CHANNEL = 0.06f;
    private static final float MIN_ISLAND_SCALE = 0.40f;
    private static final float COAST_ROOM = 5.0f;

    // With several islands the open sea at the map's edge starts a little further out, to leave them more room.
    private static final float MULTI_SEA_WALL_START = 0.86f;
    private static final float MULTI_SEA_WALL_WIDTH = 0.12f;
    private static final float MAIN_CENTRE_DEPTH = 14.0f;

    // ---- lakes ----

    private static final float LAKE_SCALE = 0.9f;
    private static final float LAKE_THRESHOLD = 19.0f;
    private static final float LAKE_WIDTH = 9.0f;
    private static final int LAKE_FLOOR_BELOW_SEA = 2;
    private static final int LAKE_EXTRA_DEPTH = 3;
    private static final float LAKE_CENTER_CLEAR = 0.12f;
    private static final float LAKE_CENTER_FADE = 0.10f;

    // ---- wide beaches ----

    private static final float BEACH_NOISE_START = 0.0f;
    private static final float BEACH_FLAT = 7.0f;
    private static final float BEACH_RIPPLE = 0.7f;

    // Beaches that run further inland: on some stretches of beach coast the sand flat reaches this much
    // further back (and slopes up to the land gently) instead of ending a few blocks from the water.
    private static final float DEEP_BEACH_THRESHOLD = 0.20f;
    private static final float DEEP_BEACH_REACH = 18.0f;

    // ---- bays (see Bay) ----

    private static final float BAY_SMOOTH = 8.0f;           // how rounded the bay's join with the coast is
    private static final float BAY_WOBBLE = 3.5f;           // noise that bends the bay's outline, in blocks
    private static final float BAY_BEACH_RANGE = 18.0f;     // the shore this close to the bay's rim is sandy
    private static final float BAY_BEACH_SHARE = 0.90f;
    private static final float BAY_DEEP_SHARE = 0.55f;      // bay beaches are wide, but not always as wide as the widest
    private static final float BAY_CENTRE_CLEARANCE = MAIN_CENTRE_DEPTH + COAST_AMPLITUDE + 8.0f;

    // ---- eroded highlands ----

    private static final int HIGHLAND_LIFT = 12;
    private static final int HIGHLAND_STEP = 4;
    private static final float HIGHLAND_THRESHOLD = 0.05f;
    private static final float INLAND_RANGE = 20.0f;
    private static final float RAMP_GENTLE = 0.35f;
    private static final float RAMP_ERODED = 0.06f;

    // ---- sea stacks ----

    private static final int STACK_TRAIL_AREA = 140000;
    private static final float STACK_RADIUS_MIN = 4.0f;
    private static final float STACK_RADIUS_MAX = 7.5f;
    private static final float STACK_LAST_RADIUS = 2.4f;
    private static final int STACK_PEAK_MIN = 8;
    private static final int STACK_PEAK_MAX = 16;
    private static final int STACK_TRAIL_MIN = 2;
    private static final int STACK_TRAIL_MAX = 5;
    private static final int STACK_GAP_MIN = 3;
    private static final int STACK_GAP_MAX = 12;
    private static final float STACK_RIDGE_CHANCE = 0.75f;
    private static final float STACK_ARCH_CHANCE = 0.70f;
    private static final int STACK_ARCH_MIN_GAP = 9;
    private static final int STACK_STEP = 2;
    private static final int STACK_TRAIL_SPACING = 90;

    // ---- surface blocks ----

    private static final int BEACH_HEIGHT = 1;
    private static final float SAND_THRESHOLD = -8.0f;
    private static final float GRAVEL_THRESHOLD = 12.0f;

    private final long seed;
    private final Fbm warpA;
    private final Fbm warpB;
    private final Fbm coastNoise;
    private final Fbm coastFine;
    private final Noise lakeNoise;
    private final Noise sandNoise;
    private final Noise gravelNoise;
    private final Noise seabedNoise;
    private final Fbm rippleNoise;
    private final Fbm beachNoise;
    private final Fbm highlandNoise;
    private final Fbm erosionNoise;
    private final Fbm bayNoise;
    private final Fbm beachDepthNoise;

    private Bay bay;
    private Capsule[] land = new Capsule[0];
    private float seaWallStart = SEA_WALL_START;
    private float seaWallWidth = SEA_WALL_WIDTH;
    private final List<Bore> bores = new ArrayList<>();

    Shoreline(long seed) {
        this.seed = seed;

        Random random = new Random(seed ^ 0x5EA51DEC0A57L);

        // Fbm = detail gets finer and weaker with every octave.
        // OctaveNoise goes the other way: later octaves are bigger and stronger.
        this.warpA = new Fbm(random, 4, 0.010f, 0.45f);
        this.warpB = new Fbm(random, 4, 0.010f, 0.45f);
        this.coastNoise = new Fbm(random, 4, 0.022f, 0.55f);
        this.coastFine = new Fbm(random, 2, 0.120f, 0.50f);
        this.lakeNoise = new OctaveNoise(random, 6);
        this.sandNoise = new OctaveNoise(random, 6);
        this.gravelNoise = new OctaveNoise(random, 6);
        this.seabedNoise = new OctaveNoise(random, 4);
        this.rippleNoise = new Fbm(random, 3, 0.09f, 0.5f);
        this.beachNoise = new Fbm(random, 3, 0.010f, 0.5f);
        this.highlandNoise = new Fbm(random, 4, 0.012f, 0.5f);
        this.erosionNoise = new Fbm(random, 3, 0.020f, 0.5f);

        // Added last, so the noise above stays the same for every seed.
        this.bayNoise = new Fbm(random, 3, 0.045f, 0.5f);
        this.beachDepthNoise = new Fbm(random, 3, 0.008f, 0.5f);
    }

    /** Layered noise, -1..1: every octave has twice the frequency and persistence times the strength of the one before. */
    private static final class Fbm {
        private final ImprovedNoise[] layers;
        private final float baseFrequency;
        private final float persistence;

        Fbm(Random random, int octaves, float baseFrequency, float persistence) {
            this.layers = new ImprovedNoise[octaves];

            for (int i = 0; i < octaves; i++) {
                this.layers[i] = new ImprovedNoise(random);
            }

            this.baseFrequency = baseFrequency;
            this.persistence = persistence;
        }

        float get(double x, double z) {
            double total = 0.0;
            double strength = 1.0;
            double sum = 0.0;
            double frequency = this.baseFrequency;

            for (ImprovedNoise layer : this.layers) {
                total += layer.get(x * frequency, z * frequency) * strength;
                sum += strength;
                strength *= this.persistence;
                frequency *= 2.0;
            }

            return Math.max(-1.0f, Math.min(1.0f, (float) (total / sum * 1.6)));
        }
    }

    /** A tunnel through a sea stack ridge. */
    static final class Bore {
        final float cx, cz, cy;
        final float dirX, dirZ;
        final float halfLength, radiusAcross, radiusY;

        Bore(float cx, float cz, float cy, float dirX, float dirZ,
             float halfLength, float radiusAcross, float radiusY) {
            this.cx = cx;
            this.cz = cz;
            this.cy = cy;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.halfLength = halfLength;
            this.radiusAcross = radiusAcross;
            this.radiusY = radiusY;
        }

        int reach() {
            return (int) Math.ceil(this.halfLength + this.radiusAcross) + 1;
        }

        boolean contains(int x, int y, int z) {
            if (y < SEA_LEVEL - 2) {
                return false;
            }

            float rx = x + 0.5f - this.cx;
            float rz = z + 0.5f - this.cz;

            float along = rx * this.dirX + rz * this.dirZ;
            if (Math.abs(along) > this.halfLength) {
                return false;
            }

            float across = (-rx * this.dirZ + rz * this.dirX) / this.radiusAcross;
            float up = (y + 0.5f - this.cy) / this.radiusY;

            return up * up + across * across <= 1.0f;
        }
    }

    /** Every tunnel, for the generator to carve out of the blocks. */
    List<Bore> bores() {
        return this.bores;
    }

    /** True for the columns a tunnel can touch. */
    boolean[][] boreColumns(int width, int height) {
        boolean[][] mask = new boolean[width][height];

        for (Bore bore : this.bores) {
            int reach = bore.reach();

            for (int x = Math.max(0, (int) bore.cx - reach);
                 x <= Math.min(width - 1, (int) bore.cx + reach); x++) {
                for (int z = Math.max(0, (int) bore.cz - reach);
                     z <= Math.min(height - 1, (int) bore.cz + reach); z++) {
                    mask[x][z] = true;
                }
            }
        }

        return mask;
    }

    /** A stretch of land or water with a round cross section. */
    private static final class Capsule {
        final float ax, az, bx, bz, ra, rb;

        Capsule(float ax, float az, float bx, float bz, float ra, float rb) {
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.ra = ra;
            this.rb = rb;
        }

        /** Distance from the point to the rim in blocks: negative inside, 0 on the rim. */
        float signedDistance(float px, float pz) {
            float dx = this.bx - this.ax;
            float dz = this.bz - this.az;
            float lengthSq = dx * dx + dz * dz;

            float t = lengthSq < 1.0E-6f
                    ? 0.0f
                    : clamp01(((px - this.ax) * dx + (pz - this.az) * dz) / lengthSq);

            float qx = this.ax + dx * t - px;
            float qz = this.az + dz * t - pz;

            return (float) Math.sqrt(qx * qx + qz * qz)
                    - (this.ra + (this.rb - this.ra) * t);
        }
    }

    /** The land: nearby pieces are blended together to make rounded joins between lobes. */
    private static float landDistance(Capsule[] shapes, float px, float pz) {
        float best = Float.MAX_VALUE;

        for (Capsule shape : shapes) {
            float d = shape.signedDistance(px, pz);
            float diff = Math.abs(best - d);

            if (diff >= SMOOTH_UNION) {
                best = Math.min(best, d);
            } else {
                float h = SMOOTH_UNION - diff;
                best = Math.min(best, d) - h * h / (4.0f * SMOOTH_UNION);
            }
        }

        return best;
    }

    private static float range(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /** One island, relative to its own centre: a round body and the lobes around it, at full size. */
    private static final class IslandPlan {
        final float[][] pieces;   // {dx, dz, radius}
        final float reach;        // from the centre to the farthest rim

        IslandPlan(float[][] pieces) {
            this.pieces = pieces;

            float farthest = 0.0f;
            for (float[] piece : pieces) {
                farthest = Math.max(farthest,
                        (float) Math.sqrt(piece[0] * piece[0] + piece[1] * piece[1]) + piece[2]);
            }
            this.reach = farthest;
        }
    }

    private static IslandPlan planIsland(Random random, float hubRadius, int lobes) {
        float[][] pieces = new float[lobes + 1][];

        // Central body of the island.
        pieces[0] = new float[]{0.0f, 0.0f, hubRadius};

        // Surrounding lobes make the coastline irregular without creating
        // long, narrow peninsulas.
        float lobeBase = random.nextFloat() * 2.0f * (float) Math.PI;

        for (int l = 0; l < lobes; l++) {
            float angle = lobeBase
                    + l * (2.0f * (float) Math.PI / lobes)
                    + (random.nextFloat() - 0.5f) * 0.8f;

            float lobeRadius = hubRadius * range(random, 0.48f, 0.68f);
            float offset = hubRadius * range(random, 0.58f, 0.78f);

            pieces[l + 1] = new float[]{
                    (float) Math.cos(angle) * offset,
                    (float) Math.sin(angle) * offset,
                    lobeRadius
            };
        }

        return new IslandPlan(pieces);
    }

    private static void addIsland(List<Capsule> shapes, IslandPlan plan, float cx, float cz, float scale) {
        for (float[] piece : plan.pieces) {
            float x = cx + piece[0] * scale;
            float z = cz + piece[1] * scale;
            float r = piece[2] * scale;

            shapes.add(new Capsule(x, z, x, z, r, r));
        }
    }

    /**
     * Decides where the islands go.
     * All randomness comes from the world seed.
     *
     * A lone island sits near the middle. With satellites, every island is given the room its coast can
     * reach (so no two ever touch, however the noise bends the coastlines), the islands are laid out
     * around the main one, and then the whole group is shifted so it is centred on the map. If the group
     * is too big for the map the islands shrink, and if that would make them too small a satellite is dropped.
     */
    private void planLand(int width, int height) {
        // Mix the seed first. This avoids similar layouts for neighbouring seeds.
        Random random = new Random((this.seed * 0x9E3779B97F4A7C15L) ^ 0x15A4D5C0DEL);
        random.nextLong();

        float size = Math.min(width, height);

        float roll = random.nextFloat();
        int islands = roll < THIRD_ISLAND_CHANCE
                ? 3
                : roll < EXTRA_ISLAND_CHANCE ? 2 : 1;

        float mainHub = size * range(random, HUB_SINGLE_MIN, HUB_SINGLE_MAX);
        IslandPlan[] plans = new IslandPlan[islands];
        plans[0] = planIsland(random, mainHub, 7 + random.nextInt(4));

        for (int i = 1; i < islands; i++) {
            float hub = size * range(random, HUB_SINGLE_MIN, HUB_SINGLE_MAX)
                    * HUB_SATELLITE_SCALE * range(random, 0.85f, 1.15f);

            plans[i] = planIsland(random, hub, 5 + random.nextInt(3));
        }

        // Where each satellite lies around the main island, and how far out (as extra room on top of touching distance).
        float[] angles = new float[islands];
        float[] spreads = new float[islands];
        float firstAngle = random.nextFloat() * 2.0f * (float) Math.PI;

        for (int i = 1; i < islands; i++) {
            angles[i] = firstAngle
                    + (i - 1) * (2.0f * (float) Math.PI / (islands - 1))
                    + (random.nextFloat() - 0.5f) * 0.9f;
            spreads[i] = 1.0f + random.nextFloat() * 0.25f;
        }

        float lonelyX = width * (0.5f + (random.nextFloat() - 0.5f) * 0.08f);
        float lonelyZ = height * (0.5f + (random.nextFloat() - 0.5f) * 0.08f);

        // Room a coast can take beyond the pieces it is built from: noise and the beach shelf. The warp bends
        // everything nearby the same way, so it only matters against the map's edge (warpRoom).
        float margin = COAST_AMPLITUDE + COAST_FINE + COAST_ROOM;
        float warpRoom = DOMAIN_WARP * size;
        float channel = size * ISLAND_CHANNEL;

        List<Capsule> shapes = new ArrayList<>();

        // Where the main island ended up, for the bay.
        float mainX = lonelyX;
        float mainZ = lonelyZ;
        float mainScale = 1.0f;
        int placed = 1;

        for (int count = islands; count > 1; count--) {
            float scale = 1.0f;
            float[][] centres = null;
            float[] reaches = new float[count];
            boolean fits = false;

            for (int attempt = 0; attempt < 16 && scale >= MIN_ISLAND_SCALE; attempt++) {
                for (int i = 0; i < count; i++) {
                    reaches[i] = plans[i].reach * scale + margin;
                }

                centres = layoutIslands(count, reaches, angles, spreads, channel, size);

                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                float minZ = Float.MAX_VALUE;
                float maxZ = -Float.MAX_VALUE;

                for (int i = 0; i < count; i++) {
                    minX = Math.min(minX, centres[i][0] - reaches[i] - warpRoom);
                    maxX = Math.max(maxX, centres[i][0] + reaches[i] + warpRoom);
                    minZ = Math.min(minZ, centres[i][1] - reaches[i] - warpRoom);
                    maxZ = Math.max(maxZ, centres[i][1] + reaches[i] + warpRoom);
                }

                // Centre the box around the whole group on the map, as far as that keeps the middle of the map
                // on dry land well inside the main island: the player starts there and lakes are kept away from it.
                // (t = how much of the full centring is used, 1 = all of it.)
                float limitX = width * 0.5f * MULTI_SEA_WALL_START;
                float limitZ = height * 0.5f * MULTI_SEA_WALL_START;

                for (int step = 10; step >= 0 && !fits; step--) {
                    float t = step / 10.0f;
                    float shiftX = width * 0.5f - (minX + maxX) * 0.5f * t;
                    float shiftZ = height * 0.5f - (minZ + maxZ) * 0.5f * t;

                    boolean inside = minX + shiftX >= width * 0.5f - limitX && maxX + shiftX <= width * 0.5f + limitX
                            && minZ + shiftZ >= height * 0.5f - limitZ && maxZ + shiftZ <= height * 0.5f + limitZ;

                    if (inside && isDeepInside(plans[0], shiftX, shiftZ, scale, width * 0.5f, height * 0.5f)) {
                        for (int i = 0; i < count; i++) {
                            centres[i][0] += shiftX;
                            centres[i][1] += shiftZ;
                        }

                        fits = true;
                    }
                }

                if (fits) {
                    break;
                }

                scale *= 0.92f;
            }

            if (fits) {
                for (int i = 0; i < count; i++) {
                    addIsland(shapes, plans[i], centres[i][0], centres[i][1], scale);
                }

                mainX = centres[0][0];
                mainZ = centres[0][1];
                mainScale = scale;
                placed = count;

                this.seaWallStart = MULTI_SEA_WALL_START;
                this.seaWallWidth = MULTI_SEA_WALL_WIDTH;

                break;
            }
        }

        if (shapes.isEmpty()) {
            addIsland(shapes, plans[0], lonelyX, lonelyZ, 1.0f);
        }

        this.land = shapes.toArray(new Capsule[0]);

        // The bay opens away from any satellites, and keeps clear of the map's middle where the player starts.
        float[] neighbours = new float[placed - 1];
        System.arraycopy(angles, 1, neighbours, 0, placed - 1);

        this.bay = Bay.plan(random, plans[0].pieces, mainX, mainZ, mainScale,
                neighbours, width * 0.5f, height * 0.5f, BAY_CENTRE_CLEARANCE);
    }

    /** True if the point (px, pz) is at least MAIN_CENTRE_DEPTH blocks inland of the main island placed at (cx, cz). */
    private static boolean isDeepInside(IslandPlan plan, float cx, float cz, float scale, float px, float pz) {
        float best = Float.MAX_VALUE;

        for (float[] piece : plan.pieces) {
            float dx = px - (cx + piece[0] * scale);
            float dz = pz - (cz + piece[1] * scale);

            best = Math.min(best, (float) Math.sqrt(dx * dx + dz * dz) - piece[2] * scale);
        }

        return best <= -MAIN_CENTRE_DEPTH;
    }

    /**
     * Island centres around the main one at (0, 0). Every island keeps at least the channel of open water
     * between its reach and that of every island placed before it.
     */
    private static float[][] layoutIslands(int count, float[] reaches, float[] angles, float[] spreads,
                                           float channel, float size) {
        float[][] centres = new float[count][2];

        for (int i = 1; i < count; i++) {
            float dirX = (float) Math.cos(angles[i]);
            float dirZ = (float) Math.sin(angles[i]);
            float distance = (reaches[0] + reaches[i] + channel) * spreads[i];

            while (true) {
                float x = dirX * distance;
                float z = dirZ * distance;

                boolean clear = true;

                for (int j = 0; j < i; j++) {
                    float dx = x - centres[j][0];
                    float dz = z - centres[j][1];
                    float needed = reaches[j] + reaches[i] + channel;

                    if (dx * dx + dz * dz < needed * needed) {
                        clear = false;
                        break;
                    }
                }

                if (clear) {
                    centres[i][0] = x;
                    centres[i][1] = z;
                    break;
                }

                distance += size * 0.02f;
            }
        }

        return centres;
    }

    /** Lowers the height map into the ocean and lakes, and shapes the beaches, the seabed and the highlands. */
    void carve(int[][] heightMap, int width, int height) {
        planLand(width, height);

        float size = Math.min(width, height);
        float warpAmount = DOMAIN_WARP * size;

        IntStream.range(0, width).parallel().forEach(x -> {
            float distX = Math.abs(x / (float) (width - 1) - 0.5f) * 2.0f;

            for (int z = 0; z < height; z++) {
                float distZ = Math.abs(z / (float) (height - 1) - 0.5f) * 2.0f;
                float radial = (float) Math.sqrt(distX * distX + distZ * distZ);
                float square = Math.max(distX, distZ);

                // Bend the position, then measure against the island pieces.
                float px = x + this.warpA.get(x, z) * warpAmount;
                float pz = z + this.warpB.get(x, z) * warpAmount;

                float coast = this.coastNoise.get(x, z) * COAST_AMPLITUDE
                        + this.coastFine.get(x, z) * COAST_FINE;

                float landDistance = landDistance(this.land, px, pz);

                // The bay is water cut out of the land, with a rounded join and a wobbly outline.
                float bayDistance = Float.MAX_VALUE;

                if (this.bay != null) {
                    bayDistance = this.bay.signedDistance(px, pz) + this.bayNoise.get(x, z) * BAY_WOBBLE;
                    landDistance = Bay.smoothMax(landDistance, -bayDistance, BAY_SMOOTH);
                }

                float edge = landDistance + coast
                        + smoothstep(clamp01((square - this.seaWallStart) / this.seaWallWidth))
                        * SEA_WALL_PUSH;

                // How much of a beach this stretch of coast gets.
                float beach = smoothstep(clamp01(
                        (this.beachNoise.get(x, z) - BEACH_NOISE_START) / 0.30f
                ));

                // The shore around a bay is sheltered, so it is a beach, and a wide one.
                float bayBeach = 0.0f;

                if (this.bay != null) {
                    bayBeach = smoothstep(clamp01(1.0f - Math.abs(bayDistance) / BAY_BEACH_RANGE));
                    beach = Math.max(beach, bayBeach * BAY_BEACH_SHARE);
                }

                // On some beach coasts the sand flat reaches much further inland.
                float deepBeach = beach * smoothstep(clamp01(
                        (this.beachDepthNoise.get(x, z) - DEEP_BEACH_THRESHOLD) / 0.25f
                ));
                deepBeach = Math.max(deepBeach, bayBeach * BAY_DEEP_SHARE);
                float beachReach = deepBeach * DEEP_BEACH_REACH;

                // Beach coasts push the waterline outwards.
                float oceanStart = OCEAN_START + beach * BEACH_FLAT;
                float ocean = smoothstep(clamp01((edge - oceanStart) / OCEAN_WIDTH));

                float ground = heightMap[x][z];

                // Highlands: terraced lift inland, back down to the shoreline.
                float inland = clamp01((OCEAN_START - edge) / INLAND_RANGE);

                if (inland > 0.0f) {
                    float highland = smoothstep(clamp01(
                            (this.highlandNoise.get(x, z) - HIGHLAND_THRESHOLD) / 0.25f
                    ));

                    if (highland > 0.0f) {
                        float eroded = smoothstep(clamp01(
                                (this.erosionNoise.get(x, z) + 0.1f) / 0.3f
                        ));

                        float ramp = lerp(RAMP_GENTLE, RAMP_ERODED, eroded);
                        float lift = HIGHLAND_LIFT * highland
                                * smoothstep(clamp01(inland / ramp));

                        ground += Math.round(lift / HIGHLAND_STEP) * HIGHLAND_STEP;
                    }
                }

                // The sand shelf.
                // A wide beach starts further inland and climbs back to the land over a longer, gentler slope.
                float shelfStart = OCEAN_START - 4.0f - beachReach;
                float shelfRamp = 4.0f + beachReach * 0.75f;
                float shelf = beach * smoothstep(clamp01((edge - shelfStart) / shelfRamp));

                if (shelf > 0.0f) {
                    float shelfHeight = SEA_LEVEL + 0.5f
                            + this.rippleNoise.get(x, z) * BEACH_RIPPLE;

                    ground = lerp(ground, shelfHeight, shelf);
                }

                // Lakes only form well inland.
                float lake = smoothstep(clamp01(
                        (float) (this.lakeNoise.get(x * LAKE_SCALE, z * LAKE_SCALE)
                                - LAKE_THRESHOLD) / LAKE_WIDTH
                ))
                        * smoothstep(clamp01((radial - LAKE_CENTER_CLEAR) / LAKE_CENTER_FADE))
                        * smoothstep(clamp01((-landDistance - 16.0f) / 12.0f));

                if (lake > 0.0f) {
                    float lakeFloor = SEA_LEVEL - LAKE_FLOOR_BELOW_SEA
                            - lake * LAKE_EXTRA_DEPTH;

                    ground = lerp(ground, lakeFloor, lake);
                }

                if (ocean > 0.0f) {
                    // Past the steep part by the coast the seabed is a wide, gently sloping shelf, and only
                    // beyond it does it fall away to the deep floor.
                    float beyondCoast = edge - oceanStart - OCEAN_WIDTH;
                    float shelfSlope = clamp01(beyondCoast / SHELF_WIDTH);
                    float deep = smoothstep(clamp01((beyondCoast - SHELF_WIDTH) / DROP_WIDTH));

                    float depthBelowSea = OCEAN_FLOOR_BELOW_SEA
                            + ocean * SHELF_BASE_DEPTH
                            + shelfSlope * SHELF_SLOPE_DEPTH
                            + deep * DROP_DEPTH;

                    // The shelf stays mostly smooth sand flats, the deep floor gets the full noise.
                    float bump = (float) (
                            this.seabedNoise.get(x * 0.7, z * 0.7) / SEABED_BUMP
                    ) * ocean * lerp(SHELF_BUMP_SHARE, 1.0f, deep);

                    float oceanFloor = SEA_LEVEL - depthBelowSea + bump;

                    ground = lerp(ground, oceanFloor, ocean);
                }

                heightMap[x][z] = Math.round(ground);
            }
        });
    }

    /**
     * Sea stacks: trails of chunky rock towers that start on the shore and lead
     * out to sea, shrinking as they go.
     */
    void stampSeaStacks(int[][] heightMap, int width, int height) {
        this.bores.clear();

        Random random = new Random(this.seed ^ 0x57ACC5L);
        List<int[]> used = new ArrayList<>();

        float expected = width * height / (float) STACK_TRAIL_AREA;
        int trails = (int) expected
                + (random.nextFloat() < expected - (int) expected ? 1 : 0);

        for (int attempt = 0;
             attempt < trails * 40 && used.size() < trails;
             attempt++) {

            int sx = 12 + random.nextInt(Math.max(1, width - 24));
            int sz = 12 + random.nextInt(Math.max(1, height - 24));

            int ground = heightMap[sx][sz];

            // A spot of shore: dry and low.
            if (ground < SEA_LEVEL || ground > SEA_LEVEL + 6) {
                continue;
            }

            boolean crowded = false;

            for (int[] other : used) {
                int dx = sx - other[0];
                int dz = sz - other[1];

                if (dx * dx + dz * dz < STACK_TRAIL_SPACING * STACK_TRAIL_SPACING) {
                    crowded = true;
                    break;
                }
            }

            if (crowded) {
                continue;
            }

            // Out to sea: the direction with the longest run of shallow water.
            float bestAngle = 0.0f;
            int bestRun = 0;

            for (int d = 0; d < 16; d++) {
                float angle = d * (float) Math.PI * 2.0f / 16.0f;
                int run = 0;

                for (int step = 3; step <= 48; step += 3) {
                    int x = Math.round(sx + (float) Math.cos(angle) * step);
                    int z = Math.round(sz + (float) Math.sin(angle) * step);

                    if (x < 6 || z < 6 || x >= width - 6 || z >= height - 6) {
                        break;
                    }

                    int g = heightMap[x][z];

                    if (g >= SEA_LEVEL || g < SEA_LEVEL - 11) {
                        break;
                    }

                    run = step;
                }

                if (run > bestRun) {
                    bestRun = run;
                    bestAngle = angle;
                }
            }

            if (bestRun < 24) {
                continue;
            }

            used.add(new int[]{sx, sz});
            growTrail(heightMap, width, height, random, sx, sz, bestAngle);
        }
    }

    private void growTrail(int[][] heightMap, int width, int height,
                           Random random, int startX, int startZ, float angle) {
        int count = STACK_TRAIL_MIN
                + random.nextInt(STACK_TRAIL_MAX - STACK_TRAIL_MIN + 1);

        float radius = STACK_RADIUS_MIN
                + random.nextFloat() * (STACK_RADIUS_MAX - STACK_RADIUS_MIN);

        float peak = STACK_PEAK_MIN
                + random.nextInt(STACK_PEAK_MAX - STACK_PEAK_MIN + 1);

        // The first tower sits half on the shore.
        float x = startX + (float) Math.cos(angle) * radius * 0.5f;
        float z = startZ + (float) Math.sin(angle) * radius * 0.5f;

        float prevX = 0.0f;
        float prevZ = 0.0f;
        float prevRadius = 0.0f;

        for (int i = 0; i < count; i++) {
            float gap = 0.0f;

            if (i > 0) {
                angle += (random.nextFloat() - 0.5f) * 0.7f;

                radius = Math.max(
                        STACK_LAST_RADIUS,
                        radius * (0.72f + random.nextFloat() * 0.20f)
                );

                peak = Math.max(
                        STACK_PEAK_MIN - 3,
                        peak * (0.82f + random.nextFloat() * 0.14f)
                );

                gap = STACK_GAP_MIN
                        + random.nextFloat() * (STACK_GAP_MAX - STACK_GAP_MIN);

                float step = prevRadius + gap + radius;

                x += (float) Math.cos(angle) * step;
                z += (float) Math.sin(angle) * step;
            }

            int cx = Math.round(x);
            int cz = Math.round(z);

            if (cx < 8 || cz < 8 || cx >= width - 8 || cz >= height - 8
                    || heightMap[cx][cz] < SEA_LEVEL - 12) {
                return;
            }

            stampStack(heightMap, width, height, random, cx, cz, radius, Math.round(peak));

            if (i > 0 && random.nextFloat() < STACK_RIDGE_CHANCE) {
                float dirX = x - prevX;
                float dirZ = z - prevZ;

                float length = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);

                dirX /= length;
                dirZ /= length;

                float halfWidth = 2.0f + random.nextFloat() * 0.8f;
                boolean arch = gap >= STACK_ARCH_MIN_GAP
                        && random.nextFloat() < STACK_ARCH_CHANCE;

                float radiusY = 3.0f + random.nextFloat() * 1.5f;
                float radiusAcross = Math.max(2.0f, Math.min(3.5f, gap / 2.0f - 1.5f));

                int ridgeTop = SEA_LEVEL
                        + (arch ? (int) radiusY + 6 : 5 + random.nextInt(3));

                stampRidge(heightMap, width, height,
                        prevX, prevZ, x, z, halfWidth, ridgeTop);

                if (arch) {
                    float middle = prevRadius + gap / 2.0f;

                    this.bores.add(new Bore(
                            prevX + dirX * middle,
                            prevZ + dirZ * middle,
                            SEA_LEVEL + 1.0f,
                            -dirZ,
                            dirX,
                            halfWidth + 2.5f,
                            radiusAcross,
                            radiusY
                    ));
                }
            }

            prevX = x;
            prevZ = z;
            prevRadius = radius;
        }
    }

    /** A flat-topped tower with an uneven outline and terraced sides. */
    private static void stampStack(int[][] heightMap, int width, int height,
                                   Random random, int cx, int cz, float radius, int peak) {
        float phase1 = random.nextFloat() * 6.28f;
        float phase2 = random.nextFloat() * 6.28f;
        float phase3 = random.nextFloat() * 6.28f;

        int reach = (int) Math.ceil(radius * 1.4f) + 1;

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                if (x < 0 || z < 0 || x >= width || z >= height) {
                    continue;
                }

                float angle = (float) Math.atan2(dz, dx);

                float outline = radius * (
                        1.0f
                                + 0.18f * (float) Math.sin(2.0f * angle + phase1)
                                + 0.12f * (float) Math.sin(3.0f * angle + phase2)
                                + 0.08f * (float) Math.sin(5.0f * angle + phase3)
                );

                float distance = (float) Math.sqrt(dx * dx + dz * dz);

                if (distance >= outline) {
                    continue;
                }

                float inside = 1.0f - distance / outline;
                float shape = smoothstep(clamp01(inside / 0.45f));

                float local = peak * (
                        0.92f
                                + 0.08f * (float) Math.sin(x * 0.6f + phase1)
                                * (float) Math.cos(z * 0.5f + phase2)
                );

                int above = Math.round(local * shape / STACK_STEP) * STACK_STEP;

                if (above <= 0) {
                    continue;
                }

                int top = SEA_LEVEL + above;

                if (top > heightMap[x][z]) {
                    heightMap[x][z] = top;
                }
            }
        }
    }

    /** A rounded ridge between two points. */
    private static void stampRidge(int[][] heightMap, int width, int height,
                                   float ax, float az, float bx, float bz,
                                   float halfWidth, int top) {
        float dx = bx - ax;
        float dz = bz - az;
        float lengthSq = dx * dx + dz * dz;

        int minX = (int) Math.floor(Math.min(ax, bx) - halfWidth - 1);
        int maxX = (int) Math.ceil(Math.max(ax, bx) + halfWidth + 1);
        int minZ = (int) Math.floor(Math.min(az, bz) - halfWidth - 1);
        int maxZ = (int) Math.ceil(Math.max(az, bz) + halfWidth + 1);

        for (int x = Math.max(0, minX); x <= Math.min(width - 1, maxX); x++) {
            for (int z = Math.max(0, minZ); z <= Math.min(height - 1, maxZ); z++) {
                float t = clamp01(((x - ax) * dx + (z - az) * dz) / lengthSq);

                float qx = ax + dx * t - x;
                float qz = az + dz * t - z;

                float across = (float) Math.sqrt(qx * qx + qz * qz);

                if (across >= halfWidth) {
                    continue;
                }

                float shape = smoothstep(clamp01((1.0f - across / halfWidth) / 0.5f));
                int above = Math.round((top - SEA_LEVEL) * shape);

                if (above <= 0) {
                    continue;
                }

                if (SEA_LEVEL + above > heightMap[x][z]) {
                    heightMap[x][z] = SEA_LEVEL + above;
                }
            }
        }
    }

    /** The top block of every column: sand or gravel by the water, grass elsewhere. */
    byte[][] buildSurface(int[][] heightMap, boolean[][] mountainMask, int width, int height) {
        byte grass = (byte) Tile.GRASS.id;
        byte sand = (byte) Tile.SAND.id;
        byte gravel = (byte) Tile.GRAVEL.id;

        byte[][] surface = new byte[width][height];

        IntStream.range(0, width).parallel().forEach(x -> {
            for (int z = 0; z < height; z++) {
                int ground = heightMap[x][z];

                if (mountainMask[x][z] || ground > SEA_LEVEL + BEACH_HEIGHT) {
                    surface[x][z] = grass;
                } else if (ground >= SEA_LEVEL) {
                    surface[x][z] = this.sandNoise.get(x, z) > SAND_THRESHOLD
                            ? sand
                            : grass;
                } else {
                    surface[x][z] = this.gravelNoise.get(x, z) > GRAVEL_THRESHOLD
                            ? gravel
                            : sand;
                }
            }
        });

        return surface;
    }

    /** Per column (index z * width + x): the y of the water surface, or -1 for a dry column. */
    int[] waterTops(int[][] heightMap, int width, int height) {
        int[] tops = new int[width * height];

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                tops[z * width + x] = heightMap[x][z] < SEA_LEVEL
                        ? SEA_LEVEL
                        : -1;
            }
        }

        // The sea runs through the arches.
        for (Bore bore : this.bores) {
            int reach = bore.reach();

            for (int x = Math.max(0, (int) bore.cx - reach);
                 x <= Math.min(width - 1, (int) bore.cx + reach); x++) {
                for (int z = Math.max(0, (int) bore.cz - reach);
                     z <= Math.min(height - 1, (int) bore.cz + reach); z++) {
                    if (bore.contains(x, SEA_LEVEL, z)) {
                        tops[z * width + x] = SEA_LEVEL;
                    }
                }
            }
        }

        return tops;
    }

    private static float clamp01(float t) {
        return Math.max(0.0f, Math.min(1.0f, t));
    }

    private static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}