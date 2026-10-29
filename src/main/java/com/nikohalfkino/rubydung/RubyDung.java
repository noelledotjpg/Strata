package com.nikohalfkino.rubydung;

import com.nikohalfkino.rubydung.audio.MusicPlayer;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.interfaces.*;
import com.nikohalfkino.rubydung.interfaces.hud.CenterIndicator;
import com.nikohalfkino.rubydung.interfaces.hud.CrosshairHud;
import com.nikohalfkino.rubydung.interfaces.hud.DebugHud;
import com.nikohalfkino.rubydung.interfaces.hud.SelectedTileHud;
import com.nikohalfkino.rubydung.interfaces.screen.LoadingScreen;
import com.nikohalfkino.rubydung.interfaces.screen.TitleScreen;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.player.PlayerInteraction;
import com.nikohalfkino.rubydung.player.input.InputHandler;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.GameRenderer;
import com.nikohalfkino.rubydung.render.ScreenshotManager;
import com.nikohalfkino.rubydung.render.Textures;
import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.KeyBinding;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import javax.swing.*;

import static org.lwjgl.opengl.GL11.*;

public class RubyDung implements Runnable {

    private final Timer timer = new Timer(60);

    private final int width = 800;
    private final int height = 600;
    private final Console console = new Console(this);
    private final InputHandler inputHandler = new InputHandler(this);
    private final GameSession session = new GameSession(this);
    private final GameRenderer gameRenderer = new GameRenderer(this);
    private final ScreenshotManager screenshotManager = new ScreenshotManager(this, this.gameRenderer);
    private final MusicPlayer musicPlayer = new MusicPlayer();
    private final Hud hud = new Hud();
    private final DebugHud debugHud = new DebugHud(this);
    private final CenterIndicator centerIndicator = new CenterIndicator(this);
    private boolean running = true;
    private Font font;
    private Screen currentScreen;

    public static void main(String[] args) {
        new Thread(new RubyDung()).start();
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public Screen getCurrentScreen() {
        return this.currentScreen;
    }

    public Console getConsole() {
        return this.console;
    }

    public InputHandler getInputHandler() {
        return this.inputHandler;
    }

    public Font getFont() {
        return this.font;
    }

    public Hud getHud() {
        return this.hud;
    }

    public DebugHud getDebugHud() {
        return this.debugHud;
    }

    public float getFps() {
        return this.timer.fps;
    }

    public boolean hasWorld() {
        return this.session.hasWorld();
    }

    public Level getLevel() {
        return this.session.getLevel();
    }

    public LevelRenderer getLevelRenderer() {
        return this.session.getLevelRenderer();
    }

    public Player getPlayer() {
        return this.session.getPlayer();
    }

    public PlayerInteraction getPlayerInteraction() {
        return this.session.getPlayerInteraction();
    }

    public Tile getSelectedTile() {
        return this.session.getSelectedTile();
    }

    public CenterIndicator getCenterIndicator() {
        return this.centerIndicator;
    }

    public void init() throws LWJGLException {
        GameSettings.load();
        KeyBinding.load();

        Display.setTitle("Strata 0.0.1");
        Display.setDisplayMode(new DisplayMode(this.width, this.height));

        Display.create();
        Keyboard.create();
        Mouse.create();

        Display.setVSyncEnabled(GameSettings.isVsyncEnabled());

        Display.setIcon(Textures.loadIcons("/assets/rubydung/icon.png"));

        glEnable(GL_TEXTURE_2D);
        glShadeModel(GL_SMOOTH);
        glClearColor(0.5F, 0.8F, 1.0F, 1.0F);
        glClearDepth(1.0);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.1F);
        glEnable(GL_CULL_FACE);
        glDepthFunc(GL_LEQUAL);

        this.font = new Font("/assets/rubydung/font/default.png");

        this.hud.register(new SelectedTileHud(this));
        this.hud.register(this.debugHud);
        this.hud.register(new CrosshairHud());
        this.hud.register(this.centerIndicator);

        WorldManager.pruneIncompleteWorlds();

        this.musicPlayer.start();

        openScreen(new TitleScreen(this));
    }

    public void finishedLoadingWorld() {
        closeScreen();
        Mouse.setGrabbed(true);
    }

    public void loadWorld(String worldName) {
        this.session.loadWorld(worldName);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, LevelGenerator.WorldSize size) {
        this.session.startNewWorld(worldName, type, size);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, long seed, LevelGenerator.WorldSize size) {
        this.session.startNewWorld(worldName, type, seed, size);
    }

    public void regenerateLevel(Long customSeed, LevelGenerator.WorldType overrideType, LevelGenerator.WorldSize overrideSize) {
        this.session.regenerateLevel(customSeed, overrideType, overrideSize);
    }

    public void saveLevel() {
        this.session.saveLevel();
    }

    public void quit() {
        this.running = false;
    }

    public void destroy() {
        saveLevel();
        GameSettings.save();
        KeyBinding.save();
        this.session.dispose();
        this.musicPlayer.stop();

        Mouse.destroy();
        Keyboard.destroy();
        Display.destroy();
    }

    @Override
    public void run() {
        try {
            init();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, e, "Failed to start Strata 0.0.1", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }

        long lastTime = System.currentTimeMillis();

        try {
            while (!Display.isCloseRequested() && this.running) {
                this.timer.advanceTime();

                for (int i = 0; i < this.timer.ticks; ++i) {
                    tick();
                }

                this.gameRenderer.render(this.timer.partialTicks);

                if (GameSettings.getFpsCap() != GameSettings.UNCAPPED_FPS) {
                    Display.sync(GameSettings.getFpsCap());
                }

                while (System.currentTimeMillis() >= lastTime + 1000L) {
                    Chunk.updates = 0;
                    lastTime += 1000L;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            destroy();
        }
    }

    private void tick() {
        if (!hasWorld() || this.currentScreen != null || this.console.isOpen() || !Display.isActive()) {
            return;
        }
        this.session.tick();
    }

    public void openScreen(Screen screen) {
        if (this.currentScreen != null) {
            this.currentScreen.onClose();
        }

        this.currentScreen = screen;
        screen.init(this.width, this.height);

        if (screen.pausesGame()) {
            Mouse.setGrabbed(false);

            if (hasWorld()) {
                Display.setTitle("Strata - Paused");
            }
        }
    }

    public void closeScreen() {
        if (this.currentScreen != null) {
            this.currentScreen.onClose();
        }
        this.currentScreen = null;

        if (!this.console.isOpen()) {
            Mouse.setGrabbed(true);
        }
        Display.setTitle("Strata 0.0.1");
    }

    public void takeScreenshot(int scale) {
        this.screenshotManager.takeScreenshot(scale);
    }

    public void takePanoramaScreenshot(int scale) {
        this.screenshotManager.takePanoramaScreenshot(scale);
    }

    public void takeIsometricScreenshot(String direction, int scale) {
        this.screenshotManager.takeIsometricScreenshot(direction, scale);
    }
}