package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.HitResult;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.hud.FpsHud;
import com.nikohalfkino.rubydung.interfaces.screen.ConsoleScreen;
import com.nikohalfkino.rubydung.level.LevelRenderer;
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

    private static final float FOG_START = -10.0F;
    private static final float FOG_END = 120.0F;

    private static final double REACH_DISTANCE = 5.0;

    private static final float FAR_PLANE = 4096.0F;

    public GameRenderer(RubyDung game) {
        this.game = game;

        this.fogColor.put(new float[]{
                14 / 255.0F,
                11 / 255.0F,
                10 / 255.0F,
                255 / 255.0F
        }).flip();
    }

    public CloudRenderer getCloudRenderer() {
        return this.cloudRenderer;
    }

    public SkyboxRenderer getSkyboxRenderer() {
        return this.skyboxRenderer;
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

        if (playing) {
            float motionX = Mouse.getDX();
            float motionY = Mouse.getDY();

            this.game.getPlayer().turn(motionX, motionY);

            pick(partialTicks);
        }

        int width = this.game.getWidth();
        int height = this.game.getHeight();

        int mouseX = Mouse.getX();
        int mouseY = height - Mouse.getY() - 1;

        this.game.getInputHandler().handle(playing, mouseX, mouseY);

        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        if (this.game.hasWorld()) {
            Player player = this.game.getPlayer();
            LevelRenderer levelRenderer = this.game.getLevelRenderer();

            float cameraPartialTicks = playing ? partialTicks : 1.0F;
            setupCamera(cameraPartialTicks, width, height);

            double eyeX = player.prevX + (player.x - player.prevX) * cameraPartialTicks;
            double eyeY = player.prevY + (player.y - player.prevY) * cameraPartialTicks;
            double eyeZ = player.prevZ + (player.z - player.prevZ) * cameraPartialTicks;

            this.skyboxRenderer.render(eyeX, eyeY, eyeZ);
            this.cloudRenderer.render(eyeX, eyeY, eyeZ);

            glEnable(GL_FOG);
            glFogi(GL_FOG_MODE, GL_LINEAR);
            glFogf(GL_FOG_START, FOG_START);
            glFogf(GL_FOG_END, FOG_END);
            glFog(GL_FOG_COLOR, this.fogColor);
            glDisable(GL_FOG);

            levelRenderer.render(0);

            glEnable(GL_FOG);
            glEnable(GL_TEXTURE_2D);
            levelRenderer.render(1);

            levelRenderer.renderBillboards(player.yRotation, player.xRotation, eyeX, eyeY, eyeZ);

            glDisable(GL_TEXTURE_2D);

            HitResult hitResult = this.game.getPlayerInteraction().getHitResult();
            if (hitResult != null && playing) {
                levelRenderer.renderHit(hitResult);
            }

            glDisable(GL_FOG);

            this.game.getHud().render(this.game.getFont(), width, height);
        }

        if (this.game.getCurrentScreen() != null) {
            this.game.getCurrentScreen().render(mouseX, mouseY);
        }

        ConsoleScreen.render(this.game.getConsole(), this.game.getFont(), width, height);
        FpsHud.render(this.game, this.game.getFont(), width, height);

        Display.update();
    }

    private void setupCamera(float partialTicks, int width, int height) {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        gluPerspective(GameSettings.getFov(), width / (float) height, 0.05F, FAR_PLANE);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        CameraUtil.moveCameraToPlayer(this.game.getPlayer(), partialTicks);
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

        playerInteraction.setHitResult(hitResult);
    }
}