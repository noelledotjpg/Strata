package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.interfaces.screen.CreateWorldScreen;
import com.nikohalfkino.rubydung.interfaces.screen.WorldListScreen;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldManager {

    private static final String SAVES_DIR = "saves";
    private static final String LEVEL_FILE_NAME = "level.dat";
    private static final String SIZE_FILE_NAME = "size.dat";
    private static final int MAX_NAME_LENGTH = 32;

    private WorldManager() {
    }

    public static final class WorldInfo {

        public final String name;
        public final long lastPlayed;

        public WorldInfo(String name, long lastPlayed) {
            this.name = name;
            this.lastPlayed = lastPlayed;
        }
    }

    public static List<WorldInfo> listWorlds() {
        List<WorldInfo> worlds = new ArrayList<>();

        File[] entries = new File(SAVES_DIR).listFiles();
        if (entries == null) {
            return worlds;
        }

        for (File entry : entries) {
            File levelFile = new File(entry, LEVEL_FILE_NAME);
            if (entry.isDirectory() && levelFile.isFile()) {
                worlds.add(new WorldInfo(entry.getName(), levelFile.lastModified()));
            }
        }

        worlds.sort(Comparator.comparingLong((WorldInfo world) -> world.lastPlayed).reversed());
        return worlds;
    }

    public static boolean exists(String worldName) {
        return new File(new File(SAVES_DIR, worldName), LEVEL_FILE_NAME).isFile();
    }

    public static File levelFile(String worldName) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();
        return new File(dir, LEVEL_FILE_NAME);
    }

    public static void saveWorldSize(String worldName, int width, int height, int depth) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(new File(dir, SIZE_FILE_NAME)))) {
            dos.writeInt(width);
            dos.writeInt(height);
            dos.writeInt(depth);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static int[] loadWorldSize(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), SIZE_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            return new int[]{dis.readInt(), dis.readInt(), dis.readInt()};
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void pruneIncompleteWorlds() {
        File[] entries = new File(SAVES_DIR).listFiles();
        if (entries == null) {
            return;
        }

        for (File entry : entries) {
            if (entry.isDirectory() && !new File(entry, LEVEL_FILE_NAME).isFile()) {
                deleteRecursively(entry);
            }
        }
    }

    public static void delete(String worldName) {
        deleteRecursively(new File(SAVES_DIR, worldName));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    public static String sanitize(String rawName) {
        String cleaned = rawName.trim()
                .replaceAll("[^a-zA-Z0-9 _-]", "")
                .replaceAll("\\s+", " ");

        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }

        return cleaned;
    }
}