package source.common;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

public class GamePhysics {

    public static final float PLAYER_SPEED = 6.0f;
    public static final float MONSTER_SPEED = 1.5f;
    public static final int ENTITY_SIZE = (int) (16 * Common.SCALE);
    private static final int MAX_PATH_NODES = 512;
    private static final int MONSTER_ATTACK_RANGE = 50;
    private static final int MONSTER_CHASE_RANGE = 450;
    private static final int MONSTER_ATTACK_COOLDOWN_TICKS = 30;

    public static void updatePlayer(GameData.PlayerState p, GameMap map, float dt) {
        p.vx = 0;
        p.vy = 0;

        float currentSpeed = PLAYER_SPEED * (dt * 60.0f);
        if (p.left) p.vx -= currentSpeed;
        if (p.right) p.vx += currentSpeed;
        if (p.up) p.vy -= currentSpeed;
        if (p.down) p.vy += currentSpeed;

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

    public static void updatePlayer(GameData.PlayerState p, GameMap map) {
        updatePlayer(p, map, 1.0f / 60.0f);
    }

    public static void updateMonster(GameData.MonsterState m, GameData.PlayerState target, GameMap map) {
        updateMonster(m, target, map, 1.0f / 60.0f);
    }

    /** Shared authoritative AI/combat step for the server and offline mode. */
    public static void updateMonster(
            GameData.MonsterState m, GameData.PlayerState target, GameMap map, float dt) {
        if (m.isDead || target == null) return;

        long now = System.currentTimeMillis();
        float dx = target.x - m.x;
        float dy = target.y - m.y;
        float distance = (float) Math.hypot(dx, dy);
        m.vx = 0;
        m.vy = 0;

        if (distance <= MONSTER_ATTACK_RANGE && hasLineOfSight(m.x, m.y, target.x, target.y, map)) {
            faceToward(m, dx, dy);
            m.currentPath = new int[0][2];
            m.pathIndex = 0;
            m.nextPathAt = now + 400;
            if (++m.attackCooldown >= MONSTER_ATTACK_COOLDOWN_TICKS) {
                m.attackCooldown = 0;
                if (target.hp > 0 && now >= target.invincibleUntil) {
                    target.hp = Math.max(0, target.hp - 10);
                    target.flashUntil = now + 150;
                    target.invincibleUntil = now + 300;
                    applyKnockback(target, m.x, m.y, 16.0f, map);
                }
            }
            return;
        }

        m.attackCooldown = 0;
        if (distance >= MONSTER_CHASE_RANGE) return;

        int startTileX = Math.floorDiv((int) (m.x + ENTITY_SIZE / 2f), Common.TILE_SIZE);
        int startTileY = Math.floorDiv((int) (m.y + ENTITY_SIZE / 2f), Common.TILE_SIZE);
        int goalTileX = Math.floorDiv((int) (target.x + ENTITY_SIZE / 2f), Common.TILE_SIZE);
        int goalTileY = Math.floorDiv((int) (target.y + ENTITY_SIZE / 2f), Common.TILE_SIZE);
        if (now >= m.nextPathAt) {
            m.currentPath = findPath(startTileX, startTileY, goalTileX, goalTileY, map);
            m.pathIndex = 0;
            m.nextPathAt = now + 400;
        }

        if (m.pathIndex >= m.currentPath.length) {
            // A player can be just outside attack range while sharing the same tile.
            if (startTileX == goalTileX && distance > MONSTER_ATTACK_RANGE) {
                moveMonsterToward(m, dx, dy, dt, map);
            }
            return;
        }

        int tileX = m.currentPath[m.pathIndex][0];
        int tileY = m.currentPath[m.pathIndex][1];
        float destX = tileX * Common.TILE_SIZE + (Common.TILE_SIZE - ENTITY_SIZE) / 2f;
        float destY = tileY * Common.TILE_SIZE + (Common.TILE_SIZE - ENTITY_SIZE) / 2f;
        float pathDx = destX - m.x;
        float pathDy = destY - m.y;
        float pathDistance = (float) Math.hypot(pathDx, pathDy);
        float step = MONSTER_SPEED * Math.min(Math.max(dt, 0), 0.1f) * 60.0f;

        if (pathDistance <= Math.max(step, 2.0f)) {
            m.x = destX;
            m.y = destY;
            m.pathIndex++;
        } else if (pathDistance > 0) {
            float move = Math.min(step, pathDistance);
            m.vx = pathDx / pathDistance * move;
            m.vy = pathDy / pathDistance * move;
            faceToward(m, m.vx, m.vy);
            moveMonsterByVelocity(m, map);
        }

        if (m.vx != 0 || m.vy != 0) {
            if (++m.animCounter >= 15) {
                m.spriteNum = m.spriteNum == 1 ? 2 : 1;
                m.animCounter = 0;
            }
        }
    }

    public static int[][] findPath(
            int startTileX, int startTileY, int goalTileX, int goalTileY, GameMap map) {
        int width = Common.MAX_CHUNK_X * Common.CHUNK_SIZE;
        int height = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE;
        if (!walkable(startTileX, startTileY, width, height, map)
                || !walkable(goalTileX, goalTileY, width, height, map)) {
            return new int[0][2];
        }
        if (startTileX == goalTileX && startTileY == goalTileY) return new int[0][2];

        int start = startTileY * width + startTileX;
        int goal = goalTileY * width + goalTileX;
        Map<Integer, Integer> cost = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        PriorityQueue<PathNode> open = new PriorityQueue<>();
        int startH = manhattan(startTileX, startTileY, goalTileX, goalTileY);
        cost.put(start, 0);
        open.add(new PathNode(startTileX, startTileY, 0, startH));

        int best = start;
        int bestH = startH;
        int expanded = 0;
        int[] offsetX = {1, -1, 0, 0};
        int[] offsetY = {0, 0, 1, -1};
        while (!open.isEmpty() && expanded < MAX_PATH_NODES) {
            PathNode currentNode = open.poll();
            int current = currentNode.y * width + currentNode.x;
            if (currentNode.g != cost.getOrDefault(current, Integer.MAX_VALUE)) continue;
            expanded++;

            int h = manhattan(currentNode.x, currentNode.y, goalTileX, goalTileY);
            if (h < bestH) {
                best = current;
                bestH = h;
            }
            if (current == goal) {
                best = goal;
                break;
            }

            for (int d = 0; d < offsetX.length; d++) {
                int nextX = currentNode.x + offsetX[d];
                int nextY = currentNode.y + offsetY[d];
                if (!walkable(nextX, nextY, width, height, map)) continue;
                int next = nextY * width + nextX;
                int nextCost = currentNode.g + 1;
                if (nextCost >= cost.getOrDefault(next, Integer.MAX_VALUE)) continue;
                cost.put(next, nextCost);
                parent.put(next, current);
                int nextH = manhattan(nextX, nextY, goalTileX, goalTileY);
                open.add(new PathNode(nextX, nextY, nextCost, nextCost + nextH));
            }
        }

        if (best == start) return new int[0][2];
        ArrayList<int[]> reversed = new ArrayList<>();
        for (int current = best; current != start; ) {
            reversed.add(new int[]{current % width, current / width});
            Integer previous = parent.get(current);
            if (previous == null) return new int[0][2];
            current = previous;
        }
        Collections.reverse(reversed);
        return reversed.toArray(new int[reversed.size()][]);
    }

    public static boolean hasLineOfSight(
            float fromX, float fromY, float toX, float toY, GameMap map) {
        float half = ENTITY_SIZE / 2f;
        float startX = fromX + half;
        float startY = fromY + half;
        float dx = toX + half - startX;
        float dy = toY + half - startY;
        int samples = Math.max(1, (int) (Math.hypot(dx, dy) / 8.0f));
        for (int i = 1; i < samples; i++) {
            int x = (int) (startX + dx * i / samples);
            int y = (int) (startY + dy * i / samples);
            if (map.isCollisionAtWorld(x, y)) return false;
        }
        return true;
    }

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

    public static void applyKnockback(
            GameData.MonsterState target, float sourceX, float sourceY, float force, GameMap map) {
        float[] push = knockbackVector(target.x, target.y, sourceX, sourceY, force);
        target.vx = push[0];
        target.vy = push[1];
        moveMonsterByVelocity(target, map);
        target.vx = 0;
        target.vy = 0;
        target.currentPath = new int[0][2];
        target.pathIndex = 0;
        target.nextPathAt = 0;
    }

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

    private static void moveMonsterToward(
            GameData.MonsterState m, float dx, float dy, float dt, GameMap map) {
        float distance = (float) Math.hypot(dx, dy);
        if (distance == 0) return;
        float step = Math.min(MONSTER_SPEED * Math.min(Math.max(dt, 0), 0.1f) * 60.0f, distance);
        m.vx = dx / distance * step;
        m.vy = dy / distance * step;
        faceToward(m, m.vx, m.vy);
        moveMonsterByVelocity(m, map);
    }

    private static void moveMonsterByVelocity(GameData.MonsterState m, GameMap map) {
        if (m.vx != 0) {
            m.x += m.vx;
            checkMonsterCollisionX(m, map);
        }
        if (m.vy != 0) {
            m.y += m.vy;
            checkMonsterCollisionY(m, map);
        }
    }

    private static void faceToward(GameData.MonsterState m, float dx, float dy) {
        if (Math.abs(dx) >= Math.abs(dy)) m.direction = dx < 0 ? "left" : "right";
        else m.direction = dy < 0 ? "up" : "down";
    }

    private static boolean walkable(int tileX, int tileY, int width, int height, GameMap map) {
        if (tileX < 0 || tileY < 0 || tileX >= width || tileY >= height) return false;
        int x = tileX * Common.TILE_SIZE;
        int y = tileY * Common.TILE_SIZE;
        int right = x + ENTITY_SIZE - 3;
        int bottom = y + ENTITY_SIZE - 3;
        return !map.isCollisionAtWorld(x + 3, y + 3)
                && !map.isCollisionAtWorld(right, y + 3)
                && !map.isCollisionAtWorld(x + 3, bottom)
                && !map.isCollisionAtWorld(right, bottom);
    }

    private static int manhattan(int x, int y, int goalX, int goalY) {
        return Math.abs(goalX - x) + Math.abs(goalY - y);
    }

    private static final class PathNode implements Comparable<PathNode> {
        final int x, y, g, f;

        PathNode(int x, int y, int g, int f) {
            this.x = x;
            this.y = y;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(PathNode other) {
            int byF = Integer.compare(f, other.f);
            return byF != 0 ? byF : Integer.compare(g, other.g);
        }
    }

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

    private static void checkMonsterCollisionX(GameData.MonsterState m, GameMap map) {
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

    private static void checkMonsterCollisionY(GameData.MonsterState m, GameMap map) {
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

    public static Rectangle getEntityBounds(float x, float y) {
        return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
    }

    public static Rectangle getAttackHitbox(float x, float y, String direction) {
        int range = 24;
        switch (direction) {
            case "up": return new Rectangle((int) x, (int) y - range, ENTITY_SIZE, range);
            case "down": return new Rectangle((int) x, (int) y + ENTITY_SIZE, ENTITY_SIZE, range);
            case "left": return new Rectangle((int) x - range, (int) y, range, ENTITY_SIZE);
            case "right": return new Rectangle((int) x + ENTITY_SIZE, (int) y, range, ENTITY_SIZE);
            default: return new Rectangle((int) x, (int) y, ENTITY_SIZE, ENTITY_SIZE);
        }
    }
}
