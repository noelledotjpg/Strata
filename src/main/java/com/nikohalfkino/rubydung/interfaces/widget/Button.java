package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;

public class Button {

    private static final String DEFAULT_9SLICE_TEXTURE = "/assets/rubydung/textures/gui/button.png";
    private static final int TEXT_HEIGHT = 8;

    private final float width;
    private final float height;

    private String label;
    private float x;
    private float y;
    private boolean selected = false;

    /**
     * Whether this button responds to clicks and renders at full strength. False dims both the
     * button texture/background and its label - see {@code VideoSettingsScreen}'s FPS Cap
     * button, disabled while VSync is on since VSync already caps the frame rate to the
     * display's refresh rate. Callers are responsible for actually skipping the click action
     * too (see {@link #isEnabled()}) - this flag only controls rendering and hover styling.
     */
    private boolean enabled = true;

    private boolean useNineSlice = false;
    private String texturePath = DEFAULT_9SLICE_TEXTURE;

    public Button(String label, float width, float height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public Button(String label, float width, float height, boolean useNineSlice) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.useNineSlice = useNineSlice;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return this.selected;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setUseNineSlice(boolean useNineSlice) {
        this.useNineSlice = useNineSlice;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    public void render(Font font, int mouseX, int mouseY) {
        boolean hovered = this.enabled && isHovered(mouseX, mouseY);

        // Dims the texture/background when disabled - see #enabled's javadoc. Doesn't affect
        // the outline below, which is its own always-full-strength draw either way, since a
        // disabled button is never meant to look "selected" in the first place.
        float buttonBrightness = this.enabled ? 1.0f : 0.35f;

        if (this.useNineSlice) {
            Gui2D.drawNineSlice(this.texturePath, this.x, this.y, this.width, this.height, 20, 20, 6, buttonBrightness);

            if (this.selected) {
                // 2px thick outline only on the bottom face
                Gui2D.fillRect(this.x, this.y + this.height - 2.0f, this.width, 2.0f, 1.0f, 1.0f, 0.4f, 1.0f);
            }
        } else {
            float alpha = this.selected ? 0.55f : (hovered ? 0.45f : 0.3f);
            if (!this.enabled) {
                // This path is a flat black fill rather than a texture, so there's no RGB to
                // darken - fall back to fading it out instead.
                alpha *= 0.5f;
            }
            Gui2D.fillRect(this.x, this.y, this.width, this.height, 0.0f, 0.0f, 0.0f, alpha);

            if (this.selected) {
                // 2px thick outline only on the bottom face
                Gui2D.fillRect(this.x, this.y + this.height - 2.0f, this.width, 2.0f, 1.0f, 1.0f, 0.4f, 1.0f);
            }
        }

        int textWidth = font.width(this.label);
        int scaledTextHeight = TEXT_HEIGHT * 2;

        int drawX = (int) (this.x + (this.width - textWidth) / 2.0f);
        int drawY = (int) (this.y + (this.height - scaledTextHeight) / 2.0f);

        int color;
        if (!this.enabled) {
            color = 0x888888; // dimmed grey, distinct from the normal white/hover-yellow
        } else {
            color = (hovered || this.selected) ? 0xFFFF00 : 0xFFFFFF;
        }
        font.drawString(this.label, drawX, drawY, color);
    }
}