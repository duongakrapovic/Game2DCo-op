package source.client;

import source.common.*;
import source.server.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Uses only synthetic XML and loopback sockets; never opens game assets or saves. */
public final class LobbySpawnRegressionChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class MemoryMap extends GameMap {
        final List<String> reads = new ArrayList<>();
        boolean broken;
        @Override protected InputStream openResource(String path) throws IOException {
            reads.add(path);
            if (path.endsWith("tileset.tsx")) {
                return stream("<tileset tilecount='256'><tile type='water' id='1'><properties>"
                        + "<property value='true' type='bool' name='collision'/>"
                        + "</properties></tile><tile id='2'><properties><property name='collision'>1</property>"
                        + "</properties></tile></tileset>");
            }
            if (!path.endsWith(".tmx")) throw new AssertionError("Unexpected resource " + path);
            if (broken) throw new IOException("Synthetic missing chunk");
            boolean first = path.endsWith("chunk0_0.tmx");
            StringJoiner ground = new StringJoiner(","), objects = new StringJoiner(",");
            for (int index = 0; index < Common.CHUNK_SIZE * Common.CHUNK_SIZE; index++) {
                // GID 17 maps to local tile 0; GID 18 is blocked water.
                ground.add(first && (index == 0 || index == 3 * Common.CHUNK_SIZE + 3) ? "18" : "17");
                objects.add(first && index == 6 * Common.CHUNK_SIZE + 6 ? "19" : "0");
            }
            return stream("<map height='32' width='32'><tileset firstgid='17' source='tileset.tsx'/>"
                    + "<layer><data encoding='csv'>" + ground + "</data></layer>"
                    + "<layer><data encoding='csv'>" + objects + "</data></layer></map>");
        }
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void mapAndSpawns() throws Exception {
        MemoryMap map = new MemoryMap();
        check(map.isCollisionAtWorld(50, 50), "Unloaded map is blocked");
        map.ensureCollisionMapLoaded();
        check(map.reads.size() == 10 && map.reads.get(0).endsWith("tileset.tsx"), "Properties loaded before nine chunks");
        map.ensureCollisionMapLoaded();
        check(map.reads.size() == 10, "Loaded map is not reset");
        int tile = Common.TILE_SIZE, width = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * tile;
        check(map.isCollisionAtWorld(0, 0), "Water collision parsed with reordered XML attributes");
        check(map.isCollisionAtWorld(6 * tile, 6 * tile), "Collision in an overlay layer");
        check(!map.isCollisionAtWorld(tile, tile), "Ground remains walkable");
        check(!map.isCollisionAtWorld(32 * tile, tile), "Chunk boundary maps to the correct local tile");
        check(map.isCollisionAtWorld(-1, 0) && map.isCollisionAtWorld(0, -1), "Negative boundaries");
        check(map.isCollisionAtWorld(width, 0) && map.isCollisionAtWorld(0, width), "Exclusive maximum boundaries");
        check(!map.isWalkable(width - 10, tile), "Whole hitbox must stay inside the map");
        check(!map.isWalkable(2 * tile + 1, 2 * tile + 1), "Blocked bottom-right corner rejects spawn");
        check(!map.isWalkable(Float.NaN, tile) && !map.isWalkable(Float.POSITIVE_INFINITY, tile), "Invalid floats rejected");
        var zero = map.validatedPlayerSpawn(0, 0);
        var water = map.validatedPlayerSpawn(3 * tile, 3 * tile);
        var outside = map.validatedPlayerSpawn(width, width);
        for (var point : List.of(zero, water, outside)) {
            check(point.x == Common.SPAWN_X && point.y == Common.SPAWN_Y, "Bad saved position resets to configured spawn");
        }
        var valid = map.validatedPlayerSpawn(tile + 0.25f, tile + 0.5f);
        check(valid.x == tile + 0.25f && valid.y == tile + 0.5f, "Valid fractional saved position preserved");
        check(new GameData.PlayerState().x == Common.SPAWN_X, "Default player spawn");

        // Populate tile images in memory to check that camera rendering matches collision coordinates.
        var field = GameMap.class.getDeclaredField("tiles"); field.setAccessible(true);
        GameMap.Tile[] tiles = (GameMap.Tile[]) field.get(map);
        Color[] colors = {Color.GREEN, Color.BLUE, Color.RED};
        for (int i = 0; i < colors.length; i++) {
            tiles[i].image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            tiles[i].image.setRGB(0, 0, colors[i].getRGB());
        }
        BufferedImage canvas = new BufferedImage(480, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        map.draw(graphics, 240, 240, 480, 480); graphics.dispose();
        check(canvas.getRGB(1, 1) == Color.BLUE.getRGB(), "Rendered water matches collision tile");
        check(canvas.getRGB(tile + 1, tile + 1) == Color.GREEN.getRGB(), "Rendered ground matches physics");
        graphics = canvas.createGraphics();
        map.draw(graphics, 32 * tile + 240, 240, 480, 480); graphics.dispose();
        check(canvas.getRGB(1, 1) == Color.GREEN.getRGB(), "Culling across chunks keeps world coordinates aligned");

        MemoryMap missing = new MemoryMap(); missing.broken = true;
        try { missing.ensureCollisionMapLoaded(); throw new AssertionError("Missing chunk accepted"); }
        catch (IllegalStateException expected) { check(missing.isCollisionAtWorld(tile, tile), "Missing chunks do not become open ground"); }
    }

    private static void clientHandshake() throws Exception {
        CountDownLatch joined = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ClientNetwork> reference = new AtomicReference<>();
        AtomicBoolean premature = new AtomicBoolean();
        try (ServerSocket tcp = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
                DatagramSocket udp = new DatagramSocket(tcp.getLocalPort(), InetAddress.getLoopbackAddress())) {
            tcp.setSoTimeout(5000); udp.setSoTimeout(5000);
            Thread mock = new Thread(() -> {
                try (Socket socket = tcp.accept()) {
                    socket.setSoTimeout(200);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                    try { in.readLine(); premature.set(true); }
                    catch (SocketTimeoutException expected) { /* No commands before WELCOME. */ }
                    socket.setSoTimeout(5000);
                    out.println("WELCOME:P1:token");
                    if (!"ROOM_LIST".equals(in.readLine())) throw new AssertionError("Missing post-WELCOME room list request");
                    if (!"CREATE_ROOM:Test:Player".equals(in.readLine())) throw new AssertionError("Pending create command lost");
                    out.println("ROOM_LIST;Test (1 nguoi)"); out.println("JOINED:Test");
                    DatagramPacket packet = new DatagramPacket(new byte[4096], 4096); udp.receive(packet);
                    if (GameProtocol.getGameUdpType(packet.getData()) != Common.UDP_GAME_REGISTER)
                        throw new AssertionError("Expected immediate UDP registration");
                    String[] reg = GameProtocol.getGameUdpPayload(packet.getData(), 0, packet.getLength()).split("\\|", -1);
                    byte[] ack = GameProtocol.createGameUdpPacket(Common.UDP_GAME_ACK, "P1|42|" + reg[1]);
                    udp.send(new DatagramPacket(ack, ack.length, packet.getSocketAddress()));
                    release.await(5, TimeUnit.SECONDS);
                } catch (Throwable error) { failure.set(error); joined.countDown(); }
            }, "Lobby-Handshake-Test");
            mock.setDaemon(true); mock.start();
            ClientNetwork client = new ClientNetwork(InetAddress.getLoopbackAddress().getHostAddress(), tcp.getLocalPort(), new ClientNetwork.Listener() {
                public void onMessage(String message) {
                    if (message.startsWith("WELCOME:")) {
                        reference.get().send(Common.CMD_ROOM_LIST);
                        reference.get().send("CREATE_ROOM:Test:Player");
                    }
                    if (message.startsWith("JOINED:")) joined.countDown();
                }
                public void onStateChanged(ClientNetwork.State state, String info) {
                    if (state == ClientNetwork.State.CONNECTED && !reference.get().isReady()) premature.set(true);
                }
                public void onSessionLost(String reason) { failure.set(new AssertionError(reason)); }
            });
            reference.set(client); client.connect();
            try {
                check(joined.await(6, TimeUnit.SECONDS), "Client receives JOINED");
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!client.isGameplayActive() && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
                if (failure.get() != null) throw new AssertionError("Handshake failed", failure.get());
                check(!premature.get(), "Readiness waits for WELCOME");
                check(client.isReady() && client.isGameplayActive(), "TCP and registered UDP ready independently");
            } finally { release.countDown(); client.close(); mock.join(2000); }
        }
    }

    private static void actualServerLobby() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
        ServerApp app = new ServerApp();
        ServerNetwork server = new ServerNetwork(port, app);
        try {
            server.start();
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                    DatagramSocket udp = new DatagramSocket()) {
                socket.setSoTimeout(30000); udp.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                // The first SecureRandom initialization can be slow in a restricted Windows runtime.
                check(in.readLine().startsWith("WELCOME:"), "Real server WELCOME");
                check(in.readLine().startsWith("ROOM_LIST"), "Real server initial room list");
                out.println("ROOM_LIST"); check(in.readLine().startsWith("ROOM_LIST"), "TCP room list command");
                out.println("JOIN_ROOM:Missing:Player"); check(in.readLine().startsWith("ERROR:"), "Missing room returns ERROR");
                out.println("LEAVE_ROOM"); check("LEFT".equals(in.readLine()), "TCP leave returns LEFT");
                check(in.readLine().startsWith("ROOM_LIST"), "Room list after leave");
                byte[] ping = GameProtocol.createGameUdpPacket(Common.UDP_GAME_PING, "123");
                udp.send(new DatagramPacket(ping, ping.length, InetAddress.getLoopbackAddress(), port));
                DatagramPacket reply = new DatagramPacket(new byte[1024], 1024); udp.receive(reply);
                check(GameProtocol.getGameUdpType(reply.getData()) == Common.UDP_GAME_PONG, "UDP shares the same numeric port as TCP");
            }
        } finally { app.shutdown(); }
    }

    public static void main(String[] args) throws Exception {
        mapAndSpawns(); clientHandshake(); actualServerLobby();
        System.out.println("PASS: " + checks + " lobby/spawn assertions");
    }
}
