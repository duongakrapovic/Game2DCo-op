package source.client;

import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;

import java.util.ArrayDeque;

/**
 * Dự đoán phía client (client-side prediction) + hòa giải (server reconciliation) cho nhân vật của mình.
 *
 * Vấn đề: server là nơi quyết định vị trí, nhưng nếu chờ STATE từ server mới vẽ thì nhân vật phản hồi
 * chậm đúng bằng RTT (bấm phím -> đợi ~100ms mới thấy di chuyển).
 *
 * Cách làm:
 *  1. Mỗi tick (60 Hz) client chạy NGAY cùng PhysicsEngine với input vừa bấm => nhân vật phản hồi tức thì.
 *     Mỗi tick đánh số seq và gửi INPUT:<l>:<r>:<u>:<d>:<seq> lên server, đồng thời lưu vào danh sách "pending".
 *  2. Server mỗi seq nhận được chạy đúng 1 bước vật lý, rồi gửi STATE kèm ack = seq cuối đã xử lý.
 *  3. Khi nhận STATE: bỏ các input đã được ack, lấy vị trí server làm gốc rồi CHẠY LẠI các input chưa được
 *     ack => vị trí dự đoán mới. Nếu hai bên tất định (cùng code, cùng map) thì kết quả trùng khớp với
 *     dự đoán cũ (sai số ~0); nếu server từ chối (đụng tường, bị đẩy...) thì vị trí tự được sửa lại.
 *
 * Lớp này không phụ thuộc Swing/Socket nên kiểm thử được độc lập (xem source/tools/NetSelfTest).
 */
public class ClientPrediction {
    public static final class Input {
        public final int seq;
        public final boolean left, right, up, down;
        Input(int seq, boolean l, boolean r, boolean u, boolean d) {
            this.seq = seq; left = l; right = r; up = u; down = d;
        }
    }

    private static final int MAX_PENDING = 180; // ~3 giây input chưa được ack

    private final MapManager map;
    private final Player predicted = new Player("me", "me");
    private final Player scratch = new Player("replay", "replay");
    private final ArrayDeque<Input> pending = new ArrayDeque<>();
    private int nextSeq = 0;
    private boolean needResync = true;
    private double lastError = 0, maxError = 0;
    private long correctionCount = 0;

    public ClientPrediction(MapManager map) {
        this.map = map;
    }

    /** Chạy 1 tick dự đoán với input hiện tại và trả về Input (đã gán seq) để gửi lên server. */
    public synchronized Input step(boolean l, boolean r, boolean u, boolean d) {
        Input in = new Input(nextSeq++, l, r, u, d);
        apply(predicted, in);
        pending.addLast(in);
        if (pending.size() > MAX_PENDING) pending.pollFirst();
        return in;
    }

    /** Có STATE mới từ server về vị trí của mình. */
    public synchronized void onServerState(float sx, float sy, int ack) {
        if (needResync) { // lần đầu / sau khi kết nối lại: tin hoàn toàn vào server
            predicted.x = sx;
            predicted.y = sy;
            pending.clear();
            needResync = false;
            lastError = 0;
            return;
        }
        while (!pending.isEmpty() && pending.peekFirst().seq <= ack) pending.pollFirst();

        scratch.x = sx;
        scratch.y = sy;
        for (Input in : pending) apply(scratch, in);

        double err = Math.hypot(scratch.x - predicted.x, scratch.y - predicted.y);
        lastError = err;
        if (err > maxError) maxError = err;
        if (err > 0.5) correctionCount++;
        predicted.x = scratch.x;
        predicted.y = scratch.y;
    }

    /** Lần STATE kế tiếp sẽ đặt lại vị trí theo server (dùng khi vào phòng / kết nối lại). */
    public synchronized void resync() {
        needResync = true;
        pending.clear();
    }

    private void apply(Player p, Input in) {
        p.left = in.left; p.right = in.right; p.up = in.up; p.down = in.down;
        PhysicsEngine.updatePlayer(p, map); // dt cố định 1/60 — giống hệt server
    }

    public synchronized float x() { return predicted.x; }
    public synchronized float y() { return predicted.y; }
    public synchronized String direction() { return predicted.direction; }
    public synchronized int spriteNum() { return predicted.spriteNum; }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized double lastError() { return lastError; }
    public synchronized double maxError() { return maxError; }
    public synchronized long correctionCount() { return correctionCount; }
    public synchronized void resetStats() { maxError = 0; lastError = 0; correctionCount = 0; }
}
