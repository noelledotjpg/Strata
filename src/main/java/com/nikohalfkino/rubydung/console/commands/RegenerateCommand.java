package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

public class RegenerateCommand implements Command {

    @Override
    public String name() {
        return "regenerate";
    }

    @Override
    public String description() {
        return "Regenerates map: regenerate [seed|-] [flat|natural] [size]";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        Long seed = null;
        if (args.length > 0 && !args[0].equals("-")) {
            seed = LevelGenerator.parseSeed(args[0]);
        }

        LevelGenerator.WorldType type = null;
        if (args.length > 1 && !args[1].equals("-")) {
            String typeArg = args[1].toLowerCase();
            if (typeArg.equals("flat")) {
                type = LevelGenerator.WorldType.FLAT;
            } else if (typeArg.equals("natural")) {
                type = LevelGenerator.WorldType.NATURAL;
            } else {
                console.println("Unknown world type '" + args[1] + "'. Use 'flat' or 'natural' - keeping current type.");
            }
        }

        LevelGenerator.WorldSize size = null;
        if (args.length > 2) {
            try {
                size = LevelGenerator.WorldSize.valueOf(args[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                console.println("Unknown world size '" + args[2] + "'. Use tiny, default, large, or insane.");
            }
        }

        game.regenerateLevel(seed, type, size);
        console.println("World regenerated successfully.");
    }
}