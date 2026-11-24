package source.common;

import java.awt.Point;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies manual ports from tranvanducb23dccn191 without game assets or save files. */
/*
 * Chương trình kiểm thử hồi quy gameplay được tích hợp từ nhánh Đức. Dùng map/Random giả trong bộ nhớ;
 * không phải chức năng người chơi hoặc vòng mô phỏng sản xuất.
 * checks đếm assertion, OpenMap định nghĩa địa hình giả, ALWAYS_DROP buộc có loot để kiểm được quy tắc.
 * Các Anonymous@dòng là lớp ẩn trong test, không phải file Java mới.
 */
public final class PortedGameplayRegressionChecks {
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
     * Map mở nhân tạo cho kiểm thử gameplay; chủ động quy định collision và vùng spawn để các ca lặp lại
     * được.
     */
    private static class OpenMap extends GameMap {
        /*
         * isCollisionAtWorld()
         * Override của OpenMap: chỉ coi x/y âm là chặn, còn vùng dương trống. Cho test vật lý một map đơn
         * giản không đọc asset.
         */
        @Override public boolean isCollisionAtWorld(int x, int y) { return x < 0 || y < 0; }
    }
    /*
     * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
     * override bên dưới.
     * ALWAYS_DROP: Bộ đếm, tài nguyên nhân tạo hoặc cấu hình chỉ dùng trong kiểm thử.
     */
    private static final Random ALWAYS_DROP = new Random(7) {
        /*
         * nextBoolean()
         * Override Random của ALWAYS_DROP, luôn true để mọi lần thử rơi đồ đi vào nhánh tạo drop; loại sự
         * ngẫu nhiên khỏi kỳ vọng test.
         */
        @Override public boolean nextBoolean() { return true; }
    };
    /*
     * player()
     * Tạo PlayerState với tên=ID, đặt x=y=100 và HP được truyền; trả mẫu nhỏ cho các test bình/chiến đấu.
     */
    private static GameData.PlayerState player(String id, int hp) {
        GameData.PlayerState player = new GameData.PlayerState(id, id);
        player.x = player.y = 100; player.hp = hp;
        return player;
    }

    /*
     * potions()
     * Kiểm tạo bình khi spawn thất bại, người đầy HP không nhặt, một bình chỉ hồi một người, respawn an
     * toàn, giới hạn 60, drop hết hạn, HP cap và không rơi trên đất không tới được. Random/map giả buộc
     * từng nhánh cần kiểm.
     */
    private static void potions() {
        GameMap map = new OpenMap();
        ItemSystem items = new ItemSystem(map, ALWAYS_DROP);
        AtomicInteger spawned = new AtomicInteger();
        /*
         * Callback/lambda: Supplier điểm spawn điều khiển được trong test, có thể trả Point hoặc null để
         * kiểm nhánh tạo/hồi sinh bình.
         */
        items.initialize(() -> spawned.getAndIncrement() == 0 ? new Point(100, 100) : null);
        check(items.items().size() == 1, "Failed initial spawn is skipped");
        GameData.PlayerState full = player("full", 100), hurt = player("hurt", 10), other = player("other", 10);
        /*
         * Callback/lambda: Supplier giả lập không tìm được điểm spawn; xác nhận caller không dùng fallback
         * sai.
         */
        items.update(List.of(full), () -> null);
        check(!items.items().iterator().next().collected, "Full-health player leaves potion");
        /*
         * Callback/lambda: Supplier giả lập không tìm được điểm spawn; xác nhận caller không dùng fallback
         * sai.
         */
        items.update(List.of(hurt, other), () -> null);
        check(hurt.hp == 40 && other.hp == 10, "One potion heals one player by 30");
        GameData.ItemState potion = items.items().iterator().next();
        check(potion.collected && potion.respawnTimer == 900, "Map potion starts its respawn timer");
        /*
         * Callback/lambda: Supplier giả lập không tìm được điểm spawn; xác nhận caller không dùng fallback
         * sai.
         */
        for (int i = 0; i < 900; i++) items.update(List.of(), () -> null);
        check(potion.collected && potion.respawnTimer == 60, "Failed respawn stays dormant and retries");
        /*
         * Callback/lambda: Supplier điểm spawn điều khiển được trong test, có thể trả Point hoặc null để
         * kiểm nhánh tạo/hồi sinh bình.
         */
        for (int i = 0; i < 60; i++) items.update(List.of(), () -> new Point(200, 200));
        check(!potion.collected && potion.x == 200, "Map potion safely respawns");

        GameData.MonsterState killed = new GameData.MonsterState("M", 100, 100);
        for (int i = 0; i < 100; i++) items.onMonsterKilled(killed);
        check(items.items().size() == 60, "Drop count bounded immediately");
        /*
         * Callback/lambda: Predicate nhận diện bình cố định POTION_ trong tập item kiểm thử.
         */
        check(items.items().stream().anyMatch(item -> item.id.startsWith("POTION_")), "Eviction retains map potions");
        /*
         * Callback/lambda: Supplier giả lập không tìm được điểm spawn; xác nhận caller không dùng fallback
         * sai.
         */
        for (int i = 0; i < 600; i++) items.update(List.of(), () -> null);
        check(items.items().size() == 1, "Unclaimed monster drops despawn after 600 ticks");

        ItemSystem drops = new ItemSystem(map, ALWAYS_DROP);
        drops.onMonsterKilled(killed);
        GameData.PlayerState nearMax = player("near", 90);
        /*
         * Callback/lambda: Supplier giả lập không tìm được điểm spawn; xác nhận caller không dùng fallback
         * sai.
         */
        drops.update(List.of(nearMax), () -> null);
        check(nearMax.hp == 100 && drops.items().isEmpty(), "Collected drop removed, HP capped");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        ItemSystem unlucky = new ItemSystem(map, new Random(1) {
            /*
             * nextBoolean()
             * Override Random luôn false, xác nhận nhánh không rơi đồ không tạo item.
             */
            @Override public boolean nextBoolean() { return false; }
        });
        unlucky.onMonsterKilled(killed);
        check(unlucky.items().isEmpty(), "Non-drop outcome preserved");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        ItemSystem noLand = new ItemSystem(new OpenMap() {
            /*
             * isSpawnReachableAtWorld()
             * Override map luôn false để kiểm không thả bình ở vùng không liên thông.
             */
            @Override public boolean isSpawnReachableAtWorld(int x, int y) { return false; }
        }, ALWAYS_DROP);
        noLand.onMonsterKilled(killed);
        check(noLand.items().isEmpty(), "No drops on unreachable land");
    }

    /*
     * combatAndSpawns()
     * Kiểm hitbox tầm mở rộng nhưng tường chặn, callback chết đúng một lần, quái chết không tạo loot lặp.
     * Dùng map đảo/tường và map chặn toàn bộ để kiểm spawn không sang vùng cô lập, có fallback và trả null
     * khi hết đất.
     */
    private static void combatAndSpawns() {
        GameData.PlayerState player = player("fighter", 100);
        player.x = player.y = 0; player.direction = "right";
        GameData.MonsterState monster = new GameData.MonsterState("M", 75, 0); monster.hp = 15;
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        GameMap wall = new OpenMap() {
            /*
             * isCollisionAtWorld()
             * Tạo tường giả ở dải x từ 50 đến dưới 60 để đòn melee không xuyên tới quái.
             */
            @Override public boolean isCollisionAtWorld(int x, int y) { return x >= 50 && x < 60; }
        };
        AtomicInteger deaths = new AtomicInteger();
        /*
         * Callback/lambda: Supplier cố định thời gian hồi sinh 180 tick cho test combat.
         * Callback/lambda: Callback onKilled tăng bộ đếm để kiểm một lần quái chết chỉ phát sự kiện một
         * lần.
         */
        CombatSystem.resolveAttack(player, List.of(monster), wall, () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(monster.hp == 15 && deaths.get() == 0, "Wall blocks extended melee hitbox");
        /*
         * Callback/lambda: Supplier cố định thời gian hồi sinh 180 tick cho test combat.
         * Callback/lambda: Callback onKilled tăng bộ đếm để kiểm một lần quái chết chỉ phát sự kiện một
         * lần.
         */
        CombatSystem.resolveAttack(player, List.of(monster), new OpenMap(), () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(monster.isDead && deaths.get() == 1, "Extended range hits and emits one kill event");
        /*
         * Callback/lambda: Supplier cố định thời gian hồi sinh 180 tick cho test combat.
         * Callback/lambda: Callback onKilled tăng bộ đếm để kiểm một lần quái chết chỉ phát sự kiện một
         * lần.
         */
        CombatSystem.resolveAttack(player, List.of(monster), new OpenMap(), () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(deaths.get() == 1, "Dead monster does not produce duplicate loot");

        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        GameMap islands = new OpenMap() {
            /*
             * isCollisionAtWorld()
             * Tạo vách tại cột tile 30 cùng biên âm, chia vùng đất thành hai phía để test flood
             * fill/spawn.
             */
            @Override public boolean isCollisionAtWorld(int x, int y) {
                return x < 0 || y < 0 || x / Common.TILE_SIZE == 30;
            }
        };
        check(islands.isSpawnReachableAtWorld((int) Common.SPAWN_X, (int) Common.SPAWN_Y), "Spawn belongs to its connected region");
        check(!islands.isSpawnReachableAtWorld(40 * Common.TILE_SIZE, 10 * Common.TILE_SIZE), "Isolated land excluded");
        Random random = new Random(11);
        for (int i = 0; i < 30; i++) {
            Point point = SpawnSystem.find(islands, random, List.of(), List.of());
            check(point != null && point.x + GamePhysics.ENTITY_SIZE <= 30 * Common.TILE_SIZE,
                    "Spawn stays on reachable side with full hitbox");
        }
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        GameMap blocked = new OpenMap() {
            /*
             * isWalkable()
             * Override luôn false nhằm mô phỏng map không có điểm đặt hitbox hợp lệ; kỳ vọng
             * SpawnSystem.find trả null.
             */
            @Override public boolean isWalkable(float x, float y) { return false; }
        };
        check(SpawnSystem.find(blocked, random, List.of(), List.of()) == null, "No land returns null instead of unsafe spawn");
    }

    /*
     * decode()
     * Đưa từng packet vào SnapshotDecoder, kiểm chiều dài/version rồi giữ snapshot khác null cuối cùng.
     * Trả null nếu bộ mảnh chưa tạo được một frame.
     */
    private static GameData.WorldSnapshot decode(GameProtocol.SnapshotDecoder decoder, List<byte[]> packets) {
        GameData.WorldSnapshot result = null;
        for (byte[] packet : packets) {
            check(packet.length <= 499, "Items preserve bounded UDP datagrams");
            check(packet[5] == GameProtocol.SNAPSHOT_VERSION, "Binary format explicitly versioned");
            GameData.WorldSnapshot snapshot = decoder.accept(packet, 5, packet.length - 5);
            if (snapshot != null) result = snapshot;
        }
        return result;
    }
    /*
     * snapshots()
     * Kiểm đủ 60 item trong full ghép đảo thứ tự, tọa độ/nhóm dữ liệu binary, delta
     * thêm/xóa/collected/heal, baseline không bị sửa, tiện ích text và từ chối version cũ. Không tạo lưu
     * lượng mạng thật.
     */
    private static void snapshots() {
        GameData.WorldSnapshot baseline = new GameData.WorldSnapshot();
        for (int i = 0; i < 4; i++) baseline.players.add(player("P" + i, 80));
        for (int i = 0; i < 15; i++) baseline.monsters.add(new GameData.MonsterState("M" + i, i * 70, 300));
        for (int i = 0; i < 60; i++) baseline.items.add(new GameData.ItemState("DROP_" + i, "HEAL", i + 0.125f, 250.5f));
        List<byte[]> full = GameProtocol.serializeSnapshotBinary(1, 9, 1, baseline, null);
        Collections.reverse(full);
        GameProtocol.SnapshotDecoder decoder = new GameProtocol.SnapshotDecoder();
        GameData.WorldSnapshot restored = decode(decoder, full);
        check(restored != null && restored.items.size() == 60, "Maximum item count reassembles atomically");
        check(restored.items.get(5).x == 5.125f && restored.items.get(5).healAmount == 30, "Binary item fields preserved exactly");
        GameData.WorldSnapshot changed = GameProtocol.copySnapshot(baseline);
        changed.items.remove(0);
        changed.items.get(0).collected = true; changed.items.get(0).healAmount = 25;
        changed.items.get(1).x = 900.75f;
        changed.items.add(new GameData.ItemState("NEW", "HEAL", 11, 22));
        restored = decode(decoder, GameProtocol.serializeSnapshotBinary(3, 9, 1, changed, baseline));
        check(restored != null && restored.items.size() == 60, "Item delta supports removal plus insertion");
        /*
         * Callback/lambda: Predicate tìm đúng item DROP_0 để kiểm snapshot sau round trip.
         */
        check(restored.items.stream().noneMatch(item -> item.id.equals("DROP_0")), "Removed item stays removed");
        check(restored.items.get(0).collected && restored.items.get(0).healAmount == 25, "Collected and healing deltas");
        check(restored.items.get(1).x == 900.75f, "Position delta");
        check(!baseline.items.get(1).collected, "Delta does not mutate baseline");
        GameData.WorldSnapshot text = GameProtocol.parseSnapshot(GameProtocol.serializeSnapshot(changed));
        check(text.items.size() == 60 && text.items.get(0).healAmount == 25, "Text utility retains item fields");
        changed.items.clear();
        restored = decode(decoder, GameProtocol.serializeSnapshotBinary(4, 9, 1, changed, baseline));
        check(restored != null && restored.items.isEmpty(), "All item removals decoded");
        GameProtocol.SnapshotDecoder oldVersion = new GameProtocol.SnapshotDecoder();
        for (byte[] packet : full) {
            byte[] old = packet.clone(); old[5] = 1;
            check(oldVersion.accept(old, 5, old.length - 5) == null, "Old binary format rejected rather than misparsed");
        }
    }

    /*
     * main()
     * Chạy lần lượt potions, combatAndSpawns và snapshots rồi in tổng số check đạt; exception làm chương
     * trình thất bại.
     */
    public static void main(String[] args) {
        potions(); combatAndSpawns(); snapshots();
        System.out.println("PASS: " + checks + " manually ported gameplay assertions");
    }
}
