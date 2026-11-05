package source.server;

import source.common.Common;
import source.common.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point của Dedicated Server + nơi quản lý phòng / phiên.
 *
 * Chạy:  java -cp . source.server.ServerApp [port] [--no-log]
 *
 * Mọi thao tác tạo / vào / rời / xóa phòng đều đi qua LOCK để tránh race giữa các ClientHandler.
 */
public class ServerApp {
    static final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    static final Object LOCK = new Object();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Server-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger idCounter = new AtomicInteger(1);
    private static final SecureRandom random = new SecureRandom();

    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        int port = Common.PORT;
        boolean log = true;
        for (String a : args) {
            if (a.equals("--no-log")) log = false;
            else if (a.matches("\\d+")) port = Integer.parseInt(a);
        }
        if (!log) System.setProperty("game.log", "false");

        ServerStats.start(rooms::size, log);
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Dedicated Server Online on Port: " + port
                + " (tick " + Common.TICK_RATE + " Hz, log " + (GameLogger.ENABLED ? "ON -> logs/" : "OFF") + ")");

        while (true) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);   // gói nhỏ gửi ngay, không chờ gộp (Nagle) -> giảm độ trễ
                socket.setKeepAlive(true);
                socket.setSoTimeout(Protocol.SERVER_IDLE_TIMEOUT_MS); // im lặng quá lâu = coi như rớt mạng
                String pId = "P" + idCounter.getAndIncrement();
                Thread t = new Thread(new ClientHandler(socket, pId), "Client-" + pId);
                t.start();
            } catch (IOException e) {
                System.err.println("Accept lỗi: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ phiên

    static String newToken() {
        byte[] b = new byte[8];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Socket của phiên bị đóng: giữ chỗ trong thời gian ân hạn nếu đang ở trong phòng. */
    static void onSocketClosed(Session s, OutChannel channel) {
        synchronized (LOCK) {
            if (s.channel != channel) return; // đã bị kết nối RESUME mới thay thế
            s.connected = false;
            GameRoom room = s.room;
            if (room == null) {
                sessions.remove(s.token);
                return;
            }
            room.detachWriter(s.pId, channel);
            System.out.println("[" + s.pId + "] mất kết nối, giữ chỗ trong phòng " + room.roomName
                    + " " + Protocol.RECONNECT_GRACE_MS / 1000 + "s");
            s.graceTask = scheduler.schedule(() -> expire(s), Protocol.RECONNECT_GRACE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void expire(Session s) {
        synchronized (LOCK) {
            if (s.connected) return;
            System.out.println("[" + s.pId + "] hết thời gian ân hạn, xóa khỏi phòng");
            leaveRoomLocked(s);
            sessions.remove(s.token);
        }
        broadcastRoomList();
    }

    // ------------------------------------------------------------------ phòng (gọi với LOCK)

    /** @return null nếu thành công, ngược lại là thông báo lỗi. */
    static String createRoom(Session s, String roomName, String playerName) {
        synchronized (LOCK) {
            leaveRoomLocked(s);
            if (rooms.containsKey(roomName)) return "Phòng đã tồn tại!";
            GameRoom room = new GameRoom(roomName);
            rooms.put(roomName, room);
            enterRoomLocked(s, room, playerName);
        }
        broadcastRoomList();
        return null;
    }

    static String joinRoom(Session s, String roomName, String playerName) {
        synchronized (LOCK) {
            GameRoom room = rooms.get(roomName);
            if (room == null) return "Phòng không tồn tại!";
            if (s.room == room) return "Bạn đang ở trong phòng này rồi!";
            if (room.players.size() >= Common.MAX_PLAYERS_PER_ROOM) return "Phòng đã đầy!";
            leaveRoomLocked(s);
            enterRoomLocked(s, room, playerName);
        }
        broadcastRoomList();
        return null;
    }

    static void leaveRoom(Session s) {
        synchronized (LOCK) { leaveRoomLocked(s); }
        broadcastRoomList();
    }

    private static void enterRoomLocked(Session s, GameRoom room, String playerName) {
        source.common.Player p = new source.common.Player(s.pId, playerName);
        room.addPlayer(s.pId, p, s.channel);
        s.room = room;
    }

    private static void leaveRoomLocked(Session s) {
        GameRoom room = s.room;
        if (room == null) return;
        room.removePlayer(s.pId);
        s.room = null;
        if (room.isEmpty()) {
            room.shutdown();           // dừng thread game loop (trước đây thread chạy mãi dù phòng đã bị xóa)
            rooms.remove(room.roomName, room);
        }
    }

    // ------------------------------------------------------------------ danh sách phòng

    static String roomListMessage() {
        StringBuilder sb = new StringBuilder(Common.CMD_ROOM_LIST);
        for (GameRoom r : rooms.values()) {
            sb.append(';').append(r.roomName).append(" (").append(r.players.size()).append(" người)");
        }
        return sb.toString();
    }

    /** Đẩy danh sách phòng mới cho mọi người đang ở sảnh khi có thay đổi. */
    static void broadcastRoomList() {
        String msg = roomListMessage();
        for (Session s : sessions.values()) {
            OutChannel ch = s.channel;
            if (s.connected && s.room == null && ch != null) ch.sendReliable(msg);
        }
    }
}
