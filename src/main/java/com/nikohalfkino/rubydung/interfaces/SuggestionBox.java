package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.console.CommandCompleter;
import com.nikohalfkino.rubydung.console.CommandSyntax;
import com.nikohalfkino.rubydung.render.Font;

import java.util.List;

/**
 * the list of matches under or over a command input: dithered backdrop (see Dither) with a sunken
 * bevel border (see Bevel / BevelBox), the selected match highlighted and the command's usage below
 *
 * not part of a widget tree, call inside Gui2D.begin
 */
public final class SuggestionBox {

    private static final int MAX_ROWS = 6;
    private static final int PAD = 6;

    // a faint solid layer under the dither so the text stays readable over a bright world
    private static final float BASE_ALPHA = 0.4F;
    private static final float DITHER_ALPHA = 0.7F;

    private static final int SELECTED_COLOR = 0xFFFFFF;
    private static final int NOTE_COLOR = 0x889999;
    private static final int HINT_COLOR = 0x99CCFF;

    private SuggestionBox() {
    }

    /**
     * the look of the box: faint solid layer, dither over it, sunken bevel just outside the rectangle
     * (2 UI units per side). shared with widget.Tooltip so the two match
     */
    public static void drawBackdrop(int x, int y, int w, int h, int tint) {
        Gui2D.fillRect(x, y, w, h, 0.0F, 0.0F, 0.0F, BASE_ALPHA);
        Dither.fill(x, y, w, h, DITHER_ALPHA, false);
        Bevel.sunkenFrame(x, y, w, h, tint);
    }

    private static int width(Font font, String text, float scale) {
        return (int) (font.getWidth(text) * scale);
    }

    private static String fit(Font font, String text, float scale, int maxWidth) {
        int end = text.length();

        while (end > 0 && width(font, text.substring(0, end), scale) > maxWidth) {
            end--;
        }

        return text.substring(0, end);
    }

    /**
     * (x, y) is the outer edge of the border: y is the bottom edge when above, otherwise the top edge,
     * and the box is at most maxWidth wide, border included
     */
    public static void draw(Font font, CommandCompleter.Popup popup,
                            int x, int y, int maxWidth, boolean above, int tint, float scale) {
        List<String> options = popup.options;
        List<String> notes = popup.notes;
        String hint = popup.hint;

        int rows = Math.min(options.size(), MAX_ROWS);

        if (rows == 0 && hint == null) {
            return;
        }

        int bevel = 2 * GameFrame.UI_SCALE;
        int rowHeight = (int) (8 * scale) + 3;

        // keep the selected match in view
        int first = 0;
        if (popup.selected >= 0) {
            first = Math.max(0, Math.min(popup.selected - rows / 2, options.size() - rows));
        }

        int optionWidth = 0;
        for (int i = first; i < first + rows; i++) {
            optionWidth = Math.max(optionWidth, width(font, options.get(i), scale));
        }

        int gap = width(font, "  ", scale);
        int widest = 0;
        for (int i = first; i < first + rows; i++) {
            String note = notes.get(i);
            widest = Math.max(widest, optionWidth + (note.isEmpty() ? 0 : gap + width(font, note, scale)));
        }
        if (hint != null) {
            widest = Math.max(widest, width(font, hint, scale));
        }

        int available = maxWidth - bevel * 2;
        int w = Math.min(available, widest + PAD * 2);
        int inner = w - PAD * 2;
        int h = (rows + (hint != null ? 1 : 0)) * rowHeight + 4;

        int bx = x + bevel;
        int by = above ? y - bevel - h : y + bevel;

        drawBackdrop(bx, by, w, h, tint);

        int lineY = by + 2;

        for (int i = first; i < first + rows; i++) {
            boolean selected = i == popup.selected;

            if (selected) {
                Gui2D.fillRect(bx + 1, lineY, w - 2, rowHeight, 0.2F, 0.4F, 0.8F, 0.6F);
            }

            int color = selected ? SELECTED_COLOR
                    : popup.commandNames ? CommandSyntax.COMMAND : CommandSyntax.CHOICE;

            font.drawStringScaled(fit(font, options.get(i), scale, inner), bx + PAD, lineY + 1, color, scale);

            String note = notes.get(i);
            if (!note.isEmpty()) {
                int noteX = bx + PAD + optionWidth + gap;
                String shown = fit(font, note, scale, bx + w - PAD - noteX);
                if (!shown.isEmpty()) {
                    font.drawStringScaled(shown, noteX, lineY + 1, NOTE_COLOR, scale);
                }
            }

            lineY += rowHeight;
        }

        if (hint != null) {
            font.drawStringScaled(fit(font, hint, scale, inner), bx + PAD, lineY + 1, HINT_COLOR, scale);
        }
    }
}
