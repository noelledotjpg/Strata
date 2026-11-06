package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelEditListener;
import com.nikohalfkino.rubydung.level.NetworkSnapshot;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.generation.WorldGenStatus;
import com.nikohalfkino.rubydung.player.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * the player's side of a connection to a GameServer: joins, downloads the world, then keeps it in step.
 *
 * packets are read on the network thread, parsed there, and everything that touches the game runs on the main thread
 * through RubyDung.runOnMainThread. while the world is still downloading those tasks wait in "pending"
 */
public final class GameClient implements Connection.Listener, LevelEditListener {

    public enum State { CONNECTING, DOWNLOADING, PLAYING, CLOSED }

    private static final int STATE_EVERY_TICKS = 3;

    private final RubyDung game;
    private final String host;
    private final int port;
    private final String name;
    private final RemotePlayers remotes;

    private volatile State state = State.CONNECTING;
    private Connection connection;

    // download (network thread)
    private int welcomeId;
    private int levelWidth, levelHeight, levelDepth;
    private int levelType;
    private long levelSeed;
    private double spawnX, spawnY, spawnZ;
    private float timeOfDay;
    private ByteArrayOutputStream download;
    private int downloadLength;

    // main thread
    private Level level;
    private final List<Runnable> pending = new ArrayList<>();
    private int ticks = 0;
    private boolean closedHandled = false;

    public GameClient(RubyDung game, String host, int port, String name, RemotePlayers remotes) {
        this.game = game;
        this.host = host;
        this.port = port;
        this.name = name;
        this.remotes = remotes;
    }

    public State getState() {
        return this.state;
    }

    public String getAddress() {
        return this.host + ":" + this.port;
    }

    /** main thread: opens the loading screen, connects in the background */
    public void connect() {
        this.game.getSession().beginNetworkWorld();
        WorldGenStatus.set("Connecting to " + getAddress() + "...");

        Thread thread = new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(this.host, this.port), Protocol.CONNECT_TIMEOUT_MS);
                this.connection = new Connection(socket, this, "server");
                this.connection.start();
                this.connection.send(Protocol.C_HELLO, out -> {
                    out.writeInt(Protocol.VERSION);
                    out.writeUTF(this.name);
                });
            } catch (IOException | RuntimeException e) {
                failed("Could not connect to " + getAddress() + ": " + e.getMessage());
            }
        }, "net-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** main thread: leaves the server */
    public void disconnect() {
        this.state = State.CLOSED;
        this.closedHandled = true;
        if (this.level != null && this.level.getEditListener() == this) {
            this.level.setEditListener(null);
        }
        if (this.connection != null) {
            this.connection.close("Disconnected");
        }
        this.remotes.clear();
    }

    /** main thread, every game tick */
    public void tick() {
        if (this.state != State.PLAYING || this.connection == null) {
            return;
        }
        Player player = this.game.getPlayer();
        if (player == null) {
            return;
        }

        if (++this.ticks % STATE_EVERY_TICKS == 0) {
            double x = player.x, y = player.y, z = player.z;
            float yaw = player.yRotation, pitch = player.xRotation, body = player.bodyYaw;
            int flags = player.flying ? Protocol.FLAG_FLYING : 0;
            this.connection.send(Protocol.C_STATE, out -> {
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(yaw);
                out.writeFloat(pitch);
                out.writeFloat(body);
                out.writeByte(flags);
            });
        }
        if (this.ticks % (Protocol.KEEPALIVE_MS * 60 / 1000) == 0) {
            this.connection.send(Protocol.C_KEEPALIVE, out -> {
            });
        }
    }

    public void sendChat(String text) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_CHAT, out -> out.writeUTF(text));
        }
    }

    // Connection.Listener (network thread)

    @Override
    public void onPacket(Connection connection, int type, DataInputStream in) throws IOException {
        switch (type) {
            case Protocol.S_WELCOME:
                onWelcome(in);
                break;
            case Protocol.S_LEVEL_CHUNK:
                onLevelChunk(in);
                break;
            case Protocol.S_LEVEL_END:
                onLevelEnd();
                break;
            case Protocol.S_BLOCK_CHANGE: {
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), id = in.readInt(), data = in.readInt();
                later(() -> this.level.applyTileFromNetwork(x, y, z, id, data));
                break;
            }
            case Protocol.S_SUBTILES: {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                List<SubTiles.Entry> entries = Protocol.readEntries(in);
                later(() -> this.level.applySubTilesFromNetwork(x, y, z, entries));
                break;
            }
            case Protocol.S_LIQUIDS: {
                int count = in.readInt();
                int[][] cells = new int[count][5];
                for (int i = 0; i < count; i++) {
                    for (int j = 0; j < 5; j++) {
                        cells[i][j] = in.readInt();
                    }
                }
                later(() -> {
                    for (int[] c : cells) {
                        this.level.applyLiquidFromNetwork(c[0], c[1], c[2], c[3], c[4]);
                    }
                });
                break;
            }
            case Protocol.S_PLAYER_JOIN: {
                int id = in.readInt();
                String playerName = in.readUTF();
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                later(() -> this.remotes.add(new RemotePlayer(id, playerName, x, y, z)));
                break;
            }
            case Protocol.S_PLAYER_LEAVE: {
                int id = in.readInt();
                later(() -> this.remotes.remove(id));
                break;
            }
            case Protocol.S_PLAYER_MOVES: {
                int count = in.readInt();
                double[][] moves = new double[count][];
                for (int i = 0; i < count; i++) {
                    int id = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat(), body = in.readFloat();
                    int flags = in.readUnsignedByte();
                    moves[i] = new double[]{id, x, y, z, yaw, pitch, body, flags};
                }
                later(() -> {
                    for (double[] m : moves) {
                        RemotePlayer p = this.remotes.get((int) m[0]);
                        if (p != null) {
                            p.setState(m[1], m[2], m[3], (float) m[4], (float) m[5], (float) m[6], (int) m[7]);
                        }
                    }
                });
                break;
            }
            case Protocol.S_CHAT: {
                String sender = in.readUTF();
                String line = in.readUTF();
                later(() -> {
                    if (sender.isEmpty()) {
                        this.game.getChatLog().notice(line);
                    } else {
                        this.game.getChatLog().sayAs(sender, line);
                    }
                });
                break;
            }
            case Protocol.S_TIME: {
                float time = in.readFloat();
                later(() -> this.game.getDayNightCycle().setTime(time));
                break;
            }
            case Protocol.S_TELEPORT: {
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                float yaw = in.readFloat(), pitch = in.readFloat();
                later(() -> {
                    Player player = this.game.getPlayer();
                    if (player != null) {
                        player.setPositionAndRotation(x, y, z, yaw, pitch);
                    }
                });
                break;
            }
            case Protocol.S_KICK: {
                String reason = in.readUTF();
                failed(reason);
                break;
            }
            case Protocol.S_KEEPALIVE:
                break;
            default:
                throw new IOException("unknown packet " + type);
        }
    }

    @Override
    public void onClosed(Connection connection, String reason) {
        failed(reason);
    }

    // download

    private void onWelcome(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != Protocol.VERSION) {
            throw new IOException("protocol version " + version);
        }
        this.welcomeId = in.readInt();
        this.levelWidth = in.readInt();
        this.levelHeight = in.readInt();
        this.levelDepth = in.readInt();
        this.levelType = in.readInt();
        this.levelSeed = in.readLong();
        this.spawnX = in.readDouble();
        this.spawnY = in.readDouble();
        this.spawnZ = in.readDouble();
        this.timeOfDay = in.readFloat();
        this.downloadLength = in.readInt();
        this.download = new ByteArrayOutputStream(Math.max(1024, this.downloadLength));
        this.state = State.DOWNLOADING;
        WorldGenStatus.set("Downloading the world...");
    }

    private void onLevelChunk(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] chunk = new byte[length];
        in.readFully(chunk);
        this.download.write(chunk);
        int percent = this.downloadLength == 0 ? 100 : (int) (this.download.size() * 100L / this.downloadLength);
        WorldGenStatus.set("Downloading the world... " + percent + "%");
    }

    private void onLevelEnd() {
        byte[] compressed = this.download.toByteArray();
        this.download = null;
        WorldGenStatus.set("Unpacking the world...");

        // building the level takes a while, the network thread must keep reading meanwhile
        Thread builder = new Thread(() -> {
            try {
                LevelGenerator.WorldType[] types = LevelGenerator.WorldType.values();
                LevelGenerator.WorldType type = types[Math.max(0, Math.min(types.length - 1, this.levelType))];
                Level built = NetworkSnapshot.read(compressed, "Multiplayer", this.levelWidth, this.levelHeight,
                        this.levelDepth, type, this.levelSeed);
                built.setPlayerPosition(this.spawnX, this.spawnY, this.spawnZ, 0.0F, 0.0F);
                this.game.runOnMainThread(() -> worldReady(built));
            } catch (IOException | RuntimeException e) {
                e.printStackTrace();
                failed("Could not read the world from the server: " + e.getMessage());
            }
        }, "net-level-build");
        builder.setDaemon(true);
        builder.start();
    }

    /** main thread */
    private void worldReady(Level built) {
        if (this.state == State.CLOSED) {
            return;
        }
        this.level = built;
        this.game.getSession().finishNetworkWorld(built);
        built.setEditListener(this);
        this.game.getDayNightCycle().setTime(this.timeOfDay);
        this.state = State.PLAYING;

        List<Runnable> waiting = new ArrayList<>(this.pending);
        this.pending.clear();
        for (Runnable task : waiting) {
            task.run();
        }
    }

    /** runs a task on the main thread once the world exists (tasks that arrive earlier wait, in order) */
    private void later(Runnable task) {
        this.game.runOnMainThread(() -> {
            if (this.state == State.PLAYING) {
                task.run();
            } else if (this.state != State.CLOSED) {
                this.pending.add(task);
            }
        });
    }

    private void failed(String reason) {
        this.game.runOnMainThread(() -> {
            if (this.closedHandled) {
                return;
            }
            this.closedHandled = true;
            this.state = State.CLOSED;
            if (this.connection != null) {
                this.connection.close(reason);
            }
            Multiplayer.clientClosed(this.game, this, reason);
        });
    }

    // LevelEditListener: what this player does to the world is asked of the server (and shown at once)

    @Override
    public void tileEdited(int x, int y, int z, int id, int data) {
        sendBlock(x, y, z, id, data);
    }

    @Override
    public void liquidPlaced(int x, int y, int z, int tileId) {
        sendBlock(x, y, z, tileId, 0);
    }

    @Override
    public void liquidChanged(int x, int y, int z, int tileId, int fill) {
        // the server runs the liquids
    }

    @Override
    public void subTilesEdited(int x, int y, int z) {
        if (this.connection == null || this.level == null) {
            return;
        }
        List<SubTiles.Entry> entries = this.level.getSubTiles().cellEntries(x, y, z);
        this.connection.send(Protocol.C_SUBTILES, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            Protocol.writeEntries(out, entries);
        });
    }

    private void sendBlock(int x, int y, int z, int id, int data) {
        if (this.connection == null || this.state != State.PLAYING) {
            return;
        }
        this.connection.send(Protocol.C_BLOCK_ACTION, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeInt(id);
            out.writeInt(data);
        });
    }

    public int getPlayerId() {
        return this.welcomeId;
    }
}
