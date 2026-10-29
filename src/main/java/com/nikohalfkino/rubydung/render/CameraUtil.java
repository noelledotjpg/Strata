package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.player.Player;

import static org.lwjgl.opengl.GL11.glRotatef;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.opengl.GL11.glTranslatef;

final class CameraUtil {

    private CameraUtil() {
    }

    static void moveCameraToPlayer(Player player, float partialTicks) {
        glTranslatef(0.0f, 0.0f, -0.3f);

        glRotatef(player.xRotation, 1.0f, 0.0f, 0.0f);
        glRotatef(player.yRotation, 0.0f, 1.0f, 0.0f);

        double x = player.prevX + (player.x - player.prevX) * partialTicks;
        double y = player.prevY + (player.y - player.prevY) * partialTicks;
        double z = player.prevZ + (player.z - player.prevZ) * partialTicks;

        glTranslated(-x, -y, -z);
    }
}