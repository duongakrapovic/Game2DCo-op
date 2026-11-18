package source.common;

import java.awt.Point;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies manual ports from tranvanducb23dccn191 without game assets or save files. */
public final class PortedGameplayRegressionChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static class OpenMap extends GameMap {
        @Override public boolean isCollisionAtWorld(int x, int y) { return x < 0 || y < 0; }
    }
    private static final Random ALWAYS_DROP = new Random(7) {
        @Override public boolean nextBoolean() { return true; }
    };
    private static GameData.PlayerState player(String id, int hp) {
        GameData.PlayerState player = new GameData.PlayerState(id, id);
        player.x = player.y = 100; player.hp = hp;
        return player;
    }

    private static void potions() {
        GameMap map = new OpenMap();
        ItemSystem items = new ItemSystem(map, ALWAYS_DROP);
        AtomicInteger spawned = new AtomicInteger();
        items.initialize(() -> spawned.getAndIncrement() == 0 ? new Point(100, 100) : null);
        check(items.items().size() == 1, "Failed initial spawn is skipped");
        GameData.PlayerState full = player("full", 100), hurt = player("hurt", 10), other = player("other", 10);
        items.update(List.of(full), () -> null);
        check(!items.items().iterator().next().collected, "Full-health player leaves potion");
        items.update(List.of(hurt, other), () -> null);
        check(hurt.hp == 40 && other.hp == 10, "One potion heals one player by 30");
        GameData.ItemState potion = items.items().iterator().next();
        check(potion.collected && potion.respawnTimer == 900, "Map potion starts its respawn timer");
        for (int i = 0; i < 900; i++) items.update(List.of(), () -> null);
        check(potion.collected && potion.respawnTimer == 60, "Failed respawn stays dormant and retries");
        for (int i = 0; i < 60; i++) items.update(List.of(), () -> new Point(200, 200));
        check(!potion.collected && potion.x == 200, "Map potion safely respawns");

        GameData.MonsterState killed = new GameData.MonsterState("M", 100, 100);
        for (int i = 0; i < 100; i++) items.onMonsterKilled(killed);
        check(items.items().size() == 60, "Drop count bounded immediately");
        check(items.items().stream().anyMatch(item -> item.id.startsWith("POTION_")), "Eviction retains map potions");
        for (int i = 0; i < 600; i++) items.update(List.of(), () -> null);
        check(items.items().size() == 1, "Unclaimed monster drops despawn after 600 ticks");

        ItemSystem drops = new ItemSystem(map, ALWAYS_DROP);
        drops.onMonsterKilled(killed);
        GameData.PlayerState nearMax = player("near", 90);
        drops.update(List.of(nearMax), () -> null);
        check(nearMax.hp == 100 && drops.items().isEmpty(), "Collected drop removed, HP capped");
        ItemSystem unlucky = new ItemSystem(map, new Random(1) {
            @Override public boolean nextBoolean() { return false; }
        });
        unlucky.onMonsterKilled(killed);
        check(unlucky.items().isEmpty(), "Non-drop outcome preserved");
        ItemSystem noLand = new ItemSystem(new OpenMap() {
            @Override public boolean isSpawnReachableAtWorld(int x, int y) { return false; }
        }, ALWAYS_DROP);
        noLand.onMonsterKilled(killed);
        check(noLand.items().isEmpty(), "No drops on unreachable land");
    }

    private static void combatAndSpawns() {
        GameData.PlayerState player = player("fighter", 100);
        player.x = player.y = 0; player.direction = "right";
        GameData.MonsterState monster = new GameData.MonsterState("M", 75, 0); monster.hp = 15;
        GameMap wall = new OpenMap() {
            @Override public boolean isCollisionAtWorld(int x, int y) { return x >= 50 && x < 60; }
        };
        AtomicInteger deaths = new AtomicInteger();
        CombatSystem.resolveAttack(player, List.of(monster), wall, () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(monster.hp == 15 && deaths.get() == 0, "Wall blocks extended melee hitbox");
        CombatSystem.resolveAttack(player, List.of(monster), new OpenMap(), () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(monster.isDead && deaths.get() == 1, "Extended range hits and emits one kill event");
        CombatSystem.resolveAttack(player, List.of(monster), new OpenMap(), () -> 180, null, 0, m -> deaths.incrementAndGet());
        check(deaths.get() == 1, "Dead monster does not produce duplicate loot");

        GameMap islands = new OpenMap() {
            @Override public boolean isCollisionAtWorld(int x, int y) {
                return x < 0 || y < 0 || x / Common.TILE_SIZE == 30;
            }
        };
        check(islands.isSpawnReachableAtWorld((int) Common.SPAWN_X, (int) Common.SPAWN_Y), "Spawn belongs to its connected region");
        check(!islands.isSpawnReachableAtWorld(40 * Common.TILE_SIZE, 10 * Common.TILE_SIZE), "Isolated land excluded");
        Random random = new Random(11);
        for (int i = 0; i < 30; i++) {
            Point point = SpawnSystem.find(islands, random, List.of(), List.of());
            check(point != null && point.x + GamePhysics.ENTITY_SIZE <= 30 * Common.TILE_SIZE,
                    "Spawn stays on reachable side with full hitbox");
        }
        GameMap blocked = new OpenMap() {
            @Override public boolean isWalkable(float x, float y) { return false; }
        };
        check(SpawnSystem.find(blocked, random, List.of(), List.of()) == null, "No land returns null instead of unsafe spawn");
    }

    private static GameData.WorldSnapshot decode(GameProtocol.SnapshotDecoder decoder, List<byte[]> packets) {
        GameData.WorldSnapshot result = null;
        for (byte[] packet : packets) {
            check(packet.length <= 499, "Items preserve bounded UDP datagrams");
            check(packet[5] == GameProtocol.SNAPSHOT_VERSION, "Binary format explicitly versioned");
            GameData.WorldSnapshot snapshot = decoder.accept(packet, 5, packet.length - 5);
            if (snapshot != null) result = snapshot;
        }
        return result;
    }
    private static void snapshots() {
        GameData.WorldSnapshot baseline = new GameData.WorldSnapshot();
        for (int i = 0; i < 4; i++) baseline.players.add(player("P" + i, 80));
        for (int i = 0; i < 15; i++) baseline.monsters.add(new GameData.MonsterState("M" + i, i * 70, 300));
        for (int i = 0; i < 60; i++) baseline.items.add(new GameData.ItemState("DROP_" + i, "HEAL", i + 0.125f, 250.5f));
        List<byte[]> full = GameProtocol.serializeSnapshotBinary(1, 9, 1, baseline, null);
        Collections.reverse(full);
        GameProtocol.SnapshotDecoder decoder = new GameProtocol.SnapshotDecoder();
        GameData.WorldSnapshot restored = decode(decoder, full);
        check(restored != null && restored.items.size() == 60, "Maximum item count reassembles atomically");
        check(restored.items.get(5).x == 5.125f && restored.items.get(5).healAmount == 30, "Binary item fields preserved exactly");
        GameData.WorldSnapshot changed = GameProtocol.copySnapshot(baseline);
        changed.items.remove(0);
        changed.items.get(0).collected = true; changed.items.get(0).healAmount = 25;
        changed.items.get(1).x = 900.75f;
        changed.items.add(new GameData.ItemState("NEW", "HEAL", 11, 22));
        restored = decode(decoder, GameProtocol.serializeSnapshotBinary(3, 9, 1, changed, baseline));
        check(restored != null && restored.items.size() == 60, "Item delta supports removal plus insertion");
        check(restored.items.stream().noneMatch(item -> item.id.equals("DROP_0")), "Removed item stays removed");
        check(restored.items.get(0).collected && restored.items.get(0).healAmount == 25, "Collected and healing deltas");
        check(restored.items.get(1).x == 900.75f, "Position delta");
        check(!baseline.items.get(1).collected, "Delta does not mutate baseline");
        GameData.WorldSnapshot text = GameProtocol.parseSnapshot(GameProtocol.serializeSnapshot(changed));
        check(text.items.size() == 60 && text.items.get(0).healAmount == 25, "Text utility retains item fields");
        changed.items.clear();
        restored = decode(decoder, GameProtocol.serializeSnapshotBinary(4, 9, 1, changed, baseline));
        check(restored != null && restored.items.isEmpty(), "All item removals decoded");
        GameProtocol.SnapshotDecoder oldVersion = new GameProtocol.SnapshotDecoder();
        for (byte[] packet : full) {
            byte[] old = packet.clone(); old[5] = 1;
            check(oldVersion.accept(old, 5, old.length - 5) == null, "Old binary format rejected rather than misparsed");
        }
    }

    public static void main(String[] args) {
        potions(); combatAndSpawns(); snapshots();
        System.out.println("PASS: " + checks + " manually ported gameplay assertions");
    }
}
