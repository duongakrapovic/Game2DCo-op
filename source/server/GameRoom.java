package source.server;

import source.common.Common;
import source.common.GameMap;
import source.common.GamePhysics;
import source.common.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameRoom implements Runnable {

    private final String roomId;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, TcpLobbyServer.ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastAcks = new ConcurrentHashMap<>();

    private final GameMap mapManager;
    private volatile boolean isRunning = true;
    private final Thread loopThread;

    public GameRoom(String roomId) {
        this.roomId = roomId;

        this.mapManager = new GameMap();
        String mapPath = "source/content/map";
        this.mapManager.initTileset(256);
        this.mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        this.mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        this.loopThread = new Thread(this, "RoomLoop-" + roomId);
        this.loopThread.start();
    }

    public String getRoomId() { return roomId; }
    public Map<String, Player> getPlayers() { return players; }
    public int getPlayerCount() { return players.size(); }
    public boolean isFull() { return players.size() >= Common.MAX_PLAYERS_PER_ROOM; }
    public boolean isEmpty() { return players.isEmpty(); }

    public void addPlayer(Player player, TcpLobbyServer.ClientHandler handler) {
        players.put(player.id, player);
        if (handler != null) {
            clientHandlers.put(player.id, handler);
        }
    }

    public void updatePlayerHandler(String playerId, TcpLobbyServer.ClientHandler handler) {
        if (handler != null && players.containsKey(playerId)) {
            clientHandlers.put(playerId, handler);
        }
    }

    public void removePlayer(String playerId) {
        players.remove(playerId);
        clientHandlers.remove(playerId);
        lastAcks.remove(playerId);
    }

    public void updateAck(String playerId, int seq) {
        lastAcks.put(playerId, seq);
    }

    public void stop() {
        isRunning = false;
        if (loopThread != null) {
            loopThread.interrupt();
        }
    }

    @Override
    public void run() {
        long nextTick = System.nanoTime();

        while (isRunning) {
            for (Player p : players.values()) {
                GamePhysics.updatePlayer(p, mapManager);
            }

            broadcastTcpState();

            nextTick += Common.TICK_NS;
            long sleepNs = nextTick - System.nanoTime();
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                nextTick = System.nanoTime();
            }
        }
    }

    private void broadcastTcpState() {
        if (players.isEmpty()) return;

        StringBuilder sb = new StringBuilder(Common.CMD_STATE);
        for (Player p : players.values()) {
            int ack = lastAcks.getOrDefault(p.id, -1);
            sb.append(";").append(p.id).append(",")
              .append(p.name).append(",")
              .append(Common.Protocol.fmt2(p.x)).append(",")
              .append(Common.Protocol.fmt2(p.y)).append(",")
              .append(p.direction).append(",")
              .append(p.spriteNum).append(",")
              .append(ack);
        }

        String stateMsg = sb.toString();
        for (TcpLobbyServer.ClientHandler handler : clientHandlers.values()) {
            handler.send(stateMsg);
        }
    }
}