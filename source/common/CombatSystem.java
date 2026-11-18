package source.common;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** Combat shared by the room simulation and the offline simulation. */
public final class CombatSystem {
    private CombatSystem() {}

    public static void resolveAttack(GameData.PlayerState player,
            Iterable<GameData.MonsterState> monsters, GameMap map,
            IntSupplier respawnTicks, MonsterHistory history, long tick) {
        player.isAttacking = true;
        player.attackTicks = 0;
        Rectangle attack = GamePhysics.getAttackHitbox(player.x, player.y, player.direction);
        int rewindTicks = Math.min(12, Math.max(0, player.pingMs / 16));
        long now = System.currentTimeMillis();
        for (GameData.MonsterState monster : monsters) {
            if (monster.isDead) continue;
            Rectangle bounds = history == null ? GamePhysics.getEntityBounds(monster.x, monster.y)
                    : history.boundsAt(monster, tick - rewindTicks);
            if (!attack.intersects(bounds)) continue;
            monster.hp = Math.max(0, monster.hp - 15);
            monster.flashUntil = now + 150;
            // Only the collision query is rewound; damage and knockback use live state.
            GamePhysics.applyKnockback(monster, player.x, player.y, 16f, map);
            if (monster.hp == 0) {
                monster.isDead = true;
                monster.respawnTimer = respawnTicks.getAsInt();
                monster.currentPath = new int[0][2];
                monster.pathIndex = 0;
                if (history != null) history.clear(monster.id);
            }
        }
    }

    /** Thirteen samples include the current tick and twelve historical ticks. */
    public static final class MonsterHistory {
        private static final int CAPACITY = 13;
        private static final class Samples {
            final long[] ticks = new long[CAPACITY];
            final float[] x = new float[CAPACITY], y = new float[CAPACITY];
            Samples() { java.util.Arrays.fill(ticks, Long.MIN_VALUE); }
        }
        private final Map<String, Samples> samples = new HashMap<>();
        public void record(Iterable<GameData.MonsterState> monsters, long tick) {
            int index = (int) (tick % CAPACITY);
            for (GameData.MonsterState monster : monsters) {
                if (monster.isDead) continue;
                Samples s = samples.computeIfAbsent(monster.id, ignored -> new Samples());
                s.ticks[index] = tick; s.x[index] = monster.x; s.y[index] = monster.y;
            }
        }
        public void clear(String id) { samples.remove(id); }
        public Rectangle boundsAt(GameData.MonsterState monster, long tick) {
            Samples s = samples.get(monster.id);
            if (s != null && tick >= 0) {
                int index = (int) (tick % CAPACITY);
                if (s.ticks[index] == tick) return GamePhysics.getEntityBounds(s.x[index], s.y[index]);
            }
            return GamePhysics.getEntityBounds(monster.x, monster.y);
        }
    }
}
