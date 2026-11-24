package source.client;

import source.common.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone checks without assets, save files, Swing windows or external libraries. */
/*
 * Kiểm hồi quy protocol, chiến đấu/A* và nối lại UDP qua server giả loopback. Không chạy cửa sổ game thật
 * hay đọc save người dùng.
 * Dùng snapshot mẫu, CountDownLatch/AtomicReference để đợi thread mock và bắt lỗi. Nhiều check packet sai
 * được sinh có seed, không tương đương phủ mọi điều kiện mạng.
 */
public final class AuditRegressionChecks {
    /*
     * checks: Bộ đếm, tài nguyên nhân tạo hoặc cấu hình chỉ dùng trong kiểm thử.
     */
    private static int checks;
    /*
     * check()
     * Tăng bộ đếm điều kiện, nếu biểu thức false thì ném AssertionError với message để test dừng và chỉ ra
     * điều kiện sai. Không thay dữ liệu game đang chơi.
     */
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /*
     * main()
     * Chạy binarySnapshots, combatAndPathfinding và reconnectKeepsUdp rồi in số check đạt.
     */
    public static void main(String[] args) throws Exception {
        binarySnapshots();
        combatAndPathfinding();
        reconnectKeepsUdp();
        System.out.println("PASS: " + checks + " regression assertions");
    }

    /*
     * world()
     * Tạo snapshot mẫu bốn player có tên Unicode/ACK/ping và 15 quái. Dùng kiểm round-trip và delta, không
     * là thế giới server đang sống.
     */
    private static GameData.WorldSnapshot world() {
        GameData.WorldSnapshot world = new GameData.WorldSnapshot();
        for (int i = 0; i < 4; i++) {
            GameData.PlayerState p = new GameData.PlayerState("P" + i, "Người_chơi_" + i);
            p.x = 100 + i * 50; p.y = 200; p.lastAckSeq = 17; p.pingMs = 80;
            world.players.add(p);
        }
        for (int i = 0; i < 15; i++) world.monsters.add(new GameData.MonsterState("M" + i, 400 + i * 50, 600));
        return world;
    }

    /*
     * packets()
     * Gọi encoder binary với stream cố định 123 rồi kiểm mọi datagram dưới 500 byte và có magic GAM1; trả
     * list packet.
     */
    private static List<byte[]> packets(long seq, long base, GameData.WorldSnapshot world, GameData.WorldSnapshot baseline) {
        List<byte[]> packets = GameProtocol.serializeSnapshotBinary(seq, 123, base, world, baseline);
        for (byte[] packet : packets) {
            check(packet.length < 500, "Every UDP datagram must be below 500 bytes");
            check(GameProtocol.isGameUdpPacket(packet, packet.length), "GAM1 header");
        }
        return packets;
    }

    /*
     * feed()
     * Đưa list packet lần lượt vào decoder và trả snapshot hoàn chỉnh mới nhất. Không send DatagramSocket.
     */
    private static GameData.WorldSnapshot feed(GameProtocol.SnapshotDecoder decoder, List<byte[]> packets) {
        GameData.WorldSnapshot result = null;
        for (byte[] packet : packets) {
            GameData.WorldSnapshot decoded = decoder.accept(packet, GameProtocol.UDP_HEADER_BYTES,
                    packet.length - GameProtocol.UDP_HEADER_BYTES);
            if (decoded != null) result = decoded;
        }
        return result;
    }

    /*
     * equivalent()
     * Kiểm actual khác null và so chuỗi do serializeSnapshot sinh cho expected/actual. Vì so qua tiện ích
     * text có làm tròn/thiếu một số trường, test này không tự chứng minh bit-for-bit mọi field binary.
     */
    private static void equivalent(GameData.WorldSnapshot expected, GameData.WorldSnapshot actual) {
        check(actual != null, "Snapshot completed");
        check(GameProtocol.serializeSnapshot(expected).equals(GameProtocol.serializeSnapshot(actual)), "Snapshot round trip");
    }

    /*
     * binarySnapshots()
     * Kiểm full ghép đảo mảnh, duplicate/late drop, delta độc lập delta mất, thêm/xóa entity, baseline,
     * full tới sau delta, missing piece, stream cũ và 200 packet ngẫu nhiên sai. Kiểm copy snapshot tách
     * khỏi source.
     */
    private static void binarySnapshots() {
        GameData.WorldSnapshot baseline = world();
        GameProtocol.SnapshotDecoder decoder = new GameProtocol.SnapshotDecoder();
        List<byte[]> full = packets(1, 1, baseline, null);
        check(full.size() > 1, "Full state is split into bounded datagrams");
        Collections.reverse(full);
        equivalent(baseline, feed(decoder, full));
        check(feed(decoder, full) == null, "Duplicate frame ignored");

        GameData.WorldSnapshot changed = GameProtocol.copySnapshot(baseline);
        changed.players.get(0).hp = 70;
        changed.players.get(0).isAttacking = true;
        changed.players.get(0).attackTicks = 7;
        changed.monsters.get(0).x += 0.125f;
        // Tick two is lost. Tick three still carries all differences from the full baseline.
        packets(2, 1, changed, baseline);
        changed.players.get(1).x += 11.5f;
        equivalent(changed, feed(decoder, packets(3, 1, changed, baseline)));
        check(baseline.players.get(0).hp == 100, "Baseline remains immutable");
        check(feed(decoder, packets(2, 1, changed, baseline)) == null, "Late state ignored");

        changed.monsters.remove(2);
        changed.monsters.add(new GameData.MonsterState("NEW", 12, 34));
        changed.players.remove(3);
        equivalent(changed, feed(decoder, packets(4, 1, changed, baseline)));
        equivalent(baseline, feed(decoder, packets(5, 1, baseline, baseline)));

        GameProtocol.SnapshotDecoder reordered = new GameProtocol.SnapshotDecoder();
        check(feed(reordered, packets(61, 60, changed, baseline)) == null, "Wait for missing full baseline");
        equivalent(changed, feed(reordered, packets(60, 60, baseline, null)));

        GameProtocol.SnapshotDecoder lost = new GameProtocol.SnapshotDecoder();
        check(feed(lost, full.subList(1, full.size())) == null, "Partial frame never published");
        check(feed(lost, packets(2, 1, changed, baseline)) == null, "No delta without baseline");
        equivalent(changed, feed(lost, packets(60, 60, changed, null)));
        lost.reset(); lost.expectStream(456);
        check(feed(lost, packets(120, 120, changed, null)) == null, "Old room stream rejected");

        Random random = new Random(9);
        for (int i = 0; i < 200; i++) {
            byte[] malformed = new byte[random.nextInt(500)]; random.nextBytes(malformed);
            check(lost.accept(malformed, 0, malformed.length) == null, "Malformed datagram ignored");
        }
        GameData.WorldSnapshot copy = GameProtocol.copySnapshot(changed);
        copy.players.get(0).x += 500;
        check(changed.players.get(0).x != copy.players.get(0).x, "Detached render snapshot");
    }

    /*
     * combatAndPathfinding()
     * Ghi lịch sử quái rồi di chuyển xa để so đòn live với rewind, kiểm knockback trên vị trí hiện tại và
     * clear history. Kiểm clamp ping, A* trên map trống, workspace tái dùng và điểm đầu sai.
     */
    private static void combatAndPathfinding() {
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        GameMap map = new GameMap() {
            /*
             * isCollisionAtWorld()
             * Map giả của test: biên âm chặn, vùng dương trống. Không nạp TMX thật.
             */
            @Override public boolean isCollisionAtWorld(int x, int y) { return x < 0 || y < 0; }
        };
        GameData.PlayerState player = new GameData.PlayerState("P", "Player");
        player.x = player.y = 0; player.direction = "right"; player.pingMs = 192;
        GameData.MonsterState monster = new GameData.MonsterState("M", 55, 0);
        List<GameData.MonsterState> monsters = List.of(monster);
        CombatSystem.MonsterHistory history = new CombatSystem.MonsterHistory();
        history.record(monsters, 0);
        monster.x = 200;
        history.record(monsters, 12);
        /*
         * Callback/lambda: Supplier cố định 180 tick cho ca combat kiểm thử.
         */
        CombatSystem.resolveAttack(player, monsters, map, () -> 180, null, 12);
        check(monster.hp == 30, "No hit at live distant position");
        /*
         * Callback/lambda: Supplier cố định 180 tick cho ca combat kiểm thử.
         */
        CombatSystem.resolveAttack(player, monsters, map, () -> 180, history, 12);
        check(monster.hp == 15, "Lag compensation hits historical bounds");
        check(monster.x > 200, "Knockback applied at current position");
        history.clear(monster.id);
        /*
         * Callback/lambda: Supplier cố định 180 tick cho ca combat kiểm thử.
         */
        CombatSystem.resolveAttack(player, monsters, map, () -> 180, history, 12);
        check(monster.hp == 15, "Cleared history cannot hit a new life");
        check(new GameData.PlayerInput(1, true, false, false, true, false, 10000).pingMs == 200, "Bound client ping");

        MonsterAI.PathfinderWorkspace workspace = new MonsterAI.PathfinderWorkspace();
        int[][] first = MonsterAI.findPath(1, 1, 9, 8, map, workspace);
        check(first.length == 15, "A-star finds shortest clear route");
        check(Arrays.deepEquals(first, MonsterAI.findPath(1, 1, 9, 8, map, workspace)), "Workspace reused safely");
        check(MonsterAI.findPath(-1, 1, 9, 8, map, workspace).length == 0, "Invalid path rejected");
    }

    /*
     * reconnectKeepsUdp()
     * Dựng TCP/UDP mock loopback, gửi WELCOME/JOINED, ngắt TCP rồi nhận RESUME token gốc và đăng ký UDP
     * lại. Kiểm không rò token tạm, endpoint UDP được giữ và snapshot sau resume tiếp tục tới listener;
     * dọn socket/client/thread ở finally.
     */
    private static void reconnectKeepsUdp() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstState = new CountDownLatch(1), resumedState = new CountDownLatch(1);
        CountDownLatch drop = new CountDownLatch(1);
        AtomicBoolean resumed = new AtomicBoolean();
        AtomicInteger registrations = new AtomicInteger();
        AtomicReference<SocketAddress> firstEndpoint = new AtomicReference<>();
        try (ServerSocket tcp = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
                DatagramSocket udp = new DatagramSocket(tcp.getLocalPort(), InetAddress.getLoopbackAddress())) {
            tcp.setSoTimeout(15000);
            ClientNetwork client = new ClientNetwork(InetAddress.getLoopbackAddress().getHostAddress(), tcp.getLocalPort(),
                    /*
                     * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống
                     * gọi các hàm override bên dưới.
                     */
                    new ClientNetwork.Listener() {
                        /*
                         * onMessage()
                         * Callback mock không xử lý dòng TCP vì test tập trung nhận snapshot/khôi phục
                         * UDP.
                         */
                        public void onMessage(String message) {}
                        /*
                         * onStateChanged()
                         * Callback mock bỏ qua state UI, không có cửa sổ cần cập nhật.
                         */
                        public void onStateChanged(ClientNetwork.State state, String info) {}
                        /*
                         * onSessionLost()
                         * Ghi AssertionError vào failure khi client mất phiên ngoài kỳ vọng; thread test
                         * chính đọc để kết luận.
                         */
                        public void onSessionLost(String reason) { failure.set(new AssertionError(reason)); }
                        /*
                         * onSnapshot()
                         * Đếm latch firstState hoặc resumedState tùy đã vào giai đoạn resume; cho test
                         * biết receiver UDP còn hoạt động ở cả hai lượt.
                         */
                        public void onSnapshot(GameData.WorldSnapshot snapshot) {
                            if (resumed.get()) resumedState.countDown(); else firstState.countDown();
                        }
                    });
            /*
             * Callback/lambda: Thân thread mock TCP tạo ngắt rồi phục hồi, kiểm client gửi đúng RESUME
             * token gốc.
             */
            Thread tcpServer = new Thread(() -> {
                try {
                    try (Socket first = tcp.accept()) {
                        PrintWriter out = new PrintWriter(new OutputStreamWriter(first.getOutputStream(), StandardCharsets.UTF_8), true);
                        out.println("WELCOME:P1:original"); out.println("JOINED:test");
                        if (!drop.await(8, TimeUnit.SECONDS)) throw new AssertionError("No first state");
                    }
                    try (Socket second = tcp.accept()) {
                        second.setSoTimeout(8000);
                        PrintWriter out = new PrintWriter(new OutputStreamWriter(second.getOutputStream(), StandardCharsets.UTF_8), true);
                        out.println("WELCOME:TEMP:temporary");
                        BufferedReader in = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8));
                        if (!"RESUME:original".equals(in.readLine())) throw new AssertionError("Canonical resume token");
                        resumed.set(true); out.println("RESUMED:test");
                        resumedState.await(8, TimeUnit.SECONDS);
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "Audit-Mock-TCP");
            /*
             * Callback/lambda: Thân thread mock UDP kiểm token, endpoint, ACK generation và phát snapshot
             * trước/sau reconnect.
             */
            Thread udpServer = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    while (!udp.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); udp.receive(packet);
                        if (GameProtocol.getGameUdpType(buffer) != Common.UDP_GAME_REGISTER) continue;
                        String token = GameProtocol.getGameUdpPayload(buffer, 0, packet.getLength());
                        String[] registration = token.split("\\|", -1);
                        if (registration.length != 2 || !"original".equals(registration[0]))
                            throw new AssertionError("Temporary token leaked into UDP");
                        registrations.incrementAndGet();
                        SocketAddress endpoint = packet.getSocketAddress();
                        if (firstEndpoint.get() == null) firstEndpoint.set(endpoint);
                        else if (!firstEndpoint.get().equals(endpoint)) throw new AssertionError("UDP socket replaced");
                        byte[] ack = GameProtocol.createGameUdpPacket(Common.UDP_GAME_ACK, "P1|7|" + registration[1]);
                        udp.send(new DatagramPacket(ack, ack.length, endpoint));
                        long seq = resumed.get() ? 60 : 1;
                        for (byte[] state : GameProtocol.serializeSnapshotBinary(seq, 7, seq, world(), null))
                            udp.send(new DatagramPacket(state, state.length, endpoint));
                    }
                } catch (SocketException closed) {
                    if (!udp.isClosed()) failure.compareAndSet(null, closed);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "Audit-Mock-UDP");
            tcpServer.setDaemon(true); udpServer.setDaemon(true);
            tcpServer.start(); udpServer.start(); client.connect();
            try {
                check(firstState.await(8, TimeUnit.SECONDS), "Initial binary UDP state received");
                drop.countDown();
                check(resumedState.await(12, TimeUnit.SECONDS), "UDP state received after TCP EOF and resume");
                check(registrations.get() >= 2, "UDP re-registers after reconnect");
                check("original".equals(client.getSessionToken()), "Resume preserves canonical token");
                check("P1".equals(client.getMyId()), "Resume preserves player identity");
                if (failure.get() != null) throw new AssertionError("Mock network failure", failure.get());
            } finally { drop.countDown(); client.close(); }
            tcpServer.join(2000);
        }
    }
}
