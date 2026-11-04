package com.nikohalfkino.rubydung.level.generation;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.generation.noise.Fbm;
import com.nikohalfkino.rubydung.level.generation.noise.Noise;
import com.nikohalfkino.rubydung.level.generation.noise.OctaveNoise;
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

    // ---- coast shape ----
    // Big, slow noise bends the whole coastline (the same idea as Archipelago), so an island has peninsulas,
    // coves and small islets instead of being a round blob. It is faded out around the map's middle, where
    // the player starts.

    private static final float SHAPE_AMPLITUDE = 0.07f;    // how far the coast moves, as a share of the map's smaller side
    private static final float SHAPE_WAVELENGTH = 0.16f;   // size of the bumps, as a share of the map's smaller side
    private static final float SHAPE_ROOM = 0.6f;          // share of the amplitude kept free between islands and the edge
    private static final float SHAPE_MIDDLE_CLEAR = 0.08f; // no shaping this close to the middle (share of the smaller side)
    private static final float SHAPE_MIDDLE_FADE = 0.12f;  // and it fades in over this much more

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
    private Fbm shapeNoise;
    private float shapeAmplitude;
    private Capsule[] land = new Capsule[0];
    private float seaWallStart = SEA_WALL_START;
    private float seaWallWidth = SEA_WALL_WIDTH;
    private final SeaStacks seaStacks;

    Shoreline(long seed) {
        this.seed = seed;
        this.seaStacks = new SeaStacks(seed);

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

    /** Sea stacks (and the arches between them) are made by SeaStacks. */
    void stampSeaStacks(int[][] heightMap, int width, int height) {
        this.seaStacks.stamp(heightMap, width, height);
    }

    /** Every tunnel, for the generator to carve out of the blocks. */
    List<SeaStacks.Bore> bores() {
        return this.seaStacks.bores();
    }

    /** True for the columns a tunnel can touch. */
    boolean[][] boreColumns(int width, int height) {
        return this.seaStacks.boreColumns(width, height);
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

        this.shapeAmplitude = SHAPE_AMPLITUDE * size;
        this.shapeNoise = new Fbm(new Random(this.seed ^ 0x5A9E0F1DL), 3, 1.0f / (size * SHAPE_WAVELENGTH), 0.5f);

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
        float margin = COAST_AMPLITUDE + COAST_FINE + COAST_ROOM + this.shapeAmplitude * SHAPE_ROOM;
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
                // The open sea at the map's edge. Measured with a rounded metric so the corners are rounded too,
                // a plain max(distX, distZ) cuts every island near a corner off in straight lines.
                float wallX = Math.max(0.0f, distX - this.seaWallStart);
                float wallZ = Math.max(0.0f, distZ - this.seaWallStart);
                float wall = (float) Math.sqrt(wallX * wallX + wallZ * wallZ);

                // Bend the position, then measure against the island pieces.
                float px = x + this.warpA.get(x, z) * warpAmount;
                float pz = z + this.warpB.get(x, z) * warpAmount;

                float coast = this.coastNoise.get(x, z) * COAST_AMPLITUDE
                        + this.coastFine.get(x, z) * COAST_FINE;

                float landDistance = landDistance(this.land, px, pz);

                // Bend the coast with the big noise (not around the middle of the map).
                float middleX = x - (width - 1) * 0.5f;
                float middleZ = z - (height - 1) * 0.5f;
                float fromMiddle = (float) Math.sqrt(middleX * middleX + middleZ * middleZ);
                float shapeFade = smoothstep(clamp01(
                        (fromMiddle - SHAPE_MIDDLE_CLEAR * size) / (SHAPE_MIDDLE_FADE * size)));

                landDistance -= this.shapeNoise.get(x, z) * this.shapeAmplitude * shapeFade;

                // The bay is water cut out of the land, with a rounded join and a wobbly outline.
                float bayDistance = Float.MAX_VALUE;

                if (this.bay != null) {
                    bayDistance = this.bay.signedDistance(px, pz) + this.bayNoise.get(x, z) * BAY_WOBBLE;
                    landDistance = Bay.smoothMax(landDistance, -bayDistance, BAY_SMOOTH);
                }

                float edge = landDistance + coast
                        + smoothstep(clamp01(wall / this.seaWallWidth))
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
        for (SeaStacks.Bore bore : this.seaStacks.bores()) {
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