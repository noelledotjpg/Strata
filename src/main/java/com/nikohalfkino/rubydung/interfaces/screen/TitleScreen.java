package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Column;
import com.nikohalfkino.rubydung.interfaces.widget.Image;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.util.GameSettings;

public class TitleScreen extends MenuScreen {

    private static final String LOGO_TEXTURE = "/assets/rubydung/textures/gui/logo.png";
    private static final float LOGO_WIDTH = 249.0f * 2;
    private static final float LOGO_HEIGHT = 55.0f * 2;

    // logo to the version text under it
    private static final float VERSION_GAP = 6.0f;

    // panel bevel to the buttons inside it
    private static final float PANEL_PADDING = 20.0f;

    // panel to the "Logged in as" line under it
    private static final float LOGIN_GAP = 12.0f;

    public TitleScreen(RubyDung game) {
        super(game);

        // Logo: centred, a quarter of the way down the window.
        Image logo = add(new Image(LOGO_TEXTURE, LOGO_WIDTH, LOGO_HEIGHT), Anchor.TOP_CENTER, 0, 0);
        logo.setOffsetFraction(0.0f, 0.25f);

        // Version, centred just under the logo.
        Label version = add(new Label("- " + RubyDung.GAME_VERSION + " -").setColor(UiStyle.TEXT_DIM),
                Anchor.TOP_CENTER, 0, LOGO_HEIGHT + VERSION_GAP);
        version.setOffsetFraction(0.0f, 0.25f);

        // Panel with the buttons, and the "Logged in as" line under it, as one block half way down.
        Column block = add(new Column(LOGIN_GAP), Anchor.TOP_CENTER, 0, 0);
        block.setOffsetFraction(0.0f, 0.5f);

        Panel menu = block.add(new Panel(UiStyle.BUTTON_GAP));
        menu.setPadding(PANEL_PADDING);
        menu.setShadowAlpha(1.0f);
        menu.add(new Button("Play").onClick(() -> game.openScreen(new WorldListScreen(game))));
        menu.add(new Button("Multiplayer").onClick(() -> game.openScreen(new JoinServerScreen(game))));
        menu.add(new Button("Options").onClick(() -> game.openScreen(new OptionsScreen(game))));
        menu.add(new Button("Quit").onClick(game::quit));

        block.add(new Label(() -> "Logged in as: " + GameSettings.getUsername()).setColor(UiStyle.TEXT_DIM));
    }
}