package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.HudElement;
import com.nikohalfkino.rubydung.render.Font;

public class CenterIndicator implements HudElement {

    private final RubyDung game;

    private String message = "";
    private long displayEndTime = 0;
    private static final long DISPLAY_DURATION_MS = 2000;

    public CenterIndicator(RubyDung game) {
        this.game = game;
    }

    public void showMessage(String msg) {
        this.message = msg;
        this.displayEndTime = System.currentTimeMillis() + DISPLAY_DURATION_MS;
    }

    @Override
    public void render(Font font, int width, int height) {
        if (message == null || message.isEmpty()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime > displayEndTime) {
            message = "";
            return;
        }

        float scale = 2.0F;
        int textWidth = (int) (font.getWidth(message) * scale);
        int x = (width - textWidth) / 2;
        int y = 15;

        font.drawStringScaled(message, x, y, 0xFFFFFF, scale);
    }
}