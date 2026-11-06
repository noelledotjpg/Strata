package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.ChatText;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;

public class SeedCommand implements Command {

    @Override
    public String name() {
        return "seed";
    }

    @Override
    public String description() {
        return "Displays the current world seed";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        long seed = game.getLevel().getSeed();
        // in the chat the number can be clicked to copy it
        console.println("World seed: " + ChatText.copyable(String.valueOf(seed)));
    }
}