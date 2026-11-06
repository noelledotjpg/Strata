package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TimeCommand implements Command {

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Controls the day-night cycle";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: time <set|speed|freeze|unfreeze|query>",
                "  time set <dawn|noon|dusk|midnight|0.0-1.0>  - jump to a time",
                "  time speed <multiplier>                     - e.g. 2 = 2x faster, 0 = freeze",
                "  time freeze / unfreeze                      - stop / resume the cycle",
                "  time query                                  - print current time & speed");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "set", "speed", "freeze", "unfreeze", "query")
                .note("set", "jump to a time")
                .note("speed", "change how fast time passes")
                .note("freeze", "stop the cycle")
                .note("unfreeze", "resume the cycle")
                .note("query", "print the current time and speed");

        if (given.length == 0) {
            return Collections.singletonList(action);
        }

        switch (given[0].toLowerCase()) {
            case "set":
                return Arrays.asList(action, CommandArg.choice("time", "dawn", "noon", "dusk", "midnight")
                        .orNumber()
                        .note("dawn", "0.25")
                        .note("noon", "0.5")
                        .note("dusk", "0.75")
                        .note("midnight", "0.0"));
            case "speed":
                return Arrays.asList(action, CommandArg.number("multiplier"));
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

        DayNightCycle cycle = game.getDayNightCycle();
        String sub = args[0].toLowerCase();

        switch (sub) {

            case "set": {
                // time set <dawn|noon|dusk|midnight|0.0–1.0>
                if (args.length < 2) {
                    console.println("Usage: time set <dawn|noon|dusk|midnight|0.0-1.0>");
                    return;
                }
                float t = parseTimeValue(args[1]);
                if (Float.isNaN(t)) {
                    console.println("Unknown time value '" + args[1] + "'. Use dawn, noon, dusk, midnight, or a number 0.0-1.0");
                    return;
                }
                cycle.setTime(t);
                console.println("Time set to " + args[1] + " (" + String.format("%.3f", t) + ")");
                break;
            }

            case "speed": {
                // time speed <multiplier>   (1.0 = normal, 0 = paused, 10 = 10× faster)
                if (args.length < 2) {
                    console.println("Usage: time speed <multiplier>  (1.0 = normal, 0 = frozen)");
                    return;
                }
                try {
                    float speed = Float.parseFloat(args[1]);
                    if (speed < 0) {
                        console.println("Speed must be >= 0");
                        return;
                    }
                    cycle.setSpeed(speed);
                    if (speed == 0) {
                        console.println("Time frozen (speed 0)");
                    } else {
                        console.println("Time speed set to " + speed + "x");
                    }
                } catch (NumberFormatException e) {
                    console.println("Speed must be a number");
                }
                break;
            }

            case "freeze": {
                cycle.setFrozen(true);
                console.println("Time frozen at " + String.format("%.3f", cycle.getTime())
                        + " (" + phaseName(cycle.getTime()) + ")");
                break;
            }

            case "unfreeze": {
                cycle.setFrozen(false);
                console.println("Time unfrozen");
                break;
            }

            case "query": {
                float t = cycle.getTime();
                console.println("Time: " + String.format("%.4f", t)
                        + " (" + phaseName(t) + ")"
                        + " | speed: " + cycle.getSpeed() + "x"
                        + (cycle.isFrozen() ? " [FROZEN]" : ""));
                break;
            }

            default:
                printUsage(console);
        }
    }

    // helpers

    private static float parseTimeValue(String s) {
        switch (s.toLowerCase()) {
            case "midnight": return 0.0F;
            case "dawn":     return 0.25F;
            case "noon":     return 0.5F;
            case "dusk":     return 0.75F;
            default:
                try {
                    float v = Float.parseFloat(s);
                    return (v >= 0.0F && v <= 1.0F) ? v : Float.NaN;
                } catch (NumberFormatException e) {
                    return Float.NaN;
                }
        }
    }

    private static String phaseName(float t) {
        if (t < 0.125F || t >= 0.875F) return "night";
        if (t < 0.375F)                return "dawn";
        if (t < 0.625F)                return "day";
        return "dusk";
    }

    private static void printUsage(Console console) {
        console.println("Usage: time <set|speed|freeze|unfreeze|query>  (type 'time help' for details)");
    }
}