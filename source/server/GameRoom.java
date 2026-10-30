package source.server;

import source.common.Common;
import source.common.MapManager;
import source.common.Player;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameRoom implements Runnable {
    public String roomName;
    public Map<String, Player> players = new ConcurrentHashMap<>();
    public Map<String, PrintWriter> writers = new ConcurrentHashMap<>();
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
            broadcastState();
            try {
                Thread.sleep(16); // 60 FPS Game Loop
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void broadcastState() {
        StringBuilder sb = new StringBuilder(Common.CMD_STATE);
        for (Player p : players.values()) {
            sb.append(";").append(p.id).append(",")
              .append(p.name).append(",")
              .append((int) p.x).append(",")
              .append((int) p.y);
        }
        String msg = sb.toString();
        for (PrintWriter out : writers.values()) {
            out.println(msg);
        }
    }
}