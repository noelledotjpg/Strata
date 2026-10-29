package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.phys.AABB;

import java.io.File;
import java.util.ArrayList;
import java.util.Random;

public class Level {

    public static final int GROUND_HEIGHT = 64 * 2 / 3;

    public final int width;
    public final int height;
    public final int depth;

    private final String worldName;

    private long seed;
    private LevelGenerator.WorldType type;

    public long getSeed() {
        return this.seed;
    }

    public LevelGenerator.WorldType getWorldType() {
        return this.type;
    }

    public String getWorldName() {
        return this.worldName;
    }

    private final byte[] blocks;
    private final byte[] data;
    private final int[] lightDepths;

    private final ArrayList<LevelListener> levelListeners = new ArrayList<>();

    public boolean hasSavedPosition = false;
    public double savedPlayerX, savedPlayerY, savedPlayerZ;
    public float savedPlayerYRotation, savedPlayerXRotation;

    public boolean hasFlightState = false;
    public boolean savedFlying, savedNoClip;
    public float savedFlySpeedMultiplier = 1.0F;

    public void setPlayerFlightState(boolean flying, boolean noClip, float flySpeedMultiplier) {
        this.savedFlying = flying;
        this.savedNoClip = noClip;
        this.savedFlySpeedMultiplier = flySpeedMultiplier;
        this.hasFlightState = true;
    }

    public Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type) {
        this(worldName, width, height, depth, type, new Random().nextLong(), true);
    }

    public Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type, long seed) {
        this(worldName, width, height, depth, type, seed, false);
    }

    private Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type, long seed,
                  boolean loadFromDisk) {
        this.worldName = worldName;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.type = type;
        this.seed = seed;

        this.blocks = LevelGenerator.generate(type, width, height, depth, seed);
        this.data = new byte[width * height * depth];
        this.lightDepths = new int[width * height];

        calcLightDepths(0, 0, width, height);

        if (loadFromDisk) {
            load();
        }
    }

    public void load() {
        LevelIO.SavedPosition position = LevelIO.load(WorldManager.levelFile(this.worldName), this.blocks, this.data);
        calcLightDepths(0, 0, this.width, this.height);

        if (position.hasSeed) {
            this.seed = position.seed;
        }
        if (position.hasWorldType) {
            this.type = position.worldType;
        }

        if (position.hasFlightState) {
            this.hasFlightState = true;
            this.savedFlying = position.flying;
            this.savedNoClip = position.noClip;
            this.savedFlySpeedMultiplier = position.flySpeedMultiplier;
        }

        this.hasSavedPosition = position.present;
        if (position.present) {
            this.savedPlayerX = position.x;
            this.savedPlayerY = position.y;
            this.savedPlayerZ = position.z;
            this.savedPlayerYRotation = position.yRotation;
            this.savedPlayerXRotation = position.xRotation;
        }

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.allChanged();
        }
    }

    public void save() {
        LevelIO.SavedPosition position = this.hasSavedPosition
                ? LevelIO.SavedPosition.of(this.savedPlayerX, this.savedPlayerY, this.savedPlayerZ,
                this.savedPlayerYRotation, this.savedPlayerXRotation)
                : LevelIO.SavedPosition.none();

        position = position.withMetadata(this.seed, this.type);

        if (this.hasFlightState) {
            position = position.withFlightState(this.savedFlying, this.savedNoClip, this.savedFlySpeedMultiplier);
        }

        LevelIO.save(WorldManager.levelFile(this.worldName), this.blocks, this.data, position);
    }

    public void setPlayerPosition(double x, double y, double z, float yRotation, float xRotation) {
        this.savedPlayerX = x;
        this.savedPlayerY = y;
        this.savedPlayerZ = z;
        this.savedPlayerYRotation = yRotation;
        this.savedPlayerXRotation = xRotation;
        this.hasSavedPosition = true;
    }

    private void calcLightDepths(int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x < minX + maxX; x++) {
            for (int z = minZ; z < minZ + maxZ; z++) {
                int prevDepth = this.lightDepths[x + z * this.width];

                int depth = this.depth - 1;
                while (depth > 0 && !isLightBlocker(x, depth, z)) {
                    depth--;
                }

                this.lightDepths[x + z * this.width] = depth;

                if (prevDepth != depth) {
                    int minTileChangeY = Math.min(prevDepth, depth);
                    int maxTileChangeY = Math.max(prevDepth, depth);

                    for (LevelListener levelListener : this.levelListeners) {
                        levelListener.lightColumnChanged(x, z, minTileChangeY, maxTileChangeY);
                    }
                }
            }
        }
    }

    public int getTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return 0;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.blocks[index] & 0xFF;
    }

    public int getData(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return 0;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.data[index] & 0xFF;
    }

    public void setData(int x, int y, int z, int val) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        int index = (y * this.height + z) * this.width + x;
        this.data[index] = (byte) val;

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
        }
    }

    public boolean isTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return false;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.blocks[index] != 0;
    }

    public boolean isSolidTile(int x, int y, int z) {
        Tile tile = Tile.byId(getTile(x, y, z));
        return tile != null && tile.isSolid();
    }

    public boolean isLightBlocker(final int x, final int y, final int z) {
        return this.isSolidTile(x, y, z);
    }

    public float getBrightness(int x, int y, int z) {
        float dark = 0.8F;
        float light = 1.0F;

        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return light;
        }

        if (y < this.lightDepths[x + z * this.width]) {
            return dark;
        }

        return light;
    }

    public ArrayList<AABB> getCubes(AABB boundingBox) {
        ArrayList<AABB> boundingBoxList = new ArrayList<>();

        int minX = (int) (Math.floor(boundingBox.minX) - 1);
        int maxX = (int) (Math.ceil(boundingBox.maxX) + 1);
        int minY = (int) (Math.floor(boundingBox.minY) - 4);
        int maxY = (int) (Math.ceil(boundingBox.maxY) + 1);
        int minZ = (int) (Math.floor(boundingBox.minZ) - 1);
        int maxZ = (int) (Math.ceil(boundingBox.maxZ) + 1);

        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        minZ = Math.max(0, minZ);

        maxX = Math.min(this.width, maxX);
        maxY = Math.min(this.depth, maxY);
        maxZ = Math.min(this.height, maxZ);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    Tile tile = Tile.byId(getTile(x, y, z));
                    if (tile != null && tile != Tile.AIR) {
                        AABB tileBox = tile.getAABB(x, y, z);
                        if (tileBox != null) {
                            boundingBoxList.add(tileBox);
                        }
                    }
                }
            }
        }
        return boundingBoxList;
    }

    public void setTile(int x, int y, int z, int id) {
        setTile(x, y, z, id, 0);
    }

    public void setTile(int x, int y, int z, int id, int data) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }

        int index = (y * this.height + z) * this.width + x;
        this.blocks[index] = (byte) id;
        this.data[index] = (byte) data;

        this.calcLightDepths(x, z, 1, 1);

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
        }
    }

    public void addListener(LevelListener levelListener) {
        this.levelListeners.add(levelListener);
    }
}