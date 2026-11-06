package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * a dedicated server: no window, no player, just the world and the network.
 *
 *   java -Xmx2g -cp <the game's classes and libraries> com.nikohalfkino.rubydung.net.ServerMain --world MyWorld
 *
 * options: --world NAME (default "server"), --port N (25566), --max-players N (16), --size tiny|default|large|insane|xxl,
 * --type flat|natural|... (a LevelGenerator.WorldType name), --seed N, --autosave MINUTES (5, 0 = off)
 *
 * an existing world of that name is loaded (its saved size and type win), otherwise a new one is made.
 * console commands: help, list, say TEXT, kick NAME, save, stop
 */
public final class ServerMain {

    private static final int TICKS_PER_SECOND = 20;

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        String worldName = "server";
        int port = Protocol.DEFAULT_PORT;
        int maxPlayers = 16;
        int autosaveMinutes = 5;
        LevelGenerator.WorldSize size = LevelGenerator.WorldSize.DEFAULT;
        LevelGenerator.WorldType type = null;
        Long seed = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (flag) {
                case "--world":
                    worldName = WorldManager.sanitize(need(flag, value));
                    i++;
                    break;
                case "--port":
                    port = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--max-players":
                    maxPlayers = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--autosave":
                    autosaveMinutes = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--size":
                    size = parseSize(need(flag, value));
                    i++;
                    break;
                case "--type":
                    type = LevelGenerator.WorldType.valueOf(need(flag, value).toUpperCase(Locale.ROOT));
                    i++;
                    break;
                case "--seed":
                    seed = Long.parseLong(need(flag, value));
                    i++;
                    break;
                default:
                    System.err.println("unknown option " + flag);
                    System.exit(2);
            }
        }

        // the tile registry has to exist before any level does (the game does this on its main thread too)
        Tile.getTiles();

        DayNightCycle clock = new DayNightCycle();
        Level level;
        if (WorldManager.exists(worldName) && WorldManager.levelFile(worldName).isFile()) {
            System.out.println("[server] loading world \"" + worldName + "\"");
            int[] savedSize = WorldManager.loadWorldSize(worldName);
            LevelGenerator.WorldSize fallback = LevelGenerator.WorldSize.DEFAULT;
            int width = savedSize != null ? savedSize[0] : fallback.width;
            int height = savedSize != null ? savedSize[1] : fallback.height;
            int depth = savedSize != null ? savedSize[2] : fallback.depth;
            LevelGenerator.WorldType savedType = WorldManager.loadWorldType(worldName);
            level = new Level(worldName, width, height, depth, savedType != null ? savedType : LevelGenerator.WorldType.FLAT);
            if (!WorldManager.loadWorldTime(worldName, clock)) {
                clock.applyCreationSettings(DayNightCycle.TimeMode.NORMAL, DayNightCycle.DEFAULT_START_TIME);
            }
        } else {
            LevelGenerator.WorldType made = type != null ? type : LevelGenerator.WorldType.NATURAL;
            long madeSeed = seed != null ? seed : new Random().nextLong();
            System.out.println("[server] creating world \"" + worldName + "\" (" + made + ", " + size + ", seed " + madeSeed + ")");
            clock.applyCreationSettings(DayNightCycle.TimeMode.NORMAL, DayNightCycle.DEFAULT_START_TIME);
            WorldManager.saveWorldSettings(worldName, size, DayNightCycle.TimeMode.NORMAL);
            level = new Level(worldName, size.width, size.height, size.depth, made, madeSeed);
            level.save();
            WorldManager.saveWorldTime(worldName, clock);
        }

        GameServer server = new GameServer(level, port, maxPlayers, clock, null, null, null, System.out::println, null);
        server.setOwnsSimulation(true);
        server.start();
        System.out.println("[server] listening on port " + port + ". type \"help\" for commands");

        Queue<String> commands = new ConcurrentLinkedQueue<>();
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line.trim());
                }
            } catch (IOException ignored) {
            }
        }, "console");
        console.setDaemon(true);
        console.start();

        final Thread mainThread = Thread.currentThread();
        final boolean[] stopRequested = {false};
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested[0] = true;
            try {
                mainThread.join(15_000);
            } catch (InterruptedException ignored) {
            }
        }));

        long tickNanos = 1_000_000_000L / TICKS_PER_SECOND;
        long nextSave = System.nanoTime() + autosaveMinutes * 60L * 1_000_000_000L;
        long next = System.nanoTime();

        while (!stopRequested[0]) {
            server.tick();

            String command;
            while ((command = commands.poll()) != null) {
                if (runCommand(command, server, level, worldName, clock)) {
                    stopRequested[0] = true;
                }
            }

            if (autosaveMinutes > 0 && System.nanoTime() >= nextSave) {
                save(level, worldName, clock);
                nextSave = System.nanoTime() + autosaveMinutes * 60L * 1_000_000_000L;
            }

            next += tickNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            } else if (sleep < -1_000_000_000L) {
                next = System.nanoTime();   // far behind (a long save): don't try to catch up
            }
        }

        System.out.println("[server] stopping...");
        server.stop("Server closed");
        save(level, worldName, clock);
        System.out.println("[server] bye");
    }

    private static boolean runCommand(String line, GameServer server, Level level, String worldName, DayNightCycle clock) {
        if (line.isEmpty()) {
            return false;
        }
        String[] parts = line.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1] : "";

        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "help":
                System.out.println("help | list | say TEXT | kick NAME | save | stop");
                break;
            case "list":
                System.out.println(server.playerCount() + " player(s): " + String.join(", ", server.playerNames()));
                break;
            case "say":
                server.announce("[Server] " + arg);
                break;
            case "kick":
                System.out.println(server.kickByName(arg, "Kicked by the server") ? "kicked " + arg : "no player named " + arg);
                break;
            case "save":
                save(level, worldName, clock);
                break;
            case "stop":
                return true;
            default:
                System.out.println("unknown command, type \"help\"");
        }
        return false;
    }

    private static void save(Level level, String worldName, DayNightCycle clock) {
        System.out.println("[server] saving...");
        level.save();
        WorldManager.saveWorldTime(worldName, clock);
        System.out.println("[server] saved");
    }

    private static LevelGenerator.WorldSize parseSize(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "tiny":
                return LevelGenerator.WorldSize.TINY;
            case "default":
                return LevelGenerator.WorldSize.DEFAULT;
            case "large":
                return LevelGenerator.WorldSize.LARGE;
            case "insane":
            case "xl":
                return LevelGenerator.WorldSize.INSANE;
            case "xxl":
                return LevelGenerator.WorldSize.XXL;
            default:
                throw new IllegalArgumentException("size must be tiny, default, large, insane or xxl");
        }
    }

    private static String need(String flag, String value) {
        if (value == null) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return value;
    }
}
