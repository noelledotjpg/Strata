package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class ConsoleScreen {

    private static final float HEIGHT_FRACTION = 0.4f;
    private static final int HORIZONTAL_PADDING = 8;

    public static void render(Console console, Font font, int width, int height) {
        if (!console.isOpen()) {
            return;
        }

        boolean previousMono = font.isMonospace();
        font.setMonospace(true);

        float consoleHeight = height * HEIGHT_FRACTION;
        int inputBarHeight = 28;
        int maxLineWidth = width - (HORIZONTAL_PADDING * 2);

        Gui2D.begin(width, height);

        // Draw classic Indev/Infdev blue-to-black gradient background (top to bottom fade for console panel)
        glDisable(GL_TEXTURE_2D);
        glBegin(GL_QUADS);
        // Top-left (black)
        glColor4f(0.0f, 0.0f, 0.0f, 0.75f);
        glVertex2f(0, 0);
        // Top-right (black)
        glColor4f(0.0f, 0.0f, 0.0f, 0.75f);
        glVertex2f(width, 0);
        // Bottom-right (blue)
        glColor4f(0.15f, 0.15f, 0.5f, 0.9f);
        glVertex2f(width, consoleHeight);
        // Bottom-left (blue)
        glColor4f(0.15f, 0.15f, 0.5f, 0.9f);
        glVertex2f(0, consoleHeight);
        glEnd();

        // Solid dark bar background for the input text field
        Gui2D.fillRect(0, consoleHeight - inputBarHeight, width, inputBarHeight, 0.0f, 0.0f, 0.0f, 0.85f);

        glEnable(GL_TEXTURE_2D);
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        List<String> wrappedLines = new ArrayList<>();
        for (String logLine : console.getLog()) {
            wrappedLines.addAll(wrapText(font, logLine, maxLineWidth));
        }

        int lineSpacing = 16;
        int maxVisibleLines = (int) ((consoleHeight - inputBarHeight - 8) / lineSpacing);

        int startIndex = Math.max(0, wrappedLines.size() - maxVisibleLines);
        int y = (int) consoleHeight - inputBarHeight - lineSpacing - 4;

        for (int i = wrappedLines.size() - 1; i >= startIndex; i--) {
            font.drawString(wrappedLines.get(i), HORIZONTAL_PADDING, y, 0xCCCCCC);
            y -= lineSpacing;
        }

        console.getInput().render(font, HORIZONTAL_PADDING, (int) consoleHeight - inputBarHeight + 6, 0xFFFFFF, "] ");

        Gui2D.end();

        font.setMonospace(previousMono);
    }

    private static List<String> wrapText(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (font.width(testLine) > maxWidth) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    // Single word exceeds max width completely, force break chars
                    lines.add(word);
                }
            } else {
                currentLine = new StringBuilder(testLine);
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }
}