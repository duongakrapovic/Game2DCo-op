package source.common;

import java.awt.Rectangle;

/*
 * Luật chuyển động, va chạm và hình học dùng chung. updatePlayer được server chạy authoritative và client
 * gọi khi prediction/replay. Các hàm moveMonsterX/Y là tên wrapper kiểm va chạm; chúng không tự cộng tọa
 * độ quái.
 * PLAYER_SPEED=6, MONSTER_SPEED=1,5 pixel mỗi bước chuẩn; ENTITY_SIZE=48. Vị trí/vận tốc được sửa trực
 * tiếp trong model; map cung cấp truy vấn điểm chặn.
 */
public class GamePhysics {

    /*
     * PLAYER_SPEED: Bước tốc độ player cơ sở, được nhân theo dt.
     */
    public static final float PLAYER_SPEED = 6.0f;
    /*
     * MONSTER_SPEED: Bước tốc độ quái cơ sở.
     */
    public static final float MONSTER_SPEED = 1.5f;
    /*
     * ENTITY_SIZE: Kích thước hitbox entity, pixel.
     */
    public static final int ENTITY_SIZE = (int) (16 * Common.SCALE);





    /*
     * updatePlayer()
     * Nhận dt giây, tính tốc độ 6 × dt × 60, cộng phím theo trục và nhân 0,7071 khi đi chéo. Dời X rồi
     * kiểm collision, dời Y rồi kiểm tiếp; cập nhật hướng theo ưu tiên trái/phải/lên/xuống, frame bước
     * chân và kết thúc animation đánh khi ++attackTicks > 15. Không tự gửi input hoặc snapshot.
     * Với overload có dt: xóa vận tốc cũ, tính tốc độ từ phím và dt×60; đi chéo nhân 0.7071 để không nhanh
     * hơn. Áp X và sửa collision X trước, rồi áp Y và sửa collision Y. Cuối cùng đổi hướng, frame đi bộ và
     * tăng thời gian đòn đánh; không tự tính damage trong hàm này.
     */
    public static void updatePlayer(GameData.PlayerState p, GameMap map, float dt) {
        p.vx = 0;
        p.vy = 0;

        float currentSpeed = PLAYER_SPEED * (dt * 60.0f);
        if (p.left) p.vx -= currentSpeed;
        if (p.right) p.vx += currentSpeed;
        if (p.up) p.vy -= currentSpeed;
        if (p.down) p.vy += currentSpeed;

        /*
         * Chuẩn hóa đi chéo để tốc độ tổng không nhanh hơn đi thẳng.
         */
        if (p.vx != 0 && p.vy != 0) {
            p.vx *= 0.7071f;
            p.vy *= 0.7071f;
        }
        /*
         * Áp và sửa collision X trước, sau đó mới tới Y; ảnh vẽ không quyết định va chạm.
         */
        if (p.vx != 0) {
            p.x += p.vx;
            checkCollisionX(p, map);
        }
        if (p.vy != 0) {
            p.y += p.vy;
            checkCollisionY(p, map);
        }

        if (p.left) p.direction = "left";
        else if (p.right) p.direction = "right";
        else if (p.up) p.direction = "up";
        else if (p.down) p.direction = "down";

        if (p.vx != 0 || p.vy != 0) {
            p.animCounter++;
            if (p.animCounter >= 12) {
                p.spriteNum = p.spriteNum == 1 ? 2 : 1;
                p.animCounter = 0;
            }
        } else {
            p.spriteNum = 1;
            p.animCounter = 0;
        }

        if (p.isAttacking && ++p.attackTicks > 15) {
            p.isAttacking = false;
            p.attackTicks = 0;
        }
    }

    /*
     * updatePlayer()
     * Gọi bản có dt với 1/60 giây; đây là overload dùng cho mỗi bước mô phỏng chuẩn và replay prediction.
     */
    public static void updatePlayer(GameData.PlayerState p, GameMap map) {
        updatePlayer(p, map, 1.0f / 60.0f);
    }



    /** Shared authoritative AI/combat step for the server and offline mode. */






    /*
     * applyKnockback()
     * Tính vector đẩy player ra xa nguồn với độ dài force, cập nhật từng trục có collision rồi xóa vận
     * tốc. Là tác động tức thời trong bước hiện tại, không phải lực gia tốc kéo dài.
     */
    public static void applyKnockback(
            GameData.PlayerState target, float sourceX, float sourceY, float force, GameMap map) {
        float[] push = knockbackVector(target.x, target.y, sourceX, sourceY, force);
        target.vx = push[0];
        target.vy = push[1];
        target.x += target.vx;
        checkCollisionX(target, map);
        target.y += target.vy;
        checkCollisionY(target, map);
        target.vx = 0;
        target.vy = 0;
    }

    /*
     * applyKnockback()
     * Đẩy quái tương tự player nhưng dùng collision của quái; sau đẩy xóa path, pathIndex và nextPathAt để
     * AI lập lại đường từ vị trí mới.
     */
    public static void applyKnockback(
            GameData.MonsterState target, float sourceX, float sourceY, float force, GameMap map) {
        float[] push = knockbackVector(target.x, target.y, sourceX, sourceY, force);
        target.vx = push[0];
        target.vy = push[1];
        target.x += target.vx;
        checkMonsterCollisionX(target, map);
        target.y += target.vy;
        checkMonsterCollisionY(target, map);
        target.vx = 0;
        target.vy = 0;
        target.currentPath = new int[0][2];
        target.pathIndex = 0;
        target.nextPathAt = 0;
    }

    /*
     * knockbackVector()
     * Lấy vector từ nguồn tới đích, chuẩn hóa và nhân force. Khi hai điểm trùng nhau dùng hướng phải để
     * tránh chia 0; trả float[2] chứa thành phần X/Y.
     */
    private static float[] knockbackVector(float x, float y, float sourceX, float sourceY, float force) {
        float dx = x - sourceX;
        float dy = y - sourceY;
        float length = (float) Math.hypot(dx, dy);
        if (length == 0) {
            dx = 1;
            length = 1;
        }
        return new float[]{dx / length * force, dy / length * force};
    }











    /*
     * checkCollisionX()
     * Với player đang đi ngang, thử hai điểm bên sườn theo dấu vx. Nếu chạm tile chặn thì đặt x sát mép
     * tile, lệch 0,1 pixel để tránh kẹt; không kiểm swept collision toàn đoạn.
     */
    private static void checkCollisionX(GameData.PlayerState p, GameMap map) {
        if (p.vx > 0) {
            if (map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE), (int)p.y + 4)
                    || map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE), (int)(p.y + ENTITY_SIZE - 4))) {
                p.x = ((int)(p.x + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (p.vx < 0) {
            if (map.isCollisionAtWorld((int)p.x, (int)p.y + 4)
                    || map.isCollisionAtWorld((int)p.x, (int)(p.y + ENTITY_SIZE - 4))) {
                p.x = (((int)p.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    /*
     * checkCollisionY()
     * Kiểm hai điểm cạnh trên/dưới theo dấu vy, sửa y về mép tile cùng epsilon 0,1. Tách trục giúp player
     * trượt dọc tường thay vì dừng cả hai chiều.
     */
    private static void checkCollisionY(GameData.PlayerState p, GameMap map) {
        if (p.vy > 0) {
            if (map.isCollisionAtWorld((int)p.x + 4, (int)(p.y + ENTITY_SIZE))
                    || map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE - 4), (int)(p.y + ENTITY_SIZE))) {
                p.y = ((int)(p.y + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (p.vy < 0) {
            if (map.isCollisionAtWorld((int)p.x + 4, (int)p.y)
                    || map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE - 4), (int)p.y)) {
                p.y = (((int)p.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    /*
     * checkMonsterCollisionX()
     * Phiên bản kiểm sườn X cho MonsterState; được knockback và AI dùng sau khi quái đã cộng x.
     */
    static void checkMonsterCollisionX(GameData.MonsterState m, GameMap map) {
        if (m.vx > 0) {
            if (map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE), (int)m.y + 4)
                    || map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE), (int)(m.y + ENTITY_SIZE - 4))) {
                m.x = ((int)(m.x + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (m.vx < 0) {
            if (map.isCollisionAtWorld((int)m.x, (int)m.y + 4)
                    || map.isCollisionAtWorld((int)m.x, (int)(m.y + ENTITY_SIZE - 4))) {
                m.x = (((int)m.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    /*
     * checkMonsterCollisionY()
     * Phiên bản kiểm cạnh Y cho quái; giới hạn tọa độ ở mép vật cản sau bước di chuyển.
     */
    static void checkMonsterCollisionY(GameData.MonsterState m, GameMap map) {
        if (m.vy > 0) {
            if (map.isCollisionAtWorld((int)m.x + 4, (int)(m.y + ENTITY_SIZE))
                    || map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE - 4), (int)(m.y + ENTITY_SIZE))) {
                m.y = ((int)(m.y + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (m.vy < 0) {
            if (map.isCollisionAtWorld((int)m.x + 4, (int)m.y)
                    || map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE - 4), (int)m.y)) {
                m.y = (((int)m.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    /*
     * moveMonsterX()
     * Wrapper package-private gọi checkMonsterCollisionX. Caller phải cộng vị trí X trước; tên hàm không
     * có nghĩa tự di chuyển.
     */
    static void moveMonsterX(GameData.MonsterState m, GameMap map) { checkMonsterCollisionX(m, map); }

    /*
     * moveMonsterY()
     * Wrapper package-private gọi checkMonsterCollisionY sau bước Y mà AI đã thực hiện.
     */
    static void moveMonsterY(GameData.MonsterState m, GameMap map) { checkMonsterCollisionY(m, map); }

    /*
     * getEntityBounds()
     * Tạo Rectangle từ x/y ép số nguyên và kích thước 48×48. Dùng để kiểm chạm quái, người chơi và bình
     * máu.
     */
    public static Rectangle getEntityBounds(float x, float y) {
        return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
    }

    /*
     * getAttackHitbox()
     * Tạo hình chữ nhật tấn công nhô 36 pixel ra khỏi cạnh nhân vật theo hướng; hướng không nhận biết dùng
     * hitbox thân. Chỉ tạo hình học, CombatSystem còn phải kiểm LOS và sát thương.
     */
    public static Rectangle getAttackHitbox(float x, float y, String direction) {
        int range = 36;
        switch (direction) {
            case "up": return new Rectangle((int) x, (int) y - range, ENTITY_SIZE, range);
            case "down": return new Rectangle((int) x, (int) y + ENTITY_SIZE, ENTITY_SIZE, range);
            case "left": return new Rectangle((int) x - range, (int) y, range, ENTITY_SIZE);
            case "right": return new Rectangle((int) x + ENTITY_SIZE, (int) y, range, ENTITY_SIZE);
            default: return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
        }
    }
}
