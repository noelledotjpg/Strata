package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Toggle;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.opengl.Display;

public class VideoSettingsScreen extends Screen {

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;
    private static final float ROW_SPACING = 14.0f;
    private static final float TOGGLE_ROW_HEIGHT = 16.0f;

    private final RubyDung game;
    private final Font font;

    private final Button fpsCapButton = new Button(fpsCapLabel(), BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Toggle vsyncToggle = new Toggle("VSync", GameSettings.isVsyncEnabled(), Toggle.LabelSide.LEFT);
    private final Toggle showFpsToggle = new Toggle("Show FPS", GameSettings.isShowFpsInHud(), Toggle.LabelSide.LEFT);

    private final Button doneButton = new Button("Done", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    public VideoSettingsScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();
    }

    private static String fpsCapLabel() {
        int cap = GameSettings.getFpsCap();
        return "FPS Cap: " + (cap == GameSettings.UNCAPPED_FPS ? "Unlimited" : String.valueOf(cap));
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        String title = "Video Settings";
        int titleWidth = this.font.width(title);
        this.font.drawString(title, (this.width - titleWidth) / 2, 40, 0xFFFFFF);

        float centerX = this.width / 2.0f;
        float buttonX = centerX - BUTTON_WIDTH / 2.0f;
        float y = 100.0f;

        this.fpsCapButton.setEnabled(!this.vsyncToggle.getValue());
        this.fpsCapButton.setPosition(buttonX, y);
        this.fpsCapButton.setLabel(fpsCapLabel());
        this.fpsCapButton.render(this.font, mouseX, mouseY);
        y += BUTTON_HEIGHT + ROW_SPACING;

        float maxLabelWidth = Math.max(
                this.vsyncToggle.getLabelWidth(this.font),
                this.showFpsToggle.getLabelWidth(this.font)
        );

        this.vsyncToggle.setPosition(centerX, y, maxLabelWidth, BUTTON_WIDTH);
        this.vsyncToggle.render(this.font, mouseX, mouseY);
        y += TOGGLE_ROW_HEIGHT + ROW_SPACING;

        this.showFpsToggle.setPosition(centerX, y, maxLabelWidth, BUTTON_WIDTH);
        this.showFpsToggle.render(this.font, mouseX, mouseY);
        y += TOGGLE_ROW_HEIGHT + ROW_SPACING * 1.5f;

        this.doneButton.setPosition(buttonX, y);
        this.doneButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (this.fpsCapButton.isHovered(mouseX, mouseY) && this.fpsCapButton.isEnabled()) {
            GameSettings.cycleFpsCap();
            GameSettings.save();
        } else if (this.vsyncToggle.isHovered(this.font, mouseX, mouseY)) {
            this.vsyncToggle.mouseClicked(this.font, mouseX, mouseY);
            GameSettings.setVsyncEnabled(this.vsyncToggle.getValue());
            Display.setVSyncEnabled(this.vsyncToggle.getValue());
            GameSettings.save();
        } else if (this.showFpsToggle.isHovered(this.font, mouseX, mouseY)) {
            this.showFpsToggle.mouseClicked(this.font, mouseX, mouseY);
            GameSettings.setShowFpsInHud(this.showFpsToggle.getValue());
            GameSettings.save();
        } else if (this.doneButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new OptionsScreen(this.game));
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyMap.ESC) {
            this.game.openScreen(new OptionsScreen(this.game));
            return true;
        }
        return true;
    }
}