package source.server;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;
import source.common.GameProtocol;
import source.common.CombatSystem;
import source.common.MonsterAI;
import java.util.concurrent.atomic.AtomicReference;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class ServerGame implements Runnable {

    private static final int MONSTER_COUNT = 15;
    private static final int RESPAWN_MIN_TICKS = 180;
    private static final int RESPAWN_MAX_TICKS = 300;

    private final String roomId;
    private final ServerNetwork serverNetwork;
    private final Map<String, GameData.PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, GameData.MonsterState> monsters = new ConcurrentHashMap<>();
    private final Map<String, ServerNetwork.ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastAcks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> attackInputs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> previousAttackInputs = new ConcurrentHashMap<>();

    private final GameMap mapManager;
    private final Random random = new Random();
    private volatile boolean running = true;
    private final Thread loopThread;
    private final RoomSaveManager saves;
    private final Map<String, AtomicReference<GameData.PlayerInput>> inputBuffers = new ConcurrentHashMap<>();
    private final CombatSystem.MonsterHistory history = new CombatSystem.MonsterHistory();
    private long tick;
    private boolean forceFullState = true;
    private GameData.WorldSnapshot baseline;
    private long baselineSequence;
    private final long streamId = java.util.UUID.randomUUID().getMostSignificantBits();
    private long stateSequence = 0;
    private int saveTickCounter = 0;

    public ServerGame(String roomId) {
        this(roomId, null);
    }

    public ServerGame(String roomId, ServerNetwork serverNetwork) {
        this.roomId = roomId;
        this.serverNetwork = serverNetwork;
        this.mapManager = new GameMap();
        mapManager.ensureCollisionMapLoaded();
        mapManager.validatedPlayerSpawn(Common.SPAWN_X, Common.SPAWN_Y);
        this.saves = new RoomSaveManager(roomId, mapManager);

        for (int i = 1; i <= MONSTER_COUNT; i++) {
            Point spawn = findRandomMapPoint();
            monsters.put("M" + i, new GameData.MonsterState("M" + i, spawn.x, spawn.y));
        }

        loopThread = new Thread(this, "RoomLoop-" + roomId);
        loopThread.start();
    }

    public String getRoomId() { return roomId; }
    public long getStreamId() { return streamId; }
    public synchronized void requestFullState() { forceFullState = true; }
    public Map<String, GameData.PlayerState> getPlayers() { return players; }
    public int getPlayerCount() { return players.size(); }
    public boolean isFull() { return players.size() >= Common.MAX_PLAYERS_PER_ROOM; }
    public boolean isEmpty() { return players.isEmpty(); }

    public synchronized boolean addPlayer(GameData.PlayerState player, ServerNetwork.ClientHandler handler) {
        if (!running) throw new IllegalStateException("Phong da dung. Hay lam moi danh sach phong.");
        if (players.containsKey(player.id)) throw new IllegalStateException("Ban da o trong phong nay.");
        if (isFull()) return false;
        saves.join(player);
        players.put(player.id, player);
        inputBuffers.put(player.id, new AtomicReference<>());
        lastAcks.put(player.id, -1);
        attackInputs.put(player.id, false);
        previousAttackInputs.put(player.id, false);
        if (handler != null) clientHandlers.put(player.id, handler);
        forceFullState = true;
        return true;
    }

    public synchronized void updatePlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        if (handler != null && players.containsKey(playerId)) {
            GameData.PlayerState player = players.get(playerId);
            java.awt.geom.Point2D.Float spawn = mapManager.validatedPlayerSpawn(player.x, player.y);
            player.x = spawn.x; player.y = spawn.y;
            clientHandlers.put(playerId, handler);
            saves.attach(playerId);
            forceFullState = true;
        }
    }

    public synchronized void detachPlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        if (!clientHandlers.remove(playerId, handler)) return;
        GameData.PlayerState player = players.get(playerId);
        if (player == null) return;
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(playerId);
        if (buffer != null) buffer.set(null);
        player.left = player.right = player.up = player.down = false;
        attackInputs.put(playerId, false);
        previousAttackInputs.put(playerId, false);
        saves.detach(player, false);
    }

    public synchronized void removePlayer(String playerId) {
        GameData.PlayerState player = players.remove(playerId);
        clientHandlers.remove(playerId);
        inputBuffers.remove(playerId);
        lastAcks.remove(playerId);
        attackInputs.remove(playerId);
        previousAttackInputs.remove(playerId);
        if (player != null) saves.detach(player, true);
        forceFullState = true;
    }

    public synchronized void bufferPlayerInput(String playerId, GameData.PlayerInput input) {
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(playerId);
        if (buffer == null || !clientHandlers.containsKey(playerId)) return;
        GameData.PlayerInput previous = buffer.get();
        if (input.seq <= lastAcks.getOrDefault(playerId, -1)
                || (previous != null && input.seq <= previous.seq)) return;
        buffer.set(input);
    }

    private void applyBufferedInput(GameData.PlayerState player) {
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(player.id);
        GameData.PlayerInput input = buffer == null ? null : buffer.get();
        if (input == null) return;
        player.left = input.left; player.right = input.right;
        player.up = input.up; player.down = input.down;
        player.pingMs = input.pingMs;
        updateAck(player.id, input.seq);
        updateAttackInput(player.id, input.attack);
    }

    public void updateAck(String playerId, int sequence) {
        if (players.containsKey(playerId)) {
            lastAcks.computeIfPresent(playerId, (ignored, previous) ->
                    sequence >= previous ? sequence : previous);
        }
    }

    public void updateAttackInput(String playerId, boolean pressed) {
        if (players.containsKey(playerId)) {
            attackInputs.put(playerId, pressed);
        }
    }

    public void stop() {
        synchronized (this) { running = false; }
        loopThread.interrupt();
        if (Thread.currentThread() != loopThread) {
            try { loopThread.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        syncAndSaveOnline();
    }

    @Override
    public void run() {
        long nextTick = System.nanoTime();
        while (running) {
            synchronized (this) {
                if (!running) break;
                history.record(monsters.values(), tick);
                for (GameData.PlayerState player : players.values()) {
                    applyBufferedInput(player);
                    updatePlayerAttack(player);
                    GamePhysics.updatePlayer(player, mapManager);
                    if (player.hp <= 0) player.respawn();
                }
                updateMonsters();
                broadcastUdpState();
                tick++;
                if (++saveTickCounter >= 900) {
                    saveTickCounter = 0;
                    syncAndSaveOnline();
                }
            }

            nextTick += Common.TICK_NS;
            long sleepNs = nextTick - System.nanoTime();
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    if (!running) {
                        break;
                    }
                }
            } else {
                nextTick = System.nanoTime();
            }
        }
    }

    private void updatePlayerAttack(GameData.PlayerState player) {
        boolean pressed = attackInputs.getOrDefault(player.id, false);
        if (pressed && !previousAttackInputs.getOrDefault(player.id, false) && !player.isAttacking) {
            CombatSystem.resolveAttack(player, monsters.values(), mapManager,
                    this::randomRespawnTicks, history, tick);
        }
        previousAttackInputs.put(player.id, pressed);
    }

    private void updateMonsters() {
        for (GameData.MonsterState monster : monsters.values()) {
            if (monster.isDead) {
                if (--monster.respawnTimer <= 0) {
                    Point spawn = findRandomMapPoint();
                    history.clear(monster.id);
                    monster.x = spawn.x;
                    monster.y = spawn.y;
                    monster.hp = monster.maxHp;
                    monster.isDead = false;
                    monster.flashUntil = 0;
                    monster.nextPathAt = 0;
                    monster.currentPath = new int[0][2];
                    monster.pathIndex = 0;
                }
                continue;
            }

            GameData.PlayerState target = findNearestPlayer(monster);
            if (target != null) {
                MonsterAI.updateMonster(monster, target, mapManager);
            }
        }
    }

    private GameData.PlayerState findNearestPlayer(GameData.MonsterState monster) {
        GameData.PlayerState nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (GameData.PlayerState player : players.values()) {
            double distance = Math.hypot(player.x - monster.x, player.y - monster.y);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private Point findRandomMapPoint() {
        int width = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int height = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int maxX = width - GamePhysics.ENTITY_SIZE;
        int maxY = height - GamePhysics.ENTITY_SIZE;

        for (int attempt = 0; attempt < 2000; attempt++) {
            int x = random.nextInt(maxX + 1);
            int y = random.nextInt(maxY + 1);
            if (!isWalkable(x, y)
                    || Math.hypot(x - Common.SPAWN_X, y - Common.SPAWN_Y)
                            < GamePhysics.ENTITY_SIZE * 3) {
                continue;
            }

            Rectangle candidate = GamePhysics.getEntityBounds(x, y);
            boolean overlaps = false;
            for (GameData.MonsterState existing : monsters.values()) {
                if (!existing.isDead
                        && candidate.intersects(GamePhysics.getEntityBounds(existing.x, existing.y))) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                for (GameData.PlayerState player : players.values()) {
                    if (candidate.intersects(GamePhysics.getEntityBounds(player.x, player.y))) {
                        overlaps = true;
                        break;
                    }
                }
            }
            if (!overlaps) {
                return new Point(x, y);
            }
        }
        throw new IllegalStateException("Khong tim duoc vi tri hop le de sinh Slime trong map.");
    }

    private boolean isWalkable(int x, int y) {
        return mapManager.isWalkable(x, y);
    }

    private int randomRespawnTicks() {
        return RESPAWN_MIN_TICKS
                + random.nextInt(RESPAWN_MAX_TICKS - RESPAWN_MIN_TICKS + 1);
    }

    private void broadcastUdpState() {
        if (players.isEmpty() || serverNetwork == null) return;
        GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
        for (GameData.PlayerState player : players.values()) {
            player.lastAckSeq = lastAcks.getOrDefault(player.id, -1);
            snapshot.players.add(player);
        }
        snapshot.monsters.addAll(monsters.values());
        stateSequence++;
        boolean full = forceFullState || baseline == null || stateSequence % 60 == 0;
        if (full) baselineSequence = stateSequence;
        java.util.List<byte[]> packets = GameProtocol.serializeSnapshotBinary(
                stateSequence, streamId, baselineSequence, snapshot, full ? null : baseline);
        if (full) {
            baseline = GameProtocol.copySnapshot(snapshot);
            forceFullState = false;
        }
        for (String playerId : players.keySet()) {
            for (byte[] packet : packets) serverNetwork.sendGameUdpPacket(playerId, packet);
        }
    }

    private synchronized void syncAndSaveOnline() {
        saves.syncAndSaveOnline(players.values());
    }
}
