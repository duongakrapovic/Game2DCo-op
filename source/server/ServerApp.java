package source.server;

import source.common.Common;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * SERVER APP (SOURCE/SERVER/SERVERAPP.JAVA)
 * ============================================================================
 * Entry Point khởi tạo Headless Game Server chạy hoàn toàn bằng TCP Thuần.
 * ============================================================================
 */
public class ServerApp {

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private TcpLobbyServer tcpLobby;

    public static void main(String[] args) {
        ServerApp app = new ServerApp();
        app.start();
    }

    public void start() {
        System.out.println("==================================================");
        System.out.println("   DUNGEON CO-OP HEADLESS GAME SERVER (TCP)       ");
        System.out.println("==================================================");

        try {
            tcpLobby = new TcpLobbyServer(Common.TCP_PORT, this);
            tcpLobby.start();
            System.out.println("[ServerApp] TCP Server da mo tai Port: " + Common.TCP_PORT);
            System.out.println("[ServerApp] Server da san sang phuc vu! (Go 'exit' de dung)");

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = console.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) {
                    shutdown();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[ServerApp] Loi khoi tao Server: " + e.getMessage());
            shutdown();
        }
    }

    public synchronized void shutdown() {
        System.out.println("[ServerApp] Dang dong Server...");
        if (tcpLobby != null) tcpLobby.stop();
        for (GameRoom room : rooms.values()) {
            room.stop();
        }
        rooms.clear();
        System.out.println("[ServerApp] Da tat Server hoan toan.");
        System.exit(0);
    }

    public Map<String, GameRoom> getRooms() { return rooms; }
    public GameRoom getRoom(String roomId) { return rooms.get(roomId); }
    public void addRoom(GameRoom room) { rooms.put(room.getRoomId(), room); }
    public void removeRoom(String roomId) { rooms.remove(roomId); }

    public String getRoomListMessage() {
        StringBuilder sb = new StringBuilder(Common.CMD_ROOM_LIST);
        for (GameRoom r : rooms.values()) {
            sb.append(";").append(r.getRoomId()).append(" (").append(r.getPlayerCount()).append("/").append(Common.MAX_PLAYERS_PER_ROOM).append(")");
        }
        return sb.toString();
    }
}