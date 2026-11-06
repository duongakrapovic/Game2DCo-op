package source.server;

public class ServerSession {
    private final String playerId;
    private final String token;
    private final String playerName;
    private volatile String roomId;
    private volatile ServerNetwork.ClientHandler clientHandler;
    private volatile long lastActiveTime;

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
