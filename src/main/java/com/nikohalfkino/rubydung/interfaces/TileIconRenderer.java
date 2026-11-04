package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import static org.lwjgl.opengl.GL11.*;

/** draws a tile as a 2d icon (isometric cube, flat sprite for billboards) for the selected-tile HUD and inventory */
public final class TileIconRenderer {

    // billboards are drawn this much larger than the cube scale so they look the same size
    private static final float BILLBOARD_SCALE = 44.0F / 32.0F;

    // billboard icons show a square of this many of the sprite's own pixels (not atlas pixels), zoomed to fill the icon
    // 16px sprite = whole, 32px = a quarter, 64px = a sixteenth
    private static final int SPRITE_CROP_PIXELS = 16;

    // 0 = top edge, 0.5 = centred, 1 = bottom edge
    private static final float SPRITE_CROP_VERTICAL = 0.5F;

    // outward normals by Tile.FACE_*, same as the tile's own table
    private static final int[][] FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    // cube tilt: 30 degrees about X, then -45 + spin about Y (see draw)
    private static final double ICON_PITCH_DEGREES = 30.0;
    private static final double ICON_YAW_DEGREES = -45.0;

    // The icon's own tessellator. The icon flags (full bright, self-cull faces) are set on it, so they are never visible
    // to the chunk build workers, which each use a tessellator of their own. Render thread only.
    private static Tessellator tessellator;

    private TileIconRenderer() {
    }

    /** centerX/Y are in window pixels (top-left origin), size is the cube scale (a cube ends up ~1.4x this wide), screenWidth/Height is the area the 2D UI is laid out in */
    public static void draw(Tile tile, Level level, float centerX, float centerY, float size,
                            int screenWidth, int screenHeight) {
        draw(tile, level, centerX, centerY, size, screenWidth, screenHeight, 0.0F);
    }

    /** darken = how much black is laid over the icon's own pixels (0 = none, 1 = solid) */
    public static void draw(Tile tile, Level level, float centerX, float centerY, float size,
                            int screenWidth, int screenHeight, float darken) {
        draw(tile, level, centerX, centerY, size, screenWidth, screenHeight, darken, 0.0F);
    }

    /** cube turned spinDegrees about its vertical axis (sprites ignore it), billboard sprites are cropped */
    public static void draw(Tile tile, Level level, float centerX, float centerY, float size,
                            int screenWidth, int screenHeight, float darken, float spinDegrees) {
        draw(tile, level, centerX, centerY, size, screenWidth, screenHeight, darken, spinDegrees, true);
    }

    /** cropSprites false shows a billboard's whole sprite */
    public static void draw(Tile tile, Level level, float centerX, float centerY, float size,
                            int screenWidth, int screenHeight, float darken, float spinDegrees,
                            boolean cropSprites) {
        draw(tile, level, centerX, centerY, size, screenWidth, screenHeight, darken, spinDegrees,
                cropSprites, 1.0F);
    }

    /** billboardScale multiplies a billboard's quad size (cubes ignore it), 1 = usual icon size, the hand HUD passes the sprite's size in blocks so every sprite pixel is the same size on screen */
    public static void draw(Tile tile, Level level, float centerX, float centerY, float size,
                            int screenWidth, int screenHeight, float darken, float spinDegrees,
                            boolean cropSprites, float billboardScale) {
        if (tile == null) {
            return;
        }
        // a cube is built by Tile.render, which needs the level; only a flat sprite can be drawn without one
        if (level == null && !tile.isBillboard()) {
            return;
        }
        if (tessellator == null) {
            tessellator = new Tessellator();
        }

        // One push/pop restores everything this method changes (enables, blend and alpha func, depth func and mask).
        // That replaces a handful of glIsEnabled / glGetInteger queries per icon, which can stall the driver and
        // add up fast: the selected-tile HUD draws several icons every frame.
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, screenWidth, screenHeight, 0.0, 100.0, 300.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(0.0F, 0.0F, -200.0F);

        // The flat sprite quad is wound the "wrong" way round for this projection, so with face
        // culling on (Gui2D.end() turns it back on) billboards vanish. Cubes survive because the
        // -size Y scale flips their winding.
        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);   // the darken pass relies on the icon having written its depth
        glClear(GL_DEPTH_BUFFER_BIT);

        glEnable(GL_TEXTURE_2D);
        Textures.bind(Chunk.getAtlasTexture());
        Textures.enableAlphaTest();
        Textures.enableAlphaBlending();

        tessellator.init();
        glTranslatef(centerX, centerY, 0.0F);

        // half the side of the square the icon can cover, for the darken quad
        float coverHalf = size * 1.5F;

        glPushMatrix();   // the icon's own transforms (scale / rotation) end at the flush below

        if (tile.isBillboard()) {
            float quadSize = size * BILLBOARD_SCALE * billboardScale;
            coverHalf = Math.max(coverHalf, quadSize * 0.5F * 1.05F);
            buildSpriteQuad(tessellator, tile, quadSize, cropSprites);
        } else {
            glScalef(size, -size, size);
            glRotatef(30.0F, 1.0F, 0.0F, 0.0F);
            glRotatef(-45.0F + spinDegrees, 0.0F, 1.0F, 0.0F);
            // The tile is drawn with its origin cell at (-1, -1, -1), so a 1x1x1 cube needs +0.5 to sit
            // on the centre. A multi-tile object (a 1x2x1 door...) spans its whole footprint from that
            // cell, so shift by half the footprint instead and it is centred as a whole.
            int iconData = tile.getIconData();
            int sizeX = tile.getSizeX(iconData);
            int sizeY = tile.getSizeY();
            int sizeZ = tile.getSizeZ(iconData);
            glTranslatef(1.0F - sizeX / 2.0F,
                    1.0F - sizeY / 2.0F,
                    1.0F - sizeZ / 2.0F);

            // a big multi-tile object is wider than the single cube the darken quad was sized for
            float footprint = (float) Math.sqrt(sizeX * sizeX + sizeZ * sizeZ);
            float reach = Math.max(footprint, footprint * 0.5F + sizeY * 0.866F) * 0.5F * 1.1F;
            coverHalf = Math.max(coverHalf, size * reach);

            // Icons ignore the day-night ambient (otherwise normal tiles are nearly black at night) and honour the
            // tile's self-cull faces (the cull faces set in TileRegistry) as if the cube sat inside a mass of the same
            // tile: the faces the camera can't see are the ones that touch it, so they are dropped. Only tiles that set
            // self-cull faces are affected.
            // Both are set on this renderer's own tessellator, not on the level or the Tile class, so a chunk being built
            // on a worker thread at the same moment never sees them.
            tessellator.setIconMode(true, hiddenFaces(spinDegrees));
            try {
                tile.render(tessellator, level, 0, -1, -1, -1, Chunk.getTotalAtlasSlots(), iconData);
            } finally {
                tessellator.setIconMode(false, 0);
            }
        }

        tessellator.flush();

        glPopMatrix();

        if (darken > 0.0F) {
            darkenIcon(coverHalf, Math.min(1.0F, darken));
        }

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);

        glPopAttrib();
    }

    // faces (bit per Tile.FACE_*) pointing away from the icon's camera, which looks down -z
    // the cube is turned by Ry(yaw) then tilted by Rx(pitch), a face is visible when its rotated normal has a positive z
    private static int hiddenFaces(float spinDegrees) {
        double yaw = Math.toRadians(ICON_YAW_DEGREES + spinDegrees);
        double pitch = Math.toRadians(ICON_PITCH_DEGREES);
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);

        int mask = 0;
        for (int face = 0; face < 6; face++) {
            int[] n = FACE_NORMALS[face];
            double turnedZ = -n[0] * sinYaw + n[2] * cosYaw;
            double z = n[1] * sinPitch + turnedZ * cosPitch;
            if (z < 1.0E-3) {
                mask |= 1 << face;
            }
        }
        return mask;
    }

    // translucent black quad over the icon, only where the icon is
    // the icon wrote its depth (the area around it kept the cleared 1.0), so a quad at depth 0.9 passes the GEQUAL test only on the icon's pixels
    // (projection is 100..300, icon sits at 200 = depth 0.5, z = -280 is depth 0.9)
    // the caller's glPopAttrib restores the depth func, so there is no need to query it here
    private static void darkenIcon(float half, float darken) {
        glDepthFunc(GL_GEQUAL);
        glDisable(GL_TEXTURE_2D);
        glColor4f(0.0F, 0.0F, 0.0F, darken);
        glBegin(GL_QUADS);
        glVertex3f(-half, -half, -80.0F);
        glVertex3f(half, -half, -80.0F);
        glVertex3f(half, half, -80.0F);
        glVertex3f(-half, half, -80.0F);
        glEnd();
        glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void buildSpriteQuad(Tessellator t, Tile tile, float size, boolean crop) {
        int slot = tile.getAtlasSlot(Tile.FACE_NORTH);

        // all in fractions of the slot's cell (0..1), turned into texture coordinates at the end
        float u0 = 0.0F;
        float uSpan = 1.0F;
        float v0 = 0.0F;
        float vSpan = 1.0F;

        // The sprite sits in the top-left of its cell, scaled up by a whole number (see
        // Textures.buildAtlas); crop to SPRITE_CROP_PIXELS of its own pixels inside that area
        // (or show all of it when crop is false).
        int atlas = Chunk.getAtlasTexture();
        int cell = Textures.getAtlasCellSize(atlas);
        int width = Textures.getAtlasSlotSourceWidth(atlas, slot);
        if (cell > 0 && width > 0) {
            float covered = width * Math.max(1, cell / width) / (float) cell;   // fraction of the cell the sprite fills
            float keep = crop
                    ? Math.min(SPRITE_CROP_PIXELS, width) / (float) width       // fraction of the sprite shown
                    : 1.0F;

            float spriteU = covered;
            uSpan = spriteU * keep;
            u0 += (spriteU - uSpan) / 2.0F;

            vSpan = covered * keep;
            v0 = (covered - vSpan) * SPRITE_CROP_VERTICAL;
        }

        float u1 = Chunk.atlasU(slot, u0 + uSpan);
        float v1 = Chunk.atlasV(slot, v0 + vSpan);
        u0 = Chunk.atlasU(slot, u0);
        v0 = Chunk.atlasV(slot, v0);
        float half = size / 2.0F;

        t.color(1.0F, 1.0F, 1.0F, 1.0F);
        t.texture(u0, v0);
        t.vertex(-half, -half, 0.0F);
        t.texture(u1, v0);
        t.vertex(half, -half, 0.0F);
        t.texture(u1, v1);
        t.vertex(half, half, 0.0F);
        t.texture(u0, v1);
        t.vertex(-half, half, 0.0F);
    }
}