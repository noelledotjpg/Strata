package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Slider;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.KeyMap;

public class OptionsScreen extends Screen {

    private static final float SLIDER_WIDTH = 300.0f;
    private static final float SLIDER_HEIGHT = 28.0f;

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;
    private static final float BUTTON_SPACING = 10.0f;

    private final RubyDung game;
    private final Font font;

    private final Slider fovSlider = new Slider("FOV", SLIDER_WIDTH, SLIDER_HEIGHT,
            GameSettings.MIN_FOV, GameSettings.MAX_FOV, GameSettings.getFov(), true);

    private float lastSavedFov = GameSettings.getFov();

    private final Slider musicSlider = new Slider("Music", SLIDER_WIDTH, SLIDER_HEIGHT,
            0.0f, 100.0f, Math.round(GameSettings.getMusicVolume() * 100.0f), true);

    private float lastSavedMusicVolume = GameSettings.getMusicVolume();

    private final Button controlsButton = new Button("Controls", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button videoButton = new Button("Video Settings", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button doneButton = new Button("Done", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    public OptionsScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        String title = "Options";
        int titleWidth = this.font.width(title);
        this.font.drawString(title, (this.width - titleWidth) / 2, 40, 0xFFFFFF);

        float centerX = this.width / 2.0f;
        float buttonX = centerX - BUTTON_WIDTH / 2.0f;
        float y = 100.0f;

        this.fovSlider.setPosition(centerX - SLIDER_WIDTH / 2.0f, y);
        this.fovSlider.updateMouse(mouseX, mouseY);
        this.fovSlider.render(this.font, mouseX, mouseY);
        GameSettings.setFov(this.fovSlider.getValue());

        if (!this.fovSlider.isDragging() && GameSettings.getFov() != this.lastSavedFov) {
            GameSettings.save();
            this.lastSavedFov = GameSettings.getFov();
        }
        y += SLIDER_HEIGHT + BUTTON_SPACING;

        this.musicSlider.setPosition(centerX - SLIDER_WIDTH / 2.0f, y);
        this.musicSlider.updateMouse(mouseX, mouseY);
        this.musicSlider.render(this.font, mouseX, mouseY);
        GameSettings.setMusicVolume(this.musicSlider.getValue() / 100.0f);

        if (!this.musicSlider.isDragging() && GameSettings.getMusicVolume() != this.lastSavedMusicVolume) {
            GameSettings.save();
            this.lastSavedMusicVolume = GameSettings.getMusicVolume();
        }
        y += SLIDER_HEIGHT + BUTTON_SPACING * 2;

        this.controlsButton.setPosition(buttonX, y);
        this.controlsButton.render(this.font, mouseX, mouseY);
        y += BUTTON_HEIGHT + BUTTON_SPACING;

        this.videoButton.setPosition(buttonX, y);
        this.videoButton.render(this.font, mouseX, mouseY);
        y += BUTTON_HEIGHT + BUTTON_SPACING * 2;

        this.doneButton.setPosition(buttonX, y);
        this.doneButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        this.fovSlider.mouseClicked(mouseX, mouseY);
        this.musicSlider.mouseClicked(mouseX, mouseY);

        if (this.controlsButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new ControlsScreen(this.game));
        } else if (this.videoButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new VideoSettingsScreen(this.game));
        } else if (this.doneButton.isHovered(mouseX, mouseY)) {
            goBack();
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyMap.ESC) {
            goBack();
            return true;
        }
        return true;
    }

    private void goBack() {
        if (this.game.hasWorld()) {
            this.game.closeScreen();
        } else {
            this.game.openScreen(new TitleScreen(this.game));
        }
    }
}