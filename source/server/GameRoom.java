package source.server;

import source.common.Common;
import source.common.MapManager;
import source.common.Player;
import source.common.PhysicsEngine;
import source.common.Enemy;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameRoom implements Runnable {
    public String roomName;
    public Map<String, Player> players = new ConcurrentHashMap<>();
    public Map<String, PrintWriter> writers = new ConcurrentHashMap<>();
    private final Map<String, Enemy> enemies = new ConcurrentHashMap<>();
    private boolean isRunning = true;
    private MapManager mapManager;

    public GameRoom(String roomName) {
        this.roomName = roomName;
        
        // Khởi tạo MapManager mới (không tham số)
        this.mapManager = new MapManager();
        
        // Đường dẫn chính xác tới thư mục chứa dữ liệu map
        String mapPath = "source/content/map";
        
        // Nạp thuộc tính cản .tsx (Server chỉ nạp dữ liệu cản, không nạp ảnh .png)
        this.mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        
        // Nạp toàn bộ ma trận 9 Chunks (3x3: từ chunk0_0 tới chunk2_2)
        this.mapManager.loadAllChunks(mapPath, 3, 3);

        int tile = Common.TILE_SIZE;
        enemies.put("E_SOLDIER", new Enemy("E_SOLDIER", "Linh", 26 * tile, 18 * tile, 85f));
        enemies.put("E_MONSTER", new Enemy("E_MONSTER", "Quai", 18 * tile, 24 * tile, 115f));

        new Thread(this).start();
    }

    public void addPlayer(String pId, Player p, PrintWriter out) {
        players.put(pId, p);
        writers.put(pId, out);
    }

    public void removePlayer(String pId) {
        players.remove(pId);
        writers.remove(pId);
    }

    @Override
    public void run() {
        while (isRunning) {
            for (Player p : players.values()) {
                // Tính toán vật lý & va chạm tilemap cho từng người chơi
                PhysicsEngine.updatePlayer(p, mapManager);
            }
            for (Enemy enemy : enemies.values()) {
                Player target = nearestPlayer(enemy);
                enemy.update(target, mapManager, 1.0f / 60.0f);
            }
            broadcastState();
            try {
                Thread.sleep(16); // 60 FPS Game Loop
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private Player nearestPlayer(Enemy enemy) {
        Player nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Player player : players.values()) {
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

    private void broadcastState() {
    StringBuilder sb = new StringBuilder(Common.CMD_STATE);
    for (Player p : players.values()) {
        sb.append(";").append(p.id).append(",")
          .append(p.name).append(",")
          .append((int) p.x).append(",")
          .append((int) p.y).append(",")
          .append(p.direction).append(",")
          .append(p.spriteNum).append(",")
          .append(p.health);
    }
    for (Enemy e : enemies.values()) {
        sb.append(";").append(e.id).append(",")
          .append(e.name).append(",")
          .append((int) e.x).append(",")
          .append((int) e.y).append(",")
          .append(e.direction).append(",1,100");
    }
    String msg = sb.toString();
    for (PrintWriter out : writers.values()) {
        out.println(msg);
    }
}
}
