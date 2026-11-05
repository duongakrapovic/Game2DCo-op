package source.common;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Creates enemy spawns and updates all enemies in one game world. */
public class EnemyManager {
    private final Map<String, Enemy> enemies = new ConcurrentHashMap<>();
    private final List<DamageEvent> damageEvents = new ArrayList<>();

    public EnemyManager() {
        int tile = Common.TILE_SIZE;
        enemies.put("E_SOLDIER", new Enemy("E_SOLDIER", "Linh", 26 * tile, 18 * tile, 85f));
        enemies.put("E_MONSTER", new Enemy("E_MONSTER", "Quai", 18 * tile, 24 * tile, 115f));
    }

    public void update(Collection<Player> players, MapManager map, float dt) {
        damageEvents.clear();
        long now = System.currentTimeMillis();
        for (Player player : players) player.updateLife(now);

        for (Enemy enemy : enemies.values()) {
            Player target = nearestPlayer(enemy, players);
            int previousHealth = target == null ? 0 : target.health;
            enemy.update(target, map, dt);
            if (target != null && target.health < previousHealth) {
                damageEvents.add(new DamageEvent("PLAYER_DAMAGE", target.id,
                        previousHealth - target.health, target.health));
            }
        }

        for (Player player : players) {
            if (!player.isAlive() || !player.attack || now < player.nextAttackAt) continue;
            Enemy target = enemyInFront(player, map);
            if (target == null) continue;
            int previousHealth = target.health;
            if (target.takeDamage(25, player.x, player.y, map, now)) {
                player.nextAttackAt = now + 450;
                damageEvents.add(new DamageEvent("ENEMY_DAMAGE", target.id,
                        previousHealth - target.health, target.health));
            }
        }
    }

    public Collection<Enemy> getEnemies() {
        return enemies.values();
    }

    public List<DamageEvent> drainDamageEvents() {
        List<DamageEvent> result = new ArrayList<>(damageEvents);
        damageEvents.clear();
        return result;
    }

    private Player nearestPlayer(Enemy enemy, Collection<Player> players) {
        Player nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Player player : players) {
            if (!player.isAlive()) continue;
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

    private Enemy enemyInFront(Player player, MapManager map) {
        Enemy nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Enemy enemy : enemies.values()) {
            if (enemy.health <= 0) continue;
            float dx = enemy.x - player.x;
            float dy = enemy.y - player.y;
            float forward;
            float side;
            switch (player.direction) {
                case "up":    forward = -dy; side = Math.abs(dx); break;
                case "down":  forward = dy;  side = Math.abs(dx); break;
                case "left":  forward = -dx; side = Math.abs(dy); break;
                default:      forward = dx;  side = Math.abs(dy); break;
            }
            float distance = dx * dx + dy * dy;
            if (forward < 0 || forward > 56 || side > 30 || distance >= nearestDistance
                    || !hasLineOfSight(player, enemy, map)) continue;
            nearest = enemy;
            nearestDistance = distance;
        }
        return nearest;
    }

    private boolean hasLineOfSight(Player player, Enemy enemy, MapManager map) {
        float fromX = player.x + Common.TILE_SIZE / 2f;
        float fromY = player.y + Common.TILE_SIZE / 2f;
        float toX = enemy.x + Common.TILE_SIZE / 2f;
        float toY = enemy.y + Common.TILE_SIZE / 2f;
        float dx = toX - fromX;
        float dy = toY - fromY;
        int samples = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / 8f));
        for (int i = 1; i < samples; i++) {
            if (map.isCollisionAtWorld((int) (fromX + dx * i / samples),
                    (int) (fromY + dy * i / samples))) return false;
        }
        return true;
    }

    public static final class DamageEvent {
        public final String command;
        public final String targetId;
        public final int damage;
        public final int health;

        private DamageEvent(String command, String targetId, int damage, int health) {
            this.command = command;
            this.targetId = targetId;
            this.damage = damage;
            this.health = health;
        }

        public String toProtocolLine() {
            return command + ":" + targetId + ":" + damage + ":" + health;
        }
    }
}
