package source.server;

/*
 * Model một phiên kết nối server trong RAM. Liên kết token, playerId, phòng và handler TCP hiện tại. Token
 * này dùng reconnect ngắn hạn, khác tên hiển thị và slot save lâu dài.
 * playerId/token/playerName là final; roomId/clientHandler/lastActiveTime là volatile. lastActiveTime được
 * touch cập nhật nhưng không có nghĩa mọi session tự hết hạn sau một thời gian idle cố định.
 */
public class ServerSession {
    /*
     * playerId: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
     */
    private final String playerId;
    /*
     * token: Token phiên RAM dùng RESUME và đăng ký UDP; không phải mật khẩu tài khoản.
     */
    private final String token;
    /*
     * playerName: Tên hiển thị; save online hiện dùng tên để tìm lại slot, chưa là tài khoản xác thực.
     */
    private final String playerName;
    /*
     * roomId: Định danh hoặc tên phòng; không phải IP server hay token phiên.
     */
    private volatile String roomId;
    /*
     * clientHandler: Handler TCP hiện gắn với phiên hoặc ánh xạ player tới handler.
     */
    private volatile ServerNetwork.ClientHandler clientHandler;
    /*
     * lastActiveTime: Mốc tương tác runtime để theo dõi hoạt động; không tự tạo heartbeat timeout đầy đủ.
     */
    private volatile long lastActiveTime;

    /*
     * Khởi tạo ServerSession
     * Gán ID, token và tên được cấp; ghi lastActiveTime bằng mili giây hiện tại. Chưa tự join phòng hoặc
     * đặt handler.
     */
    public ServerSession(String playerId, String token, String playerName) {
        this.playerId = playerId;
        this.token = token;
        this.playerName = playerName;
        this.lastActiveTime = System.currentTimeMillis();
    }

    /*
     * getPlayerId()
     * Trả ID phiên như P1 để tra entity và endpoint; không phải accountId bền.
     */
    public String getPlayerId() { return playerId; }
    /*
     * getToken()
     * Trả token phiên cho handshake/register/resume; không tự xác minh mật khẩu.
     */
    public String getToken() { return token; }
    /*
     * getPlayerName()
     * Trả tên được giữ lúc tạo session. Trong code tên này không tự đồng bộ thành tên sở hữu save trong
     * mọi bước join.
     */
    public String getPlayerName() { return playerName; }
    /*
     * getRoomId()
     * Trả tên phòng hiện tại hoặc null nếu đang lobby; UDP dùng thông tin này để tìm ServerGame.
     */
    public String getRoomId() { return roomId; }
    /*
     * getClientHandler()
     * Trả handler TCP hiện đang sở hữu phiên, giúp phân biệt disconnect cũ với kết nối resume mới.
     */
    public ServerNetwork.ClientHandler getClientHandler() { return clientHandler; }
    /*
     * getLastActiveTime()
     * Trả mốc hoạt động gần nhất theo System.currentTimeMillis. Đây là dữ liệu theo dõi, không tự tạo
     * heartbeat/timeout.
     */
    public long getLastActiveTime() { return lastActiveTime; }

    /*
     * setRoomId()
     * Đổi liên kết phòng rồi gọi touch; truyền null nghĩa trở về lobby.
     */
    public void setRoomId(String roomId) {
        this.roomId = roomId;
        touch();
    }

    /*
     * touch()
     * Cập nhật lastActiveTime về giờ hiện tại, không gửi packet hoặc save.
     */
    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }

    /*
     * setClientHandler()
     * Gán handler mới và touch; dùng khi accept hoặc chuyển quyền sang handler phục hồi phiên.
     */
    void setClientHandler(ServerNetwork.ClientHandler clientHandler) {
        this.clientHandler = clientHandler;
        touch();
    }
}
