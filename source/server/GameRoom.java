package source.server;

import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;
import source.common.Protocol;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Một phòng chơi: giữ danh sách người chơi, chạy game loop cố định 60 tick/giây (server-authoritative)
 * và broadcast STATE.
 *
 * PHẦN MẠNG (đồng bộ input):
 *  - Client gửi INPUT mỗi tick kèm số thứ tự (seq). Server đưa vào hàng đợi riêng của từng người chơi
 *    và mỗi tick tiêu thụ đúng 1 input (tối đa 2 để đuổi kịp khi bị dồn) => mỗi seq tương ứng đúng 1 bước vật lý.
 *  - STATE gửi kèm "ack" = seq cuối cùng đã xử lý. Client dùng ack để dự đoán + hòa giải (xem ClientPrediction).
 *  - Client cũ không gửi seq vẫn chạy được (chế độ "legacy": giữ trạng thái phím gần nhất).
 *
 * ĐIỂM MỞ RỘNG CHO GAMEPLAY: ghi đè/triển khai onCommand() để xử lý lệnh mới (ATTACK, CHAT...) mà
 * không cần sửa code mạng; dùng broadcast() để gửi message tới cả phòng.
 */
public class GameRoom implements Runnable {
    private static final int MAX_QUEUED_INPUTS = 30;     // ~0.5s input; vượt thì bỏ input cũ nhất
    private static final int MAX_INPUTS_PER_TICK = 2;    // đuổi kịp tối đa 2 bước/tick (chống speed-hack)
    private static final int MAX_CATCHUP_TICKS = 5;

    /** Một input của người chơi tại một tick. */
    private static final class Input {
        final boolean left, right, up, down;
        final int seq;
        Input(boolean l, boolean r, boolean u, boolean d, int seq) {
            left = l; right = r; up = u; down = d; this.seq = seq;
        }
    }

    /** Trạng thái đồng bộ mạng của một người chơi. */
    private static final class PlayerNet {
        final ArrayDeque<Input> queue = new ArrayDeque<>();
        volatile boolean seqMode = false;   // true khi client có gửi seq
        volatile int lastAck = -1;          // seq cuối cùng đã xử lý
        int lastQueuedSeq = -1;
        volatile Input legacy = new Input(false, false, false, false, -1);
    }

    public final String roomName;
    public final Map<String, Player> players = new ConcurrentHashMap<>();
    public final Map<String, OutChannel> writers = new ConcurrentHashMap<>();
    private final Map<String, PlayerNet> nets = new ConcurrentHashMap<>();

    private volatile boolean isRunning = true;
    private final MapManager mapManager;
    private final GameLogger logger;
    private long tickCount = 0;

    // Map chỉ đọc sau khi nạp => dùng chung cho mọi phòng (không parse lại 9 file TMX mỗi lần tạo phòng)
    private static MapManager sharedMap;

    private static synchronized MapManager loadSharedMap() {
        if (sharedMap == null) {
            MapManager m = new MapManager();
            String mapPath = "source/content/map";
            m.loadTilesetProperties(mapPath + "/tileset.tsx");
            m.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);
            sharedMap = m;
        }
        return sharedMap;
    }

    public GameRoom(String roomName) {
        this.roomName = roomName;
        this.mapManager = loadSharedMap();
        this.logger = new GameLogger(roomName);
        Thread t = new Thread(this, "Room-" + roomName);
        t.start();
    }

    // ------------------------------------------------------------------ quản lý người chơi

    public void addPlayer(String pId, Player p, OutChannel out) {
        nets.put(pId, new PlayerNet());
        players.put(pId, p);
        writers.put(pId, out);
    }

    public void removePlayer(String pId) {
        players.remove(pId);
        writers.remove(pId);
        nets.remove(pId);
    }

    /** Gắn kênh gửi mới cho người chơi vừa kết nối lại (RESUME). */
    public void attachWriter(String pId, OutChannel out) {
        PlayerNet pn = nets.get(pId);
        if (pn != null) {
            synchronized (pn.queue) { pn.queue.clear(); }
        }
        writers.put(pId, out);
    }

    /**
     * Người chơi rớt mạng: ngừng gửi STATE cho họ và dừng di chuyển, nhưng GIỮ nhân vật trong phòng.
     * @return false nếu kênh hiện tại không còn là kênh này (đã bị RESUME thay thế) -> không làm gì.
     */
    public boolean detachWriter(String pId, OutChannel expected) {
        if (!writers.remove(pId, expected)) return false;
        Player p = players.get(pId);
        PlayerNet pn = nets.get(pId);
        if (pn != null) {
            synchronized (pn.queue) { pn.queue.clear(); }
            pn.legacy = new Input(false, false, false, false, -1);
        }
        if (p != null) { p.left = p.right = p.up = p.down = false; }
        return true;
    }

    public boolean isEmpty() { return players.isEmpty(); }

    public void shutdown() { isRunning = false; }

    /** Gửi một message (đáng tin cậy) tới tất cả người chơi đang kết nối trong phòng. */
    public void broadcast(String msg) {
        for (OutChannel out : writers.values()) out.sendReliable(msg);
    }

    /**
     * Điểm mở rộng cho gameplay: lệnh không thuộc giao thức mạng cơ bản (vd ATTACK, CHAT) được chuyển vào đây.
     * @param parts message đã tách theo ':' ; parts[0] là tên lệnh (không phân biệt hoa thường do server đã chuẩn hóa).
     * @return true nếu lệnh đã được xử lý.
     */
    public boolean onCommand(String pId, String cmd, String[] parts) {
        return false;
    }

    // ------------------------------------------------------------------ input

    /** Nhận input từ client (gọi từ luồng của ClientHandler). seq < 0 nghĩa là client cũ không có seq. */
    public void submitInput(String pId, boolean l, boolean r, boolean u, boolean d, int seq) {
        PlayerNet pn = nets.get(pId);
        if (pn == null) return;
        if (seq < 0) {
            if (!pn.seqMode) pn.legacy = new Input(l, r, u, d, -1);
            return;
        }
        synchronized (pn.queue) {
            if (seq <= pn.lastQueuedSeq) return; // trùng / cũ
            pn.seqMode = true;
            pn.lastQueuedSeq = seq;
            if (pn.queue.size() >= MAX_QUEUED_INPUTS) pn.queue.pollFirst();
            pn.queue.addLast(new Input(l, r, u, d, seq));
        }
    }

    // ------------------------------------------------------------------ game loop

    @Override
    public void run() {
        long next = System.nanoTime();
        while (isRunning) {
            long now = System.nanoTime();
            int n = 0;
            // Bộ tích lũy thời gian: tốc độ trung bình luôn đúng 60 tick/s dù Thread.sleep không chính xác
            while (now >= next && n < MAX_CATCHUP_TICKS && isRunning) {
                tick();
                next += Common.TICK_NS;
                n++;
                now = System.nanoTime();
            }
            if (n == MAX_CATCHUP_TICKS && now >= next) next = now; // lag quá nặng: bỏ qua phần đã trễ
            long sleepNs = next - System.nanoTime();
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        logger.close();
    }

    private void tick() {
        tickCount++;
        for (Map.Entry<String, Player> e : players.entrySet()) {
            Player p = e.getValue();
            PlayerNet pn = nets.get(e.getKey());
            if (pn != null) stepPlayer(p, pn);
        }
        broadcastState();
        if (GameLogger.ENABLED && tickCount % GameLogger.EVERY_N_TICKS == 0) {
            for (Player p : players.values()) logger.log(p);
        }
    }

    private void stepPlayer(Player p, PlayerNet pn) {
        if (!pn.seqMode) { // client cũ: giữ trạng thái phím gần nhất
            Input in = pn.legacy;
            p.left = in.left; p.right = in.right; p.up = in.up; p.down = in.down;
            PhysicsEngine.updatePlayer(p, mapManager);
            return;
        }
        for (int i = 0; i < MAX_INPUTS_PER_TICK; i++) {
            Input in;
            synchronized (pn.queue) { in = pn.queue.pollFirst(); }
            if (in == null) return; // chưa có input mới -> không tự bịa thêm bước đi, chờ client
            p.left = in.left; p.right = in.right; p.up = in.up; p.down = in.down;
            PhysicsEngine.updatePlayer(p, mapManager);
            pn.lastAck = in.seq;
        }
    }

    /** STATE;id,tên,x,y,hướng,frame,ack;... */
    private void broadcastState() {
        if (writers.isEmpty()) return;
        StringBuilder sb = new StringBuilder(Common.CMD_STATE);
        for (Map.Entry<String, Player> e : players.entrySet()) {
            Player p = e.getValue();
            PlayerNet pn = nets.get(e.getKey());
            sb.append(';').append(p.id).append(',')
              .append(p.name).append(',')
              .append(Protocol.fmt2(p.x)).append(',')
              .append(Protocol.fmt2(p.y)).append(',')
              .append(p.direction).append(',')
              .append(p.spriteNum).append(',')
              .append(pn == null ? -1 : pn.lastAck);
        }
        String msg = sb.toString();
        for (OutChannel out : writers.values()) out.sendState(msg);
    }
}
