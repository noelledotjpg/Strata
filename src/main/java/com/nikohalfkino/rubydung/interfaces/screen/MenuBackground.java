package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Textures;

import static org.lwjgl.opengl.GL11.*;

final class MenuBackground {

    private static final String TEXTURE_PATH = "/assets/rubydung/textures/gui/background.png";
    private static final float TILE_SIZE = 128.0f;

    private static int textureId = -1;

    private MenuBackground() {
    }

    static void render(int width, int height) {
        if (textureId == -1) {
            textureId = Textures.loadTiledTexture(TEXTURE_PATH);
        }

        glEnable(GL_TEXTURE_2D);
        Textures.bind(textureId);

        glColor4f(0.55f, 0.55f, 0.55f, 1.0f);

        float uMax = width / TILE_SIZE;
        float vMax = height / TILE_SIZE;

        glBegin(GL_QUADS);
        glTexCoord2f(0, 0);
        glVertex2f(0, 0);
        glTexCoord2f(uMax, 0);
        glVertex2f(width, 0);
        glTexCoord2f(uMax, vMax);
        glVertex2f(width, height);
        glTexCoord2f(0, vMax);
        glVertex2f(0, height);
        glEnd();

        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        glDisable(GL_TEXTURE_2D);
    }
}