package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.LiquidSimulator;

import java.util.Arrays;
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
                "Usage:",
                "  liquid sim <on|off|toggle>  - turn the liquid simulation on or off",
                "  liquid speed <ticks/sec>    - how often liquid moves (default 10)",
                "  liquid step [n]             - run n simulation ticks now, even while off",
                "  liquid status               - print whether it is on, its speed and busy cells");
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

            case "status": {
                String busy = game.hasWorld() ? game.getLevel().getLiquids().getActiveCells() + " busy cells" : "no world";
                console.println("Liquid simulation: " + (LiquidSimulator.isEnabled() ? "on" : "off")
                        + " | " + LiquidSimulator.getTicksPerSecond() + " ticks/sec | " + busy);
                break;
            }

            default:
                printUsage(console);
        }
    }

    private static void printUsage(Console console) {
        console.println("Usage: liquid <sim|speed|step|status>  (type 'liquid help' for details)");
    }
}