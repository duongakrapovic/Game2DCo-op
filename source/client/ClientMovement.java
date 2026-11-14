package source.client;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMovement {

    public static class PredictionInput {
        public final int seq;
        public final boolean left, right, up, down, attack;
        public PredictionInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
            this.seq = seq; this.left = left; this.right = right; this.up = up; this.down = down; this.attack = attack;
        }
    }

    public static class Snap {
        public final long timestamp;
        public final float x, y;
        public final String direction;
        public final int spriteNum;

        public Snap(long timestamp, float x, float y, String direction, int spriteNum) {
            this.timestamp = timestamp;
            this.x = x; this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
        }
    }

    /** Khoảng cách giữa hai snapshot liên tiếp lớn hơn mức này coi là dịch chuyển tức thời (hồi sinh...): không nội suy. */
    private static final float TELEPORT_DISTANCE = 160f;
    private static final int MAX_PENDING_INPUTS = 180;

    private final GameData.PlayerState localPlayer;
    private final GameMap mapManager;
    private final Queue<PredictionInput> pendingInputs = new LinkedList<>();
    private final Map<String, List<Snap>> snaps = new ConcurrentHashMap<>(); // người chơi (P..) và quái (M..) có id khác nhau

    private int nextSeq = 0;
    private float maxErrorObserved = 0;
    private int correctionCount = 0;

    private boolean predictionEnabled = true;
    private boolean interpolationEnabled = true;
    private long interpolationDelayMs = 100; // vẽ thế giới chậm 100 ms để luôn có 2 snapshot để nội suy (quái cập nhật 20 lần/giây)

    public ClientMovement(GameMap mapManager) {
        this.mapManager = mapManager;
        this.localPlayer = new GameData.PlayerState("local", "Me");
    }

    /** Gọi khi vào phòng mới: xóa toàn bộ trạng thái dự đoán và nội suy cũ. */
    public synchronized void reset() {
        pendingInputs.clear();
        snaps.clear();
        nextSeq = 0;
        localPlayer.x = Common.SPAWN_X;
        localPlayer.y = Common.SPAWN_Y;
        localPlayer.direction = "down";
        localPlayer.spriteNum = 1;
        localPlayer.animCounter = 0;
    }

    /**
     * Một tick (60 lần/giây) của người chơi cục bộ: chạy vật lý ngay trên máy mình (dự đoán) để điều khiển
     * không bị trễ theo độ trễ mạng, đồng thời lưu input chờ server xác nhận (ack) để sửa lại nếu lệch.
     */
    public synchronized PredictionInput step(boolean l, boolean r, boolean u, boolean d, boolean atk) {
        PredictionInput input = new PredictionInput(nextSeq++, l, r, u, d, atk);
        if (predictionEnabled) {
            pendingInputs.add(input);
            while (pendingInputs.size() > MAX_PENDING_INPUTS) pendingInputs.poll();
            applyToLocal(input);
        }
        return input;
    }

    private void applyToLocal(PredictionInput in) {
        localPlayer.left = in.left;
        localPlayer.right = in.right;
        localPlayer.up = in.up;
        localPlayer.down = in.down;
        GamePhysics.updatePlayer(localPlayer, mapManager);
    }

    /**
     * Reconciliation: lấy vị trí server (đã xử lý tới input lastAckSeq), phát lại các input chưa được xác nhận,
     * rồi so với vị trí đã dự đoán. Thường trùng khớp; chỉ lệch khi bị đẩy lùi, hồi sinh, va chạm khác...
     */
    public synchronized void onServerState(float serverX, float serverY, int lastAckSeq) {
        if (!predictionEnabled) {
            localPlayer.x = serverX;
            localPlayer.y = serverY;
            return;
        }
        while (!pendingInputs.isEmpty() && pendingInputs.peek().seq <= lastAckSeq) {
            pendingInputs.poll();
        }

        float predictedX = localPlayer.x, predictedY = localPlayer.y;
        localPlayer.x = serverX;
        localPlayer.y = serverY;
        for (PredictionInput in : pendingInputs) {
            applyToLocal(in);
        }

        float err = (float) Math.hypot(localPlayer.x - predictedX, localPlayer.y - predictedY);
        if (err > maxErrorObserved) maxErrorObserved = err;
        if (err > 1.5f) correctionCount++;
    }

    /** Lưu một snapshot (người chơi hoặc quái) kèm thời điểm nhận để nội suy. */
    public void addSnap(String id, float x, float y, String dir, int sprite) {
        long now = System.currentTimeMillis();
        List<Snap> list = snaps.computeIfAbsent(id, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new Snap(now, x, y, dir, sprite));
            if (list.size() > 40) list.remove(0);
        }
    }

    /** Bỏ dữ liệu của những thực thể không còn trong thế giới. */
    public void retainSnaps(java.util.Set<String> ids) {
        snaps.keySet().retainAll(ids);
    }

    /**
     * Vị trí vẽ của một thực thể tại thời điểm (bây giờ - độ trễ nội suy): nội suy tuyến tính giữa hai snapshot
     * bao quanh mốc đó. Chưa đủ dữ liệu thì giữ vị trí gần nhất; không ngoại suy.
     */
    public Snap getInterpolatedState(String id) {
        List<Snap> list = snaps.get(id);
        if (list == null) return null;
        synchronized (list) {
            if (list.isEmpty()) return null;
            Snap newest = list.get(list.size() - 1);
            if (!interpolationEnabled) return newest;

            long renderTime = System.currentTimeMillis() - interpolationDelayMs;
            if (renderTime >= newest.timestamp) return newest;
            Snap oldest = list.get(0);
            if (renderTime <= oldest.timestamp) return oldest;

            for (int i = list.size() - 1; i > 0; i--) {
                Snap before = list.get(i - 1);
                Snap after = list.get(i);
                if (before.timestamp <= renderTime && renderTime <= after.timestamp) {
                    if (Math.hypot(after.x - before.x, after.y - before.y) > TELEPORT_DISTANCE) {
                        return renderTime - before.timestamp < after.timestamp - renderTime ? before : after;
                    }
                    long span = after.timestamp - before.timestamp;
                    float f = span <= 0 ? 1f : (float) (renderTime - before.timestamp) / span;
                    f = Math.max(0f, Math.min(1f, f));
                    return new Snap(renderTime,
                            before.x + (after.x - before.x) * f,
                            before.y + (after.y - before.y) * f,
                            f < 0.5f ? before.direction : after.direction,
                            f < 0.5f ? before.spriteNum : after.spriteNum);
                }
            }
            return newest;
        }
    }

    public synchronized float x() { return localPlayer.x; }
    public synchronized float y() { return localPlayer.y; }
    public synchronized String direction() { return localPlayer.direction; }
    public synchronized int spriteNum() { return localPlayer.spriteNum; }
    public synchronized int pendingCount() { return pendingInputs.size(); }
    public synchronized float maxError() { return maxErrorObserved; }
    public synchronized int correctionCount() { return correctionCount; }
    public boolean isPredictionEnabled() { return predictionEnabled; }
    public void setPredictionEnabled(boolean enabled) { this.predictionEnabled = enabled; }
    public boolean isInterpolationEnabled() { return interpolationEnabled; }
    public void setInterpolationEnabled(boolean enabled) { this.interpolationEnabled = enabled; }
    public long getInterpolationDelayMs() { return interpolationDelayMs; }
}
