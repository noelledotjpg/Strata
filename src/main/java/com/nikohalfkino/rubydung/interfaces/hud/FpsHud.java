package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;

/**
 * Standalone FPS counter, top-right corner. Split out of {@link DebugHud} so it can actually be
 * toggled by VideoSettingsScreen's "Show FPS" toggle (see {@link GameSettings#isShowFpsInHud}) -
 * previously that toggle didn't do anything, since the fps line lived inside DebugHud's F3
 * overlay and only ever respected the F3 visibility flag, never this setting.
 * <p>
 * Lives top-right rather than top-left so it doesn't sit on top of {@link SelectedTileHud}'s
 * "Selected: ..." text, which occupies the top-left corner. {@link DebugHud} also renders in the
 * top-right when the F3 overlay is open, so it checks {@link GameSettings#isShowFpsInHud} and
 * starts its own lines below this one (see {@link #getReservedHeight()}) to avoid overlapping it.
 * <p>
 * Unlike the rest of the always-on HUD (see {@link Hud}), this keeps showing on every screen -
 * title, pause, options, no world loaded, doesn't matter - the same way {@link
 * com.nikohalfkino.rubydung.interfaces.screen.ConsoleScreen} draws over everything regardless of
 * what screen is open. That's exactly why this is a plain static-render utility like {@code
 * ConsoleScreen}, not a {@link HudElement} registered with {@link Hud#register}: {@link
 * Hud#render} is only ever called while a world is loaded (see {@code GameRenderer#render}),
 * which would hide the counter exactly when it's most useful - checking fps on the title screen
 * or while a menu's open.
 */
public final class FpsHud {

    private static final float SCALE = 2.0F;
    private static final int MARGIN = 10;

    // Matches the line-spacing convention DebugHud uses between its own lines, so the two overlays
    // line up consistently when both are visible at once.
    private static final int LINE_HEIGHT = (int) (10 * SCALE);

    private FpsHud() {
    }

    /**
     * Vertical space (from the top of the screen) this HUD occupies, including its top margin.
     * {@link DebugHud} uses this to push its own top-right lines down so the two don't overlap
     * when both the fps counter and the F3 overlay are visible together.
     */
    public static int getReservedHeight() {
        return MARGIN + LINE_HEIGHT;
    }

    public static void render(RubyDung game, Font font, int width, int height) {
        if (!GameSettings.isShowFpsInHud()) {
            return;
        }

        String text = String.format("%.0f fps", game.getFps());

        // Missing this was the bug that made the counter invisible - without it, drawStringScaled's
        // pixel-space coordinates get interpreted under whatever projection matrix happened to
        // already be loaded (the 3D camera, or nothing), same as ConsoleScreen needs it below.
        Gui2D.begin(width, height);

        boolean previousMono = font.isMonospace();
        font.setMonospace(true);

        int x = width - font.width(text) - MARGIN;
        font.drawStringScaled(text, x, MARGIN, 0xFFFFFF, SCALE);

        font.setMonospace(previousMono);

        Gui2D.end();
    }
}