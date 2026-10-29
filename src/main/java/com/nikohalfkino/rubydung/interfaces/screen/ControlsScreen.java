package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.util.ArrayList;
import java.util.List;

public class ControlsScreen extends Screen {

    private static final float PANEL_WIDTH = 420.0f;
    private static final float ROW_HEIGHT = 30.0f;
    private static final float ROW_SPACING = 6.0f;
    private static final float REBIND_BUTTON_WIDTH = 120.0f;
    private static final float REBIND_BUTTON_HEIGHT = 26.0f;
    private static final float RESET_ZONE_WIDTH = 55.0f;

    private static final float BUTTON_WIDTH = 200.0f;
    private static final float BUTTON_HEIGHT = 30.0f;

    private static final int ROWS_PER_PAGE = 7;

    private static final float PAGE_BUTTON_WIDTH = 90.0f;
    private static final float PAGE_BUTTON_HEIGHT = 26.0f;

    private final RubyDung game;
    private final Font font;

    private final List<KeyBinding> bindings = new ArrayList<>(KeyBinding.all());
    private final List<Button> rebindButtons = new ArrayList<>();

    private final Button doneButton = new Button("Done", BUTTON_WIDTH, BUTTON_HEIGHT, true);
    private final Button prevPageButton = new Button("< Prev", PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, true);
    private final Button nextPageButton = new Button("Next >", PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, true);

    private KeyBinding listeningFor = null;

    private KeyBinding.Device pendingModifierDevice = null;
    private int pendingModifierCode;

    private int currentPage = 0;
    private final int totalPages;

    private float panelX;
    private float listY;

    public ControlsScreen(RubyDung game) {
        this.game = game;
        this.font = game.getFont();

        this.totalPages = Math.max(1, (this.bindings.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);

        for (KeyBinding binding : this.bindings) {
            this.rebindButtons.add(new Button(binding.getKeyName(), REBIND_BUTTON_WIDTH, REBIND_BUTTON_HEIGHT, true));
        }
    }

    private int pageStartIndex() {
        return this.currentPage * ROWS_PER_PAGE;
    }

    private int pageEndIndex() {
        return Math.min(pageStartIndex() + ROWS_PER_PAGE, this.bindings.size());
    }

    @Override
    public void render(int mouseX, int mouseY) {
        Gui2D.begin(this.width, this.height);
        MenuBackground.render(this.width, this.height);

        String title = "Controls";
        int titleWidth = this.font.width(title);
        this.font.drawString(title, (this.width - titleWidth) / 2, 40, 0xFFFFFF);

        this.panelX = (this.width - PANEL_WIDTH) / 2.0f;
        this.listY = 90.0f;

        int start = pageStartIndex();
        int end = pageEndIndex();

        for (int i = start; i < end; i++) {
            KeyBinding binding = this.bindings.get(i);
            Button rebindButton = this.rebindButtons.get(i);

            float rowY = this.listY + (i - start) * (ROW_HEIGHT + ROW_SPACING);

            float resetZoneX = this.panelX + PANEL_WIDTH - RESET_ZONE_WIDTH - 6.0f;
            boolean resetHovered = isInside(resetZoneX, rowY, RESET_ZONE_WIDTH, ROW_HEIGHT, mouseX, mouseY);
            float rebindButtonX = resetZoneX - REBIND_BUTTON_WIDTH - 6.0f;
            boolean rebindHovered = isInside(rebindButtonX, rowY, REBIND_BUTTON_WIDTH, ROW_HEIGHT, mouseX, mouseY);
            boolean rowHovered = !resetHovered && !rebindHovered && isInside(this.panelX, rowY, PANEL_WIDTH, ROW_HEIGHT, mouseX, mouseY);

            float alpha = (rowHovered || resetHovered) ? 0.45f : 0.3f;
            Gui2D.fillRect(this.panelX, rowY, PANEL_WIDTH, ROW_HEIGHT, 0.0f, 0.0f, 0.0f, alpha);

            int labelY = (int) (rowY + (ROW_HEIGHT - 16) / 2.0f);
            this.font.drawString(binding.getDisplayName(), (int) (this.panelX + 10.0f), labelY, 0xFFFFFF);

            String rebindLabel;
            if (this.listeningFor == binding) {
                rebindLabel = this.pendingModifierDevice != null
                        ? KeyBinding.describeBinding(this.pendingModifierDevice, this.pendingModifierCode) + " + ..."
                        : "> Press <";
            } else {
                rebindLabel = binding.getKeyName();
            }

            rebindButton.setPosition(rebindButtonX, rowY + (ROW_HEIGHT - REBIND_BUTTON_HEIGHT) / 2.0f);
            rebindButton.setLabel(rebindLabel);
            rebindButton.setSelected(this.listeningFor == binding);
            rebindButton.render(this.font, mouseX, mouseY);

            String resetLabel = "Reset";
            int resetWidth = this.font.width(resetLabel);
            int resetColor = resetHovered ? 0xFFFF00 : 0xFF8888;
            this.font.drawString(resetLabel, (int) (resetZoneX + (RESET_ZONE_WIDTH - resetWidth) / 2.0f), labelY, resetColor);
        }

        float paginationY = this.listY + ROWS_PER_PAGE * (ROW_HEIGHT + ROW_SPACING) + 16.0f;

        this.prevPageButton.setPosition(this.panelX, paginationY);
        this.prevPageButton.render(this.font, mouseX, mouseY);

        String pageLabel = "Page " + (this.currentPage + 1) + " / " + this.totalPages;
        int pageLabelWidth = this.font.width(pageLabel);
        this.font.drawString(pageLabel,
                (int) (this.panelX + PANEL_WIDTH / 2.0f - pageLabelWidth / 2.0f),
                (int) (paginationY + (PAGE_BUTTON_HEIGHT - 8) / 2.0f), 0xFFFFFF);

        this.nextPageButton.setPosition(this.panelX + PANEL_WIDTH - PAGE_BUTTON_WIDTH, paginationY);
        this.nextPageButton.render(this.font, mouseX, mouseY);

        float doneY = paginationY + PAGE_BUTTON_HEIGHT + 16.0f;
        this.doneButton.setPosition((this.width - BUTTON_WIDTH) / 2.0f, doneY);
        this.doneButton.render(this.font, mouseX, mouseY);

        Gui2D.end();
    }

    private boolean isInside(float x, float y, float w, float h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        int start = pageStartIndex();
        int end = pageEndIndex();

        for (int i = start; i < end; i++) {
            KeyBinding binding = this.bindings.get(i);
            float rowY = this.listY + (i - start) * (ROW_HEIGHT + ROW_SPACING);

            if (!isInside(this.panelX, rowY, PANEL_WIDTH, ROW_HEIGHT, mouseX, mouseY)) {
                continue;
            }

            float resetZoneX = this.panelX + PANEL_WIDTH - RESET_ZONE_WIDTH - 6.0f;
            float rebindButtonX = resetZoneX - REBIND_BUTTON_WIDTH - 6.0f;

            if (isInside(resetZoneX, rowY, RESET_ZONE_WIDTH, ROW_HEIGHT, mouseX, mouseY)) {
                binding.resetToDefault();
                KeyBinding.save();
            } else if (isInside(rebindButtonX, rowY, REBIND_BUTTON_WIDTH, ROW_HEIGHT, mouseX, mouseY)) {
                this.listeningFor = binding;
                this.pendingModifierDevice = null;
            }
            return;
        }

        if (this.prevPageButton.isHovered(mouseX, mouseY)) {
            if (this.currentPage > 0) {
                this.currentPage--;
            } else {
                this.currentPage = Math.min(2, this.totalPages - 1);
            }
            return;
        }

        if (this.nextPageButton.isHovered(mouseX, mouseY)) {
            if (this.currentPage < this.totalPages - 1) {
                this.currentPage++;
            } else {
                this.currentPage = 0;
            }
            return;
        }

        if (this.doneButton.isHovered(mouseX, mouseY)) {
            this.game.openScreen(new OptionsScreen(this.game));
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (this.listeningFor != null) {
            if (this.pendingModifierDevice != null) {
                this.listeningFor.setBinding(this.pendingModifierDevice, this.pendingModifierCode,
                        KeyBinding.Device.MOUSE, button);
            } else {
                this.listeningFor.setBinding(KeyBinding.Device.MOUSE, button);
            }
            this.pendingModifierDevice = null;
            this.listeningFor = null;
            KeyBinding.save();
            return;
        }

        if (button == 0) {
            mouseClicked(mouseX, mouseY);
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (this.listeningFor != null) {
            if (key == KeyMap.ESC) {
                this.pendingModifierDevice = null;
                this.listeningFor = null;
                return true;
            }

            if (isModifierKey(key) && this.pendingModifierDevice == null) {
                this.pendingModifierDevice = KeyBinding.Device.KEYBOARD;
                this.pendingModifierCode = key;
                return true;
            }

            if (this.pendingModifierDevice != null) {
                this.listeningFor.setBinding(this.pendingModifierDevice, this.pendingModifierCode,
                        KeyBinding.Device.KEYBOARD, key);
            } else {
                this.listeningFor.setKey(key);
            }

            this.pendingModifierDevice = null;
            this.listeningFor = null;
            KeyBinding.save();
            return true;
        }

        if (key == KeyMap.ESC) {
            this.game.openScreen(new OptionsScreen(this.game));
            return true;
        }

        return true;
    }

    private static boolean isModifierKey(int key) {
        return key == KeyMap.LCONTROL || key == KeyMap.RCONTROL
                || key == KeyMap.LSHIFT || key == KeyMap.RSHIFT
                || key == KeyMap.LMENU || key == KeyMap.RMENU
                || key == KeyMap.LWIN || key == KeyMap.RWIN;
    }
}