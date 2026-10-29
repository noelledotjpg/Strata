package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.TextField;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;

public class CreateWorldScreen extends Screen {

    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_SEED_LENGTH = 32;

    private static final float PANEL_WIDTH = 420.0f;
    private static final float FIELD_HEIGHT = 28.0f;

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;
    private static final float BUTTON_SPACING = 8.0f;

    private static final float SIZE_BUTTON_GAP = 12.0f;
    private static final float SIZE_BUTTON_WIDTH = (PANEL_WIDTH - SIZE_BUTTON_GAP * 3) / 4.0f;

    private final RubyDung game;
    private final Font font;
    private final TextField nameField = new TextField(MAX_NAME_LENGTH);

    private final TextField seedField = new TextField(MAX_SEED_LENGTH);

    private TextField focusedField = this.nameField;

    private final Button flatButton = new Button("Flat", 200.0f, BUTTON_HEIGHT, true);
    private final Button naturalButton = new Button("Natural", 200.0f, BUTTON_HEIGHT, true);

    private final Button tinySizeButton = new Button("Tiny", SIZE_BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button defaultSizeButton = new Button("Default", SIZE_BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button largeSizeButton = new Button("Large", SIZE_BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button insaneSizeButton = new Button("XL", SIZE_BUTTON_WIDTH, BUTTON_HEIGHT, true);

    private final Button createButton = new Button("Create", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button cancelButton = new Button("Cancel", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    private LevelGenerator.WorldType worldType = LevelGenerator.WorldType.FLAT;
    private LevelGenerator.WorldSize worldSize = LevelGenerator.WorldSize.DEFAULT;
    private String error = null;

    private float panelX;
    private float fieldY;
    private float sizeY;
    private float seedY;

    public CreateWorldScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();
        this.flatButton.setSelected(true);
        this.defaultSizeButton.setSelected(true);
    }

    @Override
    public void init(int width, int height) {
        super.init(width, height);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onClose() {
        Keyboard.enableRepeatEvents(false);
    }

    private boolean showSeedField() {
        return this.worldType == LevelGenerator.WorldType.NATURAL;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        String title = "New World";
        int titleWidth = this.font.width(title);
        this.font.drawString(title, (this.width - titleWidth) / 2, 40, 0xFFFFFF);

        this.panelX = (this.width - PANEL_WIDTH) / 2.0f;
        this.fieldY = 90.0f;

        Gui2D.fillRect(this.panelX, this.fieldY, PANEL_WIDTH, FIELD_HEIGHT, 0.0f, 0.0f, 0.0f, 0.45f);

        this.nameField.updateMouse(mouseX, mouseY, (int) (this.panelX + 8), (int) (this.fieldY + 8), this.font, "Name: ");
        this.nameField.render(this.font, (int) (this.panelX + 8), (int) (this.fieldY + 8), 0xFFFFFF, "Name: ");

        float typeY = this.fieldY + FIELD_HEIGHT + 16.0f;
        float gap = 20.0f;
        float sideBtnWidth = (PANEL_WIDTH - gap) / 2.0f;

        this.flatButton.setPosition(this.panelX, typeY);
        this.naturalButton.setPosition(this.panelX + sideBtnWidth + gap, typeY);

        this.sizeY = typeY + BUTTON_HEIGHT + 12.0f;
        float sizeStep = SIZE_BUTTON_WIDTH + SIZE_BUTTON_GAP;
        this.tinySizeButton.setPosition(this.panelX, this.sizeY);
        this.defaultSizeButton.setPosition(this.panelX + sizeStep, this.sizeY);
        this.largeSizeButton.setPosition(this.panelX + sizeStep * 2, this.sizeY);
        this.insaneSizeButton.setPosition(this.panelX + sizeStep * 3, this.sizeY);

        float afterTypeY = this.sizeY + BUTTON_HEIGHT + 16.0f;
        if (showSeedField()) {
            this.seedY = afterTypeY;

            Gui2D.fillRect(this.panelX, this.seedY, PANEL_WIDTH, FIELD_HEIGHT, 0.0f, 0.0f, 0.0f, 0.45f);

            this.seedField.updateMouse(mouseX, mouseY, (int) (this.panelX + 8), (int) (this.seedY + 8), this.font, "Seed: ");
            this.seedField.render(this.font, (int) (this.panelX + 8), (int) (this.seedY + 8), 0xFFFFFF, "Seed: ");

            afterTypeY = this.seedY + FIELD_HEIGHT + 16.0f;
        }

        float createY = afterTypeY;
        float cancelY = createY + BUTTON_HEIGHT + BUTTON_SPACING;

        float buttonX = (this.width - BUTTON_WIDTH) / 2.0f;
        this.createButton.setPosition(buttonX, createY);
        this.cancelButton.setPosition(buttonX, cancelY);

        this.flatButton.render(this.font, mouseX, mouseY);
        this.naturalButton.render(this.font, mouseX, mouseY);
        this.tinySizeButton.render(this.font, mouseX, mouseY);
        this.defaultSizeButton.render(this.font, mouseX, mouseY);
        this.largeSizeButton.render(this.font, mouseX, mouseY);
        this.insaneSizeButton.render(this.font, mouseX, mouseY);
        this.createButton.render(this.font, mouseX, mouseY);
        this.cancelButton.render(this.font, mouseX, mouseY);

        if (this.error != null) {
            int errorWidth = this.font.width(this.error);
            this.font.drawString(this.error, (this.width - errorWidth) / 2, (int) (cancelY + BUTTON_HEIGHT + 12.0f), 0xFF5555);
        }

        Gui2D.end();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (mouseX >= this.panelX && mouseX <= this.panelX + PANEL_WIDTH && mouseY >= this.fieldY && mouseY <= this.fieldY + FIELD_HEIGHT) {
            this.focusedField = this.nameField;
            this.nameField.mouseClicked(mouseX, mouseY, (int) (this.panelX + 8), (int) (this.fieldY + 8), this.font, "Name: ");
            return;
        }

        if (showSeedField() && mouseX >= this.panelX && mouseX <= this.panelX + PANEL_WIDTH
                && mouseY >= this.seedY && mouseY <= this.seedY + FIELD_HEIGHT) {
            this.focusedField = this.seedField;
            this.seedField.mouseClicked(mouseX, mouseY, (int) (this.panelX + 8), (int) (this.seedY + 8), this.font, "Seed: ");
            return;
        }

        if (this.flatButton.isHovered(mouseX, mouseY)) {
            selectType(LevelGenerator.WorldType.FLAT);
        } else if (this.naturalButton.isHovered(mouseX, mouseY)) {
            selectType(LevelGenerator.WorldType.NATURAL);
        } else if (this.tinySizeButton.isHovered(mouseX, mouseY)) {
            selectSize(LevelGenerator.WorldSize.TINY);
        } else if (this.defaultSizeButton.isHovered(mouseX, mouseY)) {
            selectSize(LevelGenerator.WorldSize.DEFAULT);
        } else if (this.largeSizeButton.isHovered(mouseX, mouseY)) {
            selectSize(LevelGenerator.WorldSize.LARGE);
        } else if (this.insaneSizeButton.isHovered(mouseX, mouseY)) {
            selectSize(LevelGenerator.WorldSize.INSANE);
        } else if (this.createButton.isHovered(mouseX, mouseY)) {
            tryCreate();
        } else if (this.cancelButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new WorldListScreen(this.game));
        }
    }

    private void selectType(LevelGenerator.WorldType type) {
        this.worldType = type;
        this.flatButton.setSelected(type == LevelGenerator.WorldType.FLAT);
        this.naturalButton.setSelected(type == LevelGenerator.WorldType.NATURAL);

        if (!showSeedField() && this.focusedField == this.seedField) {
            this.focusedField = this.nameField;
        }
    }

    private void selectSize(LevelGenerator.WorldSize size) {
        this.worldSize = size;
        this.tinySizeButton.setSelected(size == LevelGenerator.WorldSize.TINY);
        this.defaultSizeButton.setSelected(size == LevelGenerator.WorldSize.DEFAULT);
        this.largeSizeButton.setSelected(size == LevelGenerator.WorldSize.LARGE);
        this.insaneSizeButton.setSelected(size == LevelGenerator.WorldSize.INSANE);
    }

    private void tryCreate() {
        String name = WorldManager.sanitize(this.nameField.getText());

        if (name.isEmpty()) {
            this.error = "Enter a world name";
            return;
        }
        if (WorldManager.exists(name)) {
            this.error = "A world named \"" + name + "\" already exists";
            return;
        }

        String seedText = showSeedField() ? this.seedField.getText().trim() : "";
        if (seedText.isEmpty()) {
            this.game.startNewWorld(name, this.worldType, this.worldSize);
        } else {
            this.game.startNewWorld(name, this.worldType, LevelGenerator.parseSeed(seedText), this.worldSize);
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyMap.ESC) {
            this.game.openScreen(new WorldListScreen(this.game));
            return true;
        }
        if (key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER) {
            tryCreate();
            return true;
        }
        if (key == KeyMap.TAB && showSeedField()) {
            this.focusedField = (this.focusedField == this.nameField) ? this.seedField : this.nameField;
            return true;
        }

        this.focusedField.keyTyped(key, character);
        return true;
    }
}