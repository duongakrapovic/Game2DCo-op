package source.server;

import source.common.Common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/*
 * Điểm vào và bộ điều phối server. Quản lý phòng đang sống cùng session trong RAM, hẹn hết hạn khi TCP đứt
 * và khôi phục token. Không phải cửa sổ game hoặc nơi vẽ map.
 * rooms ánh xạ roomId tới ServerGame; sessionsByToken và sessionsByPlayerId cho tra cứu O(1); expiry
 * scheduler và grace task giữ chỗ reconnect. session token được sinh bằng UUID, không được lưu vào JSON
 * nhân vật.
 */
public class ServerApp {

    /*
     * RECONNECT_GRACE_SECONDS: Số giây giữ phiên sau khi server phát hiện TCP ngắt.
     */
    private static final long RECONNECT_GRACE_SECONDS = 20;

    /*
     * rooms: Danh sách phòng sống trong RAM hoặc tham chiếu phòng của một handler.
     */
    private final Map<String, ServerGame> rooms = new ConcurrentHashMap<>();
    /*
     * sessionsByPlayerId: Phiên runtime hoặc chỉ mục tra phiên theo token và ID.
     */
    private final Map<String, ServerSession> sessionsByPlayerId = new ConcurrentHashMap<>();
    /*
     * sessionsByToken: Phiên runtime hoặc chỉ mục tra phiên theo token và ID.
     */
    private final Map<String, ServerSession> sessionsByToken = new ConcurrentHashMap<>();
    /*
     * sessionExpirations: Scheduler và các tác vụ hết hạn phiên ngắt TCP đang đợi reconnect.
     */
    private final Map<String, ScheduledFuture<?>> sessionExpirations = new ConcurrentHashMap<>();
    /*
     * sessionScheduler: Scheduler và các tác vụ hết hạn phiên ngắt TCP đang đợi reconnect.
     */
    private final ScheduledExecutorService sessionScheduler =
            /*
             * Callback/lambda: ThreadFactory tạo daemon Session-Expiry cho scheduler; Runnable thực tế
             * được scheduler gọi sau.
             */
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Session-Expiry");
                thread.setDaemon(true);
                return thread;
            });
    /*
     * serverNetwork: Tham chiếu thành phần điều phối để chuyển việc tới tầng ứng dụng hoặc mạng.
     */
    private volatile ServerNetwork serverNetwork;
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile boolean running;

    /*
     * main()
     * Tạo ServerApp rồi gọi start. Đây là main cần chạy cho dedicated server, khác main của ClientApp.
     */
    public static void main(String[] args) {
        new ServerApp().start();
    }

    /*
     * start()
     * Khởi động ServerNetwork cổng Common.PORT, in trạng thái sẵn sàng và đọc console chờ exit. Thiết lập
     * dọn ứng dụng khi dừng; khởi động server không tự tạo một phòng game.
     */
    public void start() {
        System.out.println("==================================================");
        System.out.println("   DUNGEON CO-OP - DEDICATED GAME SERVER          ");
        System.out.println("==================================================");

        try {
            running = true;
            serverNetwork = new ServerNetwork(Common.TCP_PORT, this);
            serverNetwork.start();
            System.out.println("[ServerApp] TCP Server da mo tai Port: " + Common.TCP_PORT);
            System.out.println("[ServerApp] Server da san sang phuc vu! (Go 'exit' de dung)");

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while (running && (line = console.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[ServerApp] Loi khoi tao Server: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    /*
     * shutdown()
     * Ngăn/dọn tác vụ phiên, dừng network và các phòng, giải phóng bảng quản lý; có bảo vệ để shutdown
     * không xử lý lặp ngoài ý muốn. Dừng phòng sẽ lưu tiến trình theo vòng đời.
     */
    public synchronized void shutdown() {
        if (!running && serverNetwork == null && rooms.isEmpty() && sessionsByToken.isEmpty()) {
            return;
        }

        running = false;
        System.out.println("[ServerApp] Dang dong Server...");
        ServerNetwork network = serverNetwork;
        serverNetwork = null;
        if (network != null) {
            network.stop();
        }
        for (ServerGame room : rooms.values()) {
            room.stop();
        }
        rooms.clear();
        sessionsByToken.clear();
        sessionsByPlayerId.clear();
        for (ScheduledFuture<?> expiration : sessionExpirations.values()) {
            expiration.cancel(false);
        }
        sessionExpirations.clear();
        sessionScheduler.shutdownNow();
        System.out.println("[ServerApp] Da tat Server hoan toan.");
    }

    /*
     * getRoom()
     * Tra rooms theo roomId, trả ServerGame hoặc null khi không có. Join/resume/input dùng để xác định thế
     * giới đích.
     */
    public ServerGame getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /*
     * addRoom()
     * Đưa phòng vào rooms bằng putIfAbsent, trả thành công nếu tên chưa bị chiếm. Nếu một phòng được tạo
     * cạnh tranh nhưng không được nhận, caller phải dừng loop của nó.
     */
    public boolean addRoom(ServerGame room) {
        return rooms.putIfAbsent(room.getRoomId(), room) == null;
    }

    /*
     * removeRoom()
     * Bỏ phòng theo tên khỏi map rồi stop nếu còn object phòng; phòng sống biến mất không tự xóa file
     * save.
     */
    public void removeRoom(String roomId) {
        ServerGame room = rooms.remove(roomId);
        if (room != null) {
            room.stop();
        }
    }

    /*
     * createSession()
     * Sinh token UUID bỏ gạch ngang, tạo ServerSession từ ID/tên và đưa vào cả hai map tra cứu. Không tạo
     * account hoặc ghi session ra đĩa.
     */
    public ServerSession createSession(String playerId, String playerName) {
        String token = UUID.randomUUID().toString().replace("-", "");
        ServerSession session = new ServerSession(playerId, token, playerName);
        sessionsByToken.put(token, session);
        sessionsByPlayerId.put(playerId, session);
        return session;
    }

    /*
     * getSessionByToken()
     * Lấy session bằng token trong RAM. Được RESUME và đăng ký UDP dùng; null nghĩa không còn phiên hợp lệ
     * tương ứng.
     */
    public ServerSession getSessionByToken(String token) {
        return sessionsByToken.get(token);
    }

    /*
     * getSessionByPlayerId()
     * Tra session bằng ID player, tránh quét toàn bộ token mỗi gói gameplay.
     */
    public ServerSession getSessionByPlayerId(String playerId) {
        return playerId == null ? null : sessionsByPlayerId.get(playerId);
    }

    /*
     * getServerNetwork()
     * Trả đối tượng network hiện đang gắn với ứng dụng; không mở thêm socket.
     */
    public ServerNetwork getServerNetwork() {
        return serverNetwork;
    }

    /*
     * setServerNetwork()
     * Lưu network mà ServerNetwork.start đăng ký vào app, phục vụ shutdown và phối hợp.
     */
    public void setServerNetwork(ServerNetwork serverNetwork) {
        this.serverNetwork = serverNetwork;
    }

    /*
     * resumeSession()
     * Nhận token cũ, session tạm của kết nối mới và handler mới. Kiểm phiên/phòng/player còn tồn tại, hủy
     * expiry, bỏ session tạm rồi gắn handler mới vào phiên gốc và ServerGame. Trả phòng phục hồi hoặc thất
     * bại theo điều kiện; không biến token tạm thành danh tính cũ.
     * Kiểm token còn trong RAM và có roomId; phòng phải còn giữ player đó. Hủy tác vụ hết hạn, xóa session
     * tạm của TCP mới, gắn handler mới vào session gốc và room. Handler cũ dọn finally chỉ được tháo đúng
     * owner của nó; điều kiện kiểm handler tránh dọn nhầm phiên đã phục hồi.
     */
    public synchronized ServerGame resumeSession(
            String token, ServerSession temporarySession, ServerNetwork.ClientHandler handler) {
        ServerSession session = sessionsByToken.get(token);
        if (session == null || session.getRoomId() == null) {
            return null;
        }

        ServerGame room = rooms.get(session.getRoomId());
        if (room == null || !room.getPlayers().containsKey(session.getPlayerId())) {
            return null;
        }

        ScheduledFuture<?> expiration = sessionExpirations.remove(session.getToken());
        if (expiration != null) {
            /*
             * Hủy lịch hết hạn trước khi chuyển owner sang handler mới.
             */
            expiration.cancel(false);
        }
        if (temporarySession != null) {
            removeSession(temporarySession);
        }

        sessionsByPlayerId.put(session.getPlayerId(), session);
        session.setClientHandler(handler);
        session.touch();
        room.updatePlayerHandler(session.getPlayerId(), handler);
        return room;
    }

    /*
     * handleDisconnect()
     * Chỉ xử lý khi handler ngắt đúng là handler đang sở hữu phiên. Tháo handler khỏi phòng, nhả phím và
     * hẹn expire sau 20 giây nếu còn trong phòng; phiên lobby không cần giữ ghế game. Kiểm owner tránh kết
     * nối cũ dọn nhầm lần resume mới.
     * Chỉ xử lý nếu handler được báo chính là owner session. Khi phiên còn phòng đang chạy, detach handler
     * và lên lịch expire sau 20 giây; nếu đã có lịch cũ thì hủy. Bộ đếm 20 giây bắt đầu sau khi server
     * phát hiện TCP ngắt, không phải sau thời điểm vật lý mất mạng mà server chưa biết.
     */
    public synchronized void handleDisconnect(
            ServerSession session, ServerNetwork.ClientHandler handler) {
        if (session == null || session.getClientHandler() != handler) {
            return;
        }

        session.setClientHandler(null);
        String roomId = session.getRoomId();
        ServerGame room = roomId == null ? null : rooms.get(roomId);
        if (room == null || !room.getPlayers().containsKey(session.getPlayerId()) || !running) {
            removeSession(session);
            return;
        }

        room.detachPlayerHandler(session.getPlayerId(), handler);
        /*
         * Giữ player tối đa 20 giây sau khi phát hiện TCP ngắt, cho client cơ hội RESUME token cũ.
         */
        ScheduledFuture<?> nextExpiration = sessionScheduler.schedule(
                /*
                 * Callback/lambda: Tác vụ hẹn giờ gọi expireSession cho phiên ngắt TCP; bị hủy khi resume
                 * thành công.
                 */
                () -> expireSession(session), RECONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = sessionExpirations.put(session.getToken(), nextExpiration);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /*
     * expireSession()
     * Khi grace hết và phiên chưa nối lại, loại player/phiên, dừng/xóa phòng nếu trống. Nhánh này chưa
     * phát cập nhật lobby ngay như mọi nhánh rời khác, nên client có thể cần ROOM_LIST lại.
     * Nếu session đã có handler mới thì không xóa player. Nếu vẫn offline, removePlayer khỏi phòng, stop
     * phòng khi rỗng, rồi bỏ các chỉ mục session. Save JSON tồn tại độc lập; việc stop phòng sống không
     * xóa file tiến trình.
     */
    private synchronized void expireSession(ServerSession session) {
        sessionExpirations.remove(session.getToken());
        if (session.getClientHandler() != null) {
            return;
        }

        ServerGame room = session.getRoomId() == null ? null : rooms.get(session.getRoomId());
        if (room != null) {
            room.removePlayer(session.getPlayerId());
            if (room.isEmpty()) {
                removeRoom(room.getRoomId());
            }
        }
        removeSession(session);
    }

    /*
     * removeSession()
     * Hủy tác vụ hết hạn và xóa session khỏi cả map token lẫn ID với điều kiện object phù hợp. Không trực
     * tiếp xóa file save nhân vật.
     */
    private void removeSession(ServerSession session) {
        sessionsByToken.remove(session.getToken(), session);
        sessionsByPlayerId.remove(session.getPlayerId(), session);
        ScheduledFuture<?> expiration = sessionExpirations.remove(session.getToken());
        if (expiration != null) {
            expiration.cancel(false);
        }
    }

    /*
     * getRoomListMessage()
     * Ghép chuỗi ROOM_LIST;phòng (n/4);... từ các phòng đang sống và số player của phòng. Không liệt kê
     * mọi JSON save và không đếm các slot lịch sử EMPTY/DISCONNECTED.
     */
    public String getRoomListMessage() {
        StringBuilder message = new StringBuilder(Common.CMD_ROOM_LIST);
        for (ServerGame room : rooms.values()) {
            message.append(";")
                    .append(room.getRoomId())
                    .append(" (")
                    .append(room.getPlayerCount())
                    .append("/")
                    .append(Common.MAX_PLAYERS_PER_ROOM)
                    .append(")");
        }
        return message.toString();
    }
}