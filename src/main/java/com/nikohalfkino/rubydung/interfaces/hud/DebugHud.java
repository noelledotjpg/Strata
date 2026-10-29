package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.HudElement;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;

import java.util.ArrayList;
import java.util.List;

public class DebugHud implements HudElement {

    private final RubyDung game;
    private boolean visible = false;
    private static final float SCALE = 2.0F;

    public DebugHud(RubyDung game) {
        this.game = game;
    }

    public void toggle() {
        this.visible = !this.visible;
    }

    @Override
    public void render(Font font, int width, int height) {
        if (!this.visible) {
            return;
        }

        Player player = this.game.getPlayer();

        List<String> lines = new ArrayList<>();

        lines.add(String.format("x: %7.2f", player.x));
        lines.add(String.format("y: %7.2f", player.y));
        lines.add(String.format("z: %7.2f", player.z));

        float yaw = player.yRotation % 360;
        if (yaw < 0) yaw += 360;

        String facing = getCardinalDirection(yaw);

        lines.add(String.format("dir: %s (%.1f)", facing, yaw));
        lines.add(String.format("pitch: %6.1f", player.xRotation));

        if (player.flying || player.noClip) {
            lines.add(String.format("speed: %5.2fx", player.flySpeedMultiplier));
        }

        boolean previousMono = font.isMonospace();
        font.setMonospace(true);

        int y = GameSettings.isShowFpsInHud() ? FpsHud.getReservedHeight() : 10;
        int paddingRight = 10;

        for (String line : lines) {
            int x = width - font.width(line) - paddingRight;
            font.drawStringScaled(line, x, y, 0xFFFFFF, SCALE);
            y += (int) (10 * SCALE);
        }

        font.setMonospace(previousMono);
    }

    private String getCardinalDirection(float yaw) {
        if (yaw >= 315 || yaw < 45) {
            return "North (+Z)";
        } else if (yaw >= 45 && yaw < 135) {
            return "East (-X)";
        } else if (yaw >= 135 && yaw < 225) {
            return "South (-Z)";
        } else {
            return "West (+X)";
        }
    }
}