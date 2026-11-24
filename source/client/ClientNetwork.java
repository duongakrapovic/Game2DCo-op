package source.client;

import source.common.Common;
import source.common.GameProtocol;
import source.common.GameData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CLIENTNETWORK.JAVA - Quản lý TCP (phòng, session) & UDP (Gameplay: INPUT, STATE, PING/PONG)
 */
/*
 * Mạng client: mở TCP phiên/phòng, giữ socket UDP gameplay, đăng ký endpoint, nhận binary snapshot và đo
 * RTT/jitter. Không chạm trực tiếp component Swing; báo ra Listener để app chọn thread/UI phù hợp.
 * State gồm CONNECTING/CONNECTED/RECONNECTING/CLOSED. tcpReady, roomJoined, udpRegistered, gameplayActive
 * và resuming phân biệt các mức sẵn sàng. snapshotDecoder giữ baseline; sessionToken/myId ở RAM;
 * gameplayGeneration lọc ACK cũ. lagScheduler là công cụ giả lập trễ, không tự đo ping.
 */
public class ClientNetwork {
    /*
     * Trạng thái vòng TCP; CONNECTED chưa đồng nghĩa UDP gameplay sẵn sàng.
     * CONNECTING, CONNECTED, RECONNECTING, CLOSED: Một giá trị enum State của vòng kết nối TCP.
     */
    public enum State { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

    /*
     * Hợp đồng callback từ mạng về ứng dụng; UI phải chuyển việc sửa Swing sang EDT.
     */
    public interface Listener {
        /*
         * onMessage()
         * Hợp đồng Listener nhận một dòng TCP đã đọc. Interface không định nghĩa logic UI; ClientApp hoặc
         * test cung cấp body thực tế.
         */
        void onMessage(String line);
        /*
         * onSnapshot()
         * Default method của Listener biến typed snapshot thành STATE văn bản rồi gọi onMessage cho
         * listener cũ. ClientApp override để nhận object trực tiếp; không có nghĩa STATE đang được vận
         * chuyển qua TCP.
         */
        default void onSnapshot(GameData.WorldSnapshot snapshot) { onMessage(GameProtocol.serializeSnapshot(snapshot)); }
        /*
         * onStateChanged()
         * Hợp đồng callback báo State cùng thông tin. Caller phải quyết định đưa cập nhật UI sang EDT.
         */
        void onStateChanged(State s, String info);
        /*
         * onSessionLost()
         * Hợp đồng báo token/phiên không thể tiếp tục, để app thoát trạng thái chơi và xử lý lại.
         */
        void onSessionLost(String reason);
    }

    /*
     * host: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private final String host;
    /*
     * port: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private final int port;
    /*
     * listener: Callback nhận kết quả mạng hoặc trạng thái; caller quyết định chuyển sang EDT.
     */
    private final Listener listener;

    /*
     * socket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
     */
    private volatile Socket socket;
    /*
     * out: Reader và writer của dòng TCP; thông điệp được phân định bằng newline UTF-8.
     */
    private PrintWriter out;
    /*
     * in: Reader và writer của dòng TCP; thông điệp được phân định bằng newline UTF-8.
     */
    private BufferedReader in;
    /*
     * state: Trạng thái TCP, handshake, RESUME, đăng ký UDP và khả năng gameplay; không đồng nghĩa nhau.
     */
    private volatile State state = State.CLOSED;
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile boolean running = false;

    // UDP Gameplay socket & threads
    /*
     * gameUdpSocket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
     */
    private volatile DatagramSocket gameUdpSocket;
    /*
     * snapshotDecoder: Bộ ghép mảnh và giải mã snapshot đầy đủ, có kiểm stream/sequence/baseline.
     */
    private final GameProtocol.SnapshotDecoder snapshotDecoder = new GameProtocol.SnapshotDecoder();
    /*
     * gameplayActive: Trạng thái TCP, handshake, RESUME, đăng ký UDP và khả năng gameplay; không đồng
     * nghĩa nhau.
     */
    private volatile boolean gameplayActive;
    /*
     * resuming: Trạng thái TCP, handshake, RESUME, đăng ký UDP và khả năng gameplay; không đồng nghĩa
     * nhau.
     */
    private volatile boolean resuming;
    /*
     * tcpReady: Trạng thái TCP, handshake, RESUME, đăng ký UDP và khả năng gameplay; không đồng nghĩa
     * nhau.
     */
    private volatile boolean tcpReady;
    /*
     * serverAddress: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private InetAddress serverAddress;
    /*
     * udpReceiverThread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy
     * lớp.
     */
    private Thread udpReceiverThread;
    /*
     * udpHeartbeatThread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy
     * lớp.
     */
    private Thread udpHeartbeatThread;
    /*
     * udpRegistered: Trạng thái TCP, handshake, RESUME, đăng ký UDP và khả năng gameplay; không đồng nghĩa
     * nhau.
     */
    private volatile boolean udpRegistered = false;
    /*
     * gameplayGeneration: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile long gameplayGeneration;
    /*
     * roomJoined: Cờ đã vào phòng ở tầng mạng hoặc tầng giao diện; cần phân biệt với UDP ready.
     */
    private volatile boolean roomJoined;

    /*
     * myId: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
     */
    private volatile String myId = null;
    /*
     * sessionToken: Token phiên RAM dùng RESUME và đăng ký UDP; không phải mật khẩu tài khoản.
     */
    private volatile String sessionToken = null;
    /*
     * reconnectCount: Độ trễ giả lập, scheduler trì hoãn tác vụ và bộ đếm thiết lập TCP thành công.
     */
    private long reconnectCount = 0;

    /*
     * rttMs: RTT gameplay và trung bình mượt độ biến thiên RTT, đơn vị mili giây.
     */
    private volatile double rttMs = 0;
    /*
     * jitterMs: RTT gameplay và trung bình mượt độ biến thiên RTT, đơn vị mili giây.
     */
    private volatile double jitterMs = 0;

    /*
     * simulatedLagMs: Độ trễ giả lập, scheduler trì hoãn tác vụ và bộ đếm thiết lập TCP thành công.
     */
    private volatile int simulatedLagMs = 0;
    /*
     * lagScheduler: Độ trễ giả lập, scheduler trì hoãn tác vụ và bộ đếm thiết lập TCP thành công.
     */
    private final ScheduledExecutorService lagScheduler = Executors.newScheduledThreadPool(1);

    /*
     * Khởi tạo ClientNetwork
     * Lưu host/port/listener. Các field tạo decoder và scheduler, nhưng constructor chưa DNS/connect hoặc
     * start thread mạng.
     */
    public ClientNetwork(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /*
     * connect()
     * Synchronized ngăn start lặp; đặt running, báo CONNECTING và khởi động NetClient-Loop chạy
     * connectionLoop. Trả ngay để không block giao diện.
     */
    public synchronized void connect() {
        if (running) return;
        running = true;
        changeState(State.CONNECTING, "Đang kết nối tới " + host + ":" + port);

        new Thread(this::connectionLoop, "NetClient-Loop").start();
    }

    /*
     * initializeUdp()
     * Nếu socket UDP cũ còn mở thì tái dùng, nếu chưa thì tạo DatagramSocket, connect endpoint server và
     * start hai daemon thread nhận/heartbeat. connect của UDP chọn peer, không là handshake tin cậy như
     * TCP.
     */
    private synchronized void initializeUdp() throws SocketException {
        if (gameUdpSocket != null && !gameUdpSocket.isClosed()) return;
        gameUdpSocket = new DatagramSocket();
        gameUdpSocket.connect(serverAddress, port);
        udpReceiverThread = new Thread(this::udpReceiveLoop, "UDP-Game-Receiver");
        udpReceiverThread.setDaemon(true); udpReceiverThread.start();
        udpHeartbeatThread = new Thread(this::udpHeartbeatLoop, "UDP-Game-Heartbeat");
        udpHeartbeatThread.setDaemon(true); udpHeartbeatThread.start();
    }

    /*
     * isReady()
     * Trả tcpReady && running. Đây là đã qua mức chào/resume TCP cần thiết, không đồng nghĩa
     * gameplayActive.
     */
    public boolean isReady() { return tcpReady && running; }

    /*
     * requestGameUdpRegistration()
     * Chỉ gửi khi TCP ready, không resuming, đã có phòng và token. Bỏ cờ udpRegistered rồi gửi REGISTER
     * token|generation ngay; ACK tới sau mới bật gameplay.
     */
    public void requestGameUdpRegistration() {
        if (!isReady() || resuming || !roomJoined || sessionToken == null) return;
        udpRegistered = false;
        sendUdpRaw(GameProtocol.createGameUdpPacket(Common.UDP_GAME_REGISTER,
                sessionToken + "|" + gameplayGeneration));
    }

    /*
     * connectionLoop()
     * Vòng TCP trên thread riêng: retry chờ 3/6/9 giây rồi tối đa 10, connect timeout 5 giây, tạo UTF-8
     * reader/writer và tái dùng UDP. Có phiên phòng cũ thì gửi RESUME và không để WELCOME tạm ghi đè
     * token. JOINED/RESUMED reset decoder/tăng generation/đăng ký UDP; LEFT hoặc RESUME_FAIL vô hiệu
     * gameplay. EOF/lỗi đóng phần TCP và thử lại khi running; phiên hết hạn báo Listener rồi close.
     * Mở TCP timeout 5 giây, tạo reader/writer UTF-8, giữ gameplay UDP socket nếu còn. Reconnect trong
     * phòng gửi RESUME token gốc và không nhận WELCOME tạm làm ID/token mới. JOINED/RESUMED reset decoder
     * và đổi generation rồi đăng ký UDP; EOF hoặc exception đóng TCP, vô hiệu gameplay và thử lại theo
     * backoff.
     */
    private void connectionLoop() {
        int attempt = 0;
        while (running) {
            try {
                if (attempt > 0) {
                    changeState(State.RECONNECTING, "Mất kết nối - đang kết nối lại (lần thứ " + attempt + ")...");
                    Thread.sleep(Math.min(3000L * attempt, 10000L));
                }

                if (!running) break;
                Socket connected = new Socket();
                socket = connected;
                /*
                 * Timeout này giới hạn mở TCP; không phải timeout riêng cho việc mất UDP gameplay.
                 */
                connected.connect(new java.net.InetSocketAddress(host, port), 5000);
                if (!running) { connected.close(); break; }
                socket.setTcpNoDelay(true);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                serverAddress = connected.getInetAddress();
                try { initializeUdp(); }
                catch (SocketException udpError) { System.err.println("[ClientNetwork] UDP: " + udpError.getMessage()); }
                tcpReady = false;
                /*
                 * Có token và đang ở phòng thì xin phục hồi phiên, không thay token gốc bằng WELCOME tạm.
                 */
                resuming = sessionToken != null && roomJoined;
                if (resuming) {
                    out.print("RESUME:" + sessionToken + "\n");
                    out.flush();
                }

                reconnectCount++;
                udpRegistered = false;

                String line;
                while ((line = GameProtocol.readLineLimited(in, GameProtocol.MAX_LINE_LENGTH_SERVER)) != null) {
                    final String msg = line;
                    if (msg.startsWith("WELCOME:") && !resuming) {
                        String[] p = msg.split(":");
                        if (p.length >= 2) myId = p[1];
                        if (p.length >= 3) sessionToken = p[2];
                        if (myId != null && sessionToken != null) {
                            tcpReady = true;
                            attempt = 0;
                            changeState(State.CONNECTED, "Da ket noi Server");
                        }
                    }

                    /*
                     * Đổi vòng đời phòng: xóa decoder cũ, tăng generation và đăng ký lại UDP.
                     */
                    if (msg.startsWith("JOINED:") || msg.startsWith("RESUMED:")) {
                        snapshotDecoder.reset();
                        gameplayGeneration++;
                        roomJoined = true;
                        gameplayActive = false;
                        resuming = false;
                        udpRegistered = false;
                        tcpReady = true;
                        attempt = 0;
                        if (state != State.CONNECTED) changeState(State.CONNECTED, "Da khoi phuc phien");
                        requestGameUdpRegistration();
                    } else if ("LEFT".equals(msg) || "RESUME_FAIL".equals(msg)) {
                        gameplayActive = false;
                        roomJoined = false;
                        gameplayGeneration++;
                        snapshotDecoder.reset();
                        if ("RESUME_FAIL".equals(msg)) {
                            sessionToken = null; myId = null; resuming = false;
                            listener.onSessionLost("Session expired");
                            close();
                            break;
                        }
                    }
                    if (simulatedLagMs > 0) {
                        /*
                         * Callback/lambda: Tác vụ được LagScheduler thực thi sau simulatedLagMs: gọi
                         * listener.onMessage. Nó không có nghĩa toàn bộ đường UDP STATE nhận bị trì hoãn
                         * tương tự.
                         */
                        lagScheduler.schedule(() -> listener.onMessage(msg), simulatedLagMs, TimeUnit.MILLISECONDS);
                    } else {
                        listener.onMessage(msg);
                    }
                }
                if (running) attempt++;
            } catch (Exception e) {
                if (!running) break;
                System.err.println("[ClientNetwork] Connection error: " + e.getMessage());
                attempt++;
                changeState(State.RECONNECTING, "Khong ket noi duoc Server: " + e.getMessage());
            } finally {
                closeQuietly();
            }
        }
        changeState(State.CLOSED, "Đã đóng kết nối");
    }

    // ==========================================
    // UDP GAMEPLAY RECEIVE & HEARTBEAT
    // ==========================================

    /*
     * udpReceiveLoop()
     * Nhận GAM1; STATE chỉ decode khi gameplay active và đủ mảnh, rồi báo onSnapshot. ACK phải khớp
     * playerId/generation/state, đặt stream mong đợi và kích hoạt game. PONG tính RTT từ nanoTime echo,
     * jitter là làm mượt chênh RTT. SocketException khi socket còn sống làm hủy đăng ký để thử lại, không
     * luôn chấm dứt receiver.
     * ACK cần khớp ID, generation và trạng thái không resuming; có phòng thì lấy streamId cho decoder và
     * bật gameplayActive. STATE chỉ xử lý khi gameplay active và đủ mảnh. PONG trả timestamp client để
     * tính RTT trên cùng đồng hồ; khác với ping của voice.
     */
    private void udpReceiveLoop() {
        byte[] buffer = new byte[4096];
        while (running) {
            DatagramSocket ds = gameUdpSocket;
            if (ds == null || ds.isClosed()) break;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                ds.receive(packet);
                if (!GameProtocol.isGameUdpPacket(packet.getData(), packet.getLength())) {
                    continue;
                }
                byte type = GameProtocol.getGameUdpType(packet.getData());
                /*
                 * STATE là byte nhị phân; chỉ snapshot ghép đủ và đúng baseline mới được phát về ứng dụng.
                 */
                if (type == Common.UDP_GAME_STATE) {
                    if (!gameplayActive) continue;
                    GameData.WorldSnapshot snapshot = snapshotDecoder.accept(packet.getData(),
                            packet.getOffset() + GameProtocol.UDP_HEADER_BYTES,
                            packet.getLength() - GameProtocol.UDP_HEADER_BYTES);
                    if (snapshot != null) listener.onSnapshot(snapshot);
                    continue;
                }
                String payload = GameProtocol.getGameUdpPayload(packet.getData(), packet.getOffset(), packet.getLength());

                if (type == Common.UDP_GAME_ACK) {
                    String[] ack = payload.split("\\|", -1);
                    /*
                     * ACK phải đúng ID và generation hiện hành để gói trễ của phòng cũ không bật gameplay
                     * nhầm.
                     */
                    if (ack.length == 3 && ack[0].equals(myId) && !resuming && state == State.CONNECTED
                            && ack[2].equals(Long.toString(gameplayGeneration))) {
                        if (roomJoined && ack[1].isEmpty()) continue;
                        udpRegistered = true;
                        if (roomJoined) {
                            try {
                                snapshotDecoder.expectStream(Long.parseLong(ack[1]));
                                gameplayActive = true;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } else if (type == Common.UDP_GAME_PONG) {
                    try {
                        long sent = Long.parseLong(payload.trim());
                        /*
                         * Đo RTT bằng hai mốc nanoTime trên cùng client, không cần đồng bộ đồng hồ với
                         * server.
                         */
                        double rtt = (System.nanoTime() - sent) / 1e6;
                        jitterMs = Math.abs(rtt - rttMs) * 0.2 + jitterMs * 0.8;
                        rttMs = rtt;
                    } catch (Exception ignored) {}
                }
            } catch (SocketException e) {
                if (!running || ds.isClosed()) break;
                udpRegistered = false;
                gameplayActive = false;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ClientNetwork] Loi nhan UDP: " + e.getMessage());
                }
            }
        }
    }

    /*
     * udpHeartbeatLoop()
     * Mỗi vòng ngủ 100 ms; khi có phiên cần đăng ký thì thử REGISTER cách nhau ít nhất 1,5 giây; đã
     * registered gửi PING khoảng một giây. Chưa có watchdog đầy đủ để tự coi im PONG/STATE lâu là mất
     * gameplay.
     * Chạy kiểm mốc mỗi 100 ms; khi chưa registered thì thử REGISTER cách ít nhất 1500 ms; khi đã
     * registered thì PING khoảng mỗi giây. Không có watchdog đầy đủ tự đặt udpRegistered=false chỉ vì lâu
     * không có PONG hoặc STATE, nên mất riêng UDP chưa chắc được báo reconnect.
     */
    private void udpHeartbeatLoop() {
        long lastRegisterAttempt = 0;
        long lastPingAttempt = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                /*
                 * REGISTER được thử lại khi chưa có ACK; khi đã đăng ký thì gửi PING định kỳ.
                 */
                if (sessionToken != null && !resuming && !udpRegistered && state == State.CONNECTED) {
                    if (now - lastRegisterAttempt >= 1500) {
                        lastRegisterAttempt = now;
                        byte[] regPacket = GameProtocol.createGameUdpPacket(Common.UDP_GAME_REGISTER,
                                sessionToken + "|" + gameplayGeneration);
                        sendUdpRaw(regPacket);
                    }
                } else if (udpRegistered) {
                    if (now - lastPingAttempt >= 1000) {
                        lastPingAttempt = now;
                        byte[] pingPacket = GameProtocol.createGameUdpPacket(Common.UDP_GAME_PING, String.valueOf(System.nanoTime()));
                        sendUdpRaw(pingPacket);
                    }
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {}
        }
    }

    /*
     * sendGameInputUdp()
     * Nhận chuỗi seq/phím/attack, thêm ping RTT làm tròn, bọc INPUT GAM1 rồi gửi ngay hoặc hẹn theo
     * simulatedLagMs. Không thêm stream/generation vào INPUT trong bản hiện tại.
     */
    public void sendGameInputUdp(String inputPayload) {
        if (serverAddress == null || gameUdpSocket == null || gameUdpSocket.isClosed()) return;
        int ping = (int) Math.round(rttMs);
        String fullPayload = inputPayload + ":" + ping;
        byte[] packetData = GameProtocol.createGameUdpPacket(Common.UDP_GAME_INPUT, fullPayload);
        if (simulatedLagMs > 0) {
            /*
             * Callback/lambda: Tác vụ được LagScheduler thực thi sau simulatedLagMs: gọi sendUdpRaw. Nó
             * không có nghĩa toàn bộ đường UDP STATE nhận bị trì hoãn tương tự.
             */
            lagScheduler.schedule(() -> sendUdpRaw(packetData), simulatedLagMs, TimeUnit.MILLISECONDS);
        } else {
            sendUdpRaw(packetData);
        }
    }

    /*
     * sendUdpRaw()
     * Gửi byte[] bằng socket gameplay tới server đã biết khi còn mở. Lỗi chỉ log nếu vẫn chạy; không
     * ACK/retransmit từng input ở đây.
     */
    private void sendUdpRaw(byte[] data) {
        DatagramSocket ds = gameUdpSocket;
        if (ds == null || ds.isClosed() || serverAddress == null) return;
        try {
            ds.send(new DatagramPacket(data, data.length, serverAddress, port));
        } catch (IOException e) {
            if (running) {
                System.err.println("[ClientNetwork] Loi gui UDP game: " + e.getMessage());
            }
        }
    }

    // ==========================================
    // TCP COMMAND SENDING
    // ==========================================

    /*
     * send()
     * Gửi lệnh TCP ngay hoặc schedule sendRaw theo lag giả lập. Không tự sanitize hay tạo CREATE_ROOM cho
     * caller.
     */
    public void send(String msg) {
        if (simulatedLagMs > 0) {
            /*
             * Callback/lambda: Tác vụ được LagScheduler thực thi sau simulatedLagMs: gọi sendRaw. Nó không
             * có nghĩa toàn bộ đường UDP STATE nhận bị trì hoãn tương tự.
             */
            lagScheduler.schedule(() -> sendRaw(msg), simulatedLagMs, TimeUnit.MILLISECONDS);
        } else {
            sendRaw(msg);
        }
    }

    /*
     * sendRaw()
     * Synchronized kiểm running/writer/socket, ghi msg + LF rồi flush để các lời gửi không xen dòng.
     */
    private synchronized void sendRaw(String msg) {
        if (running && out != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            out.print(msg + "\n");
            out.flush();
        }
    }

    /*
     * simulateConnectionDrop()
     * Đóng TCP hiện tại để vòng reconnect phát hiện lỗi; không chủ động đóng UDP. Là công cụ test, không
     * phải rời phòng có LEAVE_ROOM.
     */
    public void simulateConnectionDrop() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    /*
     * setLagMs()
     * Gán simulatedLagMs; tác động tới các nhánh schedule giả lập trễ, không thay độ trễ thật hoặc giá trị
     * RTT đo từ mạng.
     */
    public void setLagMs(int ms) { this.simulatedLagMs = ms; }
    /*
     * getLagMs()
     * Trả độ trễ giả lập đang cấu hình.
     */
    public int getLagMs() { return simulatedLagMs; }
    /*
     * getRttMs()
     * Trả phép đo RTT gameplay mới nhất bằng mili giây.
     */
    public double getRttMs() { return rttMs; }
    /*
     * getJitterMs()
     * Trả chỉ số làm mượt biến thiên RTT; không phải jitter buffer voice.
     */
    public double getJitterMs() { return jitterMs; }

    /*
     * getMyId()
     * Trả ID phiên đang được WELCOME hoặc RESUME giữ; null nếu chưa có/hết phiên.
     */
    public String getMyId() { return myId; }
    /*
     * getSessionToken()
     * Trả token RAM để đăng ký gameplay/voice hoặc resume; không đọc từ save JSON.
     */
    public String getSessionToken() { return sessionToken; }
    /*
     * getState()
     * Trả State của vòng kết nối TCP.
     */
    public State getState() { return state; }
    /*
     * isGameplayActive()
     * Trả cờ UDP đã đăng ký đúng stream và sẵn dùng cho game; sendFrameInput dựa cờ này.
     */
    public boolean isGameplayActive() { return gameplayActive; }
    /*
     * getGameplayGeneration()
     * Trả thế hệ gameplay hiện tại, để ACK và callback snapshot cũ không lẫn vào lượt mới.
     */
    public long getGameplayGeneration() { return gameplayGeneration; }
    /*
     * getReconnectCount()
     * Trả bộ đếm các lần thiết lập kết nối TCP của connectionLoop; lần kết nối đầu cũng tăng nên không chỉ
     * đếm các lần thất bại/phục hồi.
     */
    public long getReconnectCount() { return reconnectCount; }

    /*
     * changeState()
     * Lưu state mới và gọi Listener.onStateChanged với info. Không đảm bảo callback đang ở EDT.
     */
    private void changeState(State s, String info) {
        this.state = s;
        listener.onStateChanged(s, info);
    }

    /*
     * close()
     * Synchronized dừng running/gameplay, đóng TCP, đóng UDP, interrupt/null thread nhận và heartbeat rồi
     * shutdown scheduler. Đây là đóng toàn client network.
     */
    public synchronized void close() {
        running = false;
        gameplayActive = false;
        closeQuietly();
        DatagramSocket udp = gameUdpSocket;
        if (udp != null) udp.close();
        gameUdpSocket = null;
        if (udpReceiverThread != null) {
            udpReceiverThread.interrupt();
            udpReceiverThread = null;
        }
        if (udpHeartbeatThread != null) {
            udpHeartbeatThread.interrupt();
            udpHeartbeatThread = null;
        }
        lagScheduler.shutdownNow();
    }

    /*
     * closeQuietly()
     * Đóng phần TCP nếu có, reset tcpReady/udpRegistered/gameplayActive và tăng generation. Không đóng
     * UDP, cho phép resume tiếp tục dùng endpoint/receiver.
     */
    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        tcpReady = false;
        udpRegistered = false;
        gameplayActive = false;
        gameplayGeneration++;
    }
}
