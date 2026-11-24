package source.server;

import source.common.Common;
import source.common.GameData;
import source.common.GameProtocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SERVERNETWORK.JAVA - Quản lý TCP (kết nối, phòng) & UDP đa luồng (Voice VCH1, Gameplay GAM1)
 */
/*
 * Sở hữu socket TCP/UDP của server và chuyển thông điệp tới app/phòng. Lớp lồng ClientHandler quản lý từng
 * kết nối TCP. UDP dispatcher phân biệt GAM1 gameplay với VCH1 voice, không cần hai cổng UDP khác nhau.
 * connectedClients giữ handler; map hai chiều address↔player cho gameplay/voice và room/handler voice. Một
 * pool bốn worker game, hai worker voice; fixedThreadPool hiện có queue mặc định chưa bounded.
 * playerCounter sinh ID P1, P2 trong tiến trình.
 */
public class ServerNetwork {

    /*
     * port: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private final int port;
    /*
     * app: Tham chiếu thành phần điều phối để chuyển việc tới tầng ứng dụng hoặc mạng.
     */
    private final ServerApp app;
    /*
     * playerCounter: Danh sách handler TCP sống hoặc bộ đếm cấp ID P1 P2.
     */
    private final AtomicInteger playerCounter = new AtomicInteger(1);
    /*
     * connectedClients: Danh sách handler TCP sống hoặc bộ đếm cấp ID P1 P2.
     */
    private final Set<ClientHandler> connectedClients = ConcurrentHashMap.newKeySet();

    // Voice Chat mappings
    /*
     * voicePeersByPlayer: Các chỉ mục endpoint, phòng và handler cho đăng ký và relay voice.
     */
    private final ConcurrentHashMap<String, SocketAddress> voicePeersByPlayer = new ConcurrentHashMap<>();
    /*
     * voicePlayersByAddress: Các chỉ mục endpoint, phòng và handler cho đăng ký và relay voice.
     */
    private final ConcurrentHashMap<SocketAddress, String> voicePlayersByAddress = new ConcurrentHashMap<>();
    /*
     * voiceRoomsByPlayer: Các chỉ mục endpoint, phòng và handler cho đăng ký và relay voice.
     */
    private final ConcurrentHashMap<String, String> voiceRoomsByPlayer = new ConcurrentHashMap<>();
    /*
     * voiceHandlersByPlayer: Các chỉ mục endpoint, phòng và handler cho đăng ký và relay voice.
     */
    private final ConcurrentHashMap<String, ClientHandler> voiceHandlersByPlayer = new ConcurrentHashMap<>();

    // Gameplay UDP mappings
    /*
     * gamePeersByPlayer: Ánh xạ hai chiều giữa player và endpoint UDP gameplay đã đăng ký.
     */
    private final ConcurrentHashMap<String, SocketAddress> gamePeersByPlayer = new ConcurrentHashMap<>();
    /*
     * gamePlayersByAddress: Ánh xạ hai chiều giữa player và endpoint UDP gameplay đã đăng ký.
     */
    private final ConcurrentHashMap<SocketAddress, String> gamePlayersByAddress = new ConcurrentHashMap<>();

    /*
     * serverSocket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
     */
    private volatile ServerSocket serverSocket;
    /*
     * udpSocket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
     */
    private volatile DatagramSocket udpSocket;
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile boolean running;
    /*
     * acceptThread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy lớp.
     */
    private Thread acceptThread;
    /*
     * udpDispatcherThread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy
     * lớp.
     */
    private Thread udpDispatcherThread;

    // UDP Multi-threaded pipeline
    /*
     * voiceUdpPool: Executor tách xử lý gameplay và voice sau khi UDP dispatcher nhận và copy gói.
     * Callback/lambda: ThreadFactory tạo daemon worker voice; executor có hai worker.
     */
    private final ExecutorService voiceUdpPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "UDP-Voice-Worker");
        t.setDaemon(true);
        return t;
    });

    /*
     * gameUdpPool: Executor tách xử lý gameplay và voice sau khi UDP dispatcher nhận và copy gói.
     * Callback/lambda: ThreadFactory tạo daemon worker gameplay; executor có bốn worker.
     */
    private final ExecutorService gameUdpPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "UDP-Game-Worker");
        t.setDaemon(true);
        return t;
    });

    /*
     * VOICE_MAGIC: Magic VCH1 của voice server.
     */
    private static final byte[] VOICE_MAGIC = {'V', 'C', 'H', '1'};
    /*
     * VOICE_REGISTER: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    private static final byte VOICE_REGISTER = 1;
    /*
     * VOICE_AUDIO: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    private static final byte VOICE_AUDIO = 2;
    /*
     * VOICE_ACK: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    private static final byte VOICE_ACK = 3;
    /*
     * VOICE_HEADER_BYTES: Kích thước header voice phía server.
     */
    private static final int VOICE_HEADER_BYTES = VOICE_MAGIC.length + 1;
    /*
     * MAX_VOICE_PACKET_BYTES: Giới hạn gói voice relay chấp nhận.
     */
    private static final int MAX_VOICE_PACKET_BYTES = 1200;

    /*
     * Khởi tạo ServerNetwork
     * Lưu port và tham chiếu ServerApp; các executor/map đã được field initializer tạo. Chưa bind socket
     * cho tới start.
     */
    public ServerNetwork(int port, ServerApp app) {
        this.port = port;
        this.app = app;
    }

    /*
     * start()
     * Mở/bind UDP rồi TCP cùng số port, đăng ký network với app, đặt running và chạy
     * TCP-Acceptor/UDP-Dispatcher. Nếu bind/khởi động lỗi, đóng socket đã mở và ném IOException, tránh để
     * nửa dịch vụ sống.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        DatagramSocket udpListener = new DatagramSocket(null);
        ServerSocket tcpListener = null;
        try {
            udpListener.setReuseAddress(true);
            udpListener.bind(new InetSocketAddress(port));

            tcpListener = new ServerSocket();
            tcpListener.setReuseAddress(true);
            tcpListener.bind(new InetSocketAddress(port));

            serverSocket = tcpListener;
            udpSocket = udpListener;
            running = true;
            if (app != null) {
                app.setServerNetwork(this);
            }

            acceptThread = new Thread(this::acceptConnections, "TCP-Acceptor");
            acceptThread.start();

            udpDispatcherThread = new Thread(this::receiveUdpPackets, "UDP-Dispatcher");
            udpDispatcherThread.start();
        } catch (IOException e) {
            if (tcpListener != null) {
                try {
                    tcpListener.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            udpListener.close();
            throw e;
        }
    }

    /*
     * acceptConnections()
     * Lặp accept TCP, bật TCP_NODELAY/KeepAlive, sinh playerId, tạo handler và thread TCP-Client-ID. Mỗi
     * kết nối có một handler riêng, chưa đồng nghĩa đã ở phòng.
     */
    private void acceptConnections() {
        while (running) {
            try {
                ServerSocket listener = serverSocket;
                if (listener == null) {
                    break;
                }
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                String playerId = "P" + playerCounter.getAndIncrement();
                ClientHandler handler = new ClientHandler(socket, playerId);
                connectedClients.add(handler);
                Thread clientThread = new Thread(handler, "TCP-Client-" + playerId);
                clientThread.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ServerNetwork] Loi accept: " + e.getMessage());
                }
            }
        }
    }

    /*
     * stop()
     * Ngừng accept, đóng handler, interrupt thread, shutdown hai executor và đợi ngắn, đóng UDP rồi xóa
     * mapping. Không giữ endpoint UDP của phiên đã dừng.
     */
    public void stop() {
        running = false;
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException e) {
                System.err.println("[ServerNetwork] Loi dong ServerSocket: " + e.getMessage());
            }
        }
        for (ClientHandler client : connectedClients) {
            client.close();
        }
        connectedClients.clear();

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (udpDispatcherThread != null) {
            udpDispatcherThread.interrupt();
            udpDispatcherThread = null;
        }

        voiceUdpPool.shutdownNow();
        gameUdpPool.shutdownNow();
        try {
            voiceUdpPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try {
            gameUdpPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        DatagramSocket datagramListener = udpSocket;
        udpSocket = null;
        if (datagramListener != null) {
            datagramListener.close();
        }



        voicePlayersByAddress.clear();
        voicePeersByPlayer.clear();
        voiceRoomsByPlayer.clear();
        voiceHandlersByPlayer.clear();

        gamePeersByPlayer.clear();
        gamePlayersByAddress.clear();
    }

    // ==========================================
    // UDP DISPATCHER & MULTI-THREADED PIPELINE
    // ==========================================

    /*
     * receiveUdpPackets()
     * Nhận vào buffer 4096 byte, sao chép đúng vùng datagram để worker không đọc buffer bị ghi đè. Magic
     * VCH1 đưa pool voice, GAM1 đưa pool game; gói khác bỏ. Bắt lỗi socket và rejected task khi shutdown.
     * DatagramSocket dùng chung số cổng nhận cả GAM1 và VCH1. Copy đúng slice dữ liệu trước khi giao
     * executor, tránh buffer nhận kế tiếp ghi đè nội dung task cũ. Voice có pool 2 worker, gameplay có
     * pool 4 worker. newFixedThreadPool dùng hàng đợi không bị chặn kích thước sẵn, nên tải đến quá nhanh
     * có thể làm queue tăng.
     */
    private void receiveUdpPackets() {
        byte[] buffer = new byte[4096];
        while (running) {
            DatagramSocket socket = udpSocket;
            if (socket == null || socket.isClosed()) {
                return;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (packet.getLength() < 4) {
                    continue;
                }
                /*
                 * Copy vùng datagram trước khi chuyển sang worker vì buffer nhận sẽ được tái sử dụng.
                 */
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                        packet.getOffset() + packet.getLength());
                SocketAddress clientAddress = packet.getSocketAddress();

                /*
                 * Cùng UDP port nhưng tách voice VCH1 và gameplay GAM1 theo magic.
                 */
                if (hasVoiceMagic(data)) {
                    /*
                     * Callback/lambda: Task voice giao executor xử lý bản copy datagram và endpoint.
                     */
                    voiceUdpPool.execute(() -> handleVoicePacket(data, clientAddress));
                } else if (GameProtocol.isGameUdpPacket(data, data.length)) {
                    /*
                     * Callback/lambda: Task gameplay giao executor xử lý bản copy datagram và endpoint.
                     */
                    gameUdpPool.execute(() -> handleGamePacket(data, clientAddress));
                }
            } catch (RejectedExecutionException e) {
                if (!running) return;
            } catch (SocketException e) {
                if (running) {
                    System.err.println("[ServerNetwork] Loi UDP socket: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ServerNetwork] Loi nhan UDP: " + e.getMessage());
                }
            }
        }
    }

    // --- GAMEPLAY UDP HANDLING ---

    /*
     * handleGamePacket()
     * Đọc opcode/payload gameplay, dispatch REGISTER, INPUT hoặc PING. PING echo thành PONG; nhánh này
     * chưa yêu cầu endpoint đã đăng ký, khác INPUT.
     */
    private void handleGamePacket(byte[] data, SocketAddress address) {
        byte type = GameProtocol.getGameUdpType(data);
        String payload = GameProtocol.getGameUdpPayload(data, 0, data.length);

        switch (type) {
            case Common.UDP_GAME_REGISTER:
                handleGameRegister(payload, address);
                break;
            case Common.UDP_GAME_INPUT:
                handleGameInput(payload, address);
                break;
            case Common.UDP_GAME_PING:
                sendUdpRaw(GameProtocol.createGameUdpPacket(Common.UDP_GAME_PONG, payload), address);
                break;
            default:
                break;
        }
    }

    /*
     * handleGameRegister()
     * Tách token|generation, kiểm dạng generation số và tìm session token. Ghi mapping endpoint↔player, bỏ
     * địa chỉ cũ nếu cần. ACK playerId|streamId|generation; stream rỗng khi lobby. Nếu có phòng thì yêu
     * cầu full state để client mới nhận baseline.
     * Tách token|generation, kiểm generation parse được rồi tra session. Gắn endpoint UDP với player ID,
     * xóa mapping địa chỉ cũ; trả ACK gồm ID, stream phòng nếu có, generation. Có phòng thì
     * requestFullState. Đăng ký gắn endpoint, không phải một kết nối UDP có bảo đảm giao hàng.
     */
    private void handleGameRegister(String token, SocketAddress address) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String[] registration = token.split("\\|", -1);
        if (registration.length != 2 || registration[1].length() > 20) return;
        try { Long.parseLong(registration[1]); }
        catch (NumberFormatException invalidGeneration) { return; }
        ServerSession session = app.getSessionByToken(registration[0].trim());
        if (session == null) {
            return;
        }

        String playerId = session.getPlayerId();
        /*
         * Gắn endpoint UDP với ID phiên sau khi kiểm token; xóa ánh xạ địa chỉ cũ nếu có.
         */
        SocketAddress previous = gamePeersByPlayer.put(playerId, address);
        if (previous != null) gamePlayersByAddress.remove(previous, playerId);
        gamePlayersByAddress.put(address, playerId);

        String acknowledgement = playerId + "|";
        ServerGame room = session.getRoomId() == null ? null : app.getRoom(session.getRoomId());
        if (room != null) acknowledgement += room.getStreamId();
        acknowledgement += "|" + registration[1];
        sendUdpRaw(GameProtocol.createGameUdpPacket(Common.UDP_GAME_ACK, acknowledgement), address);
        if (room != null) room.requestFullState();
    }

    /*
     * handleGameInput()
     * Từ địa chỉ UDP đã đăng ký tra playerId, session, room và player. Parse seq/phím/attack/ping rồi gọi
     * room.bufferPlayerInput; lỗi số bị bỏ, Boolean.parseBoolean coi nhiều chuỗi sai là false. INPUT hiện
     * chưa mang stream/generation nên có giới hạn với gói trễ sau đổi phòng.
     * Tra player bằng endpoint đã đăng ký, rồi tra session, room và PlayerState; không tin playerId tự
     * nhận trong payload. Parse seq/phím/đánh/ping, dựng PlayerInput để chặn ping và buffer vào room.
     * Payload không chứa tọa độ client tự quyết định hay HP quái.
     */
    private void handleGameInput(String payload, SocketAddress address) {
        /*
         * Tìm player từ endpoint đã đăng ký, không tin ID tự xưng trong payload.
         */
        String playerId = gamePlayersByAddress.get(address);
        if (playerId == null || payload == null || payload.isEmpty()) {
            return;
        }

        ServerSession session = app.getSessionByPlayerId(playerId);
        if (session == null || session.getRoomId() == null) {
            return;
        }

        ServerGame room = app.getRoom(session.getRoomId());
        if (room == null) {
            return;
        }

        GameData.PlayerState player = room.getPlayers().get(playerId);
        if (player == null) {
            return;
        }

        // Payload format: seq:left:right:up:down:attack:pingMs
        String[] parts = payload.split(":", -1);
        if (parts.length < 5) {
            return;
        }

        try {
            int seq = Integer.parseInt(parts[0]);
            boolean left = Boolean.parseBoolean(parts[1]);
            boolean right = Boolean.parseBoolean(parts[2]);
            boolean up = Boolean.parseBoolean(parts[3]);
            boolean down = Boolean.parseBoolean(parts[4]);
            boolean attack = parts.length >= 6 && Boolean.parseBoolean(parts[5]);
            int pingMs = parts.length >= 7 ? Integer.parseInt(parts[6]) : 0;

            /*
             * Worker chỉ chuyển input vào phòng; RoomLoop sẽ mô phỏng theo tick.
             */
            room.bufferPlayerInput(playerId,
                    new GameData.PlayerInput(seq, left, right, up, down, attack, pingMs));
        } catch (Exception ignored) {}
    }

    /*
     * sendGameUdpPacket()
     * Tra endpoint gameplay theo playerId rồi gửi packet qua sendUdpRaw nếu có. Không tự tạo snapshot hoặc
     * đợi ACK mỗi packet.
     */
    public void sendGameUdpPacket(String playerId, byte[] packetData) {
        SocketAddress address = gamePeersByPlayer.get(playerId);
        if (address != null) {
            sendUdpRaw(packetData, address);
        }
    }

    /*
     * removeGamePeer()
     * Xóa mapping hai chiều gameplay của player, dùng điều kiện owner khi bỏ address để tránh dọn nhầm
     * liên kết khác.
     */
    private void removeGamePeer(String playerId) {
        if (playerId == null) return;
        SocketAddress address = gamePeersByPlayer.remove(playerId);
        if (address != null) {
            gamePlayersByAddress.remove(address);
        }
    }

    // --- VOICE CHAT UDP HANDLING ---

    /*
     * handleVoicePacket()
     * Kiểm header voice, dispatch REGISTER hoặc AUDIO đúng giới hạn 1200 byte. Magic đã được dispatcher
     * nhận diện; nội dung audio không đi qua parser gameplay.
     */
    private void handleVoicePacket(byte[] data, SocketAddress address) {
        if (data.length < VOICE_HEADER_BYTES) {
            return;
        }
        byte type = data[VOICE_MAGIC.length];
        if (type == VOICE_REGISTER) {
            registerVoiceEndpoint(data, address);
        } else if (type == VOICE_AUDIO && data.length > VOICE_HEADER_BYTES
                && data.length <= MAX_VOICE_PACKET_BYTES) {
            relayVoicePacket(data, address);
        }
    }

    /*
     * registerVoiceEndpoint()
     * Đọc token UTF-8, kiểm chiều dài/session/handler và tư cách phòng; thay mapping voice cũ an toàn rồi
     * gửi VCH1 ACK. Chỉ người thực sự trong phòng mới đăng ký voice được.
     */
    private void registerVoiceEndpoint(byte[] data, SocketAddress address) {
        int tokenLength = data.length - VOICE_HEADER_BYTES;
        if (tokenLength <= 0 || tokenLength > 128) {
            return;
        }
        String token = new String(data, VOICE_HEADER_BYTES, tokenLength, StandardCharsets.UTF_8);
        ServerSession session = app.getSessionByToken(token);
        if (session == null) {
            return;
        }

        ClientHandler handler = session.getClientHandler();
        String roomId = session.getRoomId();
        if (handler == null || roomId == null || !handler.isVoiceRoomMember(roomId)) {
            return;
        }

        String playerId = session.getPlayerId();
        synchronized (this) {
            SocketAddress previousAddress = voicePeersByPlayer.put(playerId, address);
            if (previousAddress != null) {
                voicePlayersByAddress.remove(previousAddress, playerId);
            }
            String previousPlayer = voicePlayersByAddress.put(address, playerId);
            if (previousPlayer != null && !previousPlayer.equals(playerId)) {
                voicePeersByPlayer.remove(previousPlayer, address);
                voiceRoomsByPlayer.remove(previousPlayer);
                voiceHandlersByPlayer.remove(previousPlayer);
            }
            voiceRoomsByPlayer.put(playerId, roomId);
            voiceHandlersByPlayer.put(playerId, handler);
        }
        sendUdpRaw(createVoicePacket(VOICE_ACK, new byte[0]), address);
    }

    /*
     * relayVoicePacket()
     * Từ endpoint đã đăng ký xác định sender và phòng, kiểm handler/thành viên còn hợp lệ rồi gửi nguyên
     * gói tới người khác cùng phòng. Không gửi vòng lại người nói và không giải mã/trộn PCM ở server.
     */
    private void relayVoicePacket(byte[] data, SocketAddress address) {
        String senderId = voicePlayersByAddress.get(address);
        String senderRoom = senderId == null ? null : voiceRoomsByPlayer.get(senderId);
        ClientHandler senderHandler = senderId == null ? null : voiceHandlersByPlayer.get(senderId);
        if (senderRoom == null || senderHandler == null
                || !senderHandler.isVoiceRoomMember(senderRoom)) {
            return;
        }

        for (var peer : voicePeersByPlayer.entrySet()) {
            String playerId = peer.getKey();
            String roomId = voiceRoomsByPlayer.get(playerId);
            ClientHandler handler = voiceHandlersByPlayer.get(playerId);
            if (!playerId.equals(senderId) && senderRoom.equals(roomId)
                    && handler != null && handler.isVoiceRoomMember(roomId)) {
                sendUdpRaw(data, peer.getValue());
            }
        }
    }

    /*
     * sendUdpRaw()
     * Gửi byte[] tới SocketAddress bằng socket UDP chung nếu còn mở; log lỗi khi server còn running. Không
     * có truyền lại ứng dụng ở đây.
     */
    private void sendUdpRaw(byte[] data, SocketAddress address) {
        DatagramSocket socket = udpSocket;
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.send(new DatagramPacket(data, data.length, address));
        } catch (IOException e) {
            if (running) {
                System.err.println("[ServerNetwork] Loi gui UDP: " + e.getMessage());
            }
        }
    }

    /*
     * hasVoiceMagic()
     * Kiểm đủ bốn byte VCH1 để nhận diện họ gói voice; không xác thực người gửi.
     */
    private boolean hasVoiceMagic(byte[] data) {
        for (int i = 0; i < VOICE_MAGIC.length; i++) {
            if (data[i] != VOICE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /*
     * createVoicePacket()
     * Ghép magic VCH1, byte type và payload thành packet mới. REGISTER/ACK/AUDIO dùng cùng kiểu vỏ.
     */
    private static byte[] createVoicePacket(byte type, byte[] payload) {
        byte[] packet = new byte[VOICE_HEADER_BYTES + payload.length];
        System.arraycopy(VOICE_MAGIC, 0, packet, 0, VOICE_MAGIC.length);
        packet[VOICE_MAGIC.length] = type;
        System.arraycopy(payload, 0, packet, VOICE_HEADER_BYTES, payload.length);
        return packet;
    }

    /*
     * removeVoicePeer()
     * Chỉ dọn voice mapping nếu handler truyền vào đúng chủ đang gắn; xóa player/address/room/handler để
     * phiên cũ không phá voice của resume mới.
     */
    private synchronized void removeVoicePeer(ClientHandler handler) {
        String playerId = handler.playerId;
        if (voiceHandlersByPlayer.get(playerId) == handler) {
            SocketAddress address = voicePeersByPlayer.remove(playerId);
            if (address != null) {
                voicePlayersByAddress.remove(address, playerId);
            }
            voiceRoomsByPlayer.remove(playerId);
            voiceHandlersByPlayer.remove(playerId, handler);
        }
    }

    /*
     * broadcastRoomList()
     * Lấy ROOM_LIST từ app rồi gửi cho các handler đang lobby. Không gửi toàn bộ save JSON hoặc snapshot
     * game.
     */
    private void broadcastRoomList() {
        String message = app.getRoomListMessage();
        for (ClientHandler client : connectedClients) {
            if (client.isInLobby()) {
                client.send(message);
            }
        }
    }

    // ==========================================
    // TCP CLIENT HANDLER (LOBBY & SESSION ONLY)
    // ==========================================

    /*
     * Xử lý một kết nối TCP: handshake, lệnh phòng và dọn phiên khi ngắt.
     */
    public class ClientHandler implements Runnable {
        /*
         * socket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
         */
        private final Socket socket;
        /*
         * playerId: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
         */
        private volatile String playerId;
        /*
         * inLobby: Cờ handler đang ở lobby; chỉ handler lobby mới nhận broadcast danh sách phòng.
         */
        private volatile boolean inLobby = true;
        /*
         * currentRoom: Danh sách phòng sống trong RAM hoặc tham chiếu phòng của một handler.
         */
        private volatile ServerGame currentRoom;
        /*
         * session: Phiên runtime hoặc chỉ mục tra phiên theo token và ID.
         */
        private volatile ServerSession session;
        /*
         * out: Reader và writer của dòng TCP; thông điệp được phân định bằng newline UTF-8.
         */
        private PrintWriter out;

        /*
         * Khởi tạo ClientHandler
         * Chỉ lưu socket và playerId do accept cấp. Việc tạo session/token và writer diễn ra khi thread
         * chạy run, không nằm trong constructor.
         */
        private ClientHandler(Socket socket, String playerId) {
            this.socket = socket;
            this.playerId = playerId;
        }

        /*
         * isInLobby()
         * Trả inLobby && socket chưa đóng; cờ inLobby được thay đổi khi vào/rời phòng. Đây là điều kiện
         * chọn handler nhận danh sách phòng push.
         */
        public boolean isInLobby() {
            return inLobby && !socket.isClosed();
        }

        /*
         * send()
         * Hàm synchronized gửi một message TCP theo dòng qua writer rồi flush; các caller dùng chung tránh
         * xen nội dung message.
         */
        public synchronized void send(String message) {
            if (out != null && !socket.isClosed()) {
                out.println(message);
                out.flush();
                if (out.checkError()) {
                    close();
                }
            }
        }

        /*
         * close()
         * Đóng socket TCP để vòng đọc thức dậy/dừng; dọn phòng/phiên được xử lý theo finally của run và
         * các hàm vòng đời.
         */
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("[ServerNetwork] Loi dong client socket: " + e.getMessage());
            }
        }

        /*
         * run()
         * Tạo reader/writer UTF-8, gửi WELCOME và danh sách phòng, lặp readLineLimited rồi handleCommand.
         * Khi lỗi/EOF, dọn peer, handler và thông báo app disconnect theo quyền sở hữu hiện tại; không để
         * handler cũ xóa phiên đã chuyển cho resume mới.
         */
        @Override
        public void run() {
            try {
                out = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));

                session = app.createSession(playerId, "Player");
                session.setClientHandler(this);
                send(Common.CMD_WELCOME + ":" + playerId + ":" + session.getToken());
                send(app.getRoomListMessage());

                String line;
                while ((line = GameProtocol.readLineLimited(
                        in, GameProtocol.MAX_LINE_LENGTH_CLIENT)) != null) {
                    try {
                        handleCommand(line.trim());
                    } catch (IllegalStateException | IllegalArgumentException error) {
                        System.err.println("[ServerNetwork] Command failed: " + error.getMessage());
                        send(Common.CMD_ERROR + ":Khong the vao phong: " + error.getMessage());
                    }
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("[ServerNetwork] Loi client " + playerId + ": " + e.getMessage());
                }
            } finally {
                connectedClients.remove(this);
                removeVoicePeer(this);
                if (session == null || session.getClientHandler() == this) removeGamePeer(playerId);
                if (currentRoom != null) {
                    currentRoom.detachPlayerHandler(playerId, this);
                    currentRoom = null;
                    inLobby = true;
                }
                app.handleDisconnect(session, this);
                close();
            }
        }

        /*
         * handleCommand()
         * Phân loại RESUME, ROOM_LIST, CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM và lỗi. Kiểm tham số/cú pháp,
         * cập nhật phiên/tư cách phòng; không nhận INPUT/STATE gameplay qua TCP cũ.
         */
        private void handleCommand(String line) {
            if (line.isEmpty()) {
                return;
            }
            if (session != null) {
                session.touch();
            }

            if (line.startsWith("RESUME:")) {
                resumeSession(line.substring("RESUME:".length()).trim());
                return;
            }

            String[] parts = line.split(":", -1);
            switch (parts[0]) {
                case Common.CMD_ROOM_LIST:
                    send(app.getRoomListMessage());
                    break;
                case Common.CMD_CREATE_ROOM:
                    createRoom(parts);
                    break;
                case Common.CMD_JOIN_ROOM:
                    joinRoom(parts);
                    break;
                case Common.CMD_LEAVE_ROOM:
                    leaveCurrentRoomIfAny();
                    send("LEFT");
                    send(app.getRoomListMessage());
                    broadcastRoomList();
                    break;
                default:
                    send(Common.CMD_ERROR + ":Lenh khong hop le!");
                    break;
            }
        }

        /*
         * createRoom()
         * Sanitize tên phòng/player, rời phòng trước nếu có, tạo ServerGame rồi thêm vào app bằng cơ chế
         * tránh trùng. Tạo player và gọi addPlayer; nếu thành công gắn currentRoom/session, gửi JOINED và
         * phát room list. Khi tạo/add thất bại phải dừng/dọn phòng mới phù hợp và gửi ERROR.
         * Kiểm tham số, sanitize tên, rời phòng cũ, chặn phòng trùng. Tạo ServerGame và putIfAbsent vào
         * app; addPlayer có thể lỗi vì slot save, không chỉ vì 4/4 runtime. Nếu lỗi admission thì
         * remove/stop phòng vừa tạo; thành công mới gắn currentRoom, session.roomId và gửi JOINED.
         */
        private void createRoom(String[] parts) {
            if (parts.length < 3) {
                send(Common.CMD_ERROR + ":Lenh thieu tham so!");
                return;
            }

            String roomName = GameProtocol.sanitizeRoom(parts[1]);
            String playerName = GameProtocol.sanitizeName(parts[2]);
            leaveCurrentRoomIfAny();
            if (app.getRoom(roomName) != null) {
                send(Common.CMD_ERROR + ":Phong da ton tai!");
                return;
            }

            /*
             * Constructor tạo cả RoomLoop; các nhánh thất bại bên dưới phải remove/stop phòng vừa tạo.
             */
            ServerGame room = new ServerGame(roomName, ServerNetwork.this);
            if (!app.addRoom(room)) {
                room.stop();
                send(Common.CMD_ERROR + ":Phong da ton tai!");
                return;
            }
            try {
                if (!room.addPlayer(new GameData.PlayerState(playerId, playerName), this)) {
                    app.removeRoom(roomName);
                    send(Common.CMD_ERROR + ":Phong da day (4/4 nguoi)!");
                    return;
                }
            } catch (IllegalStateException | IllegalArgumentException error) {
                app.removeRoom(roomName);
                throw error;
            }

            currentRoom = room;
            inLobby = false;
            session.setRoomId(roomName);
            send(Common.CMD_JOINED + ":" + roomName);
            broadcastRoomList();
        }

        /*
         * joinRoom()
         * Tra phòng, lọc tên và kiểm. Nếu đã ở cùng phòng thì trả RESUMED thay vì thêm trùng; chuyển phòng
         * khác rời cũ trước khi add vào mới. Thất bại có thể do số người hoặc slot save và gửi thông báo
         * cụ thể; thành công cập nhật room/session và JOINED.
         * Phòng phải đang có trong rooms RAM; save JSON tự tồn tại không làm nó hiện trong lobby. Nếu
         * handler đã ở đúng phòng và player tồn tại thì trả RESUMED. Nếu khác phòng, rời phòng cũ rồi
         * addPlayer, cập nhật session và gửi JOINED; lỗi từ save được trả về qua ERROR.
         */
        private void joinRoom(String[] parts) {
            if (parts.length < 3) {
                send(Common.CMD_ERROR + ":Lenh thieu tham so!");
                return;
            }

            String roomName = GameProtocol.sanitizeRoom(parts[1]);
            String playerName = GameProtocol.sanitizeName(parts[2]);
            ServerGame room = app.getRoom(roomName);
            if (room == null) {
                send(Common.CMD_ERROR + ":Phong khong ton tai!");
                return;
            }

            /*
             * Đã ở đúng phòng thì xác nhận RESUMED, tránh tạo thêm player hoặc chiếm slot lần nữa.
             */
            if (currentRoom == room && room.getPlayers().containsKey(playerId)) {
                send("RESUMED:" + roomName);
                return;
            }
            leaveCurrentRoomIfAny();
            if (!room.addPlayer(new GameData.PlayerState(playerId, playerName), this)) {
                send(Common.CMD_ERROR + ":Phong da day (4/4 nguoi)!");
                return;
            }

            currentRoom = room;
            inLobby = false;
            session.setRoomId(roomName);
            send(Common.CMD_JOINED + ":" + roomName);
            broadcastRoomList();
        }

        /*
         * resumeSession()
         * Gọi app.resumeSession với token và session tạm; nếu thành công nhận lại phiên/phòng gốc rồi gửi
         * RESUMED, không sinh thêm player. Thất bại gửi RESUME_FAIL.
         */
        private void resumeSession(String token) {
            ServerSession oldSession = app.getSessionByToken(token);
            ServerGame room = app.resumeSession(token, session, this);
            if (oldSession == null || room == null) {
                send("RESUME_FAIL");
                return;
            }

            playerId = oldSession.getPlayerId();
            session = oldSession;
            currentRoom = room;
            inLobby = false;
            session.touch();
            send("RESUMED:" + room.getRoomId());
        }

        /*
         * isVoiceRoomMember()
         * Kiểm socket còn mở, session có và đang thuộc handler này, currentRoom không null và roomId trùng
         * cả phòng lẫn phiên. Body không trực tiếp tra players.containsKey; đây là kiểm liên kết
         * handler/session/phòng hiện tại.
         */
        private boolean isVoiceRoomMember(String roomId) {
            ServerSession activeSession = session;
            ServerGame room = currentRoom;
            return !socket.isClosed() && activeSession != null
                    && activeSession.getClientHandler() == this
                    && room != null && room.getRoomId().equals(roomId)
                    && roomId.equals(activeSession.getRoomId());
        }

        /*
         * leaveCurrentRoomIfAny()
         * Loại player khỏi phòng nếu có, dọn mapping gameplay/voice, bỏ roomId phiên; nếu phòng trống thì
         * app.removeRoom để stop/save. Là rời chủ động, khác detach giữ chỗ khi TCP vừa đứt.
         * Dọn endpoint voice/gameplay, xóa roomId ở session, bỏ player khỏi phòng và lưu qua
         * RoomSaveManager. Phòng rỗng bị remove/stop; các slot save cũ vẫn còn. Đổi card về MENU ở client
         * không tự thay thế chuỗi thao tác server này.
         */
        private void leaveCurrentRoomIfAny() {
            removeVoicePeer(this);
            removeGamePeer(playerId);
            ServerGame room = currentRoom;
            currentRoom = null;
            inLobby = true;
            if (room == null) {
                return;
            }

            room.removePlayer(playerId);
            if (session != null) {
                session.setRoomId(null);
            }
            if (room.isEmpty()) {
                app.removeRoom(room.getRoomId());
            }
        }
    }
}
