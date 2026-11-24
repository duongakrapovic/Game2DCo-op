package source.common;

import java.util.*;
import static source.common.GamePhysics.*;

/*
 * Chọn chuyển động và sát thương của slime, cùng A* tìm đường và LOS. ServerGame chọn mục tiêu gần nhất
 * rồi giao cho lớp này; không để client quyết định AI online.
 * Giới hạn chase 450 pixel, tầm đánh 42, cooldown 30 tick. PathfinderWorkspace chứa mảng cost/parent,
 * PriorityQueue open và pool PathNode tái dùng; PathNode so ưu tiên bằng f.
 */
public final class MonsterAI {
    /*
     * MAX_PATH_NODES: Giới hạn số node A* mở rộng trong một lần tìm.
     */
    private static final int MAX_PATH_NODES = 512;
    /*
     * MONSTER_ATTACK_RANGE: Khoảng cách quái có thể tấn công nếu thông tầm nhìn.
     */
    private static final int MONSTER_ATTACK_RANGE = 42;
    /*
     * MONSTER_CHASE_RANGE: Khoảng cách quái còn đuổi mục tiêu.
     */
    private static final int MONSTER_CHASE_RANGE = 450;
    /*
     * MONSTER_ATTACK_COOLDOWN_TICKS: Số tick quái tích lũy trước đòn kế tiếp.
     */
    private static final int MONSTER_ATTACK_COOLDOWN_TICKS = 30;
    /*
     * updateMonster()
     * Overload bước chuẩn, chuyển sang bản dt=1/60 giây. Caller đã quyết định quái đang sống và mục tiêu
     * phù hợp.
     */
    public static void updateMonster(GameData.MonsterState m, GameData.PlayerState target, GameMap map) {
        updateMonster(m, target, map, 1.0f / 60.0f);
    }

    /*
     * updateMonster()
     * Xử lý cooldown, hướng, khoảng cách và trạng thái target. Trong tầm/LOS có thể gây 10 sát thương nếu
     * player hết bất tử, flash và knockback; trong vùng đuổi thì tái lập đường khoảng 400 ms, bước theo
     * path hoặc hướng phù hợp, kiểm collision và animation. Sửa trực tiếp quái/player, không tự broadcast.
     * Chết hoặc không có target thì dừng. Gần trong 42 pixel và thông LOS: quay mặt, dừng path, tích
     * cooldown 30 tick, rồi gây 10 damage nếu target chưa miễn thương; thêm invincible 300 ms và
     * knockback. Xa từ 450 pixel trở lên thì không đuổi.
     * Khi còn trong vùng đuổi, đổi tọa độ tâm entity sang ô lưới; khoảng mỗi 400 ms mới tìm path lại. Đi
     * từng node bằng tốc độ có chặn dt; cập nhật animation khi chuyển động. Mục tiêu gần nhất do caller
     * chọn, không phải hàm này tự tìm mọi player.
     */
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
            if (startTileX == goalTileX && distance > MONSTER_ATTACK_RANGE
                    && hasLineOfSight(m.x, m.y, target.x, target.y, map)) {
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

    /*
     * findPath()
     * Bản tiện ích tạo PathfinderWorkspace mới cho mỗi lần gọi rồi giao bản đầy đủ. Dùng thuận tiện trong
     * test; mô phỏng lặp nên tái dùng workspace để giảm cấp phát.
     */
    public static int[][] findPath(int sx, int sy, int gx, int gy, GameMap map) {
        return findPath(sx, sy, gx, gy, map, new PathfinderWorkspace());
    }

    /*
     * findPath()
     * Nhận tile đầu/đích, map và workspace; từ chối điểm không đi được hoặc đầu trùng đích bằng mảng rỗng.
     * A* bốn hướng ưu tiên g+Manhattan, tối đa 512 node mở rộng; lưu parent rồi truy ngược thành int[][2].
     * Nếu chưa tới đích có thể trả đường tới điểm gần đích tốt nhất, không cam kết tìm tới đích trong mọi
     * lần.
     * A* dùng g là số bước đã đi, h Manhattan và f=g+h. Mở rộng bốn hướng, không đi chéo; giới hạn 512
     * node. Nếu chưa tới đích nhưng có node tiến gần hơn thì có thể trả một đoạn path; không tìm được tiến
     * bộ trả mảng rỗng. Workspace tái sử dụng cost/parent/queue và pool node, không tạo lại toàn bộ cho
     * mỗi tick.
     */
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
        /*
         * A* mở rộng bốn hướng với trần số node; workspace được tái sử dụng để giảm cấp phát.
         */
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
        /*
         * Lần theo parent để dựng đường; hết ngân sách có thể trả đoạn tiến gần hơn chứ chưa tới đích.
         */
        int[][] path = new int[count][2];
        for (int at = best, i = count - 1; i >= 0; i--, at = work.parent[at]) {
            path[i][0] = at % width; path[i][1] = at / width;
        }
        return path;
    }

    /** Per-monster arrays, queue and node objects are reused between searches. */
    /*
     * Workspace A* được tái sử dụng: cost/parent, priority queue và pool node.
     */
    public static final class PathfinderWorkspace {
        /*
         * cost: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        final int[] cost = new int[Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.MAX_CHUNK_Y * Common.CHUNK_SIZE];
        /*
         * parent: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        final int[] parent = new int[cost.length];
        /*
         * open: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        final PriorityQueue<PathNode> open = new PriorityQueue<>();
        /*
         * pool: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        final ArrayList<PathNode> pool = new ArrayList<>();
        /*
         * used: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        int used;
        /*
         * reset()
         * Xóa queue, đặt used=0, cost về Integer.MAX_VALUE và parent về -1. Pool node được giữ để tái sử
         * dụng trong lần tìm đường tiếp.
         */
        void reset() { open.clear(); used = 0; Arrays.fill(cost, Integer.MAX_VALUE); Arrays.fill(parent, -1); }
        /*
         * offer()
         * Lấy node từ pool hoặc tạo thêm nếu thiếu, điền x/y/g/f rồi đưa vào PriorityQueue. Giảm tạo
         * object trong mỗi lần A*.
         */
        void offer(int x, int y, int g, int f) {
            if (used == pool.size()) pool.add(new PathNode(x, y, g, f));
            PathNode node = pool.get(used++);
            node.x = x; node.y = y; node.g = g; node.f = f;
            open.add(node);
        }
    }

    /*
     * hasLineOfSight()
     * Lấy mẫu các điểm trên đoạn từ nguồn tới đích, khoảng cách mẫu xấp xỉ tám pixel; gặp tile chặn thì
     * false. Dùng cho cả đánh quái, đánh player và nhặt bình; không phải raycast hình học liên tục hoàn
     * hảo.
     */
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

    /*
     * moveMonsterToward()
     * Từ vector dx/dy tính vận tốc có chuẩn hóa và theo dt, chọn hướng, rồi gọi bước di chuyển có va chạm.
     * Tránh tăng tốc đường chéo.
     */
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

    /*
     * moveMonsterByVelocity()
     * Cộng x rồi gọi GamePhysics.moveMonsterX, tiếp cộng y rồi moveMonsterY. Các wrapper của physics chỉ
     * sửa collision sau cộng tọa độ.
     */
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

    /*
     * faceToward()
     * Chọn chuỗi hướng theo thành phần chiếm ưu thế của dx/dy để sprite nhìn về mục tiêu.
     */
    private static void faceToward(GameData.MonsterState m, float dx, float dy) {
        if (Math.abs(dx) >= Math.abs(dy)) m.direction = dx < 0 ? "left" : "right";
        else m.direction = dy < 0 ? "up" : "down";
    }

    /*
     * walkable()
     * Kiểm tile trong grid width/height, đổi sang pixel và thử bốn điểm hitbox lùi vào mép ba pixel qua
     * isCollisionAtWorld. Không gọi trực tiếp map.isWalkable và không kiểm đúng toàn bộ mép ngoài như hàm
     * đó; đây là phép thử node A* hiện tại.
     */
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

    /*
     * manhattan()
     * Trả |x−goalX| + |y−goalY|, heuristic cho các bước ngang/dọc chi phí bằng nhau.
     */
    private static int manhattan(int x, int y, int goalX, int goalY) {
        return Math.abs(goalX - x) + Math.abs(goalY - y);
    }

    /*
     * Node A*; ưu tiên theo tổng chi phí f rồi tới g.
     */
    private static final class PathNode implements Comparable<PathNode> {
        /*
         * x, y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         * g, f: Mảng chi phí, node cha, priority queue và pool của A*; g là giá đã đi, f là g cộng
         * heuristic.
         */
        int x, y, g, f;

        /*
         * Khởi tạo PathNode
         * Gán tọa độ và chi phí g/f ban đầu cho một node; workspace có thể cập nhật và tái dùng nó về sau.
         */
        PathNode(int x, int y, int g, int f) {
            this.x = x;
            this.y = y;
            this.g = g;
            this.f = f;
        }

        /*
         * compareTo()
         * So f trước; nếu bằng nhau thì so g bằng Integer.compare. PriorityQueue ưu tiên tổng chi phí ước
         * lượng nhỏ, dùng g làm tiêu chí phụ.
         */
        @Override
        public int compareTo(PathNode other) {
            int byF = Integer.compare(f, other.f);
            return byF != 0 ? byF : Integer.compare(g, other.g);
        }
    }


}
