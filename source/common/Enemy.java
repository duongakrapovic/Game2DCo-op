package source.common;

/** Server/offline simulated enemy that follows the nearest player with A*. */
public class Enemy {
    public final String id;
    public final String name;
    public final float speed;
    public float x;
    public float y;
    public String direction = "down";

    private long nextPathAt;
    private int[][] path = new int[0][2];
    private int pathIndex;
    private long nextAttackAt;

    public Enemy(String id, String name, float x, float y, float speed) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.speed = speed;
    }

    public void update(Player target, MapManager map, float dt) {
        if (target == null) return;
        float attackDx = target.x - x;
        float attackDy = target.y - y;
        if (attackDx * attackDx + attackDy * attackDy <= 40f * 40f
                && hasLineOfSight(target, map)) {
            long now = System.currentTimeMillis();
            if (now >= nextAttackAt) {
                target.health = Math.max(0, target.health - ("E_MONSTER".equals(id) ? 15 : 10));
                nextAttackAt = now + 1000;
                if (target.health == 0) target.respawnToHome();
            }
        }
        int goalX = ((int) (target.x + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int goalY = ((int) (target.y + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int startX = ((int) (x + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        int startY = ((int) (y + Common.TILE_SIZE / 2)) / Common.TILE_SIZE;
        long now = System.currentTimeMillis();
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
        int[][] cost = new int[width][height];
        int[][] parentX = new int[width][height];
        int[][] parentY = new int[width][height];
        for (int xx = 0; xx < width; xx++) {
            java.util.Arrays.fill(cost[xx], Integer.MAX_VALUE);
            java.util.Arrays.fill(parentX[xx], -1);
        }
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>();
        cost[sx][sy] = 0;
        open.add(new Node(sx, sy, 0, Math.abs(gx - sx) + Math.abs(gy - sy)));
        int[] ox = {1, -1, 0, 0};
        int[] oy = {0, 0, 1, -1};
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.g != cost[n.x][n.y]) continue;
            if (n.x == gx && n.y == gy) break;
            for (int d = 0; d < 4; d++) {
                int nx = n.x + ox[d], ny = n.y + oy[d];
                if (!walkable(nx, ny, width, height, map) || cost[nx][ny] <= n.g + 1) continue;
                cost[nx][ny] = n.g + 1;
                parentX[nx][ny] = n.x;
                parentY[nx][ny] = n.y;
                int h = Math.abs(gx - nx) + Math.abs(gy - ny);
                open.add(new Node(nx, ny, n.g + 1, n.g + 1 + h));
            }
        }
        if (cost[gx][gy] == Integer.MAX_VALUE) return new int[0][2];
        java.util.ArrayList<int[]> reversed = new java.util.ArrayList<>();
        int x = gx, y = gy;
        while (x != sx || y != sy) {
            reversed.add(new int[]{x, y});
            int px = parentX[x][y], py = parentY[x][y];
            if (px < 0) return new int[0][2];
            x = px; y = py;
        }
        java.util.Collections.reverse(reversed);
        return reversed.toArray(new int[reversed.size()][]);
    }

    private static boolean walkable(int x, int y, int width, int height, MapManager map) {
        return x >= 0 && y >= 0 && x < width && y < height
                && !map.isCollisionAtWorld(x * Common.TILE_SIZE + Common.TILE_SIZE / 2,
                                           y * Common.TILE_SIZE + Common.TILE_SIZE / 2);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, g, f;
        Node(int x, int y, int g, int f) { this.x = x; this.y = y; this.g = g; this.f = f; }
        @Override public int compareTo(Node other) { return Integer.compare(f, other.f); }
    }
}
