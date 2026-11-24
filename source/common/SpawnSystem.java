package source.common;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Random;

/** Shared safe spawn search; exhaustion returns null, never an unchecked fallback. */
/*
 * Tập trung chọn điểm spawn để mọi caller dùng cùng kiểm tra an toàn. Trả null có chủ đích khi không tìm
 * được chỗ; ServerGame/ItemSystem phải xử lý thay vì truy cập tọa độ của null.
 * Dùng map, Random và danh sách entity do caller truyền; không giữ danh sách thế giới riêng hoặc mở
 * mạng/file.
 */
public final class SpawnSystem {
    /*
     * Khởi tạo SpawnSystem
     * Constructor private chặn tạo object tiện ích; các thao tác chọn spawn là static.
     */
    private SpawnSystem() {}
    /*
     * find()
     * Thử tối đa 2000 tọa độ ngẫu nhiên trong vùng map, mỗi điểm qua valid. Nếu chưa có, quét grid tile
     * theo thứ tự làm fallback; trả Point đầu đạt hoặc null. Fallback hữu hạn tránh vòng lặp ngẫu nhiên vô
     * tận.
     * Thử ngẫu nhiên tối đa 2000 lần; nếu chưa được thì quét ô lưới toàn map. valid đòi vừa hitbox, nối
     * được tới spawn, cách spawn mặc định ít nhất ba cạnh entity và không chồng player/quái sống. Hết lựa
     * chọn trả null; caller phải hoãn spawn thay vì dùng điểm không được kiểm.
     */
    public static Point find(GameMap map, Random random, Iterable<GameData.MonsterState> monsters,
            Iterable<GameData.PlayerState> players) {
        int maxX = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        int maxY = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        /*
         * Thử random hữu hạn trước, rồi quét lưới; không tìm được thì trả null để caller hoãn spawn.
         */
        for (int attempt = 0; attempt < 2000; attempt++) {
            int x = random.nextInt(maxX + 1), y = random.nextInt(maxY + 1);
            if (valid(map, x, y, monsters, players)) return new Point(x, y);
        }
        for (int y = 0; y <= maxY; y += Common.TILE_SIZE) {
            for (int x = 0; x <= maxX; x += Common.TILE_SIZE) {
                if (valid(map, x, y, monsters, players)) return new Point(x, y);
            }
        }
        return null;
    }
    /*
     * valid()
     * Kiểm hitbox đi được, thuộc vùng liên thông với spawn, xa spawn mặc định ít nhất ba kích thước entity
     * và không giao player/quái còn sống. Quái chết không cản spawn như quái đang sống.
     */
    private static boolean valid(GameMap map, int x, int y, Iterable<GameData.MonsterState> monsters,
            Iterable<GameData.PlayerState> players) {
        int half = GamePhysics.ENTITY_SIZE / 2;
        if (!map.isWalkable(x, y) || !map.isSpawnReachableAtWorld(x + half, y + half)
                || Math.hypot(x - Common.SPAWN_X, y - Common.SPAWN_Y) < GamePhysics.ENTITY_SIZE * 3) return false;
        Rectangle bounds = GamePhysics.getEntityBounds(x, y);
        for (GameData.MonsterState monster : monsters) {
            if (!monster.isDead && bounds.intersects(GamePhysics.getEntityBounds(monster.x, monster.y))) return false;
        }
        for (GameData.PlayerState player : players) {
            if (bounds.intersects(GamePhysics.getEntityBounds(player.x, player.y))) return false;
        }
        return true;
    }
}
