package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.HudElement;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.level.Tiles.TreeTile;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import static org.lwjgl.opengl.GL11.*;

public class SelectedTileHud implements HudElement {

    private static final float ICON_SIZE = 32.0F;

    private static final float ICON_TOP_MARGIN = 24.0F;

    private static final float BILLBOARD_ICON_SIZE = 44.0F;

    private final RubyDung game;
    private final Tessellator iconTessellator = new Tessellator();

    public SelectedTileHud(RubyDung game) {
        this.game = game;
    }

    @Override
    public void render(Font font, int width, int height) {
        Tile tile = this.game.getSelectedTile();

        String name = (tile instanceof TreeTile) ? "Tree" : formatTileName(tile.name);

        String prefix = "Selected: ";
        float scale = 2.0F;

        font.drawStringScaled(prefix, 10, 10, 0xFFFFFF, scale);

        int prefixWidth = (int) (font.getWidth(prefix) * scale);

        font.drawStringScaled(name, 10 + prefixWidth, 10, 0xFFFF00, scale);

        int lastLineY = 10;

        if (tile instanceof TreeTile) {
            TreeTile treeTile = (TreeTile) tile;

            String typePrefix = "Type: ";
            String typeValue = treeTile.getType().displayName;

            int secondLineY = 10 + (int) (12 * scale);

            font.drawStringScaled(typePrefix, 10, secondLineY, 0xFFFFFF, scale);

            int typePrefixWidth = (int) (font.getWidth(typePrefix) * scale);

            font.drawStringScaled(typeValue, 10 + typePrefixWidth, secondLineY, 0xFFFF00, scale);

            lastLineY = secondLineY;
        }

        int iconTopY = (int) (lastLineY + 12 * scale + ICON_TOP_MARGIN);
        renderSelectedTileIcon(tile, iconTopY, width, height);
    }

    private void renderSelectedTileIcon(Tile tile, int iconTopY, int screenWidth, int screenHeight) {
        Level level = this.game.getLevel();

        float iconCenterX = 10.0F + ICON_SIZE / 2.0F;
        float iconCenterY = iconTopY + ICON_SIZE / 2.0F;

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, screenWidth, screenHeight, 0.0, 100.0, 300.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(0.0F, 0.0F, -200.0F);

        glEnable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT);

        glEnable(GL_TEXTURE_2D);
        Textures.bind(Chunk.getAtlasTexture());

        Textures.enableAlphaTest();
        Textures.enableAlphaBlending();

        this.iconTessellator.init();

        if (tile.isBillboard()) {
            glTranslatef(iconCenterX, iconCenterY, 0.0F);
            buildSpriteQuad(this.iconTessellator, tile, BILLBOARD_ICON_SIZE);
        } else {
            glTranslatef(iconCenterX, iconCenterY, 0.0F);
            glScalef(ICON_SIZE, -ICON_SIZE, ICON_SIZE);
            glRotatef(30.0F, 1.0F, 0.0F, 0.0F);
            glRotatef(-45.0F, 0.0F, 1.0F, 0.0F);
            glTranslatef(0.5F, 0.5F, 0.5F);
            tile.render(this.iconTessellator, level, 0, -1, -1, -1, Chunk.getTotalAtlasSlots());
        }

        this.iconTessellator.flush();

        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private static void buildSpriteQuad(Tessellator t, Tile tile, float size) {
        int slots = Chunk.getTotalAtlasSlots();
        float u0 = tile.getAtlasSlot(Tile.FACE_NORTH) / (float) slots;
        float u1 = u0 + 1.0F / slots;
        float half = size / 2.0F;

        t.color(1.0F, 1.0F, 1.0F, 1.0F);
        t.texture(u0, 0.0F);
        t.vertex(-half, -half, 0.0F);
        t.texture(u1, 0.0F);
        t.vertex(half, -half, 0.0F);
        t.texture(u1, 1.0F);
        t.vertex(half, half, 0.0F);
        t.texture(u0, 1.0F);
        t.vertex(-half, half, 0.0F);
    }

    private String formatTileName(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "";
        }

        String[] words = rawName.split("_");
        StringBuilder formattedName = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                formattedName.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase());

                if (i < words.length - 1) {
                    formattedName.append(" ");
                }
            }
        }

        return formattedName.toString();
    }
}