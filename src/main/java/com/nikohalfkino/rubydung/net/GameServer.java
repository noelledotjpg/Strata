package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelEditListener;
import com.nikohalfkino.rubydung.level.NetworkSnapshot;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.player.Player;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * the authoritative copy of a world. used two ways: a LAN host runs it inside the game (the host's own Level is the
 * world, the host plays on it and tick() is called from the game loop), a dedicated server runs it headless (ServerMain).
 *
 * THREADING: sockets live on their own threads, but every packet is queued and handled inside tick(), so the level is
 * only ever touched by the one thread that calls tick() (and edits the level)
 */
public final class GameServer implements Connection.Listener, LevelEditListener {

    private static final int INBOX_LIMIT = 8192;
    private static final long BROADCAST_NANOS = 50_000_000L;
    private static final long TIME_SYNC_NANOS = 5_000_000_000L;
    private static final int LIQUIDS_PER_PACKET = 4096;

    /** one connected player */
    private static final class Session {
        final int id;
        final Connection connection;
        String name = "";
        boolean joined = false;

        boolean hasState = false;
        boolean dirty = false;
        double x, y, z;
        float yaw, pitch, bodyYaw;
        int flags;

        long actionWindowStart = 0L;
        int actionsInWindow = 0;

        Session(int id, Connection connection) {
            this.id = id;
            this.connection = connection;
        }
    }

    private final Level level;
    private final int port;
    private final int maxPlayers;
    private final DayNightCycle clock;
    private final Consumer<String> log;
    // what a player said, for the host's own chat (sender, text); null on a dedicated server, which just logs it
    private final BiConsumer<String, String> chatSink;

    // LAN host: its name, its player and the registry that draws the others. all null on a dedicated server
    private final String hostName;
    private final Player hostPlayer;
    private final RemotePlayers mirror;

    // dedicated server: nobody else steps the liquids and the clock
    private boolean ownsSimulation = false;

    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Session> sessions = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Long, int[]> pendingLiquids = new LinkedHashMap<>();

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private long lastBroadcast = 0L;
    private long lastTimeSync = 0L;
    private long lastKeepalive = 0L;
    private long lastTick = 0L;
    private float clockCarry = 0.0F;

    // what the host looked like when the others were last told
    private double sentHostX, sentHostY, sentHostZ;
    private float sentHostYaw, sentHostPitch, sentHostBody;
    private int sentHostFlags;
    private boolean hostSent = false;

    public GameServer(Level level, int port, int maxPlayers, DayNightCycle clock, String hostName, Player hostPlayer,
                      RemotePlayers mirror, Consumer<String> log,
                      BiConsumer<String, String> chatSink) {
        this.level = level;
        this.port = port;
        this.maxPlayers = maxPlayers;
        this.clock = clock;
        this.hostName = hostName;
        this.hostPlayer = hostPlayer;
        this.mirror = mirror;
        this.log = log;
        this.chatSink = chatSink;
    }

    /** a dedicated server steps the liquids and the clock itself */
    public void setOwnsSimulation(boolean ownsSimulation) {
        this.ownsSimulation = ownsSimulation;
    }

    public int getPort() {
        return this.port;
    }

    public boolean isRunning() {
        return this.running;
    }

    public Level getLevel() {
        return this.level;
    }

    public int playerCount() {
        int count = 0;
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                count++;
            }
        }
        return count;
    }

    public List<String> playerNames() {
        List<String> names = new ArrayList<>();
        if (this.hostName != null) {
            names.add(this.hostName);
        }
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                names.add(s.name);
            }
        }
        return names;
    }

    // lifecycle

    public void start() throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(this.port));
        this.running = true;
        this.lastTick = System.nanoTime();
        this.level.setEditListener(this);

        Thread accept = new Thread(this::acceptLoop, "net-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** on the thread that ticks the server */
    public void stop(String reason) {
        if (!this.running) {
            return;
        }
        this.running = false;
        if (this.level.getEditListener() == this) {
            this.level.setEditListener(null);
        }
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Session s : new ArrayList<>(this.sessions.values())) {
            s.connection.send(Protocol.S_KICK, out -> out.writeUTF(reason));
            s.connection.closeAfterFlush(reason);
        }
        this.sessions.clear();
        if (this.mirror != null) {
            this.mirror.clear();
        }
    }

    private void acceptLoop() {
        while (this.running) {
            try {
                Socket socket = this.serverSocket.accept();
                int id = this.nextId.getAndIncrement();
                Connection connection = new Connection(socket, this, "c" + id);
                Session session = new Session(id, connection);
                connection.attachment = session;
                this.inbox.add(() -> this.sessions.put(id, session));
                connection.start();
            } catch (IOException e) {
                if (this.running) {
                    this.log.accept("[server] accept failed: " + e.getMessage());
                }
            }
        }
    }

    // Connection.Listener (reader threads: only queue)

    @Override
    public void onPacket(Connection connection, int type, DataInputStream in) {
        Session session = (Session) connection.attachment;
        this.inbox.add(() -> {
            try {
                handle(session, type, in);
            } catch (IOException | RuntimeException e) {
                this.log.accept("[server] bad packet " + type + " from " + session.name + ": " + e);
                kick(session, "Bad packet");
            }
        });
    }

    @Override
    public void onClosed(Connection connection, String reason) {
        Session session = (Session) connection.attachment;
        this.inbox.add(() -> {
            if (this.sessions.remove(session.id) != null && session.joined) {
                session.joined = false;
                sendAll(Connection.frame(Protocol.S_PLAYER_LEAVE, out -> out.writeInt(session.id)));
                if (this.mirror != null) {
                    this.mirror.remove(session.id);
                }
                announce(session.name + " left the game");
            }
        });
    }

    // tick

    /** call about 20 to 60 times a second from the thread that owns the level */
    public void tick() {
        if (!this.running) {
            return;
        }

        for (int i = 0; i < INBOX_LIMIT; i++) {
            Runnable task = this.inbox.poll();
            if (task == null) {
                break;
            }
            task.run();
        }

        long now = System.nanoTime();
        float seconds = Math.min((now - this.lastTick) * 1.0E-9F, 0.25F);
        this.lastTick = now;

        if (this.ownsSimulation) {
            this.level.getLiquids().update(seconds);
            this.clockCarry += seconds * 60.0F;
            while (this.clockCarry >= 1.0F) {
                this.clock.tick(0.0F);
                this.clockCarry -= 1.0F;
            }
        }

        if (now - this.lastBroadcast >= BROADCAST_NANOS) {
            this.lastBroadcast = now;
            broadcastMoves();
            flushLiquids();
        }

        if (now - this.lastTimeSync >= TIME_SYNC_NANOS) {
            this.lastTimeSync = now;
            float time = this.clock.getTime();
            sendAll(Connection.frame(Protocol.S_TIME, out -> out.writeFloat(time)));
        }

        if (now - this.lastKeepalive >= Protocol.KEEPALIVE_MS * 1_000_000L) {
            this.lastKeepalive = now;
            sendAll(Connection.frame(Protocol.S_KEEPALIVE, out -> {
            }));
        }
    }

    // packets (all on the tick thread)

    private void handle(Session s, int type, DataInputStream in) throws IOException {
        if (type == Protocol.C_HELLO) {
            if (!s.joined && s.name.isEmpty()) {
                onHello(s, in);
            }
            return;
        }
        if (!s.joined) {
            return;
        }

        switch (type) {
            case Protocol.C_STATE:
                onState(s, in);
                break;
            case Protocol.C_BLOCK_ACTION:
                onBlockAction(s, in);
                break;
            case Protocol.C_SUBTILES:
                onSubTiles(s, in);
                break;
            case Protocol.C_CHAT:
                onChat(s, in);
                break;
            case Protocol.C_KEEPALIVE:
                break;
            default:
                throw new IOException("unknown packet " + type);
        }
    }

    private void onHello(Session s, DataInputStream in) throws IOException {
        int version = in.readInt();
        String name = Protocol.sanitizeName(in.readUTF());

        if (version != Protocol.VERSION) {
            kick(s, "Version mismatch (server " + Protocol.VERSION + ", you " + version + ")");
            return;
        }
        if (playerCount() + (this.hostName != null ? 1 : 0) >= this.maxPlayers) {
            kick(s, "The server is full");
            return;
        }

        s.name = uniqueName(name);
        double[] spawn = spawnPoint();

        byte[] snapshot = NetworkSnapshot.write(this.level);
        float time = this.clock.getTime();

        s.connection.send(Protocol.S_WELCOME, out -> {
            out.writeInt(Protocol.VERSION);
            out.writeInt(s.id);
            out.writeInt(this.level.width);
            out.writeInt(this.level.height);
            out.writeInt(this.level.depth);
            out.writeInt(this.level.getWorldType().ordinal());
            out.writeLong(this.level.getSeed());
            out.writeDouble(spawn[0]);
            out.writeDouble(spawn[1]);
            out.writeDouble(spawn[2]);
            out.writeFloat(time);
            out.writeInt(snapshot.length);
        });
        for (int offset = 0; offset < snapshot.length; offset += Protocol.LEVEL_CHUNK_BYTES) {
            int from = offset;
            int length = Math.min(Protocol.LEVEL_CHUNK_BYTES, snapshot.length - offset);
            s.connection.send(Protocol.S_LEVEL_CHUNK, out -> {
                out.writeInt(length);
                out.write(snapshot, from, length);
            });
        }
        s.connection.send(Protocol.S_LEVEL_END, out -> {
        });

        s.joined = true;
        s.x = spawn[0];
        s.y = spawn[1];
        s.z = spawn[2];

        // the newcomer learns who is here, the others learn about the newcomer
        if (this.hostPlayer != null) {
            double hx = this.hostPlayer.x, hy = this.hostPlayer.y, hz = this.hostPlayer.z;
            s.connection.send(Protocol.S_PLAYER_JOIN, out -> {
                out.writeInt(Protocol.HOST_ID);
                out.writeUTF(this.hostName);
                out.writeDouble(hx);
                out.writeDouble(hy);
                out.writeDouble(hz);
            });
        }
        for (Session other : this.sessions.values()) {
            if (other != s && other.joined) {
                s.connection.send(Protocol.S_PLAYER_JOIN, out -> {
                    out.writeInt(other.id);
                    out.writeUTF(other.name);
                    out.writeDouble(other.x);
                    out.writeDouble(other.y);
                    out.writeDouble(other.z);
                });
            }
        }
        byte[] joinFrame = Connection.frame(Protocol.S_PLAYER_JOIN, out -> {
            out.writeInt(s.id);
            out.writeUTF(s.name);
            out.writeDouble(spawn[0]);
            out.writeDouble(spawn[1]);
            out.writeDouble(spawn[2]);
        });
        for (Session other : this.sessions.values()) {
            if (other != s && other.joined) {
                other.connection.sendFrame(joinFrame);
            }
        }
        if (this.mirror != null) {
            this.mirror.add(new RemotePlayer(s.id, s.name, spawn[0], spawn[1], spawn[2]));
        }

        announce(s.name + " joined the game");
    }

    private void onState(Session s, DataInputStream in) throws IOException {
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        float bodyYaw = in.readFloat();
        int flags = in.readUnsignedByte();

        if (!finite(x) || !finite(y) || !finite(z)) {
            return;
        }
        s.x = clamp(x, -64.0D, this.level.width + 64.0D);
        s.y = clamp(y, -64.0D, this.level.depth + 256.0D);
        s.z = clamp(z, -64.0D, this.level.height + 64.0D);
        s.yaw = yaw;
        s.pitch = pitch;
        s.bodyYaw = bodyYaw;
        s.flags = flags;
        s.hasState = true;
        s.dirty = true;

        if (this.mirror != null) {
            RemotePlayer p = this.mirror.get(s.id);
            if (p != null) {
                p.setState(s.x, s.y, s.z, yaw, pitch, bodyYaw, flags);
            }
        }
    }

    private void onBlockAction(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        int id = in.readInt() & 0xFF;
        int data = in.readInt() & 0xFF;

        if (!allowAction(s) || !inReach(s, x, y, z) || Tile.byId(id) == null) {
            sendCorrection(s, x, y, z);
            return;
        }

        // the level reports the change to this server, which tells everyone (the sender included)
        this.level.setTile(x, y, z, id, data);

        if (!(Tile.byId(id) instanceof LiquidTile)
                && (this.level.getTile(x, y, z) != id || this.level.getData(x, y, z) != data)) {
            // what the sender did could not happen here, put its copy right
            sendCorrection(s, x, y, z);
        }
    }

    private void onSubTiles(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        List<SubTiles.Entry> entries = Protocol.readEntries(in);

        if (!allowAction(s) || !inReach(s, x, y, z)) {
            sendSubTiles(s.connection, x, y, z);
            return;
        }
        this.level.setSubTilesOfCell(x, y, z, entries);
    }

    private void onChat(Session s, DataInputStream in) throws IOException {
        String text = Protocol.sanitizeChat(in.readUTF());
        if (!text.isEmpty()) {
            say(s.name, text);
        }
    }

    // helpers

    private boolean allowAction(Session s) {
        long now = System.nanoTime();
        if (now - s.actionWindowStart > 1_000_000_000L) {
            s.actionWindowStart = now;
            s.actionsInWindow = 0;
        }
        return ++s.actionsInWindow <= Protocol.MAX_ACTIONS_PER_SECOND;
    }

    private boolean inReach(Session s, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.level.width || y >= this.level.depth || z >= this.level.height) {
            return false;
        }
        if (!s.hasState) {
            return true;
        }
        double dx = x + 0.5D - s.x;
        double dy = y + 0.5D - s.y;
        double dz = z + 0.5D - s.z;
        return dx * dx + dy * dy + dz * dz <= Protocol.MAX_REACH * Protocol.MAX_REACH;
    }

    private void sendCorrection(Session s, int x, int y, int z) {
        int id = this.level.getTile(x, y, z);
        int data = this.level.getData(x, y, z);
        s.connection.send(Protocol.S_BLOCK_CHANGE, out -> writeBlock(out, x, y, z, id, data));
        sendSubTiles(s.connection, x, y, z);
    }

    private void sendSubTiles(Connection connection, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.level.width || y >= this.level.depth || z >= this.level.height) {
            return;
        }
        List<SubTiles.Entry> entries = this.level.getSubTiles().cellEntries(x, y, z);
        connection.send(Protocol.S_SUBTILES, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            Protocol.writeEntries(out, entries);
        });
    }

    private static void writeBlock(java.io.DataOutputStream out, int x, int y, int z, int id, int data) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(id);
        out.writeInt(data);
    }

    private void kick(Session s, String reason) {
        s.connection.send(Protocol.S_KICK, out -> out.writeUTF(reason));
        s.connection.closeAfterFlush(reason);
    }

    private String uniqueName(String wanted) {
        String name = wanted;
        int suffix = 2;
        while (nameTaken(name)) {
            String tail = Integer.toString(suffix++);
            name = wanted.substring(0, Math.min(wanted.length(), Protocol.MAX_NAME_LENGTH - tail.length())) + tail;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        if (name.equalsIgnoreCase(this.hostName)) {
            return true;
        }
        for (Session s : this.sessions.values()) {
            if (s.joined && s.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** the host's position if there is a host, else the saved one, else the nearest dry land to the middle of the map */
    private double[] spawnPoint() {
        if (this.hostPlayer != null) {
            return new double[]{this.hostPlayer.x, this.hostPlayer.y + 0.1D, this.hostPlayer.z};
        }
        if (this.level.hasSavedPosition) {
            return new double[]{this.level.savedPlayerX, this.level.savedPlayerY, this.level.savedPlayerZ};
        }

        int cx = this.level.width / 2;
        int cz = this.level.height / 2;
        for (int radius = 0; radius < 256; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    int top = surface(x, z);
                    if (top >= 0 && !this.level.getLiquids().has(x, top + 1, z)) {
                        return new double[]{x + 0.5D, top + 1 + Player.EYE_HEIGHT + 0.1D, z + 0.5D};
                    }
                }
            }
        }
        return new double[]{cx + 0.5D, this.level.depth, cz + 0.5D};
    }

    private int surface(int x, int z) {
        if (x < 0 || z < 0 || x >= this.level.width || z >= this.level.height) {
            return -1;
        }
        for (int y = this.level.depth - 1; y >= 0; y--) {
            if (this.level.isSolidTile(x, y, z)) {
                return y;
            }
        }
        return -1;
    }

    // outgoing

    private void sendAll(byte[] frame) {
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                s.connection.sendFrame(frame);
            }
        }
    }

    /** a line for everybody: the clients' chat and the host's chat (or the console of a dedicated server) */
    public void announce(String line) {
        sendAll(Connection.frame(Protocol.S_CHAT, out -> {
            out.writeUTF("");
            out.writeUTF(line);
        }));
        this.log.accept(line);
    }

    /** a player's chat message: everybody gets it as "<sender> text", the host's chat shows it the same way */
    private void say(String sender, String text) {
        sendAll(Connection.frame(Protocol.S_CHAT, out -> {
            out.writeUTF(sender);
            out.writeUTF(text);
        }));
        if (this.chatSink != null) {
            this.chatSink.accept(sender, text);
        } else {
            this.log.accept("<" + sender + "> " + text);
        }
    }

    /** the LAN host said something: everybody else hears it, the host's own chat shows it already */
    public void hostSays(String text) {
        String clean = Protocol.sanitizeChat(text);
        if (!clean.isEmpty() && this.hostName != null) {
            sendAll(Connection.frame(Protocol.S_CHAT, out -> {
                out.writeUTF(this.hostName);
                out.writeUTF(clean);
            }));
        }
    }

    /** moves a player (dedicated server console, or a LAN host command) */
    public void teleport(String name, double x, double y, double z) {
        for (Session s : this.sessions.values()) {
            if (s.joined && s.name.equalsIgnoreCase(name)) {
                s.connection.send(Protocol.S_TELEPORT, out -> {
                    out.writeDouble(x);
                    out.writeDouble(y);
                    out.writeDouble(z);
                    out.writeFloat(s.yaw);
                    out.writeFloat(s.pitch);
                });
            }
        }
    }

    public boolean kickByName(String name, String reason) {
        for (Session s : this.sessions.values()) {
            if (s.joined && s.name.equalsIgnoreCase(name)) {
                kick(s, reason);
                return true;
            }
        }
        return false;
    }

    private void broadcastMoves() {
        boolean hostChanged = false;
        double hx = 0, hy = 0, hz = 0;
        float hyaw = 0, hpitch = 0, hbody = 0;
        int hflags = 0;
        if (this.hostPlayer != null) {
            hx = this.hostPlayer.x;
            hy = this.hostPlayer.y;
            hz = this.hostPlayer.z;
            hyaw = this.hostPlayer.yRotation;
            hpitch = this.hostPlayer.xRotation;
            hbody = this.hostPlayer.bodyYaw;
            hflags = this.hostPlayer.flying ? Protocol.FLAG_FLYING : 0;
            hostChanged = !this.hostSent || hx != this.sentHostX || hy != this.sentHostY || hz != this.sentHostZ
                    || hyaw != this.sentHostYaw || hpitch != this.sentHostPitch || hbody != this.sentHostBody
                    || hflags != this.sentHostFlags;
            if (hostChanged) {
                this.hostSent = true;
                this.sentHostX = hx;
                this.sentHostY = hy;
                this.sentHostZ = hz;
                this.sentHostYaw = hyaw;
                this.sentHostPitch = hpitch;
                this.sentHostBody = hbody;
                this.sentHostFlags = hflags;
            }
        }

        List<Session> movers = new ArrayList<>();
        for (Session s : this.sessions.values()) {
            if (s.joined && s.dirty) {
                movers.add(s);
            }
        }
        if (movers.isEmpty() && !hostChanged) {
            return;
        }

        final boolean sendHost = hostChanged;
        final double fx = hx, fy = hy, fz = hz;
        final float fyaw = hyaw, fpitch = hpitch, fbody = hbody;
        final int fflags = hflags;

        for (Session recipient : this.sessions.values()) {
            if (!recipient.joined) {
                continue;
            }
            List<Session> list = new ArrayList<>(movers.size());
            for (Session m : movers) {
                if (m != recipient) {
                    list.add(m);
                }
            }
            if (list.isEmpty() && !sendHost) {
                continue;
            }
            recipient.connection.send(Protocol.S_PLAYER_MOVES, out -> {
                out.writeInt(list.size() + (sendHost ? 1 : 0));
                if (sendHost) {
                    out.writeInt(Protocol.HOST_ID);
                    out.writeDouble(fx);
                    out.writeDouble(fy);
                    out.writeDouble(fz);
                    out.writeFloat(fyaw);
                    out.writeFloat(fpitch);
                    out.writeFloat(fbody);
                    out.writeByte(fflags);
                }
                for (Session m : list) {
                    out.writeInt(m.id);
                    out.writeDouble(m.x);
                    out.writeDouble(m.y);
                    out.writeDouble(m.z);
                    out.writeFloat(m.yaw);
                    out.writeFloat(m.pitch);
                    out.writeFloat(m.bodyYaw);
                    out.writeByte(m.flags);
                }
            });
        }
        for (Session m : movers) {
            m.dirty = false;
        }
    }

    private void flushLiquids() {
        if (this.pendingLiquids.isEmpty()) {
            return;
        }
        List<int[]> cells = new ArrayList<>(this.pendingLiquids.values());
        this.pendingLiquids.clear();

        for (int from = 0; from < cells.size(); from += LIQUIDS_PER_PACKET) {
            List<int[]> part = cells.subList(from, Math.min(cells.size(), from + LIQUIDS_PER_PACKET));
            sendAll(Connection.frame(Protocol.S_LIQUIDS, out -> {
                out.writeInt(part.size());
                for (int[] c : part) {
                    out.writeInt(c[0]);
                    out.writeInt(c[1]);
                    out.writeInt(c[2]);
                    out.writeInt(c[3]);
                    out.writeInt(c[4]);
                }
            }));
        }
    }

    // LevelEditListener: the level changed, tell everybody

    @Override
    public void tileEdited(int x, int y, int z, int id, int data) {
        sendAll(Connection.frame(Protocol.S_BLOCK_CHANGE, out -> writeBlock(out, x, y, z, id, data)));
    }

    @Override
    public void liquidPlaced(int x, int y, int z, int tileId) {
        // the liquid layer reports the cell itself (liquidChanged)
    }

    @Override
    public void liquidChanged(int x, int y, int z, int tileId, int fill) {
        long key = ((long) y << 40) | ((long) z << 20) | x;
        this.pendingLiquids.put(key, new int[]{x, y, z, tileId, fill});
    }

    @Override
    public void subTilesEdited(int x, int y, int z) {
        List<SubTiles.Entry> entries = this.level.getSubTiles().cellEntries(x, y, z);
        sendAll(Connection.frame(Protocol.S_SUBTILES, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            Protocol.writeEntries(out, entries);
        }));
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
