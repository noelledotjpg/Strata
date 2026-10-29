package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;

public class ClearCommand implements Command {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clears the console log";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        console.clear();
    }
}
