package source.common;

import java.awt.Point;
import java.util.*;
import java.util.function.Supplier;

/** Potion rules ported from tranvanducb23dccn191; simulation owns all mutations. */
/*
 * Sở hữu trạng thái bình máu trong một mô phỏng. Item ID bắt đầu POTION_ là bình map hồi lại, DROP_ là đồ
 * quái rơi có hạn sống. Online được ServerGame gọi trên loop authoritative, client chỉ nhận snapshot và
 * vẽ.
 * LinkedHashMap giữ thứ tự để tìm drop cũ nhất khi cần nhường chỗ. MAX_ITEMS=60; DROP_DESPAWN_TICKS=600;
 * POTION_RESPAWN_TICKS=900; nextId sinh ID trong một đời hệ thống.
 */
public final class ItemSystem {
    /*
     * MAX_ITEMS: Giới hạn tổng item trong ItemSystem.
     */
    public static final int MAX_ITEMS = 60;
    /*
     * DROP_DESPAWN_TICKS: Số tick item quái rơi còn tồn tại.
     */
    public static final int DROP_DESPAWN_TICKS = 600;
    /*
     * POTION_RESPAWN_TICKS: Số tick bình cố định chờ xuất hiện lại.
     */
    public static final int POTION_RESPAWN_TICKS = 900;
    /*
     * items: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu xuống
     * JSON.
     */
    private final Map<String, GameData.ItemState> items = new LinkedHashMap<>();
    /*
     * map: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    private final GameMap map;
    /*
     * random: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private final Random random;
    /*
     * nextId: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private long nextId;

    /*
     * Khởi tạo ItemSystem
     * Lưu GameMap và Random do caller truyền. Chưa tạo bình tới khi initialize được gọi.
     */
    public ItemSystem(GameMap map, Random random) { this.map = map; this.random = random; }
    /*
     * items()
     * Trả collection view không cho sửa cấu trúc trực tiếp. Các object ItemState bên trong vẫn là object
     * đang được mô phỏng sở hữu; đây không phải deep copy.
     */
    public Collection<GameData.ItemState> items() { return Collections.unmodifiableCollection(items.values()); }

    /*
     * initialize()
     * Xóa item/ID cũ, thử gọi hàm cấp spawn tám lần. Chỉ tạo POTION_ HEAL khi điểm khác null và hợp lệ;
     * không bảo đảm đủ tám bình nếu map hết chỗ.
     * Thử cấp tối đa tám bình cố định, mỗi lần lấy spawn từ Supplier. Nếu null hoặc vị trí không hợp lệ
     * thì bỏ lượt, không tự ép đủ tám bình bằng điểm fallback nguy hiểm. ID POTION_ đánh dấu bình cố định,
     * khác DROP_ từ quái.
     */
    public void initialize(Supplier<Point> spawn) {
        items.clear(); nextId = 0;
        for (int i = 0; i < 8; i++) {
            Point point = spawn.get();
            if (point == null || !valid(point.x, point.y)) continue;
            String id = "POTION_" + ++nextId;
            items.put(id, new GameData.ItemState(id, "HEAL", point.x, point.y));
        }
    }

    /*
     * onMonsterKilled()
     * Dùng nextBoolean để quyết định có drop rồi kiểm vị trí quái. Nếu đã đạt 60 item, loại DROP_ cũ nhất
     * nếu có, giữ bình map; tạo DROP_ HEAL với timer 600 tại vị trí hiện tại. Được callback chiến đấu gọi
     * khi quái thực sự chết.
     * Đầu tiên xét xác suất Random.nextBoolean và tính hợp lệ của vị trí. Nếu đang chạm trần 60 item thì
     * thử bỏ DROP_ cũ nhất; không có DROP_ để bỏ thì không tạo thêm. Bình rơi có thời gian tồn tại 600
     * tick và không tự hồi sinh như POTION_.
     */
    public void onMonsterKilled(GameData.MonsterState monster) {
        if (!random.nextBoolean() || !valid(monster.x, monster.y)) return;
        if (items.size() >= MAX_ITEMS) {
            /*
             * Callback/lambda: Predicate lọc ID DROP_ để tìm item rơi cũ nhất có thể loại khi chạm giới
             * hạn tổng item.
             */
            String oldest = items.keySet().stream().filter(id -> id.startsWith("DROP_")).findFirst().orElse(null);
            if (oldest == null) return;
            items.remove(oldest);
        }
        String id = "DROP_" + ++nextId;
        GameData.ItemState item = new GameData.ItemState(id, "HEAL", monster.x, monster.y);
        item.respawnTimer = DROP_DESPAWN_TICKS;
        items.put(id, item);
    }

    /*
     * valid()
     * Trả true khi toàn hitbox đi được và tâm entity nằm trong vùng spawn liên thông. Không chỉ xét một
     * pixel dưới chân item.
     */
    private boolean valid(float x, float y) {
        return map.isWalkable(x, y) && map.isSpawnReachableAtWorld(
                (int) (x + GamePhysics.ENTITY_SIZE / 2f), (int) (y + GamePhysics.ENTITY_SIZE / 2f));
    }

    /*
     * update()
     * Duyệt từng item bằng iterator. Bình map đã nhặt đếm 900 tick rồi tìm spawn mới, thất bại đợi thêm
     * 60; drop chưa nhặt đếm về 0 thì xóa. Player còn sống/thiếu HP/chạm và có LOS được cộng tối đa 30 tới
     * maxHp; bình map ẩn chờ, drop bị xóa. Break sau một người để một bình không hồi cho nhiều player cùng
     * lúc.
     * Bình cố định đã nhặt đếm xuống 900 tick rồi tìm spawn mới; chưa có spawn hợp lệ thì chờ thêm 60
     * tick. Bình DROP_ chưa ai nhặt sẽ bị xóa khi hết 600 tick. Khi nhặt, player phải còn sống, chưa đầy
     * máu, giao hitbox và thông LOS; chỉ hồi cho một player rồi dừng duyệt player của bình đó.
     */
    public void update(Iterable<GameData.PlayerState> players, Supplier<Point> spawn) {
        Iterator<GameData.ItemState> iterator = items.values().iterator();
        while (iterator.hasNext()) {
            GameData.ItemState item = iterator.next();
            /*
             * POTION_ là bình cố định được hồi sinh; DROP_ là bình quái rơi sẽ bị xóa/despawn.
             */
            boolean permanent = item.id.startsWith("POTION_");
            if (item.collected) {
                if (!permanent) { iterator.remove(); continue; }
                if (--item.respawnTimer <= 0) {
                    Point point = spawn.get();
                    if (point != null && valid(point.x, point.y)) {
                        item.x = point.x; item.y = point.y; item.collected = false;
                    } else item.respawnTimer = 60;
                }
                continue;
            }
            if (!permanent && --item.respawnTimer <= 0) { iterator.remove(); continue; }
            for (GameData.PlayerState player : players) {
                if (player.hp <= 0 || player.hp >= player.maxHp) continue;
                if (!GamePhysics.getEntityBounds(player.x, player.y).intersects(GamePhysics.getEntityBounds(item.x, item.y))
                        || !MonsterAI.hasLineOfSight(player.x, player.y, item.x, item.y, map)) continue;
                player.hp = Math.min(player.maxHp, player.hp + item.healAmount);
                if (permanent) { item.collected = true; item.respawnTimer = POTION_RESPAWN_TICKS; }
                else iterator.remove();
                /*
                 * Một bình chỉ hồi cho một player, kể cả khi nhiều player giao cùng hitbox.
                 */
                break; // A potion can heal only one player, even when several overlap.
            }
        }
    }
}
