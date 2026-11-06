package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.PlayerCamera;
import com.nikohalfkino.rubydung.render.PlayerRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/** the other players in the world being played: updated from packets, drawn every frame. main thread only */
public final class RemotePlayers {

    private static final double NAME_TAG_RANGE = 64.0D;

    // how far above the feet the tag is anchored (the head is at about 1.8), plus a gap in screen pixels
    private static final double NAME_TAG_HEIGHT = 2.35D;
    private static final int NAME_TAG_GAP = 6;

    // the tag is scaled by distance (a tag far away is smaller) but never outside these bounds,
    // so it stays readable in the distance and doesn't fill the screen up close
    // (a glyph is 8 px at scale 1, the old fixed tag was drawn at scale 2)
    private static final float TAG_REFERENCE_DISTANCE = 10.0F;
    private static final float TAG_BASE_SCALE = 2.0F;
    private static final float TAG_MIN_SCALE = 1.5F;
    private static final float TAG_MAX_SCALE = 3.0F;

    // name tags are projected to the screen while the 3D matrices are active and drawn later, with the HUD
    private static final class Tag {
        final String name;
        final int color;
        final float scale;
        final float fx, fy;

        Tag(String name, int color, float scale, float fx, float fy) {
            this.name = name;
            this.color = color;
            this.scale = scale;
            this.fx = fx;
            this.fy = fy;
        }
    }

    private final Map<Integer, RemotePlayer> players = new LinkedHashMap<>();
    private final List<Tag> tags = new ArrayList<>();
    private long lastFrameNanos = 0L;

    private final FloatBuffer model = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer window = BufferUtils.createFloatBuffer(3);

    public void add(RemotePlayer player) {
        this.players.put(player.id, player);
    }

    public RemotePlayer get(int id) {
        return this.players.get(id);
    }

    public RemotePlayer remove(int id) {
        return this.players.remove(id);
    }

    public void clear() {
        this.players.clear();
        this.tags.clear();
    }

    public Collection<RemotePlayer> all() {
        return this.players.values();
    }

    public int size() {
        return this.players.size();
    }

    /** 3D pass, after the terrain and before the billboards, like the local player's sprite */
    public void render(Level level, PlayerRenderer renderer, PlayerCamera camera) {
        this.tags.clear();
        if (this.players.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        float seconds = this.lastFrameNanos == 0L ? 0.0F : Math.min((now - this.lastFrameNanos) * 1.0E-9F, 0.25F);
        this.lastFrameNanos = now;

        this.model.clear();
        this.projection.clear();
        this.viewport.clear();
        glGetFloat(GL_MODELVIEW_MATRIX, this.model);
        glGetFloat(GL_PROJECTION_MATRIX, this.projection);
        glGetInteger(GL_VIEWPORT, this.viewport);

        for (RemotePlayer p : this.players.values()) {
            p.step(seconds);

            double feetY = p.drawY - Player.EYE_HEIGHT;
            renderer.renderRemote(level, p.drawX, feetY, p.drawZ, p.drawBodyYaw, camera);

            double dx = p.drawX - camera.getX();
            double dy = p.drawY - camera.getY();
            double dz = p.drawZ - camera.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > NAME_TAG_RANGE * NAME_TAG_RANGE) {
                continue;
            }
            float distance = (float) Math.sqrt(distanceSq);
            float scale = Math.max(TAG_MIN_SCALE, Math.min(TAG_MAX_SCALE,
                    TAG_BASE_SCALE * TAG_REFERENCE_DISTANCE / Math.max(distance, 0.001F)));

            this.window.clear();
            boolean ok = GLU.gluProject((float) p.drawX, (float) (feetY + NAME_TAG_HEIGHT), (float) p.drawZ,
                    this.model, this.projection, this.viewport, this.window);
            float depth = this.window.get(2);
            if (ok && depth > 0.0F && depth < 1.0F) {
                float fx = (this.window.get(0) - this.viewport.get(0)) / this.viewport.get(2);
                float fy = 1.0F - (this.window.get(1) - this.viewport.get(1)) / this.viewport.get(3);
                this.tags.add(new Tag(p.name, PlayerColors.of(p.name), scale, fx, fy));
            }
        }
    }

    /** HUD pass: the name tags found by render(), width and height are the HUD's size */
    public void renderTags(Font font, int width, int height) {
        if (this.tags.isEmpty()) {
            return;
        }

        Gui2D.begin(width, height);
        for (Tag tag : this.tags) {
            int textWidth = Math.round(font.getWidth(tag.name) * tag.scale);
            int textHeight = Math.round(8 * tag.scale);
            int x = Math.round(tag.fx * width) - textWidth / 2;
            int y = Math.round(tag.fy * height) - textHeight - NAME_TAG_GAP;

            Gui2D.fillRect(x - 3, y - 2, textWidth + 6, textHeight + 4, 0.0F, 0.0F, 0.0F, 0.4F);
            font.drawStringScaled(tag.name, x, y, tag.color, tag.scale);
        }
        Gui2D.end();
    }
}
