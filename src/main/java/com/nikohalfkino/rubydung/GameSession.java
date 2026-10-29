package com.nikohalfkino.rubydung;

import com.nikohalfkino.rubydung.interfaces.screen.LoadingScreen;
import com.nikohalfkino.rubydung.level.Level;

import com.nikohalfkino.rubydung.level.LevelIO;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.player.PlayerInteraction;
import org.lwjgl.input.Mouse;

import java.util.Random;

public class GameSession {

    private static final LevelGenerator.WorldSize FALLBACK_SIZE = LevelGenerator.WorldSize.DEFAULT;

    private final RubyDung game;

    private Level level;
    private LevelRenderer levelRenderer;
    private Player player;
    private PlayerInteraction playerInteraction;

    public GameSession(RubyDung game) {
        this.game = game;
    }

    public boolean hasWorld() {
        return this.level != null;
    }

    public Level getLevel() {
        return this.level;
    }

    public LevelRenderer getLevelRenderer() {
        return this.levelRenderer;
    }

    public Player getPlayer() {
        return this.player;
    }

    public PlayerInteraction getPlayerInteraction() {
        return this.playerInteraction;
    }

    public Tile getSelectedTile() {
        return this.playerInteraction.getSelectedTile();
    }

    public void tick() {
        this.player.tick();
        this.playerInteraction.tick();
    }

    private void enterWorld(String worldName, LevelGenerator.WorldType type, Long seed, int width, int height, int depth) {
        this.level = seed != null
                ? new Level(worldName, width, height, depth, type, seed)
                : new Level(worldName, width, height, depth, type);

        if (this.levelRenderer != null) {
            this.levelRenderer.dispose();
        }

        this.levelRenderer = new LevelRenderer(this.level);
        this.player = new Player(this.level);
        this.playerInteraction = new PlayerInteraction(this.level, this.player);

        if (this.level.hasSavedPosition) {
            this.player.setPositionAndRotation(
                    this.level.savedPlayerX, this.level.savedPlayerY, this.level.savedPlayerZ,
                    this.level.savedPlayerYRotation, this.level.savedPlayerXRotation
            );
        }

        if (this.level.hasFlightState) {
            this.player.flying = this.level.savedFlying;
            this.player.noClip = this.level.savedNoClip;
            this.player.flySpeedMultiplier = this.level.savedFlySpeedMultiplier;
        }

        this.game.openScreen(new LoadingScreen(this.game, this.levelRenderer));
    }

    public void loadWorld(String worldName) {
        int[] savedSize = WorldManager.loadWorldSize(worldName);
        int width = savedSize != null ? savedSize[0] : FALLBACK_SIZE.width;
        int height = savedSize != null ? savedSize[1] : FALLBACK_SIZE.height;
        int depth = savedSize != null ? savedSize[2] : FALLBACK_SIZE.depth;

        LevelGenerator.WorldType worldType = LevelGenerator.WorldType.FLAT;
        java.io.File levelFile = WorldManager.levelFile(worldName);
        if (levelFile.isFile()) {
            byte[] dummyBlocks = new byte[width * height * depth];
            byte[] dummyData = new byte[width * height * depth];
            LevelIO.SavedPosition pos = LevelIO.load(levelFile, dummyBlocks, dummyData);
            if (pos.hasWorldType) {
                worldType = pos.worldType;
            }
        }

        enterWorld(worldName, worldType, null, width, height, depth);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, LevelGenerator.WorldSize size) {
        WorldManager.saveWorldSize(worldName, size.width, size.height, size.depth);
        enterWorld(worldName, type, null, size.width, size.height, size.depth);

        this.level.save();
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, long seed, LevelGenerator.WorldSize size) {
        WorldManager.saveWorldSize(worldName, size.width, size.height, size.depth);
        enterWorld(worldName, type, seed, size.width, size.height, size.depth);
        this.level.save();
    }

    public void regenerateLevel(Long customSeed, LevelGenerator.WorldType overrideType, LevelGenerator.WorldSize overrideSize) {
        if (!hasWorld()) {
            return;
        }

        long seed = customSeed != null ? customSeed : new Random().nextLong();
        String worldName = this.level.getWorldName();
        LevelGenerator.WorldType type = overrideType != null ? overrideType : this.level.getWorldType();

        int width = overrideSize != null ? overrideSize.width : this.level.width;
        int height = overrideSize != null ? overrideSize.height : this.level.height;
        int depth = overrideSize != null ? overrideSize.depth : this.level.depth;

        if (overrideSize != null) {
            WorldManager.saveWorldSize(worldName, width, height, depth);
        }

        this.level = new Level(worldName, width, height, depth, type, seed);

        if (this.levelRenderer != null) {
            this.levelRenderer.dispose();
        }

        this.levelRenderer = new LevelRenderer(this.level);
        this.player = new Player(this.level);
        this.playerInteraction = new PlayerInteraction(this.level, this.player);

        saveLevel();
        Mouse.setGrabbed(true);
    }

    public void saveLevel() {
        if (!hasWorld()) {
            return;
        }
        this.level.setPlayerPosition(this.player.x, this.player.y, this.player.z, this.player.yRotation, this.player.xRotation);
        this.level.setPlayerFlightState(this.player.flying, this.player.noClip, this.player.flySpeedMultiplier);
        this.level.save();
    }

    public void dispose() {
        if (this.levelRenderer != null) {
            this.levelRenderer.dispose();
        }
    }
}