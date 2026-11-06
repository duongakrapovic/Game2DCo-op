package source.common;

/**
 * ============================================================================
 * MODULE PHYSICS ENGINE (SOURCE/COMMON/PHYSICSENGINE.JAVA)
 * ============================================================================
 * TÁC DỤNG:
 *  - Xử lý di chuyển vật lý Top-Down ở chuẩn 60 Hz.
 *  - Chuẩn hóa vận tốc khi đi đường chéo (ngăn bug nhân vật đi chéo nhanh gấp 1.41 lần).
 *  - Xử lý va chạm 2 trục X - Y độc lập (tạo hiệu ứng trượt va chạm mượt dọc bờ tường).
 *  - Đồng bộ logic vật lý 100% giữa Server (tính toán chuẩn) và Client (prediction).
 * ============================================================================
 */
public class PhysicsEngine {

    // Tốc độ di chuyển tiêu chuẩn ở 60 FPS (12.0 pixel / tick)
    public static final float BASE_SPEED = 12.0f;

    /**
     * Cập nhật vị trí, vận tốc, va chạm và animation của nhân vật theo Delta Time.
     * @param p Nhân vật cần cập nhật
     * @param mapManager Bộ quản lý bản đồ chứa thông tin ô cản (Collision Tiles)
     * @param dt Thời gian trôi qua giữa 2 frame (giây)
     */
    public static void updatePlayer(Player p, MapManager mapManager, float dt) {
        p.vx = 0;
        p.vy = 0;

        // Chuẩn hóa Hệ số Thời gian theo chuẩn 60 FPS (Nếu dt = 1/60s ~ 0.0166s => timeFactor = 1.0f)
        float timeFactor = dt * 60.0f;
        float currentSpeed = BASE_SPEED * timeFactor;

        // 1. Tính toán vận tốc dựa trên phím bấm di chuyển
        if (p.left)  p.vx -= currentSpeed;
        if (p.right) p.vx += currentSpeed;
        if (p.up)    p.vy -= currentSpeed;
        if (p.down)  p.vy += currentSpeed;

        // 2. Chuẩn hóa đường chéo (Normalizing Diagonal Movement: 1 / sqrt(2) ~ 0.7071)
        if (p.vx != 0 && p.vy != 0) {
            p.vx *= 0.7071f;
            p.vy *= 0.7071f;
        }

        // 3. Xử lý va chạm và cập nhật vị trí theo Trục X
        if (p.vx != 0) {
            p.x += p.vx;
            checkCollisionX(p, mapManager);
        }

        // 4. Xử lý va chạm và cập nhật vị trí theo Trục Y
        if (p.vy != 0) {
            p.y += p.vy;
            checkCollisionY(p, mapManager);
        }

        // 5. Cập nhật hướng nhìn nhân vật
        if (p.left) {
            p.direction = "left";
        } else if (p.right) {
            p.direction = "right";
        } else if (p.up) {
            p.direction = "up";
        } else if (p.down) {
            p.direction = "down";
        }

        // 6. Cập nhật nhịp animation bước chân qua AnimationController
        boolean isMoving = (p.vx != 0 || p.vy != 0);
        if (p.anim != null) {
            p.anim.update(p, isMoving);
        }
    }

    /** Overload mặc định chạy ở nhịp 60 FPS (dt = 1/60 giây) */
    public static void updatePlayer(Player p, MapManager mapManager) {
        updatePlayer(p, mapManager, 1.0f / 60.0f);
    }

    /** Xử lý va chạm theo Trục Hoành (X) */
    private static void checkCollisionX(Player p, MapManager mapManager) {
        int playerSize = (int) (16 * Common.SCALE); // Kích thước Hitbox nhân vật (32px)

        if (p.vx > 0) { // Đang đi sang phải
            if (mapManager.isCollisionAtWorld((int)(p.x + playerSize), (int)p.y + 4) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize), (int)(p.y + playerSize - 4))) {
                // Đẩy nhân vật ra sát mép trái ô cản
                p.x = ((int)(p.x + playerSize) / Common.TILE_SIZE) * Common.TILE_SIZE - playerSize - 0.1f;
            }
        } else if (p.vx < 0) { // Đang đi sang trái
            if (mapManager.isCollisionAtWorld((int)p.x, (int)p.y + 4) || 
                mapManager.isCollisionAtWorld((int)p.x, (int)(p.y + playerSize - 4))) {
                // Đẩy nhân vật ra sát mép phải ô cản
                p.x = (((int)p.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    /** Xử lý va chạm theo Trục Tung (Y) */
    private static void checkCollisionY(Player p, MapManager mapManager) {
        int playerSize = (int) (16 * Common.SCALE);

        if (p.vy > 0) { // Đang đi xuống dưới
            if (mapManager.isCollisionAtWorld((int)p.x + 4, (int)(p.y + playerSize)) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize - 4), (int)(p.y + playerSize))) {
                // Đẩy nhân vật ra sát mép trên ô cản
                p.y = ((int)(p.y + playerSize) / Common.TILE_SIZE) * Common.TILE_SIZE - playerSize - 0.1f;
            }
        } else if (p.vy < 0) { // Đang đi lên trên
            if (mapManager.isCollisionAtWorld((int)p.x + 4, (int)p.y) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize - 4), (int)p.y)) {
                // Đẩy nhân vật ra sát mép dưới ô cản
                p.y = (((int)p.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }
}