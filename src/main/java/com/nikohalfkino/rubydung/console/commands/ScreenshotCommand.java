package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.Console;

public class ScreenshotCommand implements Command {

    @Override
    public String name() {
        return "screenshot";
    }

    @Override
    public String description() {
        return "Takes a screenshot: screenshot [pano|iso] [direction|scale] [scale]";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        if (game.getLevel() == null) {
            console.println("No world is loaded.");
            return;
        }

        int scale = 1;

        if (args.length == 0) {
            game.takeScreenshot(1);
            console.println("Screenshot saved.");
            return;
        }

        String arg0 = args[0].toLowerCase();

        if (isScaleArgument(arg0)) {
            scale = parseScale(arg0);
            game.takeScreenshot(scale);
            console.println("Screenshot saved (" + scale + "x scale).");
            return;
        }

        if (arg0.equals("pano") || arg0.equals("panorama")) {
            if (args.length > 1 && isScaleArgument(args[1])) {
                scale = parseScale(args[1]);
            }
            game.takePanoramaScreenshot(scale);
            console.println("Panorama screenshot saved (" + scale + "x scale).");
        } else if (arg0.equals("iso") || arg0.equals("isometric")) {
            String dir = "NW";
            if (args.length > 1 && !isScaleArgument(args[1])) {
                dir = args[1].toUpperCase();
                if (args.length > 2 && isScaleArgument(args[2])) {
                    scale = parseScale(args[2]);
                } else {
                    scale = 2;
                }
            } else if (args.length > 1 && isScaleArgument(args[1])) {
                scale = parseScale(args[1]);
            } else {
                scale = 2;
            }
            game.takeIsometricScreenshot(dir, scale);
            console.println("Isometric screenshot saved (" + dir + ", " + scale + "x scale).");
        } else {
            console.println("Unknown screenshot argument '" + args[0] + "'.");
        }
    }

    private boolean isScaleArgument(String arg) {
        return arg.endsWith("x") || arg.matches("\\d+");
    }

    private int parseScale(String arg) {
        try {
            String clean = arg.endsWith("x") ? arg.substring(0, arg.length() - 1) : arg;
            return Math.max(1, Integer.parseInt(clean));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}