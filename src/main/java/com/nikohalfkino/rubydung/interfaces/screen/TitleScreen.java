package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.render.Font;

public class TitleScreen extends Screen {

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;
    private static final float BUTTON_SPACING = 10.0f;

    private static final float LOGO_WIDTH = 249.0f * 2;
    private static final float LOGO_HEIGHT = 55.0f * 2;

    private final RubyDung game;
    private final Font font;

    private final Button playButton = new Button("Play", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button optionsButton = new Button("Options", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button quitButton = new Button("Quit", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    public TitleScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        float logoX = (this.width - LOGO_WIDTH) / 2.0f;
        float logoY = this.height * 0.25f;

        Gui2D.drawTexture("/assets/rubydung/textures/gui/logo.png", logoX, logoY, LOGO_WIDTH, LOGO_HEIGHT);

        float buttonX = (this.width - BUTTON_WIDTH) / 2.0f;
        float playY = this.height * 0.5f;
        float optionsY = playY + BUTTON_HEIGHT + BUTTON_SPACING;
        float quitY = optionsY + BUTTON_HEIGHT + BUTTON_SPACING;

        this.playButton.setPosition(buttonX, playY);
        this.optionsButton.setPosition(buttonX, optionsY);
        this.quitButton.setPosition(buttonX, quitY);
        this.playButton.render(this.font, mouseX, mouseY);
        this.optionsButton.render(this.font, mouseX, mouseY);
        this.quitButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (this.playButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new WorldListScreen(this.game));
        } else if (this.optionsButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new OptionsScreen(this.game));
        } else if (this.quitButton.isHovered(mouseX, mouseY)) {
            this.game.quit();
        }
    }
}