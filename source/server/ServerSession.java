package source.server;

import java.net.SocketAddress;

public class ServerSession {
    private final String playerId;
    private final String token;
    private final String playerName;
    private volatile String roomId;
    private volatile ServerNetwork.ClientHandler clientHandler;
    private volatile long lastActiveTime;
    private volatile SocketAddress udpAddr;   // địa chỉ UDP game đã đăng ký (null nếu chưa)
    private volatile long udpLastSeen;

    public ServerSession(String playerId, String token, String playerName) {
        this.playerId = playerId;
        this.token = token;
        this.playerName = playerName;
        this.lastActiveTime = System.currentTimeMillis();
    }

    public String getPlayerId() { return playerId; }
    public String getToken() { return token; }
    public String getPlayerName() { return playerName; }
    public String getRoomId() { return roomId; }
    public ServerNetwork.ClientHandler getClientHandler() { return clientHandler; }
    public long getLastActiveTime() { return lastActiveTime; }

    public SocketAddress getUdpAddr() { return udpAddr; }

    public boolean isUdpAlive() {
        return udpAddr != null
                && System.currentTimeMillis() - udpLastSeen < source.common.GameUdp.UDP_ALIVE_MS;
    }

    /**
     * Gắn địa chỉ UDP. Chưa tính là "sống": chỉ khi client gửi INPUT qua UDP (nghĩa là client đã nhận được
     * REGISTERED, đường xuống thông) thì server mới bắt đầu gửi STATE qua UDP.
     */
    void setUdpAddr(SocketAddress addr) {
        if (!addr.equals(this.udpAddr)) {
            this.udpAddr = addr;
            this.udpLastSeen = 0;
        }
    }

    void touchUdp() {
        this.udpLastSeen = System.currentTimeMillis();
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
        touch();
    }

    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }

    void setClientHandler(ServerNetwork.ClientHandler clientHandler) {
        this.clientHandler = clientHandler;
        touch();
    }
}
