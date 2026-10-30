package source.server;

import source.common.Common;
import source.common.MapManager;
import source.common.Player;

public class PhysicsEngine {
    private static final float MOVE_SPEED = 4.0f;

    public static void updatePlayer(Player p, MapManager mapManager) {
        p.vx = 0;
        p.vy = 0;

        // Di chuyển Top-Down 4 hướng W-A-S-D (hoặc mũi tên)
        if (p.left)  p.vx -= MOVE_SPEED;
        if (p.right) p.vx += MOVE_SPEED;
        if (p.up)    p.vy -= MOVE_SPEED; // Cần thêm biến up/down
        if (p.down)  p.vy += MOVE_SPEED;

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