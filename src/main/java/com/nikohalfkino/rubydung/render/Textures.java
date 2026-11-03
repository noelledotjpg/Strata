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

    // per atlas texture: fraction of its cell (both axes) the image really covers
    // 1.0 when the cell is a whole multiple of the image size, below that the rest is transparent padding and sprites must only sample the covered part to stay pixel-exact
    private static final Map<String, Float> ATLAS_EXTENTS = new HashMap<>();

    // original pixel width of every texture in the atlas (before scaling)
    private static final Map<String, Integer> SOURCE_WIDTHS = new HashMap<>();

    // per atlas texture id: width of one cell, in atlas pixels
    private static final Map<Integer, Integer> ATLAS_CELL_SIZES = new HashMap<>();

    // per atlas texture id: original pixel width of the image in each slot (same order as buildAtlas's names)
    private static final Map<Integer, int[]> ATLAS_SLOT_WIDTHS = new HashMap<>();

    // per atlas texture id: how its cells are laid out (see AtlasLayout)
    private static final Map<Integer, AtlasLayout> ATLAS_LAYOUTS = new HashMap<>();

    // Biggest atlas side tried first. Integrated and older GPUs report a bigger limit than they handle well,
    // so a layout that fits in this is always preferred, and only if none does is the reported limit used.
    private static final int PREFERRED_MAX_ATLAS_SIZE = 4096;

    /**
     * where each slot of an atlas sits: the slots fill a grid row by row (slot 0 top left), the grid's cells are
     * cell x cell pixels, and the whole texture is padded up to a power of two in both directions.
     *
     * a single row of slots used to be the layout, which for hundreds of tiles is wider than integrated and older
     * GPUs can hold (or a size they don't support without power-of-two textures), and the atlas then came out
     * as an untextured white / black texture
     */
    public static final class AtlasLayout {
        public final int columns;
        public final int cell;
        public final int width;
        public final int height;

        AtlasLayout(int columns, int cell, int width, int height) {
            this.columns = columns;
            this.cell = cell;
            this.width = width;
            this.height = height;
        }

        /** texture coordinate u of a point inside the slot's cell, local = 0 at its left edge, 1 at its right edge */
        public float u(int slot, float local) {
            return ((slot % this.columns) * this.cell + local * this.cell) / this.width;
        }

        /** texture coordinate v of a point inside the slot's cell, local = 0 at its top edge, 1 at its bottom edge */
        public float v(int slot, float local) {
            return ((slot / this.columns) * this.cell + local * this.cell) / this.height;
        }
    }

    /** the layout of an atlas built by buildAtlas, null if atlasId isn't one */
    public static AtlasLayout getAtlasLayout(int atlasId) {
        return ATLAS_LAYOUTS.get(atlasId);
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    // true if the driver says it can make an RGBA texture of this size (the proxy texture comes back with width 0 if not)
    private static boolean supportsTextureSize(int width, int height) {
        glTexImage2D(GL_PROXY_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return glGetTexLevelParameteri(GL_PROXY_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != 0;
    }

    private static AtlasLayout chooseLayout(int slots, int cell) {
        int reportedMax = glGetInteger(GL_MAX_TEXTURE_SIZE);
        if (reportedMax <= 0) {
            reportedMax = 2048;
        }

        int[] limits = {Math.min(PREFERRED_MAX_ATLAS_SIZE, reportedMax), reportedMax};
        for (int limit : limits) {
            AtlasLayout best = null;

            for (int columns = 1; ; columns *= 2) {
                int usedColumns = Math.min(columns, slots);
                int rows = (slots + usedColumns - 1) / usedColumns;
                int width = nextPowerOfTwo(usedColumns * cell);
                int height = nextPowerOfTwo(rows * cell);

                if (width <= limit && height <= limit && supportsTextureSize(width, height)) {
                    boolean smaller = best == null
                            || (long) width * height < (long) best.width * best.height
                            || ((long) width * height == (long) best.width * best.height
                            && Math.max(width, height) < Math.max(best.width, best.height));
                    if (smaller) {
                        best = new AtlasLayout(usedColumns, cell, width, height);
                    }
                }

                if (columns >= slots) {
                    break;
                }
            }

            if (best != null) {
                return best;
            }
        }

        throw new IllegalStateException("The block texture atlas needs " + slots + " textures of " + cell + "x" + cell
                + " pixels, which does not fit in a texture this graphics card supports (largest side: "
                + reportedMax + "). Use smaller block textures.");
    }

    /** width of one cell of the atlas texture atlasId in atlas pixels, 0 if it isn't an atlas built here */
    public static int getAtlasCellSize(int atlasId) {
        Integer size = ATLAS_CELL_SIZES.get(atlasId);
        return size == null ? 0 : size;
    }

    /** original pixel width of the sprite in slot of atlasId (before scaling up to the cell), 0 if unknown. it fills the top-left width * floor(cell / width) atlas pixels of its cell */
    public static int getAtlasSlotSourceWidth(int atlasId, int slot) {
        int[] widths = ATLAS_SLOT_WIDTHS.get(atlasId);
        return widths == null || slot < 0 || slot >= widths.length ? 0 : widths[slot];
    }

    /** original pixel width of an atlas texture, 0 if the atlas hasn't been built with it yet */
    public static int getSourceWidth(String resourceName) {
        Integer width = SOURCE_WIDTHS.get(resourceName);
        return width == null ? 0 : width;
    }

    // sliced textures: the atlas only holds square cells, but a texture can be any rectangle whose sides are whole multiples of the cell size (a 16x48 strip is three 16x16 slices, a 64x48 sheet is 4 x 3)
    // a slice is named "<resource path>@<col>,<row>" (col 0 = left, row 0 = top) and gets its own atlas slot
    // a plain name for a non-square image means its first slice

    private static final Map<String, int[]> SLICE_GRIDS = new HashMap<>();

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // side of one slice (the largest square that tiles the image exactly)
    private static int sliceSide(int width, int height) {
        return Math.max(1, gcd(width, height));
    }

    // {columns, rows} of slices in the image ({1, 1} for a square one)
    public static int[] sliceGrid(String resourcePath) {
        int[] grid = SLICE_GRIDS.get(resourcePath);
        if (grid == null) {
            BufferedImage image = readImage(resourcePath);
            int side = sliceSide(image.getWidth(), image.getHeight());
            grid = new int[]{image.getWidth() / side, image.getHeight() / side};
            SLICE_GRIDS.put(resourcePath, grid);
        }
        return grid.clone();
    }

    public static String sliceName(String resourcePath, int col, int row) {
        return resourcePath + "@" + col + "," + row;
    }

    public static float getAtlasExtent(String resourceName) {
        Float extent = ATLAS_EXTENTS.get(resourceName);
        return extent == null ? 1.0F : extent;
    }

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
        Map<String, BufferedImage> sources = new HashMap<>(); // whole images that are cut into slices
        int cellSize = 1;

        for (int i = 0; i < resourceNames.length; i++) {
            BufferedImage image = readAtlasImage(resourceNames[i], sources);
            if (image.getWidth() != image.getHeight()) {
                throw new IllegalArgumentException("Tile texture '" + resourceNames[i]
                        + "' must be square, but was " + image.getWidth() + "x" + image.getHeight()
                        + " (name a slice of it: path@col,row)");
            }

            images[i] = image;
            cellSize = Math.max(cellSize, image.getWidth());
        }

        int[] slotWidths = new int[resourceNames.length];
        AtlasLayout layout = chooseLayout(Math.max(1, resourceNames.length), cellSize);
        BufferedImage atlas = new BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        for (int i = 0; i < images.length; i++) {
            // Scale by a whole number only, so every source pixel stays the same size. When the
            // cell isn't an exact multiple, the image sits in the cell's top-left corner and the
            // remainder is left transparent (see ATLAS_EXTENTS).
            int width = images[i].getWidth();
            int factor = Math.max(1, cellSize / width);
            int drawn = width * factor;
            graphics.drawImage(images[i], (i % layout.columns) * cellSize, (i / layout.columns) * cellSize, drawn, drawn, null);
            ATLAS_EXTENTS.put(resourceNames[i], drawn / (float) cellSize);
            SOURCE_WIDTHS.put(resourceNames[i], width);
            slotWidths[i] = width;
        }

        graphics.dispose();

        int id = loadTexture(atlas, mode);
        ATLAS_LAYOUTS.put(id, layout);
        ATLAS_CELL_SIZES.put(id, cellSize);
        ATLAS_SLOT_WIDTHS.put(id, slotWidths);
        return id;
    }

    // an atlas entry's image: the whole image, or the cut-out square for a "path@col,row" slice
    private static BufferedImage readAtlasImage(String name, Map<String, BufferedImage> sources) {
        int at = name.lastIndexOf('@');
        if (at < 0) {
            return readImage(name);
        }

        String path = name.substring(0, at);
        int comma = name.indexOf(',', at);
        int col, row;
        try {
            col = Integer.parseInt(name.substring(at + 1, comma));
            row = Integer.parseInt(name.substring(comma + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad texture slice '" + name + "' (expected path@col,row)");
        }

        BufferedImage whole = sources.get(path);
        if (whole == null) {
            whole = readImage(path);
            sources.put(path, whole);
        }
        int side = sliceSide(whole.getWidth(), whole.getHeight());
        if (col < 0 || row < 0 || (col + 1) * side > whole.getWidth() || (row + 1) * side > whole.getHeight()) {
            throw new IllegalArgumentException("Slice " + col + "," + row + " is outside " + path + " ("
                    + whole.getWidth() / side + " x " + whole.getHeight() / side + " slices)");
        }
        return whole.getSubimage(col * side, row * side, side, side);
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