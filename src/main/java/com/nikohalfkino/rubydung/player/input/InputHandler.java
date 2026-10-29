package com.nikohalfkino.rubydung.player.input;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.interfaces.screen.PauseScreen;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class InputHandler {

    private final RubyDung game;

    private boolean wasPlaying = false;

    public InputHandler(RubyDung game) {
        this.game = game;
    }

    public void handle(boolean playing, int mouseX, int mouseY) {
        if (playing && !this.wasPlaying) {
            this.game.getPlayerInteraction().suppressHeldButtonsUntilReleased();
        }
        this.wasPlaying = playing;

        handleScrollWheel(playing);
        handleMouse(playing, mouseX, mouseY);
        handleKeyboard();
    }

    private void handleScrollWheel(boolean playing) {
        int wheel = Mouse.getDWheel();
        if (!playing || wheel == 0) {
            return;
        }

        Player player = this.game.getPlayer();
        boolean ctrlHeld = KeyBinding.FLY_SPEED_MODIFIER.isDown() || KeyMap.isDown(KeyMap.RCONTROL);

        if (ctrlHeld && (player.flying || player.noClip)) {
            player.adjustFlySpeed(wheel > 0 ? 1 : -1);
        } else {
            this.game.getPlayerInteraction().cycleSelectedTile(wheel > 0 ? 1 : -1);
        }
    }

    private void handleMouse(boolean playing, int mouseX, int mouseY) {
        while (Mouse.next()) {
            if (!playing) {
                Screen currentScreen = this.game.getCurrentScreen();
                if (currentScreen != null && Mouse.getEventButtonState()) {
                    currentScreen.mouseClicked(mouseX, mouseY, Mouse.getEventButton());

                    if (this.game.getCurrentScreen() == null) {
                        this.game.getPlayerInteraction().suppressHeldButtonsUntilReleased();
                    }
                }
            }
        }
    }

    private void handleKeyboard() {
        Console console = this.game.getConsole();

        while (Keyboard.next()) {
            int key = Keyboard.getEventKey();
            char character = Keyboard.getEventCharacter();
            boolean pressed = Keyboard.getEventKeyState();

            if (key == KeyBinding.CONSOLE.getKey() && pressed) {
                console.toggle();
                continue;
            }

            if (console.isOpen()) {
                if (pressed) {
                    console.keyTyped(key, character);
                }
                continue;
            }

            Screen currentScreen = this.game.getCurrentScreen();
            if (currentScreen != null) {
                if (pressed) {
                    currentScreen.keyPressed(key, character);
                }
                continue;
            }

            handleGameplayKey(key, pressed);
        }
    }

    private void handleGameplayKey(int key, boolean pressed) {
        if (!pressed) {
            return;
        }

        if (key == KeyMap.ENTER) {
            this.game.saveLevel();
        } else if (key == KeyMap.ESC) {
            this.game.openScreen(new PauseScreen(this.game, this.game.getFont()));
        } else if (key == KeyBinding.DEBUG_HUD.getKey()) {
            this.game.getDebugHud().toggle();
        } else if (key == KeyBinding.PICK_BLOCK.getKey() || key == Keyboard.KEY_RMENU) {
            this.game.getPlayerInteraction().pickTargetedTile();
        } else if (key == KeyBinding.CYCLE_TREE.getKey()) {
            Tile current = this.game.getPlayerInteraction().getSelectedTile();

            if (current == Tile.TREE_SAPLING) {
                this.game.getPlayerInteraction().setSelectedTile(Tile.TREE_MEDIUM);
            } else if (current == Tile.TREE_MEDIUM) {
                this.game.getPlayerInteraction().setSelectedTile(Tile.TREE_LARGE);
            } else if (current == Tile.TREE_LARGE) {
                this.game.getPlayerInteraction().setSelectedTile(Tile.TREE_SAPLING);
            }
        } else if (key == KeyBinding.FLY_TOGGLE.getKey()) {
            Player player = this.game.getPlayer();
            player.flying = !player.flying;
            if (!player.flying) {
                player.noClip = false;
            }
            this.game.getCenterIndicator().showMessage(player.flying ? "Flying Enabled" : "Flying Disabled");
        } else if (key == KeyBinding.NOCLIP_TOGGLE.getKey()) {
            Player player = this.game.getPlayer();
            player.noClip = !player.noClip;
            if (player.noClip) {
                player.flying = true;
            } else {
                player.flying = false;
            }
            this.game.getCenterIndicator().showMessage(player.noClip ? "Noclip Enabled" : "Noclip Disabled");
        } else if (key == KeyBinding.SCREENSHOT.getKey()) {
            this.game.takeScreenshot(2);
            this.game.getCenterIndicator().showMessage("Screenshot Saved");
        }
    }
}