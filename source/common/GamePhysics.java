package source.common;

import java.awt.Rectangle;

public class GamePhysics {

    public static final float PLAYER_SPEED = 6.0f; // Đã tăng tốc độ di chuyển
    public static final float MONSTER_SPEED = 1.5f;
    public static final int ENTITY_SIZE = (int) (16 * Common.SCALE); // Kích thước hitbox chuẩn (32px / 40px)

    // 1. Cập nhật Player & Va chạm Map
    public static void updatePlayer(GameData.PlayerState p, GameMap map, float dt) {
        p.vx = 0;
        p.vy = 0;

        float currentSpeed = PLAYER_SPEED * (dt * 60.0f);

        if (p.left)  p.vx -= currentSpeed;
        if (p.right) p.vx += currentSpeed;
        if (p.up)    p.vy -= currentSpeed;
        if (p.down)  p.vy += currentSpeed;

        if (p.vx != 0 && p.vy != 0) {
            p.vx *= 0.7071f;
            p.vy *= 0.7071f;
        }

        if (p.vx != 0) {
            p.x += p.vx;
            checkCollisionX(p, map);
        }
        if (p.vy != 0) {
            p.y += p.vy;
            checkCollisionY(p, map);
        }

        if (p.left)        p.direction = "left";
        else if (p.right) p.direction = "right";
        else if (p.up)    p.direction = "up";
        else if (p.down)  p.direction = "down";

        if (p.vx != 0 || p.vy != 0) {
            p.animCounter++;
            if (p.animCounter >= 12) {
                p.spriteNum = (p.spriteNum == 1) ? 2 : 1;
                p.animCounter = 0;
            }
        } else {
            p.spriteNum = 1;
            p.animCounter = 0;
        }

        if (p.isAttacking) {
            p.attackTicks++;
            if (p.attackTicks > 15) {
                p.isAttacking = false;
                p.attackTicks = 0;
            }
        }
    }

    public static void updatePlayer(GameData.PlayerState p, GameMap map) {
        updatePlayer(p, map, 1.0f / 60.0f);
    }

    // 2. Cập nhật AI Quái Slime + Va chạm Map (Không đi xuyên tường)
    public static void updateMonster(GameData.MonsterState m, GameData.PlayerState target, GameMap map) {
        if (m.isDead || target == null) return;

        float dx = target.x - m.x;
        float dy = target.y - m.y;
        float dist = (float) Math.hypot(dx, dy);

        m.vx = 0;
        m.vy = 0;

        // Nếu Player ở khoảng cách < 350px thì đuổi theo
        if (dist > 5 && dist < 350) {
            float moveStep = MONSTER_SPEED;
            m.vx = (dx / dist) * moveStep;
            m.vy = (dy / dist) * moveStep;

            // Xử lý va chạm X độc lập cho Quái
            if (m.vx != 0) {
                m.x += m.vx;
                checkMonsterCollisionX(m, map);
            }
            // Xử lý va chạm Y độc lập cho Quái
            if (m.vy != 0) {
                m.y += m.vy;
                checkMonsterCollisionY(m, map);
            }

            m.animCounter++;
            if (m.animCounter >= 15) {
                m.spriteNum = (m.spriteNum == 1) ? 2 : 1;
                m.animCounter = 0;
            }
        }

        // Quái đánh Player nếu va chạm Hitbox
        Rectangle mBounds = getEntityBounds(m.x, m.y);
        Rectangle pBounds = getEntityBounds(target.x, target.y);
        if (mBounds.intersects(pBounds)) {
            m.attackCooldown++;
            if (m.attackCooldown >= 30) {
                target.hp = Math.max(0, target.hp - 5);
                m.attackCooldown = 0;
            }
        }
    }

    // --- CÁC HÀM KIỂM TRA VA CHẠM THUẦN TÚY DÙNG CHUNG ---

    private static void checkCollisionX(GameData.PlayerState p, GameMap map) {
        if (p.vx > 0) {
            if (map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE), (int)p.y + 4) || 
                map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE), (int)(p.y + ENTITY_SIZE - 4))) {
                p.x = ((int)(p.x + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (p.vx < 0) {
            if (map.isCollisionAtWorld((int)p.x, (int)p.y + 4) || 
                map.isCollisionAtWorld((int)p.x, (int)(p.y + ENTITY_SIZE - 4))) {
                p.x = (((int)p.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    private static void checkCollisionY(GameData.PlayerState p, GameMap map) {
        if (p.vy > 0) {
            if (map.isCollisionAtWorld((int)p.x + 4, (int)(p.y + ENTITY_SIZE)) || 
                map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE - 4), (int)(p.y + ENTITY_SIZE))) {
                p.y = ((int)(p.y + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (p.vy < 0) {
            if (map.isCollisionAtWorld((int)p.x + 4, (int)p.y) || 
                map.isCollisionAtWorld((int)(p.x + ENTITY_SIZE - 4), (int)p.y)) {
                p.y = (((int)p.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    private static void checkMonsterCollisionX(GameData.MonsterState m, GameMap map) {
        if (m.vx > 0) {
            if (map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE), (int)m.y + 4) || 
                map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE), (int)(m.y + ENTITY_SIZE - 4))) {
                m.x = ((int)(m.x + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (m.vx < 0) {
            if (map.isCollisionAtWorld((int)m.x, (int)m.y + 4) || 
                map.isCollisionAtWorld((int)m.x, (int)(m.y + ENTITY_SIZE - 4))) {
                m.x = (((int)m.x / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    private static void checkMonsterCollisionY(GameData.MonsterState m, GameMap map) {
        if (m.vy > 0) {
            if (map.isCollisionAtWorld((int)m.x + 4, (int)(m.y + ENTITY_SIZE)) || 
                map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE - 4), (int)(m.y + ENTITY_SIZE))) {
                m.y = ((int)(m.y + ENTITY_SIZE) / Common.TILE_SIZE) * Common.TILE_SIZE - ENTITY_SIZE - 0.1f;
            }
        } else if (m.vy < 0) {
            if (map.isCollisionAtWorld((int)m.x + 4, (int)m.y) || 
                map.isCollisionAtWorld((int)(m.x + ENTITY_SIZE - 4), (int)m.y)) {
                m.y = (((int)m.y / Common.TILE_SIZE) + 1) * Common.TILE_SIZE + 0.1f;
            }
        }
    }

    public static Rectangle getEntityBounds(float x, float y) {
        return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
    }

    public static Rectangle getAttackHitbox(float x, float y, String direction) {
        int range = 24;
        switch (direction) {
            case "up":    return new Rectangle((int) x, (int) y - range, ENTITY_SIZE, range);
            case "down":  return new Rectangle((int) x, (int) y + ENTITY_SIZE, ENTITY_SIZE, range);
            case "left":  return new Rectangle((int) x - range, (int) y, range, ENTITY_SIZE);
            case "right": return new Rectangle((int) x + ENTITY_SIZE, (int) y, range, ENTITY_SIZE);
            default:      return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
        }
    }
}