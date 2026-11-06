package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.screen.JoinServerScreen;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.PlayerCamera;
import com.nikohalfkino.rubydung.render.PlayerRenderer;
import com.nikohalfkino.rubydung.util.GameSettings;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * the game's one door to multiplayer: at most one LAN server (this game hosts the world it is playing) or one client
 * connection (this game plays on somebody else's server) at a time. everything here runs on the main thread
 */
public final class Multiplayer {

    private static final int MAX_PLAYERS = 16;

    private static GameServer server;
    private static GameClient client;
    private static final RemotePlayers REMOTES = new RemotePlayers();

    private Multiplayer() {
    }

    // state

    /** playing on somebody else's server (the world is a copy, liquids are not simulated here, nothing is saved) */
    public static boolean isClient() {
        return client != null;
    }

    public static boolean isHosting() {
        return server != null && server.isRunning();
    }

    /** hosting or joined: things that only make sense alone (dimensional doors) are off */
    public static boolean isActive() {
        return isClient() || isHosting();
    }

    public static RemotePlayers remotes() {
        return REMOTES;
    }

    // LAN host

    /** opens the world being played to the network. returns an error message, or null when it worked */
    public static String hostLan(RubyDung game, int port) {
        if (isActive()) {
            return isHosting() ? "Already open on port " + server.getPort() : "You are playing on a server";
        }
        if (!game.hasWorld()) {
            return "There is no world to open";
        }

        Level level = game.getLevel();
        String hostName = GameSettings.getUsername();
        GameServer created = new GameServer(level, port, MAX_PLAYERS, game.getDayNightCycle(), hostName,
                game.getPlayer(), REMOTES, line -> game.getChatLog().notice(line),
                (sender, text) -> game.getChatLog().sayAs(sender, text));
        try {
            created.start();
        } catch (IOException e) {
            return "Could not open port " + port + ": " + e.getMessage();
        }
        server = created;
        REMOTES.clear();

        game.getChatLog().notice("Open to LAN on port " + port + ". Friends can join with:");
        for (String address : localAddresses()) {
            game.getChatLog().notice("  " + address + ":" + port);
        }
        return null;
    }

    /** closes the LAN server, kicking everyone (the world stays open and playable) */
    public static void stopHosting(String reason) {
        if (server != null) {
            server.stop(reason);
            server = null;
            REMOTES.clear();
        }
    }

    // client

    public static void connect(RubyDung game, String address) {
        if (isActive()) {
            return;
        }

        String host = address.trim();
        int port = Protocol.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
                host = host.substring(0, colon).trim();
            } catch (NumberFormatException e) {
                game.openScreen(new JoinServerScreen(game, "That port is not a number"));
                return;
            }
        }
        if (host.isEmpty()) {
            game.openScreen(new JoinServerScreen(game, "Enter the server's address"));
            return;
        }

        REMOTES.clear();
        client = new GameClient(game, host, port, GameSettings.getUsername(), REMOTES);
        client.connect();
    }

    /** main thread: the connection ended (kicked, lost, or never made). back to the join screen with the reason */
    static void clientClosed(RubyDung game, GameClient closed, String reason) {
        if (client != closed) {
            return;
        }
        client = null;
        REMOTES.clear();
        game.quitToTitle();
        game.openScreen(new JoinServerScreen(game, reason));
    }

    // both

    /** called from the game loop every tick, whether or not a screen is open */
    public static void tick(RubyDung game) {
        if (server != null) {
            server.tick();
        }
        if (client != null) {
            client.tick();
        }
    }

    /** leaving the world (quit to title, closing the game) */
    public static void shutdown(String reason) {
        stopHosting(reason);
        if (client != null) {
            GameClient closing = client;
            client = null;
            closing.disconnect();
        }
        REMOTES.clear();
    }

    /** the world being played is about to be replaced: a LAN server would be left holding a dead level */
    public static void worldLeft() {
        stopHosting("The host left the world");
    }

    /** chat typed in the chat screen. true if it was handled here (a client), false if the caller should show it itself */
    public static boolean sendChat(String text) {
        if (client != null) {
            client.sendChat(text);
            return true;
        }
        if (server != null) {
            server.hostSays(text);
        }
        return false;
    }

    /** everybody in the world for the chat screen's user list: yourself first, then the others */
    public static List<String> playerNames(String self) {
        List<String> names = new ArrayList<>();
        names.add(self);
        if (isActive()) {
            for (RemotePlayer p : REMOTES.all()) {
                if (!p.name.equalsIgnoreCase(self)) {
                    names.add(p.name);
                }
            }
        }
        return names;
    }

    // drawing

    public static void renderPlayers(RubyDung game, Level level, PlayerRenderer renderer, PlayerCamera camera) {
        if (isActive()) {
            REMOTES.render(level, renderer, camera);
        }
    }

    public static void renderNameTags(Font font, int width, int height) {
        if (isActive()) {
            REMOTES.renderTags(font, width, height);
        }
    }

    // the addresses this computer has on the local network, for telling friends

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        out.add(address.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
        }
        if (out.isEmpty()) {
            out.add("(your local IP address)");
        }
        return out;
    }
}
