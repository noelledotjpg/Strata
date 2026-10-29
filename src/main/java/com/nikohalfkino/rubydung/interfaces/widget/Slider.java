package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import org.lwjgl.input.Mouse;

/**
 * A horizontal slider for picking a value in a range. Drawn as a 9-slice track ({@code
 * slider_track.png}, same 20x20/border-6 layout {@link Button} uses for {@code button.png})
 * with a small square thumb ({@code slider_thumb.png}, 8x8 source art, drawn 2x like everything
 * else in this UI) riding along it.
 * <p>
 * Dragging follows the same self-polling pattern {@code TextField} uses for click-and-drag
 * selection: since {@link com.nikohalfkino.rubydung.interfaces.Screen} has no {@code
 * mouseDragged}/{@code mouseReleased} callback to hook, {@link #updateMouse} checks {@link
 * Mouse#isButtonDown} itself and is expected to be called every frame from {@code render}, the
 * same way {@code CreateWorldScreen} calls {@code nameField.updateMouse(...)} before {@code
 * nameField.render(...)}.
 */
public class Slider {

    private static final String TRACK_TEXTURE = "/assets/rubydung/textures/gui/slider_track.png";
    private static final String THUMB_TEXTURE = "/assets/rubydung/textures/gui/slider_thumb.png";

    private static final int TRACK_TEX_SIZE = 20;
    private static final int TRACK_BORDER = 6;

    // Source art is 8x8; doubled to match the 2x scale the rest of the GUI (fonts, toggle
    // sprites) renders at.
    private static final float THUMB_SIZE = 32.0f;
    private static final int LABEL_HEIGHT = 16;

    private final String label;
    private final float width;
    private final float height;
    private final float minValue;
    private final float maxValue;

    /** Snap dragged/clicked values to whole numbers - for things like an integer FOV or FPS cap. */
    private final boolean wholeNumbers;

    private float x;
    private float y;
    private float value;
    private boolean dragging = false;

    public Slider(String label, float width, float height, float minValue, float maxValue, float initialValue, boolean wholeNumbers) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.wholeNumbers = wholeNumbers;
        this.value = clamp(initialValue);
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public float getValue() {
        return this.value;
    }

    public void setValue(float value) {
        this.value = clamp(value);
    }

    /**
     * Whether a drag is currently in progress (mouse held down since a {@link #mouseClicked}
     * that landed on the track). Exposed so callers that apply this slider's value live on every
     * frame - see {@code OptionsScreen}'s FOV slider - can tell a mid-drag frame from the frame
     * a drag actually ends, and only persist the new value then rather than on every frame of
     * the drag.
     */
    public boolean isDragging() {
        return this.dragging;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    /**
     * Starts a drag if the click landed on the track, immediately snapping the thumb under the
     * cursor rather than waiting for the first drag movement - clicking anywhere on the track
     * jumps the value there, same as most slider widgets.
     */
    public void mouseClicked(int mouseX, int mouseY) {
        if (isHovered(mouseX, mouseY)) {
            this.dragging = true;
            setValueFromMouseX(mouseX);
        }
    }

    /**
     * Call every frame with fresh mouse coordinates (see class javadoc). Only does anything
     * once a drag has started via {@link #mouseClicked}, and releases the moment the mouse
     * button comes back up.
     */
    public void updateMouse(int mouseX, int mouseY) {
        if (!this.dragging) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            this.dragging = false;
            return;
        }
        setValueFromMouseX(mouseX);
    }

    private void setValueFromMouseX(int mouseX) {
        float usableWidth = this.width - THUMB_SIZE;
        float t = usableWidth <= 0 ? 0 : (mouseX - (this.x + THUMB_SIZE / 2.0f)) / usableWidth;
        t = Math.max(0.0f, Math.min(1.0f, t));

        float newValue = this.minValue + t * (this.maxValue - this.minValue);
        this.value = clamp(this.wholeNumbers ? Math.round(newValue) : newValue);
    }

    private float clamp(float v) {
        return Math.max(this.minValue, Math.min(this.maxValue, v));
    }

    public void render(Font font, int mouseX, int mouseY) {
        Gui2D.drawNineSlice(TRACK_TEXTURE, this.x, this.y, this.width, this.height, TRACK_TEX_SIZE, TRACK_TEX_SIZE, TRACK_BORDER);

        float t = (this.value - this.minValue) / (this.maxValue - this.minValue);
        float thumbX = this.x + t * (this.width - THUMB_SIZE);
        float thumbY = this.y + (this.height - THUMB_SIZE) / 2.0f;
        Gui2D.drawTexture(THUMB_TEXTURE, thumbX, thumbY, THUMB_SIZE, THUMB_SIZE);

        String text = this.label + ": " + formatValue();
        int textWidth = font.width(text);
        int drawX = (int) (this.x + (this.width - textWidth) / 2.0f);
        int drawY = (int) (this.y + (this.height - LABEL_HEIGHT) / 2.0f);

        boolean hovered = isHovered(mouseX, mouseY);
        int color = (hovered || this.dragging) ? 0xFFFF00 : 0xFFFFFF;
        font.drawString(text, drawX, drawY, color);
    }

    private String formatValue() {
        return this.wholeNumbers ? String.valueOf(Math.round(this.value)) : String.format("%.2f", this.value);
    }
}