package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class WorldListScreen extends Screen {

    private static final SimpleDateFormat LAST_PLAYED_FORMAT = new SimpleDateFormat("MMM d, HH:mm");

    private static final float ROW_WIDTH = 420.0f;
    private static final float ROW_HEIGHT = 32.0f;
    private static final float ROW_GAP = 4.0f;
    private static final float DELETE_ZONE_WIDTH = 70.0f;
    private static final int TOTAL_WORLD_SLOTS = 10;

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;
    private static final float BUTTON_SPACING = 8.0f;

    private final RubyDung game;
    private final Font font;

    private final Button newWorldButton = new Button("New World", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button backButton = new Button("Back", BUTTON_WIDTH, BUTTON_HEIGHT, true);

    private List<WorldManager.WorldInfo> worlds;
    private String pendingDelete = null;
    private float listStartY;

    public WorldListScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();
    }

    @Override
    public void init(int width, int height) {
        super.init(width, height);
        refresh();
    }

    private void refresh() {
        this.worlds = WorldManager.listWorlds();
        this.pendingDelete = null;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        String title = "Select World";
        int titleWidth = this.font.width(title);
        this.font.drawString(title, (this.width - titleWidth) / 2, 15, 0xFFFFFF);

        float rowX = (this.width - ROW_WIDTH) / 2.0f;
        this.listStartY = 45.0f;

        for (int i = 0; i < TOTAL_WORLD_SLOTS; i++) {
            float rowY = this.listStartY + i * (ROW_HEIGHT + ROW_GAP);
            if (i < this.worlds.size()) {
                drawWorldRow(rowX, rowY, this.worlds.get(i), mouseX, mouseY);
            } else {
                drawEmptyRow(rowX, rowY, i + 1);
            }
        }

        float buttonX = (this.width - BUTTON_WIDTH) / 2.0f;
        float buttonsY = this.listStartY + TOTAL_WORLD_SLOTS * (ROW_HEIGHT + ROW_GAP) + 10.0f;
        float backY = buttonsY + BUTTON_HEIGHT + BUTTON_SPACING;

        this.newWorldButton.setPosition(buttonX, buttonsY);
        this.backButton.setPosition(buttonX, backY);
        this.newWorldButton.render(this.font, mouseX, mouseY);
        this.backButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    private void drawWorldRow(float x, float y, WorldManager.WorldInfo world, int mouseX, int mouseY) {
        if (world.name.equals(this.pendingDelete)) {
            Gui2D.fillRect(x, y, ROW_WIDTH, ROW_HEIGHT, 0.35f, 0.1f, 0.1f, 0.9f);
            String prompt = "Are you sure? (Click again to confirm)";
            this.font.drawString(prompt, (int) (x + 8), (int) (y + 8), 0xFFAAAA);
            return;
        }

        float deleteZoneX = x + ROW_WIDTH - DELETE_ZONE_WIDTH;
        boolean deleteHovered = isInside(deleteZoneX, y, DELETE_ZONE_WIDTH, ROW_HEIGHT, mouseX, mouseY);
        boolean rowHovered = !deleteHovered && isInside(x, y, ROW_WIDTH, ROW_HEIGHT, mouseX, mouseY);

        float alpha = (rowHovered || deleteHovered) ? 0.45f : 0.3f;
        Gui2D.fillRect(x, y, ROW_WIDTH, ROW_HEIGHT, 0.0f, 0.0f, 0.0f, alpha);

        int nameColor = rowHovered ? 0xFFFF00 : 0xFFFFFF;
        this.font.drawString(world.name, (int) (x + 8), (int) (y + 8), nameColor);

        int nameWidth = this.font.width(world.name);
        String lastPlayed = "  (" + LAST_PLAYED_FORMAT.format(new Date(world.lastPlayed)) + ")";
        this.font.drawString(lastPlayed, (int) (x + 8 + nameWidth), (int) (y + 8), 0xAAAAAA);

        String deleteLabel = "Delete";
        int deleteWidth = this.font.width(deleteLabel);
        int deleteColor = deleteHovered ? 0xFFFF00 : 0xFF8888;
        this.font.drawString(deleteLabel, (int) (x + ROW_WIDTH - deleteWidth - 8), (int) (y + 8), deleteColor);
    }

    private void drawEmptyRow(float x, float y, int slotNum) {
        Gui2D.fillRect(x, y, ROW_WIDTH, ROW_HEIGHT, 0.0f, 0.0f, 0.0f, 0.15f);
        String emptyLabel = "- Empty World Slot " + slotNum + " -";
        int labelWidth = this.font.width(emptyLabel);
        this.font.drawString(emptyLabel, (int) (x + (ROW_WIDTH - labelWidth) / 2.0f), (int) (y + 8), 0x555555);
    }

    private boolean isInside(float x, float y, float w, float h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        float rowX = (this.width - ROW_WIDTH) / 2.0f;
        int visibleCount = Math.min(this.worlds.size(), TOTAL_WORLD_SLOTS);

        for (int i = 0; i < visibleCount; i++) {
            WorldManager.WorldInfo world = this.worlds.get(i);
            float rowY = this.listStartY + i * (ROW_HEIGHT + ROW_GAP);

            if (!isInside(rowX, rowY, ROW_WIDTH, ROW_HEIGHT, mouseX, mouseY)) {
                continue;
            }

            if (world.name.equals(this.pendingDelete)) {
                WorldManager.delete(world.name);
                refresh();
            } else if (isInside(rowX + ROW_WIDTH - DELETE_ZONE_WIDTH, rowY, DELETE_ZONE_WIDTH, ROW_HEIGHT, mouseX, mouseY)) {
                this.pendingDelete = world.name;
            } else {
                this.game.loadWorld(world.name);
            }
            return;
        }

        this.pendingDelete = null;

        if (this.newWorldButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new CreateWorldScreen(this.game));
        } else if (this.backButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new TitleScreen(this.game));
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyMap.ESC) {
            this.game.openScreen(new TitleScreen(this.game));
            return true;
        }
        return false;
    }
}