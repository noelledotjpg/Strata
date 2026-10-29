package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.interfaces.HudElement;
import com.nikohalfkino.rubydung.render.Font;

import static org.lwjgl.opengl.GL11.*;

public class CrosshairHud implements HudElement {

    private static final float SIZE = 2.0f;

    @Override
    public void render(Font font, int width, int height) {
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;

        glDisable(GL_TEXTURE_2D);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR);
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        glBegin(GL_QUADS);
        glVertex2f(centerX - SIZE, centerY - SIZE);
        glVertex2f(centerX + SIZE, centerY - SIZE);
        glVertex2f(centerX + SIZE, centerY + SIZE);
        glVertex2f(centerX - SIZE, centerY + SIZE);
        glEnd();

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_BLEND);
    }
}