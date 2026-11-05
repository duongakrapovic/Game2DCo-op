package source.server;

import java.net.Socket;
import java.util.concurrent.ScheduledFuture;

/**
 * Phiên của một người chơi trên server. Tồn tại độc lập với socket: khi socket rớt, phiên (và nhân vật
 * trong phòng) được giữ lại trong thời gian ân hạn (Protocol.RECONNECT_GRACE_MS) để client có thể
 * kết nối lại bằng token (lệnh RESUME) mà không mất chỗ.
 */
public class Session {
    public final String token;
    public final String pId;

    public volatile GameRoom room;      // null nếu đang ở sảnh
    public volatile OutChannel channel; // kênh gửi của kết nối hiện tại
    public volatile Socket socket;
    public volatile boolean connected;
    public volatile ScheduledFuture<?> graceTask;

    public Session(String token, String pId) {
        this.token = token;
        this.pId = pId;
    }
}
