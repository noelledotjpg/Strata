package com.nikohalfkino.rubydung.level.generation;

/**
 * what world creation is doing right now, as a short line for the loading screen ("Carving...").
 * written by the generator on whatever thread it runs on, read by the screen every frame, so it is volatile
 */
public final class WorldGenStatus {

    private static volatile String current = "";

    private WorldGenStatus() {
    }

    public static void set(String text) {
        current = text;
    }

    public static String get() {
        return current;
    }
}
