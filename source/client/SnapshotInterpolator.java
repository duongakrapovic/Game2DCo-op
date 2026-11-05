package source.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nội suy (interpolation) vị trí người chơi KHÁC.
 *
 * Server gửi snapshot ~60 lần/giây nhưng qua mạng chúng đến không đều (jitter, TCP gộp gói). Nếu vẽ
 * ngay khi nhận thì nhân vật khác bị giật. Cách làm: lưu snapshot kèm thời điểm nhận, rồi vẽ thế giới
 * "chậm hơn hiện tại một chút" (renderTime = now - delay, mặc định 100 ms) và nội suy tuyến tính giữa
 * hai snapshot bao quanh renderTime => chuyển động mượt, đánh đổi bằng độ trễ hiển thị cố định.
 */
public class SnapshotInterpolator {
    public static final class Snap {
        public final long t;          // thời điểm nhận (System.nanoTime)
        public final float x, y;
        public final String dir;
        public final int sprite;
        public Snap(long t, float x, float y, String dir, int sprite) {
            this.t = t; this.x = x; this.y = y; this.dir = dir; this.sprite = sprite;
        }
    }

    private static final int MAX_SNAPS = 60;
    private static final float TELEPORT_DISTANCE = 200f; // nhảy xa hơn mức này thì không nội suy (respawn/dịch chuyển)

    private final Map<String, List<Snap>> buffers = new HashMap<>();

    public synchronized void add(String id, long t, float x, float y, String dir, int sprite) {
        List<Snap> b = buffers.computeIfAbsent(id, k -> new ArrayList<>());
        b.add(new Snap(t, x, y, dir, sprite));
        if (b.size() > MAX_SNAPS) b.remove(0);
    }

    /** Xóa dữ liệu của những người chơi không còn trong STATE. */
    public synchronized void retainOnly(Set<String> ids) {
        buffers.keySet().retainAll(ids);
    }

    public synchronized void clear() { buffers.clear(); }

    /** Snapshot mới nhất (không nội suy). */
    public synchronized Snap latest(String id) {
        List<Snap> b = buffers.get(id);
        return (b == null || b.isEmpty()) ? null : b.get(b.size() - 1);
    }

    /** Vị trí tại thời điểm renderTime (nanoTime), nội suy giữa hai snapshot bao quanh. */
    public synchronized Snap sample(String id, long renderTime) {
        List<Snap> b = buffers.get(id);
        if (b == null || b.isEmpty()) return null;

        // bỏ snapshot cũ, giữ lại 1 cái nằm trước/ở renderTime
        while (b.size() > 2 && b.get(1).t <= renderTime) b.remove(0);

        Snap a = b.get(0);
        if (b.size() == 1 || renderTime <= a.t) return a;
        Snap c = b.get(1);
        if (renderTime >= c.t) return c; // chưa có snapshot mới hơn: giữ nguyên vị trí cuối

        if (Math.hypot(c.x - a.x, c.y - a.y) > TELEPORT_DISTANCE) return c;
        float f = (float) (renderTime - a.t) / (float) (c.t - a.t);
        Snap ref = f < 0.5f ? a : c;
        return new Snap(renderTime, a.x + (c.x - a.x) * f, a.y + (c.y - a.y) * f, ref.dir, ref.sprite);
    }
}
