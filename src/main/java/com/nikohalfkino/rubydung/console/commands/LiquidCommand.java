package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.LiquidSimulator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LiquidCommand implements Command {

    @Override
    public String name() {
        return "liquid";
    }

    @Override
    public String description() {
        return "Controls the liquid simulation";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: liquid <sim|finite|speed|step|status>",
                "  liquid sim <on|off|toggle>  - turn the liquid simulation on or off",
                "  liquid finite <on|off|toggle> - finite: liquid is conserved, no infinite sources",
                "  liquid speed <ticks/sec>    - how often liquid moves (default 10)",
                "  liquid step [n]             - run n simulation ticks now, even while off",
                "  liquid status               - print whether it is on, its speed and busy cells");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "sim", "finite", "speed", "step", "status")
                .note("sim", "turn the liquid simulation on or off")
                .note("finite", "conserve liquid, no infinite sources")
                .note("speed", "how often liquid moves")
                .note("step", "run simulation ticks now")
                .note("status", "print the simulation state");

        if (given.length == 0) {
            return Collections.singletonList(action);
        }

        switch (given[0].toLowerCase()) {
            case "sim":
            case "finite":
                return Arrays.asList(action, CommandArg.choice("state", "on", "off", "toggle"));
            case "speed":
                return Arrays.asList(action, CommandArg.number("ticks/sec"));
            case "step":
                return Arrays.asList(action, CommandArg.integer("n").optional());
            default:
                return Collections.singletonList(action);
        }
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        if (args.length == 0) {
            printUsage(console);
            return;
        }

        switch (args[0].toLowerCase()) {

            case "sim": {
                if (args.length < 2) {
                    console.println("Usage: liquid sim <on|off|toggle>");
                    return;
                }
                String value = args[1].toLowerCase();
                boolean on;
                if (value.equals("on") || value.equals("true")) {
                    on = true;
                } else if (value.equals("off") || value.equals("false")) {
                    on = false;
                } else if (value.equals("toggle")) {
                    on = !LiquidSimulator.isEnabled();
                } else {
                    console.println("Usage: liquid sim <on|off|toggle>");
                    return;
                }
                LiquidSimulator.setEnabled(on);
                console.println("Liquid simulation " + (on ? "on" : "off"));
                break;
            }

            case "finite": {
                if (args.length < 2) {
                    console.println("Usage: liquid finite <on|off|toggle>");
                    return;
                }
                String value = args[1].toLowerCase();
                boolean on;
                if (value.equals("on") || value.equals("true")) {
                    on = true;
                } else if (value.equals("off") || value.equals("false")) {
                    on = false;
                } else if (value.equals("toggle")) {
                    on = !LiquidSimulator.isFinite();
                } else {
                    console.println("Usage: liquid finite <on|off|toggle>");
                    return;
                }
                LiquidSimulator.setFinite(on);
                console.println("Finite liquid " + (on ? "on (liquid is conserved)" : "off (sources are infinite)"));
                break;
            }

            case "speed": {
                if (args.length < 2) {
                    console.println("Usage: liquid speed <ticks/sec>");
                    return;
                }
                try {
                    float tps = Float.parseFloat(args[1]);
                    if (tps <= 0 || tps > 200) {
                        console.println("Speed must be above 0 and at most 200");
                        return;
                    }
                    LiquidSimulator.setTicksPerSecond(tps);
                    console.println("Liquid speed set to " + tps + " ticks/sec");
                } catch (NumberFormatException e) {
                    console.println("Speed must be a number");
                }
                break;
            }

            case "step": {
                if (!game.hasWorld()) {
                    console.println("No world loaded");
                    return;
                }
                int n = 1;
                if (args.length >= 2) {
                    try {
                        n = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        console.println("Tick count must be a whole number");
                        return;
                    }
                }
                n = Math.max(1, Math.min(n, 1000));
                game.getLevel().getLiquids().step(n);
                console.println("Ran " + n + " liquid tick" + (n == 1 ? "" : "s"));
                break;
            }

            case "sea": {
                console.println("The sea has its own command now, type 'sea help'");
                break;
            }

            case "status": {
                String busy = game.hasWorld() ? game.getLevel().getLiquids().getActiveCells() + " busy cells" : "no world";
                console.println("Liquid simulation: " + (LiquidSimulator.isEnabled() ? "on" : "off")
                        + " | " + (LiquidSimulator.isFinite() ? "finite" : "infinite")
                        + " | " + LiquidSimulator.getTicksPerSecond() + " ticks/sec | " + busy);
                break;
            }

            default:
                printUsage(console);
        }
    }

    private static void printUsage(Console console) {
        console.println("Usage: liquid <sim|finite|speed|step|status>  (the sea is the 'sea' command)  (type 'liquid help' for details)");
    }
}