package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.SubTiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** packet ids, limits and the small codecs both sides share. every packet is [int length][byte type][payload] */
public final class Protocol {

    private Protocol() {
    }

    /** bump when a packet changes, a client with another version is refused */
    public static final int VERSION = 2;

    public static final int DEFAULT_PORT = 25566;

    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    public static final int LEVEL_CHUNK_BYTES = 32 * 1024;

    public static final int SOCKET_TIMEOUT_MS = 30_000;
    public static final int KEEPALIVE_MS = 5_000;
    public static final int CONNECT_TIMEOUT_MS = 8_000;

    public static final int MAX_NAME_LENGTH = 16;
    public static final int MAX_CHAT_LENGTH = 256;
    public static final int MAX_SUBTILES_PER_CELL = 16;

    /** how far from its last reported position a player may change a block (blocks, generous for lag and flying) */
    public static final double MAX_REACH = 24.0;
    public static final int MAX_ACTIONS_PER_SECOND = 400;

    // client -> server
    public static final int C_HELLO = 1;         // int version, UTF name
    public static final int C_STATE = 2;         // double x y z, float yaw pitch bodyYaw, byte flags
    public static final int C_BLOCK_ACTION = 3;  // int x y z id data
    public static final int C_SUBTILES = 4;      // int x y z, entries
    public static final int C_CHAT = 5;          // UTF text
    public static final int C_KEEPALIVE = 6;

    // server -> client
    public static final int S_WELCOME = 20;      // see GameServer.onHello
    public static final int S_LEVEL_CHUNK = 21;  // int length, bytes
    public static final int S_LEVEL_END = 22;
    public static final int S_BLOCK_CHANGE = 23; // int x y z id data
    public static final int S_SUBTILES = 24;     // int x y z, entries
    public static final int S_LIQUIDS = 25;      // int count, then count * (int x y z id fill)
    public static final int S_PLAYER_JOIN = 26;  // int id, UTF name, double x y z
    public static final int S_PLAYER_LEAVE = 27; // int id
    public static final int S_PLAYER_MOVES = 28; // int count, then count * (int id, double x y z, float yaw pitch bodyYaw, byte flags)
    public static final int S_CHAT = 29;         // UTF sender (empty = a notice), UTF text
    public static final int S_TIME = 30;         // float time of day
    public static final int S_TELEPORT = 31;     // double x y z, float yaw pitch
    public static final int S_KICK = 32;         // UTF reason
    public static final int S_KEEPALIVE = 33;

    public static final int FLAG_FLYING = 1;

    /** id the host of a LAN game has among the players (a dedicated server has no such player) */
    public static final int HOST_ID = 0;

    /** writes the body of a packet */
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public static String sanitizeName(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            }
            if (out.length() >= MAX_NAME_LENGTH) {
                break;
            }
        }
        return out.length() == 0 ? "Player" : out.toString();
    }

    public static String sanitizeChat(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= ' ' && c != 0x7F) {
                out.append(c);
            }
            if (out.length() >= MAX_CHAT_LENGTH) {
                break;
            }
        }
        return out.toString().trim();
    }

    public static void writeEntries(DataOutputStream out, List<SubTiles.Entry> entries) throws IOException {
        out.writeInt(entries.size());
        for (SubTiles.Entry e : entries) {
            out.writeInt(e.x);
            out.writeInt(e.y);
            out.writeInt(e.z);
            out.writeUTF(e.tileName);
            out.writeInt(e.data);
            out.writeFloat(e.ox);
            out.writeFloat(e.oy);
            out.writeFloat(e.oz);
        }
    }

    public static List<SubTiles.Entry> readEntries(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SUBTILES_PER_CELL) {
            throw new IOException("bad sub-tile count " + count);
        }
        List<SubTiles.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            String name = in.readUTF();
            int data = in.readInt();
            float ox = in.readFloat();
            float oy = in.readFloat();
            float oz = in.readFloat();
            entries.add(new SubTiles.Entry(x, y, z, name, data, ox, oy, oz));
        }
        return entries;
    }
}
