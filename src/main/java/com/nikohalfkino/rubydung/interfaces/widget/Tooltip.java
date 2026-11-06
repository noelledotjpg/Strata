package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.SuggestionBox;
import com.nikohalfkino.rubydung.render.Font;

/**
 * a one-line label that follows the mouse, drawn like the command suggestions (dithered backdrop with
 * a sunken bevel, see SuggestionBox.drawBackdrop)
 *
 * not placed by a layout: the owner calls show() every frame the mouse is over something (hide() when it
 * isn't) and then render() at the point it wants the tooltip on top, e.g. the end of Screen.renderForeground
 * so it covers everything drawn before it. setScreenSize keeps it inside the window
 */
public class Tooltip extends Widget {

    private static final int PAD = 6;
    // between the mouse and the box's outer edge
    private static final int OFFSET_X = 12;
    private static final int OFFSET_Y = 6;
    private static final int MARGIN = 2;

    private String text = null;
    private int color = 0xFFFFFF;
    private int tint = 0xFFFFFF;
    private float scale = 2.0F;
    private int screenWidth = 0;
    private int screenHeight = 0;

    /** colour multiplier for the bevel, pass the screen's getFrameTint() to match the frame */
    public Tooltip setTint(int tint) {
        this.tint = tint;
        return this;
    }

    /** scale of the text, like the other chat strings */
    public Tooltip setScale(float scale) {
        this.scale = scale;
        return this;
    }

    /** size of the window, the tooltip never leaves it */
    public Tooltip setScreenSize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        return this;
    }

    /** the text to show (0xRRGGBB colour), takes effect on the next render() */
    public void show(String text, int color) {
        this.text = text;
        this.color = color;
    }

    public void hide() {
        this.text = null;
    }

    public boolean isShowing() {
        return this.text != null && !this.text.isEmpty();
    }

    @Override
    protected void draw(Font font, int mouseX, int mouseY) {
        if (!isShowing()) {
            return;
        }

        int bevel = 2 * GameFrame.UI_SCALE;
        int textWidth = (int) (font.getWidth(this.text) * this.scale);
        int w = textWidth + PAD * 2;
        int h = (int) (8 * this.scale) + 3 + 4;

        int boxX = mouseX + OFFSET_X + bevel;
        int boxY = mouseY - OFFSET_Y - bevel - h;

        if (this.screenWidth > 0) {
            boxX = Math.min(boxX, this.screenWidth - w - bevel - MARGIN);
        }
        boxX = Math.max(bevel + MARGIN, boxX);

        // no room above the mouse: go below it
        if (boxY < bevel + MARGIN) {
            boxY = mouseY + OFFSET_Y + bevel + (int) (8 * this.scale);
        }
        if (this.screenHeight > 0) {
            boxY = Math.min(boxY, this.screenHeight - h - bevel - MARGIN);
        }

        this.x = boxX;
        this.y = boxY;
        this.width = w;
        this.height = h;

        Gui2D.begin(this.screenWidth > 0 ? this.screenWidth : (int) getRootWidth(),
                this.screenHeight > 0 ? this.screenHeight : (int) getRootHeight());

        SuggestionBox.drawBackdrop(boxX, boxY, w, h, this.tint);
        font.drawStringScaled(this.text, boxX + PAD, boxY + 2 + 1, this.color, this.scale);

        Gui2D.end();
    }
}
