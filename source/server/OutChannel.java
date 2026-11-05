package source.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Kênh gửi dữ liệu tới MỘT client, chạy bằng luồng riêng.
 *
 * Vì sao cần: trước đây game loop của phòng gọi thẳng out.println() cho từng client; nếu một client
 * mạng chậm (bộ đệm TCP đầy) thì println() bị chặn và CẢ PHÒNG bị đứng. Giờ game loop chỉ bỏ message
 * vào hàng đợi (không bao giờ chặn); luồng của kênh này mới là luồng thực sự ghi ra socket.
 *
 *  - sendReliable(): message điều khiển (ERROR, ROOM_LIST, PONG...) — gửi đủ, đúng thứ tự, ưu tiên cao.
 *  - sendState():    snapshot trạng thái game — chỉ cần bản MỚI NHẤT; nếu client chưa kịp nhận bản cũ
 *                    thì bản cũ bị thay thế (coalescing) thay vì dồn ứ hàng đợi.
 */
public class OutChannel implements Runnable {
    private static final int MAX_CONTROL_BACKLOG = 1000;

    private final Socket socket;
    private final PrintWriter out;
    private final ArrayDeque<String> control = new ArrayDeque<>();
    private String latestState;
    private volatile boolean closed = false;

    public OutChannel(Socket socket, String label) throws IOException {
        this.socket = socket;
        this.out = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), false);
        Thread t = new Thread(this, "Out-" + label);
        t.setDaemon(true);
        t.start();
    }

    /** Tương thích với code cũ dùng PrintWriter.println(). */
    public void println(String msg) { sendReliable(msg); }

    public synchronized void sendReliable(String msg) {
        if (closed) return;
        if (control.size() >= MAX_CONTROL_BACKLOG) { // client không đọc nữa -> ngắt
            close();
            closeSocketQuietly();
            return;
        }
        control.add(msg);
        notifyAll();
    }

    public synchronized void sendState(String msg) {
        if (closed) return;
        if (latestState != null) ServerStats.statesCoalesced.incrementAndGet();
        latestState = msg;
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public boolean isClosed() { return closed; }

    @Override
    public void run() {
        while (true) {
            String msg;
            synchronized (this) {
                while (!closed && control.isEmpty() && latestState == null) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
                if (!control.isEmpty()) {
                    msg = control.poll();
                } else {
                    msg = latestState;
                    latestState = null;
                }
            }
            out.print(msg);
            out.print('\n');
            out.flush();
            if (out.checkError()) {
                close();
                closeSocketQuietly();
                return;
            }
            ServerStats.msgsOut.incrementAndGet();
            ServerStats.bytesOut.addAndGet(msg.length() + 1L);
        }
    }

    private void closeSocketQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
