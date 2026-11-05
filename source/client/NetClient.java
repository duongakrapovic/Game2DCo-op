package source.client;

import source.common.Common;
import source.common.Protocol;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tầng mạng phía client (không phụ thuộc Swing).
 *
 *  - Kết nối TCP (TCP_NODELAY, UTF-8), luồng đọc riêng, gửi an toàn đa luồng.
 *  - Heartbeat: gửi PING mỗi giây, nhận PONG => đo RTT (làm mượt bằng EMA) và jitter.
 *    Không nhận được gì trong CLIENT_IDLE_TIMEOUT_MS => coi là rớt mạng.
 *  - Tự động kết nối lại: khi rớt mạng bất ngờ, thử lại nhiều lần và gửi RESUME:<token> để lấy lại
 *    nhân vật trong phòng (server giữ chỗ RECONNECT_GRACE_MS).
 *  - Giả lập độ trễ (mỗi chiều) để demo / đo prediction: -Dnet.lag=<ms>, setLagMs() hoặc phím F4 trong game.
 *  - Thống kê cho HUD: RTT, jitter, STATE/s, KB/s vào-ra.
 *
 * Mọi callback của Listener chạy trên luồng mạng, KHÔNG phải luồng giao diện (Swing EDT).
 */
public class NetClient {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    public interface Listener {
        /** Nhận một dòng từ server (trừ PONG đã được xử lý nội bộ). */
        void onMessage(String line);
        /** Trạng thái kết nối thay đổi. info là mô tả ngắn (vd "lần thử 2"). */
        void onStateChanged(State state, String info);
        /** Mất phiên hẳn (kết nối lại thất bại / phiên hết hạn). */
        void onSessionLost(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private volatile Socket socket;
    private volatile PrintWriter out;
    private final Object writeLock = new Object();

    private volatile State state = State.DISCONNECTED;
    private volatile boolean userClosed = false;
    private volatile String myId;
    private volatile String token;

    private volatile int simLagMs = Integer.getInteger("net.lag", 0);
    private final ScheduledThreadPoolExecutor lagExec = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "Net-LagSim");
        t.setDaemon(true);
        return t;
    });

    // --- thống kê ---
    private volatile double rttMs = -1, jitterMs = 0, lastRttMs = -1;
    private final AtomicLong bytesIn = new AtomicLong(), bytesOut = new AtomicLong(), statesIn = new AtomicLong();
    private volatile double statePerSec, kbpsIn, kbpsOut;
    private volatile int reconnectCount = 0;

    public NetClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        lagExec.setRemoveOnCancelPolicy(true);
    }

    // ------------------------------------------------------------------ kết nối

    /** Kết nối lần đầu (chặn tới khi xong hoặc lỗi) — gọi ngoài luồng giao diện. */
    public void connect() throws IOException {
        setState(State.CONNECTING, host);
        Socket s = openSocket();
        install(s, new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)));
        setState(State.CONNECTED, host);
        startHeartbeat();
    }

    private Socket openSocket() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(Protocol.CLIENT_IDLE_TIMEOUT_MS);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private void install(Socket s, BufferedReader in) throws IOException {
        PrintWriter w = new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)), false);
        synchronized (writeLock) {
            socket = s;
            out = w;
        }
        Thread t = new Thread(() -> readLoop(s, in), "Net-Reader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(Socket s, BufferedReader in) {
        try {
            String line;
            while ((line = Protocol.readLineLimited(in, Protocol.MAX_LINE_LENGTH_CLIENT)) != null) {
                bytesIn.addAndGet(line.length() + 1L);
                final String l = line;
                if (simLagMs > 0 || !lagExec.getQueue().isEmpty()) {
                    try {
                        lagExec.schedule(() -> process(l), simLagMs, TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException closing) {
                        return; // NetClient đang đóng
                    }
                } else {
                    process(l);
                }
            }
        } catch (IOException ignored) {
            // timeout hoặc socket đóng -> xử lý bên dưới
        }
        onConnectionLost(s);
    }

    private void process(String line) {
        if (line.startsWith(Common.CMD_PONG + ":")) {
            try {
                long sent = Long.parseLong(line.substring(Common.CMD_PONG.length() + 1));
                double rtt = (System.nanoTime() - sent) / 1_000_000.0;
                if (lastRttMs >= 0) jitterMs += (Math.abs(rtt - lastRttMs) - jitterMs) / 8.0; // jitter kiểu RFC 3550
                lastRttMs = rtt;
                rttMs = (rttMs < 0) ? rtt : rttMs * 0.8 + rtt * 0.2;
            } catch (NumberFormatException ignored) {}
            return;
        }
        if (line.startsWith(Common.CMD_WELCOME + ":")) {
            String[] p = line.split(":");
            if (p.length >= 3) { myId = p[1]; token = p[2]; }
        } else if (line.startsWith(Common.CMD_STATE)) {
            statesIn.incrementAndGet();
        }
        listener.onMessage(line);
    }

    // ------------------------------------------------------------------ gửi

    /** Gửi một dòng lệnh. Bị bỏ qua (im lặng) nếu đang không kết nối. */
    public void send(String msg) {
        if (simLagMs > 0 || !lagExec.getQueue().isEmpty()) {
            try {
                lagExec.schedule(() -> rawSend(msg), simLagMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException closing) {
                // NetClient đang đóng: bỏ qua
            }
        } else {
            rawSend(msg);
        }
    }

    private void rawSend(String msg) {
        Socket s;
        synchronized (writeLock) {
            PrintWriter w = out;
            s = socket;
            if (w == null || state != State.CONNECTED) return;
            w.print(msg);
            w.print('\n');
            w.flush();
            if (w.checkError()) {
                closeQuietly(s);
                return;
            }
        }
        bytesOut.addAndGet(msg.length() + 1L);
    }

    // ------------------------------------------------------------------ mất kết nối & kết nối lại

    private void onConnectionLost(Socket s) {
        if (s != socket) return; // socket cũ đã được thay thế
        synchronized (writeLock) { out = null; }
        closeQuietly(s);
        if (userClosed) { setState(State.DISCONNECTED, "closed"); return; }
        if (token == null) { // chưa kịp có phiên -> không thể RESUME
            setState(State.DISCONNECTED, "lost");
            listener.onSessionLost("Mất kết nối tới server.");
            return;
        }
        setState(State.RECONNECTING, "đang thử lại...");
        Thread t = new Thread(this::reconnectLoop, "Net-Reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void reconnectLoop() {
        long deadline = System.currentTimeMillis() + Protocol.CLIENT_RECONNECT_WINDOW_MS;
        int attempt = 0;
        while (!userClosed && System.currentTimeMillis() < deadline) {
            attempt++;
            setState(State.RECONNECTING, "lần thử " + attempt);
            Socket ns = null;
            try {
                ns = openSocket();
                BufferedReader in = new BufferedReader(new InputStreamReader(ns.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter w = new PrintWriter(new BufferedWriter(new OutputStreamWriter(ns.getOutputStream(), StandardCharsets.UTF_8)), false);
                w.print(Common.CMD_RESUME + ":" + token + "\n");
                w.flush();

                // Server gửi WELCOME(tạm) + ROOM_LIST trước, rồi mới trả lời RESUME bằng WELCOME + RESUMED / RESUME_FAIL
                ns.setSoTimeout(3000);
                String line;
                while ((line = Protocol.readLineLimited(in, Protocol.MAX_LINE_LENGTH_CLIENT)) != null) {
                    if (line.startsWith(Common.CMD_RESUMED + ":")) {
                        reconnectCount++;
                        ns.setSoTimeout(Protocol.CLIENT_IDLE_TIMEOUT_MS);
                        install(ns, in);
                        setState(State.CONNECTED, "đã kết nối lại");
                        // process() lo WELCOME; RESUMED chuyển cho ứng dụng để đồng bộ lại vị trí
                        listener.onMessage(line);
                        return;
                    }
                    if (line.startsWith(Common.CMD_RESUME_FAIL) || line.startsWith(Common.CMD_ERROR)) {
                        closeQuietly(ns);
                        giveUp("Phiên đã hết hạn, không thể vào lại phòng.");
                        return;
                    }
                    if (line.startsWith(Common.CMD_WELCOME + ":")) {
                        String[] p = line.split(":");
                        if (p.length >= 3 && p[2].equals(token)) myId = p[1]; // WELCOME của phiên cũ
                    }
                }
            } catch (IOException e) {
                // thử lại
            }
            closeQuietly(ns);
            try { Thread.sleep(Math.min(attempt * 1000L, 3000L)); } catch (InterruptedException e) { return; }
        }
        if (!userClosed) giveUp("Không thể kết nối lại tới server.");
    }

    private void giveUp(String reason) {
        setState(State.DISCONNECTED, reason);
        listener.onSessionLost(reason);
    }

    /** Dùng cho demo/test: cắt đứt socket như khi rớt mạng thật (client sẽ tự kết nối lại). */
    public void simulateConnectionDrop() {
        closeQuietly(socket);
    }

    public void close() {
        userClosed = true;
        synchronized (writeLock) { out = null; }
        closeQuietly(socket);
        lagExec.shutdownNow();
        setState(State.DISCONNECTED, "closed");
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    private void setState(State s, String info) {
        state = s;
        listener.onStateChanged(s, info);
    }

    // ------------------------------------------------------------------ heartbeat + thống kê

    private void startHeartbeat() {
        Thread t = new Thread(() -> {
            long pBi = 0, pBo = 0, pSt = 0, pT = System.nanoTime();
            while (!userClosed) {
                try { Thread.sleep(Protocol.PING_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (state == State.CONNECTED) send(Common.CMD_PING + ":" + System.nanoTime());

                long now = System.nanoTime();
                double dt = (now - pT) / 1e9;
                pT = now;
                long bi = bytesIn.get(), bo = bytesOut.get(), st = statesIn.get();
                kbpsIn = (bi - pBi) / dt / 1024.0;
                kbpsOut = (bo - pBo) / dt / 1024.0;
                statePerSec = (st - pSt) / dt;
                pBi = bi; pBo = bo; pSt = st;
            }
        }, "Net-Heartbeat");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ getters

    public State getState() { return state; }
    public String getMyId() { return myId; }
    public double getRttMs() { return rttMs; }
    public double getJitterMs() { return jitterMs; }
    public double getStatePerSec() { return statePerSec; }
    public double getKbpsIn() { return kbpsIn; }
    public double getKbpsOut() { return kbpsOut; }
    public int getReconnectCount() { return reconnectCount; }
    public int getLagMs() { return simLagMs; }
    public void setLagMs(int ms) { simLagMs = Math.max(0, ms); }
}
