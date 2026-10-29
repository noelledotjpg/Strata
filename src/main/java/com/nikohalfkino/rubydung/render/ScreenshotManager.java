package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.util.GameSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.util.glu.GLU.gluPerspective;

public class ScreenshotManager {

    private static final File SCREENSHOTS_DIR = new File("screenshots");

    private final RubyDung game;
    private final GameRenderer gameRenderer;

    public ScreenshotManager(RubyDung game, GameRenderer gameRenderer) {
        this.game = game;
        this.gameRenderer = gameRenderer;
    }

    private interface SceneCamera {
        void applyProjection(int viewportWidth, int viewportHeight);

        void applyView();

        float yaw();

        float pitch();
    }

    private SceneCamera playerCamera() {
        Player player = this.game.getPlayer();

        return new SceneCamera() {
            @Override
            public void applyProjection(int viewportWidth, int viewportHeight) {
                gluPerspective(GameSettings.getFov(), viewportWidth / (float) viewportHeight, 0.05F, 1000);
            }

            @Override
            public void applyView() {
                CameraUtil.moveCameraToPlayer(player, 1.0F);
            }

            @Override
            public float yaw() {
                return player.yRotation;
            }

            @Override
            public float pitch() {
                return player.xRotation;
            }
        };
    }

    private SceneCamera panoramaFaceCamera(float yaw, float pitch) {
        Player player = this.game.getPlayer();

        return new SceneCamera() {
            @Override
            public void applyProjection(int viewportWidth, int viewportHeight) {
                gluPerspective(90.0F, 1.0F, 0.05F, 1000);
            }

            @Override
            public void applyView() {
                glRotatef(pitch, 1.0F, 0.0F, 0.0F);
                glRotatef(yaw, 0.0F, 1.0F, 0.0F);
                glTranslated(-player.x, -player.y, -player.z);
            }

            @Override
            public float yaw() {
                return yaw;
            }

            @Override
            public float pitch() {
                return pitch;
            }
        };
    }

    private SceneCamera isometricCamera(float yaw) {
        Player player = this.game.getPlayer();
        float viewSize = 40.0F;
        float pitch = 35.264F;

        return new SceneCamera() {
            @Override
            public void applyProjection(int viewportWidth, int viewportHeight) {
                glOrtho(-viewSize, viewSize, -viewSize, viewSize, -1000, 1000);
            }

            @Override
            public void applyView() {
                glRotatef(pitch, 1.0F, 0.0F, 0.0F);
                glRotatef(yaw, 0.0F, 1.0F, 0.0F);
                glTranslated(-player.x, -player.y, -player.z);
            }

            @Override
            public float yaw() {
                return yaw;
            }

            @Override
            public float pitch() {
                return pitch;
            }
        };
    }

    private float isometricYaw(String direction) {
        switch (direction) {
            case "NE":
                return 45.0F;
            case "SE":
                return 135.0F;
            case "SW":
                return 225.0F;
            case "NW":
            default:
                return 315.0F;
        }
    }

    private BufferedImage captureView(int viewportWidth, int viewportHeight, SceneCamera camera) {
        int fbo = GL30.glGenFramebuffers();
        int colorTexture = glGenTextures();
        int depthRenderbuffer = GL30.glGenRenderbuffers();

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            glBindTexture(GL_TEXTURE_2D, colorTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, viewportWidth, viewportHeight, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, colorTexture, 0);

            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRenderbuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24,
                    viewportWidth, viewportHeight);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depthRenderbuffer);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                System.out.println("Screenshot framebuffer incomplete (status " + status + "), image may be blank.");
            }

            glViewport(0, 0, viewportWidth, viewportHeight);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            camera.applyProjection(viewportWidth, viewportHeight);

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            camera.applyView();

            LevelRenderer levelRenderer = this.game.getLevelRenderer();
            Player player = this.game.getPlayer();

            double eyeX = player.prevX + (player.x - player.prevX) * 1.0F;
            double eyeY = player.prevY + (player.y - player.prevY) * 1.0F;
            double eyeZ = player.prevZ + (player.z - player.prevZ) * 1.0F;

            this.gameRenderer.getSkyboxRenderer().render(eyeX, eyeY, eyeZ);
            this.gameRenderer.getCloudRenderer().render(eyeX, eyeY, eyeZ);

            glEnable(GL_FOG);
            glFogi(GL_FOG_MODE, GL_LINEAR);
            glFogf(GL_FOG_START, this.gameRenderer.getFogStart());
            glFogf(GL_FOG_END, this.gameRenderer.getFogEnd());
            glFog(GL_FOG_COLOR, this.gameRenderer.getFogColor());
            glDisable(GL_FOG);

            levelRenderer.render(0);

            glEnable(GL_FOG);
            levelRenderer.render(1);

            levelRenderer.renderBillboards(camera.yaw(), camera.pitch());

            glDisable(GL_TEXTURE_2D);
            glDisable(GL_FOG);

            glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            return readPixels(viewportWidth, viewportHeight);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            glViewport(0, 0, this.game.getWidth(), this.game.getHeight());

            GL30.glDeleteFramebuffers(fbo);
            glDeleteTextures(colorTexture);
            GL30.glDeleteRenderbuffers(depthRenderbuffer);
        }
    }

    private BufferedImage readPixels(int width, int height) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (x + (height - y - 1) * width) * 4;
                int r = buffer.get(i) & 0xFF;
                int g = buffer.get(i + 1) & 0xFF;
                int b = buffer.get(i + 2) & 0xFF;

                int a = 255;

                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private String newScreenshotTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
    }

    private void writeImage(BufferedImage image, String timestamp, String suffix) {
        SCREENSHOTS_DIR.mkdirs();

        File file = new File(SCREENSHOTS_DIR, timestamp + suffix + ".png");

        try {
            ImageIO.write(image, "png", file);
            System.out.println("Saved screenshot to " + file.getPath());
        } catch (IOException e) {
            System.out.println("Failed to save screenshot: " + e.getMessage());
        }

        com.nikohalfkino.rubydung.util.ImageTransferable.copyToClipboard(image);
        System.out.println("Copied screenshot to clipboard.");
    }

    public void takeScreenshot(int scale) {
        if (!this.game.hasWorld()) {
            return;
        }

        BufferedImage image = captureView(this.game.getWidth() * scale, this.game.getHeight() * scale, playerCamera());
        writeImage(image, newScreenshotTimestamp(), scale > 1 ? "_" + scale + "x" : "");
    }

    public void takePanoramaScreenshot(int scale) {
        if (!this.game.hasWorld()) {
            return;
        }

        int size = Math.min(this.game.getWidth(), this.game.getHeight()) * scale;
        float[][] faces = {
                {0.0F, 0.0F}, {90.0F, 0.0F}, {180.0F, 0.0F}, {270.0F, 0.0F}, {0.0F, -90.0F}, {0.0F, 90.0F}
        };

        String timestamp = newScreenshotTimestamp();
        for (int i = 0; i < faces.length; i++) {
            BufferedImage image = captureView(size, size, panoramaFaceCamera(faces[i][0], faces[i][1]));
            writeImage(image, timestamp, "_panorama_" + i + (scale > 1 ? "_" + scale + "x" : ""));
        }
    }

    public void takeIsometricScreenshot(String direction, int scale) {
        if (!this.game.hasWorld()) {
            return;
        }

        int size = Math.min(this.game.getWidth(), this.game.getHeight()) * scale;
        float yaw = isometricYaw(direction);

        BufferedImage image = captureView(size, size, isometricCamera(yaw));
        writeImage(image, newScreenshotTimestamp(), "_isometric_" + direction.toLowerCase() + (scale > 1 ? "_" + scale + "x" : ""));
    }
}