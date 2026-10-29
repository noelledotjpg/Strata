package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;

/**
 * An on/off switch drawn as one of two whole-sprite textures ({@code toggle_on.png} / {@code
 * toggle_off.png}, both native 16x16 - no 9-slice needed since, unlike a {@link Button}, a
 * toggle is never stretched to an arbitrary size) with a text label beside it.
 */
public class Toggle {

    private static final String ON_TEXTURE = "/assets/rubydung/textures/gui/toggle_on.png";
    private static final String OFF_TEXTURE = "/assets/rubydung/textures/gui/toggle_off.png";
    private static final float SPRITE_SIZE = 32.0f;
    private static final float LABEL_GAP = 12.0f;
    private static final int LABEL_HEIGHT = 16;

    public enum LabelSide {
        LEFT, RIGHT
    }

    private final String label;
    private final LabelSide labelSide;

    private float x;
    private float y;
    private float customTotalWidth = -1.0f;
    private float customMaxLabelWidth = -1.0f;
    private boolean value;

    public Toggle(String label, boolean initialValue, LabelSide labelSide) {
        this.label = label;
        this.labelSide = labelSide;
        this.value = initialValue;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        this.customTotalWidth = -1.0f;
        this.customMaxLabelWidth = -1.0f;
    }

    public void setPosition(float centerX, float y, float maxLabelWidth, float totalWidth) {
        this.y = y;
        this.customMaxLabelWidth = maxLabelWidth;
        this.customTotalWidth = totalWidth;
        this.x = centerX - totalWidth / 2.0f;
    }

    public boolean getValue() {
        return this.value;
    }

    public void setValue(boolean value) {
        this.value = value;
    }

    public float getLabelWidth(Font font) {
        return font.width(this.label);
    }

    public float getWidth(Font font) {
        return SPRITE_SIZE + LABEL_GAP + font.width(this.label);
    }

    /** Hit test covers the sprite and the label both, so clicking the text also toggles it. */
    public boolean isHovered(Font font, int mouseX, int mouseY) {
        float totalWidth = customTotalWidth >= 0 ? customTotalWidth : getWidth(font);
        return mouseX >= this.x && mouseX <= this.x + totalWidth
                && mouseY >= this.y && mouseY <= this.y + SPRITE_SIZE;
    }

    public void mouseClicked(Font font, int mouseX, int mouseY) {
        if (isHovered(font, mouseX, mouseY)) {
            this.value = !this.value;
        }
    }

    public void render(Font font, int mouseX, int mouseY) {
        float spriteX;
        float labelX;

        if (customMaxLabelWidth >= 0 && this.labelSide == LabelSide.LEFT) {
            // Text left-aligned at the start of the row, toggle button aligned to the right edge
            labelX = this.x;
            spriteX = this.x + customTotalWidth - SPRITE_SIZE;
        } else {
            spriteX = this.labelSide == LabelSide.LEFT
                    ? this.x + font.width(this.label) + LABEL_GAP
                    : this.x;
            labelX = this.labelSide == LabelSide.LEFT
                    ? this.x
                    : this.x + SPRITE_SIZE + LABEL_GAP;
        }

        String texture = this.value ? ON_TEXTURE : OFF_TEXTURE;
        Gui2D.drawTexture(texture, spriteX, this.y, SPRITE_SIZE, SPRITE_SIZE);

        // Draw a 2px thick grey horizontal line between the label and the toggle button
        if (customMaxLabelWidth >= 0 && this.labelSide == LabelSide.LEFT) {
            float lineStartX = labelX + font.width(this.label) + 6.0f;
            float lineEndX = spriteX - 6.0f;
            if (lineEndX > lineStartX) {
                float lineHeight = 2.0f;
                float lineY = this.y + (SPRITE_SIZE - lineHeight) / 2.0f;
                Gui2D.fillRect(lineStartX, lineY, lineEndX - lineStartX, lineHeight, 0.5f, 0.5f, 0.5f, 1.0f);
            }
        }

        boolean hovered = isHovered(font, mouseX, mouseY);
        int color = hovered ? 0xFFFF00 : 0xFFFFFF;
        int labelY = (int) (this.y + (SPRITE_SIZE - LABEL_HEIGHT) / 2.0f);
        font.drawString(this.label, (int) labelX, labelY, color);
    }
}