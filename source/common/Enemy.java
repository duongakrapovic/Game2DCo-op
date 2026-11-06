package source.common;

/** Server/offline simulated enemy that follows the nearest player with A*. */
public class Enemy {
    public static final int MAX_HEALTH = 100;
    public static final long ATTACK_COOLDOWN_MS = 1200;
    public static final long HIT_FLASH_MS = 150;
    private static final int MAX_PATH_NODES = 512;

    public final String id;
    public final String name;
    public final float speed;
    public final float spawnX;
    public final float spawnY;
    public float x;
    public float y;
    public int health = MAX_HEALTH;
    public String direction = "down";
    public long flashUntil;

    private long nextPathAt;
    private int[][] path = new int[0][2];
    private int pathIndex;
    private long nextAttackAt;
    private long respawnAt;

    public Enemy(String id, String name, float x, float y, float speed) {
        this.id = id;
        this.name = name;
        this.spawnX = x;
        this.spawnY = y;
        this.x = x;
        this.y = y;
        this.speed = speed;
    }

    public void update(Player target, MapManager map, float dt) {
        long now = System.currentTimeMillis();
        if (health == 0) {
            if (now >= respawnAt) {
                health = MAX_HEALTH;
                x = spawnX;
                y = spawnY;
                nextPathAt = 0;
            } else {
                return;
            }
        }
        if (target == null || !target.isAlive()) return;
        float attackDx = target.x - x;
        float attackDy = target.y - y;
        if (attackDx * attackDx + attackDy * attackDy <= 48f * 48f
                && hasLineOfSight(target, map)) {
            if (Math.abs(attackDx) > Math.abs(attackDy)) {
                direction = attackDx < 0 ? "left" : "right";
            } else {
                direction = attackDy < 0 ? "up" : "down";
            }
            if (now >= nextAttackAt) {
                target.takeDamage("E_MONSTER".equals(id) ? 15 : 10, x, y, map, now);
                nextAttackAt = now + ATTACK_COOLDOWN_MS;
            }
            // Hold position and face the player while attacking. Repath immediately
            // if the player leaves attack range on the next update.
            nextPathAt = now;
            return;
        }
        int goalX = ((int) (target.x + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int goalY = ((int) (target.y + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int startX = ((int) (x + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int startY = ((int) (y + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        if (now >= nextPathAt) {
            path = findPath(startX, startY, goalX, goalY, map);
            pathIndex = 0;
            nextPathAt = now + 400;
        }
        if (pathIndex >= path.length) return;

        int destX = path[pathIndex][0] * Common.TILE_SIZE + (Common.TILE_SIZE - hitboxSize()) / 2;
        int destY = path[pathIndex][1] * Common.TILE_SIZE + (Common.TILE_SIZE - hitboxSize()) / 2;
        float dx = destX - x;
        float dy = destY - y;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float step = speed * Math.min(Math.max(dt, 0), 0.1f);
        if (distance <= Math.max(step, 2f)) {
            x = destX;
            y = destY;
            pathIndex++;
        } else if (distance > 0) {
            float moveX = dx / distance * step;
            float moveY = dy / distance * step;
            if (Math.abs(dx) >= Math.abs(dy)) direction = dx < 0 ? "left" : "right";
            else direction = dy < 0 ? "up" : "down";
            moveWithCollision(moveX, moveY, map);
        }
    }

    public boolean takeDamage(int damage, float sourceX, float sourceY, MapManager map, long now) {
        if (health <= 0) return false;
        health = Math.max(0, health - Math.max(0, damage));
        flashUntil = now + HIT_FLASH_MS;
        applyKnockback(sourceX, sourceY, map);
        if (health == 0) {
            respawnAt = now + 2000;
            path = new int[0][2];
            pathIndex = 0;
        }
        return true;
    }

    private void applyKnockback(float sourceX, float sourceY, MapManager map) {
        float dx = x - sourceX;
        float dy = y - sourceY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            dx = 1f;
            length = 1f;
        }
        moveWithCollision(dx / length * 12f, dy / length * 12f, map);
    }

    private void moveWithCollision(float dx, float dy, MapManager map) {
        int size = hitboxSize();
        float nextX = x + dx;
        if (!isBlocked(nextX, y, size, map)) x = nextX;
        float nextY = y + dy;
        if (!isBlocked(x, nextY, size, map)) y = nextY;
    }

    private boolean isBlocked(float candidateX, float candidateY, int size, MapManager map) {
        int left = (int) candidateX + 3;
        int right = (int) candidateX + size - 3;
        int top = (int) candidateY + 3;
        int bottom = (int) candidateY + size - 3;
        return map.isCollisionAtWorld(left, top) || map.isCollisionAtWorld(right, top)
                || map.isCollisionAtWorld(left, bottom) || map.isCollisionAtWorld(right, bottom);
    }

    private boolean hasLineOfSight(Player target, MapManager map) {
        float fromX = x + hitboxSize() / 2f;
        float fromY = y + hitboxSize() / 2f;
        float toX = target.x + hitboxSize() / 2f;
        float toY = target.y + hitboxSize() / 2f;
        float dx = toX - fromX;
        float dy = toY - fromY;
        int samples = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / 8f));
        for (int i = 1; i < samples; i++) {
            int sampleX = (int) (fromX + dx * i / samples);
            int sampleY = (int) (fromY + dy * i / samples);
            if (map.isCollisionAtWorld(sampleX, sampleY)) return false;
        }
        return true;
    }

    private int hitboxSize() { return (int) (16 * Common.SCALE); }

    private static int[][] findPath(int sx, int sy, int gx, int gy, MapManager map) {
        if (sx == gx && sy == gy) return new int[0][2];
        final int width = Common.MAX_CHUNK_X * Common.CHUNK_SIZE;
        final int height = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE;
        if (!walkable(sx, sy, width, height, map) || !walkable(gx, gy, width, height, map)) return new int[0][2];
        int start = sy * width + sx;
        int goal = gy * width + gx;
        java.util.Map<Integer, Integer> cost = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> parent = new java.util.HashMap<>();
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>();
        int startH = heuristic(sx, sy, gx, gy);
        cost.put(start, 0);
        open.add(new Node(sx, sy, 0, startH));
        int[] ox = {1, -1, 0, 0};
        int[] oy = {0, 0, 1, -1};
        int best = start;
        int bestH = startH;
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_PATH_NODES) {
            Node n = open.poll();
            int current = n.y * width + n.x;
            if (n.g != cost.getOrDefault(current, Integer.MAX_VALUE)) continue;
            expanded++;

            int h = heuristic(n.x, n.y, gx, gy);
            if (h < bestH) {
                best = current;
                bestH = h;
            }
            if (current == goal) {
                best = goal;
                break;
            }

            for (int d = 0; d < 4; d++) {
                int nx = n.x + ox[d], ny = n.y + oy[d];
                if (!walkable(nx, ny, width, height, map)) continue;
                int next = ny * width + nx;
                int candidateCost = n.g + 1;
                if (candidateCost >= cost.getOrDefault(next, Integer.MAX_VALUE)) continue;
                cost.put(next, candidateCost);
                parent.put(next, current);
                int nextH = heuristic(nx, ny, gx, gy);
                open.add(new Node(nx, ny, candidateCost, candidateCost + nextH));
            }
        }

        if (best == start) return new int[0][2];
        java.util.ArrayList<int[]> reversed = new java.util.ArrayList<>();
        int current = best;
        while (current != start) {
            reversed.add(new int[]{current % width, current / width});
            Integer previous = parent.get(current);
            if (previous == null) return new int[0][2];
            current = previous;
        }
        java.util.Collections.reverse(reversed);
        return reversed.toArray(new int[reversed.size()][]);
    }

    private static int heuristic(int x, int y, int goalX, int goalY) {
        return Math.abs(goalX - x) + Math.abs(goalY - y);
    }

    private static boolean walkable(int x, int y, int width, int height, MapManager map) {
        return x >= 0 && y >= 0 && x < width && y < height
                && !map.isCollisionAtWorld(x * Common.TILE_SIZE + Common.TILE_SIZE / 2,
                                           y * Common.TILE_SIZE + Common.TILE_SIZE / 2);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, g, f;
        Node(int x, int y, int g, int f) { this.x = x; this.y = y; this.g = g; this.f = f; }
        @Override public int compareTo(Node other) {
            int byCost = Integer.compare(f, other.f);
            if (byCost != 0) return byCost;
            return Integer.compare(g, other.g);
        }
    }
}
