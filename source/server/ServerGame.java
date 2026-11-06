package source.server;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;
import source.common.GameProtocol;

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

    public ServerGame(String roomId) {
        this.roomId = roomId;
        this.mapManager = new GameMap();
        String mapPath = "source/content/map";
        mapManager.initTileset(256);
        mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        for (int i = 1; i <= MONSTER_COUNT; i++) {
            Point spawn = findRandomMapPoint();
            monsters.put("M" + i, new GameData.MonsterState("M" + i, spawn.x, spawn.y));
        }

        loopThread = new Thread(this, "RoomLoop-" + roomId);
        loopThread.start();
    }

    public String getRoomId() { return roomId; }
    public Map<String, GameData.PlayerState> getPlayers() { return players; }
    public int getPlayerCount() { return players.size(); }
    public boolean isFull() { return players.size() >= Common.MAX_PLAYERS_PER_ROOM; }
    public boolean isEmpty() { return players.isEmpty(); }

    public synchronized boolean addPlayer(
            GameData.PlayerState player, ServerNetwork.ClientHandler handler) {
        if (players.size() >= Common.MAX_PLAYERS_PER_ROOM || players.containsKey(player.id)) {
            return false;
        }

        players.put(player.id, player);
        lastAcks.put(player.id, -1);
        attackInputs.put(player.id, false);
        previousAttackInputs.put(player.id, false);
        if (handler != null) {
            clientHandlers.put(player.id, handler);
        }
        return true;
    }

    public void updatePlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        if (handler != null && players.containsKey(playerId)) {
            clientHandlers.put(playerId, handler);
        }
    }

    public void detachPlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        clientHandlers.remove(playerId, handler);
        GameData.PlayerState player = players.get(playerId);
        if (player != null) {
            player.left = false;
            player.right = false;
            player.up = false;
            player.down = false;
        }
        attackInputs.put(playerId, false);
        previousAttackInputs.put(playerId, false);
    }

    public void removePlayer(String playerId) {
        players.remove(playerId);
        clientHandlers.remove(playerId);
        lastAcks.remove(playerId);
        attackInputs.remove(playerId);
        previousAttackInputs.remove(playerId);
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
        running = false;
        loopThread.interrupt();
    }

    @Override
    public void run() {
        long nextTick = System.nanoTime();
        while (running) {
            for (GameData.PlayerState player : players.values()) {
                updatePlayerAttack(player);
                GamePhysics.updatePlayer(player, mapManager);
                if (player.hp <= 0) {
                    player.respawn();
                }
            }

            updateMonsters();
            broadcastTcpState();

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
        boolean previouslyPressed = previousAttackInputs.getOrDefault(player.id, false);
        if (pressed && !previouslyPressed && !player.isAttacking) {
            player.isAttacking = true;
            player.attackTicks = 0;
            Rectangle attackHitbox = GamePhysics.getAttackHitbox(
                    player.x, player.y, player.direction);
            for (GameData.MonsterState monster : monsters.values()) {
                if (!monster.isDead && attackHitbox.intersects(
                        GamePhysics.getEntityBounds(monster.x, monster.y))) {
                    monster.hp = Math.max(0, monster.hp - 15);
                    if (monster.hp == 0) {
                        monster.isDead = true;
                        monster.respawnTimer = randomRespawnTicks();
                    }
                }
            }
        }
        previousAttackInputs.put(player.id, pressed);
    }

    private void updateMonsters() {
        for (GameData.MonsterState monster : monsters.values()) {
            if (monster.isDead) {
                if (--monster.respawnTimer <= 0) {
                    Point spawn = findRandomMapPoint();
                    monster.x = spawn.x;
                    monster.y = spawn.y;
                    monster.hp = monster.maxHp;
                    monster.isDead = false;
                }
                continue;
            }

            GameData.PlayerState target = findNearestPlayer(monster);
            if (target != null) {
                GamePhysics.updateMonster(monster, target, mapManager);
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
        int right = x + GamePhysics.ENTITY_SIZE - 1;
        int bottom = y + GamePhysics.ENTITY_SIZE - 1;
        return !mapManager.isCollisionAtWorld(x + 2, y + 2)
                && !mapManager.isCollisionAtWorld(right - 2, y + 2)
                && !mapManager.isCollisionAtWorld(x + 2, bottom - 2)
                && !mapManager.isCollisionAtWorld(right - 2, bottom - 2);
    }

    private int randomRespawnTicks() {
        return RESPAWN_MIN_TICKS
                + random.nextInt(RESPAWN_MAX_TICKS - RESPAWN_MIN_TICKS + 1);
    }

    private void broadcastTcpState() {
        if (players.isEmpty()) {
            return;
        }

        GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
        for (GameData.PlayerState player : players.values()) {
            player.lastAckSeq = lastAcks.getOrDefault(player.id, -1);
            snapshot.players.add(player);
        }
        snapshot.monsters.addAll(monsters.values());

        String stateMessage = GameProtocol.serializeSnapshot(snapshot);
        for (ServerNetwork.ClientHandler handler : clientHandlers.values()) {
            handler.send(stateMessage);
        }
    }
}
