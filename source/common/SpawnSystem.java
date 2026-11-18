package source.common;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Random;

/** Shared safe spawn search; exhaustion returns null, never an unchecked fallback. */
public final class SpawnSystem {
    private SpawnSystem() {}
    public static Point find(GameMap map, Random random, Iterable<GameData.MonsterState> monsters,
            Iterable<GameData.PlayerState> players) {
        int maxX = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        int maxY = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        for (int attempt = 0; attempt < 2000; attempt++) {
            int x = random.nextInt(maxX + 1), y = random.nextInt(maxY + 1);
            if (valid(map, x, y, monsters, players)) return new Point(x, y);
        }
        for (int y = 0; y <= maxY; y += Common.TILE_SIZE) {
            for (int x = 0; x <= maxX; x += Common.TILE_SIZE) {
                if (valid(map, x, y, monsters, players)) return new Point(x, y);
            }
        }
        return null;
    }
    private static boolean valid(GameMap map, int x, int y, Iterable<GameData.MonsterState> monsters,
            Iterable<GameData.PlayerState> players) {
        int half = GamePhysics.ENTITY_SIZE / 2;
        if (!map.isWalkable(x, y) || !map.isSpawnReachableAtWorld(x + half, y + half)
                || Math.hypot(x - Common.SPAWN_X, y - Common.SPAWN_Y) < GamePhysics.ENTITY_SIZE * 3) return false;
        Rectangle bounds = GamePhysics.getEntityBounds(x, y);
        for (GameData.MonsterState monster : monsters) {
            if (!monster.isDead && bounds.intersects(GamePhysics.getEntityBounds(monster.x, monster.y))) return false;
        }
        for (GameData.PlayerState player : players) {
            if (bounds.intersects(GamePhysics.getEntityBounds(player.x, player.y))) return false;
        }
        return true;
    }
}
