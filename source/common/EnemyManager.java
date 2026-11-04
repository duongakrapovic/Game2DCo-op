package source.common;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Creates enemy spawns and updates all enemies in one game world. */
public class EnemyManager {
    private final Map<String, Enemy> enemies = new ConcurrentHashMap<>();

    public EnemyManager() {
        int tile = Common.TILE_SIZE;
        enemies.put("E_SOLDIER", new Enemy("E_SOLDIER", "Linh", 26 * tile, 18 * tile, 85f));
        enemies.put("E_MONSTER", new Enemy("E_MONSTER", "Quai", 18 * tile, 24 * tile, 115f));
    }

    public void update(Collection<Player> players, MapManager map, float dt) {
        for (Enemy enemy : enemies.values()) {
            Player target = nearestPlayer(enemy, players);
            enemy.update(target, map, dt);
        }
    }

    public Collection<Enemy> getEnemies() {
        return enemies.values();
    }

    private Player nearestPlayer(Enemy enemy, Collection<Player> players) {
        Player nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Player player : players) {
            float dx = player.x - enemy.x;
            float dy = player.y - enemy.y;
            float distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
