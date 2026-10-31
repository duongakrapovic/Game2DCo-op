package source.common;

public class PhysicsEngine {
    // Tốc độ di chuyển cơ bản ở chuẩn 60 FPS (12.0f px / tick)
    public static final float BASE_SPEED = 12.0f;

    /**
     * Cập nhật vật lý và di chuyển của Player theo khoảng thời gian delta time (dt).
     * @param p Đối tượng người chơi
     * @param mapManager Quản lý map để kiểm tra va chạm
     * @param dt Thời gian trôi qua giữa 2 lần cập nhật (giây)
     */
    public static void updatePlayer(Player p, MapManager mapManager, float dt) {
        p.vx = 0;
        p.vy = 0;

        // Chuẩn hóa theo 60 FPS: nếu dt = 1/60s (~0.0166s) thì timeFactor = 1.0f
        float timeFactor = dt * 60.0f;
        float currentSpeed = BASE_SPEED * timeFactor;

        // Di chuyển Top-Down 4 hướng W-A-S-D (hoặc mũi tên)
        if (p.left)  p.vx -= currentSpeed;
        if (p.right) p.vx += currentSpeed;
        if (p.up)    p.vy -= currentSpeed;
        if (p.down)  p.vy += currentSpeed;

        // Chuẩn hóa tốc độ khi đi đường chéo
        if (p.vx != 0 && p.vy != 0) {
            p.vx *= 0.7071f;
            p.vy *= 0.7071f;
        }

        // Xử lý va chạm trục X
        if (p.vx != 0) {
            p.x += p.vx;
            checkCollisionX(p, mapManager);
        }

        // Xử lý va chạm trục Y
        if (p.vy != 0) {
            p.y += p.vy;
            checkCollisionY(p, mapManager);
        }

        // Cập nhật hướng nhìn
        if (p.left) {
            p.direction = "left";
        } else if (p.right) {
            p.direction = "right";
        } else if (p.up) {
            p.direction = "up";
        } else if (p.down) {
            p.direction = "down";
        }

        // Cập nhật nhịp bước chân qua AnimationController
        boolean isMoving = (p.vx != 0 || p.vy != 0);
        if (p.anim != null) {
            p.anim.update(p, isMoving);
        }
    }

    /**
     * Quá tải cho tick chuẩn 60 FPS (tương thích máy chủ GameRoom)
     */
    public static void updatePlayer(Player p, MapManager mapManager) {
        updatePlayer(p, mapManager, 1.0f / 60.0f);
    }

    private static void checkCollisionX(Player p, MapManager mapManager) {
        int playerSize = (int) (16 * Common.SCALE); // Kích thước hitbox
        if (p.vx > 0) { // Sang phải
            if (mapManager.isCollisionAtWorld((int)(p.x + playerSize), (int)p.y + 4) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize), (int)(p.y + playerSize - 4))) {
                p.x = ((int)(p.x + playerSize) / Common.TILE_SIZE) * Common.TILE_SIZE - playerSize - 0.1f;
            }
        } else if (p.vx < 0) { // Sang trái
            if (mapManager.isCollisionAtWorld((int)p.x, (int)p.y + 4) || 
                mapManager.isCollisionAtWorld((int)p.x, (int)(p.y + playerSize - 4))) {
                p.x = (((int)p.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    private static void checkCollisionY(Player p, MapManager mapManager) {
        int playerSize = (int) (16 * Common.SCALE);
        if (p.vy > 0) { // Đi xuống
            if (mapManager.isCollisionAtWorld((int)p.x + 4, (int)(p.y + playerSize)) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize - 4), (int)(p.y + playerSize))) {
                p.y = ((int)(p.y + playerSize) / Common.TILE_SIZE) * Common.TILE_SIZE - playerSize - 0.1f;
            }
        } else if (p.vy < 0) { // Đi lên
            if (mapManager.isCollisionAtWorld((int)p.x + 4, (int)p.y) || 
                mapManager.isCollisionAtWorld((int)(p.x + playerSize - 4), (int)p.y)) {
                p.y = (((int)p.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }
}
