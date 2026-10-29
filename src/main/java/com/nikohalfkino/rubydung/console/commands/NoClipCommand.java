package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.player.Player;

public class NoClipCommand implements Command {

    @Override
    public String name() {
        return "noclip";
    }

    @Override
    public String description() {
        return "Toggles flying through walls";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        Player player = game.getPlayer();
        player.noClip = !player.noClip;
        console.println("noclip " + (player.noClip ? "enabled" : "disabled"));
    }
}
