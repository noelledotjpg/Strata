package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class TextField {

    private final StringBuilder text = new StringBuilder();
    private int maxLength = Integer.MAX_VALUE;

    private int cursorPosition = 0;
    private int selectionAnchor = 0;
    private boolean isDragging = false;

    public TextField() {
    }

    public TextField(int maxLength) {
        this.maxLength = maxLength;
    }

    public void mouseClicked(int mouseX, int mouseY, int fieldX, int fieldY, Font font, String prefix) {
        int charIndex = getCharIndexAt(mouseX, fieldX, font, prefix);
        this.cursorPosition = charIndex;
        this.selectionAnchor = charIndex;
        this.isPressed = true; // Track drag start
    }

    private boolean isPressed = false;

    public void updateMouse(int mouseX, int mouseY, int fieldX, int fieldY, Font font, String prefix) {
        if (!isPressed) return;

        if (Mouse.isButtonDown(0)) {
            int charIndex = getCharIndexAt(mouseX, fieldX, font, prefix);
            this.cursorPosition = charIndex;
        } else {
            isPressed = false;
        }
    }

    private int getCharIndexAt(int mouseX, int fieldX, Font font, String prefix) {
        int prefixWidth = font.width(prefix);
        if (mouseX <= fieldX + prefixWidth) {
            return 0;
        }

        int currentWidth = fieldX + prefixWidth;
        for (int i = 0; i < this.text.length(); i++) {
            String sub = this.text.substring(0, i + 1);
            int nextWidth = fieldX + prefixWidth + font.width(sub);
            if (mouseX < (currentWidth + nextWidth) / 2) {
                return i;
            }
            currentWidth = nextWidth;
        }
        return this.text.length();
    }

    public boolean keyTyped(int key, char character) {
        boolean ctrlPressed = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA);

        if (ctrlPressed && (key == Keyboard.KEY_A || character == 'a' || character == 'A')) {
            selectAll();
            return true;
        }

        if (hasSelection()) {
            if (key == KeyMap.BACKSPACE || key == Keyboard.KEY_DELETE) {
                deleteSelection();
                return true;
            }
            if (character >= 32 && character != 127) {
                deleteSelection();
            }
        }

        if (key == KeyMap.BACKSPACE) {
            if (this.cursorPosition > 0 && this.text.length() > 0) {
                this.text.deleteCharAt(this.cursorPosition - 1);
                this.cursorPosition--;
                this.selectionAnchor = this.cursorPosition;
            }
            return true;
        }

        if (character >= 32 && character != 127 && this.text.length() < this.maxLength) {
            this.text.insert(this.cursorPosition, character);
            this.cursorPosition++;
            this.selectionAnchor = this.cursorPosition;
            return true;
        }

        return false;
    }

    public boolean hasSelection() {
        return this.cursorPosition != this.selectionAnchor;
    }

    public void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.text.length();
    }

    public void deleteSelection() {
        int start = Math.min(this.cursorPosition, this.selectionAnchor);
        int end = Math.max(this.cursorPosition, this.selectionAnchor);
        this.text.delete(start, end);
        this.cursorPosition = start;
        this.selectionAnchor = start;
    }

    public String getText() {
        return this.text.toString();
    }

    public void setText(String value) {
        this.text.setLength(0);
        this.text.append(value);
        this.cursorPosition = this.text.length();
        this.selectionAnchor = this.cursorPosition;
    }

    public void clear() {
        this.text.setLength(0);
        this.cursorPosition = 0;
        this.selectionAnchor = 0;
    }

    public boolean isEmpty() {
        return this.text.length() == 0;
    }

    public void render(Font font, int x, int y, int color, String prefix) {
        int prefixWidth = font.width(prefix);

        if (hasSelection()) {
            int startIdx = Math.min(this.cursorPosition, this.selectionAnchor);
            int endIdx = Math.max(this.cursorPosition, this.selectionAnchor);

            String subBefore = this.text.substring(0, startIdx);
            String subSelected = this.text.substring(startIdx, endIdx);

            int selectXOffset = font.width(prefix + subBefore);
            int selectWidth = font.width(subSelected);

            Gui2D.fillRect(x + selectXOffset, y - 1, selectWidth, 18, 0.2f, 0.4f, 0.8f, 0.6f);
        }

        boolean showCursor = (System.currentTimeMillis() / 400L) % 2 == 0;
        String line = prefix + this.text + (showCursor && !hasSelection() ? "_" : "");
        font.drawString(line, x, y, color);
    }
}