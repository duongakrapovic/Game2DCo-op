package source.common;

import java.util.*;
import static source.common.GamePhysics.*;

public final class MonsterAI {
    private static final int MAX_PATH_NODES = 512;
    private static final int MONSTER_ATTACK_RANGE = 50;
    private static final int MONSTER_CHASE_RANGE = 450;
    private static final int MONSTER_ATTACK_COOLDOWN_TICKS = 30;
    public static void updateMonster(GameData.MonsterState m, GameData.PlayerState target, GameMap map) {
        updateMonster(m, target, map, 1.0f / 60.0f);
    }

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
            if (m.pathfinderWorkspace == null) m.pathfinderWorkspace = new PathfinderWorkspace();
            m.currentPath = findPath(startTileX, startTileY, goalTileX, goalTileY, map, m.pathfinderWorkspace);
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

    public static int[][] findPath(int sx, int sy, int gx, int gy, GameMap map) {
        return findPath(sx, sy, gx, gy, map, new PathfinderWorkspace());
    }

    public static int[][] findPath(int sx, int sy, int gx, int gy, GameMap map, PathfinderWorkspace work) {
        int width = Common.MAX_CHUNK_X * Common.CHUNK_SIZE;
        int height = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE;
        if (!walkable(sx, sy, width, height, map) || !walkable(gx, gy, width, height, map)
                || (sx == gx && sy == gy)) return new int[0][2];
        work.reset();
        int start = sy * width + sx, goal = gy * width + gx;
        int best = start, bestH = manhattan(sx, sy, gx, gy), expanded = 0;
        work.cost[start] = 0;
        work.offer(sx, sy, 0, bestH);
        while (!work.open.isEmpty() && expanded < MAX_PATH_NODES) {
            PathNode node = work.open.poll();
            int current = node.y * width + node.x;
            if (node.g != work.cost[current]) continue;
            expanded++;
            int h = manhattan(node.x, node.y, gx, gy);
            if (h < bestH) { best = current; bestH = h; }
            if (current == goal) { best = goal; break; }
            for (int d = 0; d < 4; d++) {
                int x = node.x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int y = node.y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (!walkable(x, y, width, height, map)) continue;
                int next = y * width + x, cost = node.g + 1;
                if (cost >= work.cost[next]) continue;
                work.cost[next] = cost;
                work.parent[next] = current;
                work.offer(x, y, cost, cost + manhattan(x, y, gx, gy));
            }
        }
        if (best == start) return new int[0][2];
        int count = 0;
        for (int at = best; at != start; at = work.parent[at]) {
            if (at < 0 || ++count > work.parent.length) return new int[0][2];
        }
        int[][] path = new int[count][2];
        for (int at = best, i = count - 1; i >= 0; i--, at = work.parent[at]) {
            path[i][0] = at % width; path[i][1] = at / width;
        }
        return path;
    }

    /** Per-monster arrays, queue and node objects are reused between searches. */
    public static final class PathfinderWorkspace {
        final int[] cost = new int[Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.MAX_CHUNK_Y * Common.CHUNK_SIZE];
        final int[] parent = new int[cost.length];
        final PriorityQueue<PathNode> open = new PriorityQueue<>();
        final ArrayList<PathNode> pool = new ArrayList<>();
        int used;
        void reset() { open.clear(); used = 0; Arrays.fill(cost, Integer.MAX_VALUE); Arrays.fill(parent, -1); }
        void offer(int x, int y, int g, int f) {
            if (used == pool.size()) pool.add(new PathNode(x, y, g, f));
            PathNode node = pool.get(used++);
            node.x = x; node.y = y; node.g = g; node.f = f;
            open.add(node);
        }
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
            GamePhysics.moveMonsterX(m, map);
        }
        if (m.vy != 0) {
            m.y += m.vy;
            GamePhysics.moveMonsterY(m, map);
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
        int x, y, g, f;

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


}
