package source.common;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** Combat shared by the room simulation and the offline simulation. */
/*
 * Xử lý đòn đánh người chơi trên trạng thái mô phỏng. ServerGame gọi khi nhận một trigger đánh; cùng hàm
 * còn được thành phần offline dùng lại. Lớp lồng MonsterHistory lưu vị trí quái để kiểm va chạm ở quá khứ,
 * không quay ngược toàn bộ thế giới.
 * MonsterHistory có map ID → Samples. Mỗi Samples chứa 13 tick stamp và tọa độ x/y; chỉ mẫu đúng tick được
 * dùng. onKilled là callback nối chiến đấu với hệ thống rơi bình máu.
 */
public final class CombatSystem {
    /*
     * Khởi tạo CombatSystem
     * Constructor private ngăn tạo một đối tượng CombatSystem; đây là nhóm hàm tiện ích static.
     */
    private CombatSystem() {}

    /*
     * resolveAttack()
     * Bản sáu tham số chuyển tiếp sang resolveAttack đầy đủ, truyền một callback onKilled rỗng. Luật đánh
     * vẫn giống nhau nhưng caller không nhận sự kiện quái chết để tạo loot.
     */
    public static void resolveAttack(GameData.PlayerState player,
            Iterable<GameData.MonsterState> monsters, GameMap map,
            IntSupplier respawnTicks, MonsterHistory history, long tick) {
        /*
         * Callback/lambda: Callback onKilled rỗng của overload không cần xử lý item rơi.
         */
        resolveAttack(player, monsters, map, respawnTicks, history, tick, ignored -> {});
    }

    /*
     * resolveAttack()
     * Nhận player, quái, map, hàm cấp thời gian hồi sinh, lịch sử và tick hiện tại. Bật animation đánh,
     * tạo hitbox theo hướng, lùi kiểm tra quái tối đa 12 tick theo pingMs/16 rồi kiểm giao hình và line of
     * sight. Quái trúng mất 15 HP và bị đẩy 16 pixel trên vị trí hiện tại; nếu chết thì đặt timer, xóa
     * path/history và gọi onKilled đúng lần chết. Hàm sửa trực tiếp player/quái, không trả snapshot.
     * Trình tự: bật isAttacking và reset attackTicks; dựng hitbox trước mặt; tính số tick rewind từ
     * pingMs/16, chặn 0 đến 12; duyệt quái còn sống. Chỉ quái có hitbox giao và thông line of sight mới
     * mất 15 HP, nháy 150 ms và bị đẩy lùi 16 pixel.
     * Nếu HP quái về 0 thì đánh dấu chết, lấy respawnTimer từ IntSupplier, xóa path và lịch sử đời cũ,
     * cuối cùng gọi onKilled để ItemSystem có cơ hội rơi bình. Rewind chỉ áp vào query va chạm; damage và
     * knockback vẫn tác động lên trạng thái quái hiện tại.
     */
    public static void resolveAttack(GameData.PlayerState player,
            Iterable<GameData.MonsterState> monsters, GameMap map,
            IntSupplier respawnTicks, MonsterHistory history, long tick,
            java.util.function.Consumer<GameData.MonsterState> onKilled) {
        player.isAttacking = true;
        player.attackTicks = 0;
        Rectangle attack = GamePhysics.getAttackHitbox(player.x, player.y, player.direction);
        /*
         * Chỉ lùi query hitbox quái tối đa 12 tick; damage và knockback vẫn áp vào entity hiện tại.
         */
        int rewindTicks = Math.min(12, Math.max(0, player.pingMs / 16));
        long now = System.currentTimeMillis();
        for (GameData.MonsterState monster : monsters) {
            if (monster.isDead) continue;
            Rectangle bounds = history == null ? GamePhysics.getEntityBounds(monster.x, monster.y)
                    : history.boundsAt(monster, tick - rewindTicks);
            if (!attack.intersects(bounds) || !MonsterAI.hasLineOfSight(
                    player.x, player.y, bounds.x, bounds.y, map)) continue;
            monster.hp = Math.max(0, monster.hp - 15);
            monster.flashUntil = now + 150;
            // Only the collision query is rewound; damage and knockback use live state.
            GamePhysics.applyKnockback(monster, player.x, player.y, 16f, map);
            /*
             * Chuyển sang chết đúng một lần, xóa lịch sử đời cũ và báo callback tạo item rơi.
             */
            if (monster.hp == 0) {
                monster.isDead = true;
                monster.respawnTimer = respawnTicks.getAsInt();
                monster.currentPath = new int[0][2];
                monster.pathIndex = 0;
                if (history != null) history.clear(monster.id);
                onKilled.accept(monster);
            }
        }
    }

    /** Thirteen samples include the current tick and twelve historical ticks. */
    /*
     * Giữ lịch sử hitbox quái theo tick để kiểm đòn đánh khi có độ trễ mạng.
     */
    public static final class MonsterHistory {
        /*
         * CAPACITY: Số mẫu ring buffer lịch sử quái.
         */
        private static final int CAPACITY = 13;
        /*
         * Ring buffer tọa độ và tick của một quái; mẫu cũ bị ghi đè theo modulo.
         */
        private static final class Samples {
            /*
             * ticks: Lịch sử tick và tọa độ quái dùng query hitbox lùi thời gian.
             */
            final long[] ticks = new long[CAPACITY];
            /*
             * x, y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
             */
            final float[] x = new float[CAPACITY], y = new float[CAPACITY];
            /*
             * Khởi tạo Samples
             * Điền các tick stamp bằng Long.MIN_VALUE để ô lịch sử chưa ghi không bị hiểu nhầm là mẫu ở
             * tick 0; các mảng tọa độ có sẵn trong object.
             */
            Samples() { java.util.Arrays.fill(ticks, Long.MIN_VALUE); }
        }
        /*
         * samples: Lịch sử tick và tọa độ quái dùng query hitbox lùi thời gian.
         */
        private final Map<String, Samples> samples = new HashMap<>();
        /*
         * record()
         * Mỗi tick ghi x/y của quái còn sống vào ô tick % 13; tạo Samples nếu ID chưa có. Stamp tick giúp
         * phân biệt dữ liệu cũ đã bị vòng ghi đè.
         */
        public void record(Iterable<GameData.MonsterState> monsters, long tick) {
            int index = (int) (tick % CAPACITY);
            for (GameData.MonsterState monster : monsters) {
                if (monster.isDead) continue;
                /*
                 * Callback/lambda: Factory computeIfAbsent tạo ring buffer Samples khi lần đầu ghi lịch sử
                 * quái.
                 */
                Samples s = samples.computeIfAbsent(monster.id, ignored -> new Samples());
                s.ticks[index] = tick; s.x[index] = monster.x; s.y[index] = monster.y;
            }
        }
        /*
         * clear()
         * Xóa toàn bộ lịch sử của một ID quái. Được dùng khi quái chết hoặc bước sang đời hồi sinh để
         * tránh đòn đánh trúng vị trí của đời trước.
         */
        public void clear(String id) { samples.remove(id); }
        /*
         * boundsAt()
         * Trả Rectangle tại tọa độ lịch sử nếu tick không âm và ô vòng có stamp đúng tick yêu cầu. Nếu
         * thiếu mẫu, trả hitbox ở vị trí quái hiện tại; không tự sửa tọa độ quái.
         */
        public Rectangle boundsAt(GameData.MonsterState monster, long tick) {
            Samples s = samples.get(monster.id);
            if (s != null && tick >= 0) {
                int index = (int) (tick % CAPACITY);
                if (s.ticks[index] == tick) return GamePhysics.getEntityBounds(s.x[index], s.y[index]);
            }
            return GamePhysics.getEntityBounds(monster.x, monster.y);
        }
    }
}
