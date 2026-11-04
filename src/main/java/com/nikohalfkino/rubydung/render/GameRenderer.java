package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.player.HitResult;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.FrameScreen;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.hud.FpsHud;
import com.nikohalfkino.rubydung.interfaces.screen.ConsoleScreen;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.LiquidRaycaster;
import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.VoxelRaycaster;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.player.PlayerInteraction;
import com.nikohalfkino.rubydung.util.GameSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.util.glu.GLU.gluPerspective;

public class GameRenderer {

    private final RubyDung game;

    private final FloatBuffer fogColor = BufferUtils.createFloatBuffer(4);

    private final CloudRenderer cloudRenderer = new CloudRenderer();
    private final SkyboxRenderer skyboxRenderer = new SkyboxRenderer();
    private final GameFrame frame = new GameFrame();
    private final PixelationFilter pixelation = new PixelationFilter();
    private final PlayerCamera playerCamera = new PlayerCamera();
    private final PlayerRenderer playerRenderer = new PlayerRenderer();
    private final DebugRenderer debugRenderer = new DebugRenderer();

    private long lastFrameNanos = System.nanoTime();

    private static final float FOG_START = -10.0F;
    private static final float FOG_END = 120.0F;

    private static final double REACH_DISTANCE = 5.0;

    private static final float FAR_PLANE = 4096.0F;

    public GameRenderer(RubyDung game) {
        this.game = game;
    }

    public PlayerCamera getPlayerCamera() {
        return this.playerCamera;
    }

    public CloudRenderer getCloudRenderer() {
        return this.cloudRenderer;
    }

    public SkyboxRenderer getSkyboxRenderer() {
        return this.skyboxRenderer;
    }

    public DebugRenderer getDebugRenderer() {
        return this.debugRenderer;
    }

    public FloatBuffer getFogColor() {
        return this.fogColor;
    }

    public float getFogStart() {
        return FOG_START;
    }

    public float getFogEnd() {
        return FOG_END;
    }

    public float getCloudY() {
        return CloudRenderer.HEIGHT_OFFSET;
    }

    public void render(float partialTicks) {
        boolean playing = this.game.hasWorld() && this.game.getCurrentScreen() == null
                && !this.game.getConsole().isOpen() && Display.isActive();

        // The frame opens while a FrameScreen (inventory, pause menu) is current and animates closed otherwise.
        Screen screen = this.game.getCurrentScreen();
        FrameScreen frameScreen = screen instanceof FrameScreen ? (FrameScreen) screen : null;
        this.frame.setTarget(frameScreen != null, frameScreen);
        this.frame.update();

        if (playing) {
            float motionX = Mouse.getDX();
            float motionY = Mouse.getDY();

            this.playerCamera.turn(this.game.getPlayer(), motionX, motionY);

            if (this.playerCamera.isFreecam()) {
                // The player isn't aimed in freecam, so nothing is targeted.
                this.game.getPlayerInteraction().setHitResult(null);
            } else {
                pick(partialTicks);
            }
        }

        int width = this.game.getWidth();
        int height = this.game.getHeight();

        WindowManager wm = this.game.getWindowManager();

        // Window mouse position -> game UI coordinates (differs from raw in Fit / Bordered fullscreen).
        int mouseX = wm.toLogicalX(Mouse.getX());
        int mouseY = wm.toLogicalY(Mouse.getY());

        this.game.getInputHandler().handle(playing, mouseX, mouseY);

        // Black everywhere, then everything below is clipped to the game area.
        wm.beginFrame();

        if (this.game.hasWorld()) {
            Player player = this.game.getPlayer();
            LevelRenderer levelRenderer = this.game.getLevelRenderer();
            DayNightCycle cycle = this.game.getDayNightCycle();

            // Ambient is applied to terrain by TerrainShader at draw time (and to billboards per frame).
            this.game.getLevel().setSkyAmbient(cycle.getAmbient());

            // Liquids flow in real time, except while a screen (pause menu, inventory) is open.
            long now = System.nanoTime();
            float frameSeconds = Math.min((now - this.lastFrameNanos) * 1.0E-9F, 0.25F);
            this.lastFrameNanos = now;
            if (this.game.getCurrentScreen() == null) {
                this.game.getLevel().getLiquids().update(frameSeconds);
            }

            // The world is drawn into the frame's viewport; without a frame (closed, or window
            // too small) it fills the whole window.
            int viewWidth = width;
            int viewHeight = height;
            int viewGlX = 0;
            int viewGlY = 0;

            GameFrame.Layout layout = this.frame.layout(width, height);
            if (layout != null) {
                this.frame.render(layout, width, height, this.game.getFont());

                viewWidth = layout.viewWidth;
                viewHeight = layout.viewHeight;
                viewGlX = layout.viewX;
                viewGlY = height - layout.viewY - layout.viewHeight;
                wm.viewport(viewGlX, viewGlY, viewWidth, viewHeight);

                // Let the screen know where the panel is right now (it slides in).
                if (frameScreen != null) {
                    frameScreen.setLayout(layout);
                }
            }

            // Pixelation draws the world into a smaller viewport; setupCamera still gets the full
            // view size so the aspect ratio is right.
            boolean pixelate = GameSettings.isPixelationEnabled() && GameSettings.getPixelSize() > 1;
            if (pixelate) {
                // The filter works in real window pixels; the pixel size grows with the Fit scale.
                this.pixelation.begin(wm.glX(viewGlX), wm.glY(viewGlY), wm.size(viewWidth), wm.size(viewHeight),
                        Math.max(1, Math.round(GameSettings.getPixelSize() * wm.getScale())));
            }

            float cameraPartialTicks = playing ? partialTicks : 1.0F;
            this.playerCamera.update(player, this.game.getLevel(), cameraPartialTicks, playing);
            setupCamera(viewWidth, viewHeight);

            // Where the camera is (the player's eyes in first person).
            double eyeX = this.playerCamera.getX();
            double eyeY = this.playerCamera.getY();
            double eyeZ = this.playerCamera.getZ();

            // Update fog color from the day-night cycle.
            float[] fc = cycle.getFogColor();
            this.fogColor.clear();
            this.fogColor.put(new float[]{fc[0], fc[1], fc[2], 1.0F}).flip();

            // The sky belongs to the player, not to the camera: in the freecam it stays centred on the player,
            // so the camera can fly out and look at it up close (or from outside). Otherwise the camera is the centre.
            boolean freecam = this.playerCamera.isFreecam();
            final double skyX, skyY, skyZ;
            if (freecam) {
                skyX = player.prevX + (player.x - player.prevX) * cameraPartialTicks;
                skyY = player.prevY + (player.y - player.prevY) * cameraPartialTicks;
                skyZ = player.prevZ + (player.z - player.prevZ) * cameraPartialTicks;
            } else {
                skyX = eyeX;
                skyY = eyeY;
                skyZ = eyeZ;
            }

            // Away from the player the sky can be seen from outside, so it has to write and test depth,
            // otherwise its far side is painted over its near side.
            this.skyboxRenderer.setSolid(freecam);

            this.skyboxRenderer.render(skyX, skyY, skyZ, cycle);
            // The sun and moon are drawn between the white (lower) and coloured (upper) cloud layers.
            this.cloudRenderer.render(skyX, skyY, skyZ, cycle,
                    () -> this.skyboxRenderer.renderSunAndMoon(skyX, skyY, skyZ, cycle));

            // Distance fog at the edge of the render distance (the terrain and liquid shaders apply it themselves,
            // the fixed function fog is only switched on for the billboards).
            Fog.update(GameSettings.getRenderDistance() * (float) Chunk.SIZE, fc, eyeX, eyeZ);
            glDisable(GL_FOG);

            levelRenderer.setViewPosition(eyeX, eyeY, eyeZ);
            levelRenderer.render(0);

            glEnable(GL_TEXTURE_2D);
            levelRenderer.render(1);

            // after all opaque terrain: the liquids blend over it
            levelRenderer.renderLiquids();

            // Before the billboards, so those behind the player are hidden by it.
            this.playerRenderer.render(this.game.getLevel(), player, this.playerCamera, cameraPartialTicks);

            levelRenderer.renderBillboards(this.playerCamera.getYaw(), this.playerCamera.getPitch(),
                    eyeX, eyeY, eyeZ);

            glDisable(GL_TEXTURE_2D);

            HitResult hitResult = this.game.getPlayerInteraction().getHitResult();
            if (hitResult != null && playing) {
                levelRenderer.renderHit(hitResult);
            }

            // Debug overlays (the chunkborders and vectors commands), drawn in world space with the camera's matrices.
            if (this.debugRenderer.isActive()) {
                this.debugRenderer.render(this.game.getLevel(), player, eyeX, eyeY, eyeZ, cameraPartialTicks);
            }

            glDisable(GL_FOG);

            // Stretch the low-res world back over the view (the HUD and frame stay full resolution).
            if (pixelate) {
                this.pixelation.end();
            }

            // Dither the world while the frame is open (checkerboard of black pixels).
            if (layout != null) {
                this.frame.renderDarken(viewWidth, viewHeight);
            }

            // The HUD lays itself out inside the viewport (crosshair centred on the world view).
            this.game.getHud().render(this.game.getFont(), viewWidth, viewHeight);

            wm.viewport(0, 0, width, height);
        }

        if (this.game.getCurrentScreen() != null) {
            this.game.getCurrentScreen().render(mouseX, mouseY);
        }

        ConsoleScreen.render(this.game.getConsole(), this.game.getFont(), width, height);
        FpsHud.render(this.game, this.game.getFont(), width, height);

        wm.endFrame();

        Display.update();
    }

    private void setupCamera(int width, int height) {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        gluPerspective(GameSettings.getFov(), width / (float) height, 0.05F, FAR_PLANE);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        this.playerCamera.apply();
    }

    private void pick(float partialTicks) {
        Player player = this.game.getPlayer();
        PlayerInteraction playerInteraction = this.game.getPlayerInteraction();

        double eyeX = player.prevX + (player.x - player.prevX) * partialTicks;
        double eyeY = player.prevY + (player.y - player.prevY) * partialTicks;
        double eyeZ = player.prevZ + (player.z - player.prevZ) * partialTicks;

        double yawRad = Math.toRadians(player.yRotation);
        double pitchRad = Math.toRadians(player.xRotation);
        double dirX = Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = -(Math.cos(yawRad) * Math.cos(pitchRad));

        HitResult hitResult = VoxelRaycaster.raycast(
                this.game.getLevel(), eyeX, eyeY, eyeZ, dirX, dirY, dirZ, REACH_DISTANCE);

        // Liquids aren't blocks: holding a liquid's tile makes that liquid targetable (to break it or
        // build against it) when it lies before the block the ray hit.
        Tile selected = playerInteraction.getSelectedTile();
        if (selected instanceof LiquidTile) {
            HitResult liquidHit = LiquidRaycaster.raycast(this.game.getLevel(), eyeX, eyeY, eyeZ,
                    dirX, dirY, dirZ, REACH_DISTANCE, (LiquidTile) selected, hitResult);
            if (liquidHit != null) {
                hitResult = liquidHit;
            }
        }

        playerInteraction.setHitResult(hitResult);
    }
}