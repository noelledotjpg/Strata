package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.tiles.LiquidTile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SeaCommand implements Command {

    @Override
    public String name() {
        return "sea";
    }

    @Override
    public String description() {
        return "Controls the ocean: its height and what it is made of";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: sea [height] [blocks/sec] | rise | stop | liquid | status",
                "  sea                              - print the sea height, target, rate and liquid",
                "  sea <height>                     - move the whole sea to this height at once (blocks)",
                "  sea <height> <blocks/sec>        - let the sea rise or fall to a height by itself (eases in and out)",
                "  sea rise <height> <blocks/sec>   - same as above",
                "  sea stop                         - freeze the sea where it is",
                "  sea liquid                       - print the sea's liquid and the ones you can pick",
                "  sea liquid <name>                - make the sea out of another liquid (water, lava...)");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg first = CommandArg.choice("height", "rise", "stop", "liquid", "status")
                .orNumber()
                .optional()
                .note("rise", "ease the sea to a height at a rate")
                .note("stop", "freeze the sea where it is")
                .note("liquid", "show or change what the sea is made of")
                .note("status", "print the sea height and rate");

        if (given.length == 0) {
            return Collections.singletonList(first);
        }

        String sub = given[0].toLowerCase();

        switch (sub) {
            case "rise":
                return Arrays.asList(first, CommandArg.number("height"), CommandArg.number("blocks/sec"));
            case "liquid": {
                List<String> names = new ArrayList<>();
                for (LiquidTile liquid : LiquidTile.all()) {
                    names.add(liquid.name);
                }
                return Arrays.asList(first, CommandArg.choice("liquid", names.toArray(new String[0])).optional());
            }
            case "stop":
            case "status":
                return Collections.singletonList(first);
            default:
                // "sea <height> [blocks/sec]"
                return Arrays.asList(first, CommandArg.number("blocks/sec").optional());
        }
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        if (!game.hasWorld()) {
            console.println("No world loaded");
            return;
        }
        LiquidLayer liquids = game.getLevel().getLiquids();
        if (!liquids.hasOcean()) {
            console.println("This world has no ocean");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            printStatus(console, liquids);
            return;
        }

        String sub = args[0].toLowerCase();
        try {
            if (sub.equals("stop")) {
                liquids.riseTo(liquids.getSeaHeight(), 0.0F);
                console.println("Sea stopped at " + liquids.getSeaHeight());

            } else if (sub.equals("liquid")) {
                liquid(console, liquids, args);

            } else if (sub.equals("rise")) {
                if (args.length < 3) {
                    console.println("Usage: sea rise <height> <blocks/sec>");
                    return;
                }
                rise(console, liquids, Float.parseFloat(args[1]), Float.parseFloat(args[2]));

            } else if (args.length >= 2) {
                rise(console, liquids, Float.parseFloat(args[0]), Float.parseFloat(args[1]));

            } else {
                float height = Float.parseFloat(args[0]);
                liquids.riseTo(height, 0.0F);
                liquids.setSeaHeight(height);
                console.println("Sea height set to " + liquids.getSeaHeight());
            }
        } catch (NumberFormatException e) {
            console.println("Height and rate must be numbers (type 'sea help')");
        }
    }

    private static void rise(Console console, LiquidLayer liquids, float target, float rate) {
        if (rate <= 0) {
            console.println("Rate must be above 0");
            return;
        }
        liquids.riseTo(target, rate);
        console.println("Sea moving to " + liquids.getSeaTarget() + " at " + rate + " blocks/sec");
    }

    private static void liquid(Console console, LiquidLayer liquids, String[] args) {
        if (args.length < 2) {
            console.println("Sea liquid: " + liquids.getOceanLiquid().name + " | available: " + names());
            return;
        }
        LiquidTile liquid = find(args[1]);
        if (liquid == null) {
            console.println("Unknown liquid '" + args[1] + "'. Available: " + names());
            return;
        }
        if (liquid == liquids.getOceanLiquid()) {
            console.println("The sea is already " + liquid.name);
            return;
        }
        liquids.setOceanLiquid(liquid);
        console.println("The sea is now " + liquid.name);
    }

    private static LiquidTile find(String name) {
        for (LiquidTile liquid : LiquidTile.all()) {
            if (liquid.name.equalsIgnoreCase(name)) {
                return liquid;
            }
        }
        return null;
    }

    private static String names() {
        List<String> names = new ArrayList<>();
        for (LiquidTile liquid : LiquidTile.all()) {
            names.add(liquid.name);
        }
        return String.join(", ", names);
    }

    private static void printStatus(Console console, LiquidLayer liquids) {
        console.println("Sea of " + liquids.getOceanLiquid().name + " at height " + liquids.getSeaHeight()
                + ", drawn at " + liquids.getSurfaceHeight() + " (top cell y=" + liquids.getSeaTop() + ")"
                + (liquids.isSeaMoving()
                ? " | moving to " + liquids.getSeaTarget() + " at " + liquids.getSeaRate() + " blocks/sec"
                : " | still"));
    }
}