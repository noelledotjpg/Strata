package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.render.Font;

import static org.lwjgl.opengl.GL11.*;

public class LoadingScreen extends Screen {

    private static final int CHUNKS_PER_FRAME = 32;

    private final RubyDung game;
    private final LevelRenderer levelRenderer;
    private final Font font;

    public LoadingScreen(RubyDung game, LevelRenderer levelRenderer) {
        this.game = game;
        this.levelRenderer = levelRenderer;
        this.font = game.getFont();
    }

    @Override
    public boolean pausesGame() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        boolean more = this.levelRenderer.buildNextChunks(CHUNKS_PER_FRAME);
        if (!more) {
            this.game.finishedLoadingWorld();
            return;
        }

        Gui2D.begin(this.width, this.height);

        Gui2D.fillRect(0, 0, this.width, this.height, 0.0f, 0.0f, 0.0f, 1.0f);

        String label = "Loading...";
        int labelWidth = this.font.width(label);
        this.font.drawString(label, (this.width - labelWidth) / 2, (int) (this.height * 0.45f), 0xFFFFFF);

        float barWidth = 300.0f;
        float barHeight = 4.0f;
        float barX = (this.width - barWidth) / 2.0f;
        float barY = this.height * 0.5f;

        Gui2D.fillRect(barX, barY, barWidth, barHeight, 0.2f, 0.2f, 0.2f, 1.0f);

        float progress = this.levelRenderer.getBuildProgress();
        float currentFillWidth = barWidth * progress;

        if (currentFillWidth > 0.0f) {
            glDisable(GL_TEXTURE_2D);
            glBegin(GL_QUADS);
            glColor4f(0.1f, 0.9f, 0.2f, 1.0f);
            glVertex2f(barX, barY);
            glColor4f(0.1f, 0.9f, 0.2f, 1.0f);
            glVertex2f(barX, barY + barHeight);
            glColor4f(0.0f, 0.2f, 0.05f, 1.0f);
            glVertex2f(barX + currentFillWidth, barY + barHeight);
            glColor4f(0.0f, 0.2f, 0.05f, 1.0f);
            glVertex2f(barX + currentFillWidth, barY);
            glEnd();
            glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        }

        int percent = (int) (progress * 100.0f);
        String percentLabel = percent + "%";
        int percentWidth = this.font.width(percentLabel);
        this.font.drawString(percentLabel, (int) (this.width - percentWidth) / 2, (int) (barY + barHeight + 6.0f), 0xAAAAAA);

        Gui2D.end();
    }
}