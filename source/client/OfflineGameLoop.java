package source.client;

import source.common.*;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.swing.SwingUtilities;

/** Owns the offline simulation; Swing sees detached snapshots only. */
public final class OfflineGameLoop implements Runnable {
    private final ClientGame panel;
    private final Random random = new Random();
    private final Object stateLock = new Object();
    private final List<GameData.MonsterState> monsters = new ArrayList<>();
    private volatile boolean running;
    private volatile long generation;
    private Thread thread;
    private GameData.PlayerState player;
    private String name;
    private boolean previousAttack;
    private boolean queuedAttack;
    private ItemSystem itemSystem;

    public OfflineGameLoop(ClientGame panel) { this.panel = panel; }
    public boolean isRunning() { return running; }

    public void startOfflineMode(String playerName) {
        stopOfflineMode();
        panel.mapManager.ensureCollisionMapLoaded();
        panel.mapManager.validatedPlayerSpawn(Common.SPAWN_X, Common.SPAWN_Y);
        synchronized (stateLock) {
            name = playerName;
            player = new GameData.PlayerState("LOCAL_1", playerName);
            SaveManager.OfflineSaveData save = SaveManager.loadOfflineSave(panel.mapManager);
            if (save != null) {
                if (save.x > 0 || save.y > 0) { player.x = save.x; player.y = save.y; }
                if (save.hp > 0) { player.hp = save.hp; player.maxHp = save.maxHp; }
            }
            monsters.clear();
            for (int i = 1; i <= 15; i++) {
                Point point = getRandomMapPoint();
                if (point != null) monsters.add(new GameData.MonsterState("M" + i, point.x, point.y));
            }
            itemSystem = new ItemSystem(panel.mapManager, random);
            itemSystem.initialize(this::getRandomMapPoint);
            previousAttack = false;
            queuedAttack = false;
            running = true;
            generation++;
            thread = new Thread(this, "Offline-Loop");
            thread.start();
        }
    }

    public void stopOfflineMode() {
        running = false;
        generation++;
        Thread old = thread;
        if (old != null) {
            old.interrupt();
            if (Thread.currentThread() != old) {
                try { old.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            thread = null;
        }
        saveOfflineProgress();
    }

    public void saveOfflineProgress() {
        synchronized (stateLock) {
            if (player != null) SaveManager.saveOffline(name, player.x, player.y, player.hp, player.maxHp);
        }
    }

    private Point getRandomMapPoint() {
        return SpawnSystem.find(panel.mapManager, random, monsters, player == null ? List.of() : List.of(player));
    }

    @Override public void run() {
        long next = System.nanoTime(), epoch = generation;
        int saveTicks = 0;
        while (running && epoch == generation && !Thread.currentThread().isInterrupted()) {
            GameData.WorldSnapshot view;
            synchronized (stateLock) {
                if (!running || epoch != generation) break;
                player.left = panel.left; player.right = panel.right;
                player.up = panel.up; player.down = panel.down;
                boolean attack = panel.attack;
                queuedAttack |= panel.consumeAttackPress() || (attack && !previousAttack);
                if (queuedAttack && !player.isAttacking) {
                    queuedAttack = false;
                    CombatSystem.resolveAttack(player, monsters, panel.mapManager, () -> 180, null, 0, itemSystem::onMonsterKilled);
                }
                previousAttack = attack;
                GamePhysics.updatePlayer(player, panel.mapManager);
                for (GameData.MonsterState monster : monsters) {
                    if (monster.isDead) {
                        if (--monster.respawnTimer <= 0) {
                            Point point = getRandomMapPoint();
                            if (point == null) { monster.respawnTimer = 60; continue; }
                            monster.x = point.x; monster.y = point.y;
                            monster.hp = monster.maxHp; monster.isDead = false;
                            monster.flashUntil = 0; monster.nextPathAt = 0;
                            monster.currentPath = new int[0][2]; monster.pathIndex = 0;
                        }
                    } else MonsterAI.updateMonster(monster, player, panel.mapManager);
                }
                if (player.hp <= 0) player.respawn();
                itemSystem.update(List.of(player), this::getRandomMapPoint);
                if (++saveTicks >= 900) { saveTicks = 0; saveOfflineProgress(); }
                GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
                snapshot.players.add(player); snapshot.monsters.addAll(monsters);
                snapshot.items.addAll(itemSystem.items());
                view = GameProtocol.copySnapshot(snapshot);
            }
            SwingUtilities.invokeLater(() -> {
                if (running && epoch == generation) panel.applyOfflineSnapshot(view);
            });
            next += Common.TICK_NS;
            long sleep = next - System.nanoTime();
            if (sleep <= 0) { next = System.nanoTime(); continue; }
            try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
