package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.render.Font;
import org.lwjgl.input.Keyboard;

import static org.lwjgl.opengl.GL11.*;

public class PauseScreen extends Screen {

    private static final float PANEL_WIDTH = 300.0f;
    private static final float PANEL_HEIGHT = 240.0f;

    private static final float BUTTON_WIDTH = 260.0f;
    private static final float BUTTON_HEIGHT = 28.0f;

    private final RubyDung game;
    private final Font font;

    private final Button resumeButton = new Button("Resume Game", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button optionsButton = new Button("Options", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button saveButton = new Button("Save Game", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button quitButton = new Button("Quit to Title", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    private long saveMessageTicksRemaining = 0;

    public PauseScreen(RubyDung game, Font font) {
        this.game = game;
        this.font = font;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);

        glDisable(GL_TEXTURE_2D);
        glBegin(GL_QUADS);
        glColor4f(0.0f, 0.0f, 0.0f, 0.7f);
        glVertex2f(0, 0);
        glColor4f(0.0f, 0.0f, 0.0f, 0.7f);
        glVertex2f(this.width, 0);
        glColor4f(0.15f, 0.15f, 0.5f, 0.85f);
        glVertex2f(this.width, this.height);
        glColor4f(0.15f, 0.15f, 0.5f, 0.85f);
        glVertex2f(0, this.height);
        glEnd();
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        float panelX = (this.width - PANEL_WIDTH) / 2.0f;
        float panelY = (this.height - PANEL_HEIGHT) / 2.0f;

        if (this.saveMessageTicksRemaining > 0) {
            String savedMsg = "Level Saved!";
            int msgX = (int) (this.width - this.font.width(savedMsg)) / 2;
            int msgY = (int) panelY - 30;

            float alpha = 1.0F;
            if (this.saveMessageTicksRemaining < 30) {
                alpha = this.saveMessageTicksRemaining / 30.0F;
            }

            this.font.drawString(savedMsg, msgX, msgY, 0xFFFF00, alpha);
            this.saveMessageTicksRemaining--;
        }

        Gui2D.fillRect(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0.2f, 0.2f, 0.2f, 0.85f);

        String title = "Game Paused";
        this.font.drawString(title, (int) (this.width - this.font.width(title)) / 2,
                (int) panelY + 16, 0xFFFFFF);

        float buttonX = panelX + (PANEL_WIDTH - BUTTON_WIDTH) / 2.0f;

        this.resumeButton.setPosition(buttonX, panelY + 50);
        this.optionsButton.setPosition(buttonX, panelY + 90);
        this.saveButton.setPosition(buttonX, panelY + 130);
        this.quitButton.setPosition(buttonX, panelY + 170);

        this.resumeButton.render(this.font, mouseX, mouseY);
        this.optionsButton.render(this.font, mouseX, mouseY);
        this.saveButton.render(this.font, mouseX, mouseY);
        this.quitButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (this.resumeButton.isHovered(mouseX, mouseY)) {
            this.game.closeScreen();
        } else if (this.optionsButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new OptionsScreen(this.game));
        } else if (this.saveButton.isHovered(mouseX, mouseY)) {
            this.game.saveLevel();
            this.saveMessageTicksRemaining = 120;
        } else if (this.quitButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new TitleScreen(this.game));
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == Keyboard.KEY_ESCAPE) {
            this.game.closeScreen();
            return true;
        }
        return false;
    }
}