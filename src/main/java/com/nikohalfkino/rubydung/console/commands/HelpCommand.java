package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;

import java.util.List;

public class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Lists all available commands";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        List<Command> commands = console.commands();

        int maxNameLength = 0;
        for (Command command : commands) {
            maxNameLength = Math.max(maxNameLength, command.name().length());
        }

        String formatSpecifier = "%-" + maxNameLength + "s - %s";
        for (Command command : commands) {
            console.println(String.format(formatSpecifier, command.name(), command.description()));
        }
    }
}