package source.tools;

import source.client.ClientPrediction;
import source.client.NetClient;
import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kiểm thử tự động phần mạng trên một server ĐANG CHẠY.
 *
 *   java -cp . source.tools.NetSelfTest [host] [port]
 *
 * Kiểm tra: bắt tay + heartbeat, chống lệnh sai / dòng quá dài, lọc ký tự cấm trong tên, giới hạn
 * số người, tự rời phòng cũ, RESUME sai token, client-side prediction (sai số so với mô phỏng cục bộ,
 * có giả lập độ trễ 100 ms mỗi chiều), và tự kết nối lại sau khi rớt mạng.
 * Thoát với mã 1 nếu có kiểm tra thất bại.
 */
public class NetSelfTest {
    static int passed = 0, failed = 0;
    static String host = "localhost";
    static int port = Common.PORT;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);
        System.out.println("=== NetSelfTest -> " + host + ":" + port + " ===");

        testHandshakeAndHeartbeat();
        testMalformedInput();
        testSanitization();
        testRoomRules();
        testPredictionAndReconnect();

        System.out.println("\nKết quả: " + passed + " đạt, " + failed + " không đạt");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ các nhóm kiểm tra

    static void testHandshakeAndHeartbeat() throws Exception {
        System.out.println("\n[1] Bắt tay + heartbeat");
        Collector col = new Collector();
        NetClient c = new NetClient(host, port, col);
        c.connect();
        check("nhận WELCOME có id + token", waitUntil(() -> c.getMyId() != null, 2000), "id=" + c.getMyId());
        check("nhận ROOM_LIST khi vừa kết nối", col.waitFor("ROOM_LIST", 2000) != null, "");
        check("PING/PONG đo được RTT", waitUntil(() -> c.getRttMs() >= 0, 3000), String.format("rtt=%.1f ms", c.getRttMs()));
        c.close();
    }

    static void testMalformedInput() throws Exception {
        System.out.println("\n[2] Lệnh sai / dữ liệu xấu không làm sập kết nối");
        Raw r = new Raw();
        r.send("CREATE_ROOM");
        r.send("JOIN_ROOM::");
        r.send("INPUT:foo:bar");
        r.send("KHONGCOTHAT:1:2");
        r.send("");
        check("lệnh thiếu tham số -> ERROR", r.waitFor("ERROR", 1000) != null, "");
        r.send("PING:12345");
        check("sau khi gửi rác kết nối vẫn sống (nhận PONG)", r.waitFor("PONG:12345", 1500) != null, "");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append('x');
        r.send(big.toString());
        check("dòng quá dài -> server ngắt kết nối", r.waitClosed(2000), "");
        r.close();
    }

    static void testSanitization() throws Exception {
        System.out.println("\n[3] Lọc ký tự cấm trong tên");
        Raw r = new Raw();
        r.send("CREATE_ROOM:Ro om;1:Ev;il,na");
        String joined = r.waitFor("JOINED", 1500);
        check("tên phòng được chuẩn hóa (không còn khoảng trắng/;)", "JOINED:Ro_om1".equals(joined), String.valueOf(joined));
        String st = r.waitFor("STATE", 1500);
        boolean ok = false;
        if (st != null) {
            String[] recs = st.split(";");
            String[] f = recs.length == 2 ? recs[1].split(",") : new String[0];
            ok = f.length == 7 && f[1].equals("Evilna");
        }
        check("STATE đúng định dạng 1 người, 7 trường, tên đã lọc", ok, String.valueOf(st));
        r.close();
    }

    static void testRoomRules() throws Exception {
        System.out.println("\n[4] Quy tắc phòng");
        List<Raw> rs = new ArrayList<>();
        Raw host1 = new Raw();
        host1.send("CREATE_ROOM:fullroom:A");
        host1.waitFor("JOINED", 1000);
        rs.add(host1);
        for (int i = 2; i <= Common.MAX_PLAYERS_PER_ROOM; i++) {
            Raw x = new Raw();
            x.send("JOIN_ROOM:fullroom:P" + i);
            check("người thứ " + i + " vào được phòng", x.waitFor("JOINED", 1000) != null, "");
            rs.add(x);
        }
        Raw extra = new Raw();
        extra.send("JOIN_ROOM:fullroom:Extra");
        String err = extra.waitFor("ERROR", 1000);
        check("người thứ " + (Common.MAX_PLAYERS_PER_ROOM + 1) + " bị từ chối (phòng đầy)", err != null && err.contains("đầy"), String.valueOf(err));
        extra.close();

        Raw mover = new Raw();
        mover.send("CREATE_ROOM:roomA:M");
        mover.waitFor("JOINED", 1000);
        mover.send("CREATE_ROOM:roomB:M");
        mover.waitFor("JOINED", 1000);
        Raw watcher = new Raw();
        watcher.send("ROOM_LIST");
        Thread.sleep(300);
        watcher.send("ROOM_LIST");
        String list = watcher.lastWithPrefix("ROOM_LIST");
        check("vào phòng mới thì tự rời phòng cũ (phòng cũ trống bị xóa)",
                list != null && list.contains("roomB") && !list.contains("roomA"), String.valueOf(list));
        watcher.close();
        mover.close();

        Raw bad = new Raw();
        bad.send("RESUME:deadbeefdeadbeef");
        check("RESUME sai token -> RESUME_FAIL", bad.waitFor("RESUME_FAIL", 1000) != null, "");
        bad.close();
        for (Raw x : rs) x.close();
    }

    /** Prediction với 100 ms trễ mỗi chiều, sau đó cắt mạng giữa chừng để thử tự kết nối lại. */
    static void testPredictionAndReconnect() throws Exception {
        System.out.println("\n[5] Client-side prediction (lag giả lập 100 ms mỗi chiều) + tự kết nối lại");

        MapManager map = new MapManager();
        map.loadTilesetProperties("source/content/map/tileset.tsx");
        map.loadAllChunks("source/content/map", Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);
        ClientPrediction pred = new ClientPrediction(map);

        final String[] myId = {null};
        final String[] lastState = {null};
        final long[] stateCount = {0};
        NetClient c = new NetClient(host, port, new NetClient.Listener() {
            public void onMessage(String line) {
                if (line.startsWith("WELCOME:")) myId[0] = line.split(":")[1];
                else if (line.startsWith("JOINED:") || line.startsWith("RESUMED:")) pred.resync();
                else if (line.startsWith("STATE")) {
                    lastState[0] = line;
                    stateCount[0]++;
                    for (String rec : line.split(";")) {
                        String[] f = rec.split(",");
                        if (f.length >= 7 && f[0].equals(myId[0])) {
                            pred.onServerState(Float.parseFloat(f[2]), Float.parseFloat(f[3]), Integer.parseInt(f[6]));
                        }
                    }
                }
            }
            public void onStateChanged(NetClient.State s, String info) {}
            public void onSessionLost(String reason) {}
        });
        c.setLagMs(100);
        c.connect();
        waitUntil(() -> c.getMyId() != null, 2000);
        c.send("CREATE_ROOM:predroom:Bot");
        check("vào phòng qua đường truyền có lag", waitUntil(() -> lastState[0] != null, 3000), "");

        // Kịch bản input: phải, xuống, trái, lên, đứng yên — chạy ở 60 tick/s
        boolean[][] script = new boolean[][]{
                {false, true, false, false}, {false, false, false, true},
                {true, false, false, false}, {false, false, true, false}, {false, false, false, false}};
        int[] ticks = {90, 60, 80, 40, 90};

        // Mô phỏng cục bộ "chân lý" (cùng PhysicsEngine) và mô phỏng không có tường để chứng minh tường có tác dụng
        Player truth = new Player("t", "t");
        Player free = new Player("f", "f");
        MapManager empty = new MapManager();

        double naiveSum = 0, naiveMax = 0;
        long n = 0;
        long next = System.nanoTime();
        for (int s = 0; s < script.length; s++) {
            for (int t = 0; t < ticks[s]; t++) {
                while (System.nanoTime() < next) Thread.sleep(1);
                next += Common.TICK_NS;
                boolean[] k = script[s];
                ClientPrediction.Input in = pred.step(k[0], k[1], k[2], k[3]);
                c.send("INPUT:" + in.left + ":" + in.right + ":" + in.up + ":" + in.down + ":" + in.seq);
                setKeys(truth, k); PhysicsEngine.updatePlayer(truth, map);
                setKeys(free, k);  PhysicsEngine.updatePlayer(free, empty);

                // "sai số ngây thơ": nếu vẽ thẳng vị trí server thay vì dự đoán thì lệch bao nhiêu px
                float[] sp = ownPos(lastState[0], myId[0]);
                if (sp != null) {
                    double d = Math.hypot(pred.x() - sp[0], pred.y() - sp[1]);
                    naiveSum += d; n++;
                    if (d > naiveMax) naiveMax = d;
                }
            }
        }
        Thread.sleep(800); // chờ server xử lý hết input còn trong đường truyền
        float[] fin = ownPos(lastState[0], myId[0]);
        check("server nhận hết input (pending về 0)", pred.pendingCount() <= 1, "pending=" + pred.pendingCount());
        double dServer = fin == null ? 1e9 : Math.hypot(fin[0] - truth.x, fin[1] - truth.y);
        check("vị trí server == mô phỏng cục bộ (va chạm phía server hoạt động)", dServer < 0.05,
                String.format("server=(%.2f,%.2f) local=(%.2f,%.2f)", fin[0], fin[1], truth.x, truth.y));
        check("bản đồ có tường thật (khác đường đi tự do)", Math.hypot(free.x - truth.x, free.y - truth.y) > 1.0,
                String.format("free=(%.0f,%.0f) vs walled=(%.0f,%.0f)", free.x, free.y, truth.x, truth.y));
        check("prediction không bị sửa vị trí (sai số lớn nhất < 0.5 px)", pred.maxError() < 0.5,
                String.format("maxErr=%.3f px, số lần hiệu chỉnh=%d", pred.maxError(), pred.correctionCount()));
        System.out.printf("    (info) nếu KHÔNG dự đoán, nhân vật sẽ lệch server trung bình %.1f px, tối đa %.1f px ở lag này%n",
                naiveSum / Math.max(1, n), naiveMax);

        // --- rớt mạng ---
        String idBefore = c.getMyId();
        long statesBefore = stateCount[0];
        c.setLagMs(0);
        c.simulateConnectionDrop();
        check("tự kết nối lại (RESUME) trong 10s", waitUntil(() -> c.getReconnectCount() > 0
                && c.getState() == NetClient.State.CONNECTED, 10000), "state=" + c.getState());
        check("giữ nguyên id sau khi kết nối lại", idBefore != null && idBefore.equals(c.getMyId()), idBefore + " -> " + c.getMyId());
        check("STATE tiếp tục về sau khi kết nối lại", waitUntil(() -> stateCount[0] > statesBefore + 5, 3000), "");
        float[] after = ownPos(lastState[0], c.getMyId());
        check("nhân vật vẫn ở đúng chỗ cũ sau khi kết nối lại", after != null && Math.hypot(after[0] - truth.x, after[1] - truth.y) < 0.05,
                after == null ? "không thấy nhân vật" : String.format("(%.2f,%.2f)", after[0], after[1]));
        c.close();
    }

    // ------------------------------------------------------------------ tiện ích

    static void setKeys(Player p, boolean[] k) { p.left = k[0]; p.right = k[1]; p.up = k[2]; p.down = k[3]; }

    static float[] ownPos(String state, String id) {
        if (state == null || id == null) return null;
        for (String rec : state.split(";")) {
            String[] f = rec.split(",");
            if (f.length >= 7 && f[0].equals(id)) return new float[]{Float.parseFloat(f[2]), Float.parseFloat(f[3])};
        }
        return null;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println("  " + (ok ? "[ĐẠT]    " : "[KHÔNG ĐẠT] ") + name + (detail.isEmpty() ? "" : "  — " + detail));
    }

    interface Cond { boolean ok(); }

    static boolean waitUntil(Cond c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(20);
        }
        return c.ok();
    }

    /** Listener đơn giản gom các dòng nhận được. */
    static class Collector implements NetClient.Listener {
        final List<String> lines = new CopyOnWriteArrayList<>();
        public void onMessage(String line) { lines.add(line); }
        public void onStateChanged(NetClient.State s, String info) {}
        public void onSessionLost(String reason) {}
        String waitFor(String prefix, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            do {
                for (String l : lines) if (l.startsWith(prefix)) return l;
                Thread.sleep(20);
            } while (System.currentTimeMillis() < end);
            return null;
        }
    }

    /** Kết nối TCP thô (không qua NetClient) để gửi dữ liệu tùy ý. */
    static class Raw {
        final Socket s;
        final PrintWriter w;
        final List<String> lines = new CopyOnWriteArrayList<>();
        volatile boolean closed = false;

        Raw() throws IOException {
            s = new Socket(host, port);
            s.setTcpNoDelay(true);
            w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = in.readLine()) != null) lines.add(l);
                } catch (IOException ignored) {}
                closed = true;
            });
            t.setDaemon(true);
            t.start();
        }
        void send(String m) { w.print(m + "\n"); w.flush(); }
        String waitFor(String prefix, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            do {
                for (String l : lines) if (l.startsWith(prefix)) return l;
                Thread.sleep(20);
            } while (System.currentTimeMillis() < end);
            return null;
        }
        String lastWithPrefix(String prefix) {
            String r = null;
            for (String l : lines) if (l.startsWith(prefix)) r = l;
            return r;
        }
        boolean waitClosed(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) { if (closed) return true; Thread.sleep(20); }
            return closed;
        }
        void close() { try { s.close(); } catch (IOException ignored) {} }
    }
}
