package source.common;

import java.awt.Point;
import java.util.*;
import java.util.function.Supplier;

/** Potion rules ported from tranvanducb23dccn191; simulation owns all mutations. */
public final class ItemSystem {
    public static final int MAX_ITEMS = 60;
    public static final int DROP_DESPAWN_TICKS = 600;
    public static final int POTION_RESPAWN_TICKS = 900;
    private final Map<String, GameData.ItemState> items = new LinkedHashMap<>();
    private final GameMap map;
    private final Random random;
    private long nextId;

    public ItemSystem(GameMap map, Random random) { this.map = map; this.random = random; }
    public Collection<GameData.ItemState> items() { return Collections.unmodifiableCollection(items.values()); }

    public void initialize(Supplier<Point> spawn) {
        items.clear(); nextId = 0;
        for (int i = 0; i < 8; i++) {
            Point point = spawn.get();
            if (point == null || !valid(point.x, point.y)) continue;
            String id = "POTION_" + ++nextId;
            items.put(id, new GameData.ItemState(id, "HEAL", point.x, point.y));
        }
    }

    public void onMonsterKilled(GameData.MonsterState monster) {
        if (!random.nextBoolean() || !valid(monster.x, monster.y)) return;
        if (items.size() >= MAX_ITEMS) {
            String oldest = items.keySet().stream().filter(id -> id.startsWith("DROP_")).findFirst().orElse(null);
            if (oldest == null) return;
            items.remove(oldest);
        }
        String id = "DROP_" + ++nextId;
        GameData.ItemState item = new GameData.ItemState(id, "HEAL", monster.x, monster.y);
        item.respawnTimer = DROP_DESPAWN_TICKS;
        items.put(id, item);
    }

    private boolean valid(float x, float y) {
        return map.isWalkable(x, y) && map.isSpawnReachableAtWorld(
                (int) (x + GamePhysics.ENTITY_SIZE / 2f), (int) (y + GamePhysics.ENTITY_SIZE / 2f));
    }

    public void update(Iterable<GameData.PlayerState> players, Supplier<Point> spawn) {
        Iterator<GameData.ItemState> iterator = items.values().iterator();
        while (iterator.hasNext()) {
            GameData.ItemState item = iterator.next();
            boolean permanent = item.id.startsWith("POTION_");
            if (item.collected) {
                if (!permanent) { iterator.remove(); continue; }
                if (--item.respawnTimer <= 0) {
                    Point point = spawn.get();
                    if (point != null && valid(point.x, point.y)) {
                        item.x = point.x; item.y = point.y; item.collected = false;
                    } else item.respawnTimer = 60;
                }
                continue;
            }
            if (!permanent && --item.respawnTimer <= 0) { iterator.remove(); continue; }
            for (GameData.PlayerState player : players) {
                if (player.hp <= 0 || player.hp >= player.maxHp) continue;
                if (!GamePhysics.getEntityBounds(player.x, player.y).intersects(GamePhysics.getEntityBounds(item.x, item.y))
                        || !MonsterAI.hasLineOfSight(player.x, player.y, item.x, item.y, map)) continue;
                player.hp = Math.min(player.maxHp, player.hp + item.healAmount);
                if (permanent) { item.collected = true; item.respawnTimer = POTION_RESPAWN_TICKS; }
                else iterator.remove();
                break; // A potion can heal only one player, even when several overlap.
            }
        }
    }
}
