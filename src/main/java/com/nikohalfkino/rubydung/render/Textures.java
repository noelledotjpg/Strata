package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

public class Textures {

    public static void enableAlphaTest() {
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.1f);
    }

    public static void enableAlphaBlending() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private static final Map<String, Integer> TEXTURE_CACHE = new HashMap<>();

    public static int loadTexture(String resourceName, int mode) {
        String cacheKey = resourceName + ":" + mode;
        Integer cached = TEXTURE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int id = loadTexture(readImage(resourceName), mode);
        TEXTURE_CACHE.put(cacheKey, id);
        return id;
    }

    public static int loadTexture(BufferedImage image, int mode) {
        return loadTexture(image, mode, GL_CLAMP_TO_EDGE);
    }

    public static int loadTiledTexture(String resourceName) {
        return loadTexture(readImage(resourceName), GL_NEAREST, GL_REPEAT);
    }

    public static int loadTiledTexture(BufferedImage image) {
        return loadTexture(image, GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, true);
    }

    public static int loadCloudTexture(BufferedImage image) {
        return loadTexture(image, GL_LINEAR, GL_LINEAR, GL_REPEAT, false);
    }

    public static void updateTexture(int id, BufferedImage image) {
        bind(id);

        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        ByteBuffer byteBuffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            byteBuffer.put((byte) ((pixel >> 16) & 0xFF));
            byteBuffer.put((byte) ((pixel >> 8) & 0xFF));
            byteBuffer.put((byte) (pixel & 0xFF));
            byteBuffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        byteBuffer.flip();

        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, byteBuffer);
    }

    private static int loadTexture(BufferedImage image, int mode, int wrapMode) {
        return loadTexture(image, mode, mode, wrapMode, false);
    }

    private static int loadTexture(BufferedImage image, int minFilter, int magFilter, int wrapMode,
                                   boolean generateMipmaps) {
        int id = glGenTextures();
        bind(id);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, magFilter);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);

        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        ByteBuffer byteBuffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            byteBuffer.put((byte) ((pixel >> 16) & 0xFF));
            byteBuffer.put((byte) ((pixel >> 8) & 0xFF));
            byteBuffer.put((byte) (pixel & 0xFF));
            byteBuffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        byteBuffer.flip();

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, byteBuffer);

        if (generateMipmaps) {
            GL30.glGenerateMipmap(GL_TEXTURE_2D);
        }

        return id;
    }

    public static int buildAtlas(String[] resourceNames, int mode) {
        BufferedImage[] images = new BufferedImage[resourceNames.length];
        int cellSize = 1;

        for (int i = 0; i < resourceNames.length; i++) {
            BufferedImage image = readImage(resourceNames[i]);
            if (image.getWidth() != image.getHeight()) {
                throw new IllegalArgumentException("Tile texture '" + resourceNames[i]
                        + "' must be square, but was " + image.getWidth() + "x" + image.getHeight());
            }

            images[i] = image;
            cellSize = Math.max(cellSize, image.getWidth());
        }

        BufferedImage atlas = new BufferedImage(resourceNames.length * cellSize, cellSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        for (int i = 0; i < images.length; i++) {
            graphics.drawImage(images[i], i * cellSize, 0, cellSize, cellSize, null);
        }

        graphics.dispose();

        return loadTexture(atlas, mode);
    }

    static BufferedImage readImage(String resourceName) {
        InputStream inputStream = Textures.class.getResourceAsStream(resourceName);

        if (inputStream == null) {
            throw new RuntimeException("Could not find texture " + resourceName);
        }

        try {
            return ImageIO.read(inputStream);
        } catch (IOException exception) {
            throw new RuntimeException("Could not load texture " + resourceName, exception);
        }
    }

    public static ByteBuffer[] loadIcons(String resourceName) {
        BufferedImage source = readImage(resourceName);

        return new ByteBuffer[]{
                imageToRgbaBuffer(scaleImage(source, 32)),
                imageToRgbaBuffer(scaleImage(source, 16))
        };
    }

    private static BufferedImage scaleImage(BufferedImage source, int size) {
        if (source.getWidth() == size && source.getHeight() == size) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, size, size, null);
        graphics.dispose();

        return scaled;
    }

    private static ByteBuffer imageToRgbaBuffer(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
        }
        buffer.flip();

        return buffer;
    }

    public static void bind(int id) {
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public static boolean resourceExists(String resourceName) {
        try (InputStream inputStream = Textures.class.getResourceAsStream(resourceName)) {
            return inputStream != null;
        } catch (IOException e) {
            return false;
        }
    }
}