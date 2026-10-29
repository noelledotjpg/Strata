package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.render.Font;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class Hud {

    private final List<HudElement> elements = new ArrayList<>();

    public void register(HudElement element) {
        this.elements.add(element);
    }

    public void render(Font font, int width, int height) {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        for (HudElement element : this.elements) {
            element.render(font, width, height);
        }

        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
    }
}