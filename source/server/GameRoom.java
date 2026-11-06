package source.server;

import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * GAME ROOM (SOURCE/SERVER/GAMEROOM.JAVA)
 * ============================================================================
 * Chạy Game Loop 60 FPS độc lập cho 1 phòng chơi.
 * Tính toán vật lý và broadcast trạng thái (STATE) qua TCP đến các Client trong phòng.
 * ============================================================================
 */
public class GameRoom implements Runnable {

    private final String roomId;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, TcpLobbyServer.ClientHandler> clientHandlers = new ConcurrentHashMap<>();

    private final MapManager mapManager;
    private volatile boolean isRunning = true;
    private final Thread loopThread;

    public GameRoom(String roomId) {
        this.roomId = roomId;

        this.mapManager = new MapManager();
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

    public void removePlayer(String playerId) {
        players.remove(playerId);
        clientHandlers.remove(playerId);
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
            // 1. Cập nhật vật lý cho tất cả player trong phòng
            for (Player p : players.values()) {
                PhysicsEngine.updatePlayer(p, mapManager);
            }

            // 2. Broadcast trạng thái tọa độ qua TCP
            broadcastTcpState();

            // 3. Đồng hồ nhịp 60Hz ổn định
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

    /**
     * Đóng gói tọa độ và gửi trạng thái STATE qua TCP cho từng Client trong phòng
     */
    private void broadcastTcpState() {
        if (players.isEmpty()) return;

        StringBuilder sb = new StringBuilder(Common.CMD_STATE);
        for (Player p : players.values()) {
            sb.append(";").append(p.id).append(",")
              .append(p.name).append(",")
              .append((int)p.x).append(",")
              .append((int)p.y).append(",")
              .append(p.direction).append(",")
              .append(p.spriteNum);
        }

        String stateMsg = sb.toString();
        for (TcpLobbyServer.ClientHandler handler : clientHandlers.values()) {
            handler.send(stateMsg);
        }
    }
}