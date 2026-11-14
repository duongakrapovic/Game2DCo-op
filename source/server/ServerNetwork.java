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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SERVERNETWORK.JAVA - Quản lý TCP (kết nối, phòng) & UDP đa luồng (Voice VCH1, Gameplay GAM1)
 */
public class ServerNetwork {

    private final int port;
    private final ServerApp app;
    private final AtomicInteger playerCounter = new AtomicInteger(1);
    private final Set<ClientHandler> connectedClients = ConcurrentHashMap.newKeySet();

    // Voice Chat mappings
    private final ConcurrentHashMap<String, SocketAddress> voicePeersByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, String> voicePlayersByAddress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> voiceRoomsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientHandler> voiceHandlersByPlayer = new ConcurrentHashMap<>();

    // Gameplay UDP mappings
    private final ConcurrentHashMap<String, SocketAddress> gamePeersByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, String> gamePlayersByAddress = new ConcurrentHashMap<>();

    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket udpSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private Thread udpDispatcherThread;

    // UDP Multi-threaded pipeline
    private final ExecutorService voiceUdpPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "UDP-Voice-Worker");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService gameUdpPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "UDP-Game-Worker");
        t.setDaemon(true);
        return t;
    });

    private static final byte[] VOICE_MAGIC = {'V', 'C', 'H', '1'};
    private static final byte VOICE_REGISTER = 1;
    private static final byte VOICE_AUDIO = 2;
    private static final byte VOICE_ACK = 3;
    private static final int VOICE_HEADER_BYTES = VOICE_MAGIC.length + 1;
    private static final int MAX_VOICE_PACKET_BYTES = 1200;

    public ServerNetwork(int port, ServerApp app) {
        this.port = port;
        this.app = app;
    }

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

    public synchronized void stop() {
        running = false;
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException e) {
                System.err.println("[ServerNetwork] Loi dong ServerSocket: " + e.getMessage());
            }
        }
        DatagramSocket datagramListener = udpSocket;
        udpSocket = null;
        if (datagramListener != null) {
            datagramListener.close();
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
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                        packet.getOffset() + packet.getLength());
                SocketAddress clientAddress = packet.getSocketAddress();

                if (hasVoiceMagic(data)) {
                    voiceUdpPool.execute(() -> handleVoicePacket(data, clientAddress));
                } else if (GameProtocol.isGameUdpPacket(data, data.length)) {
                    gameUdpPool.execute(() -> handleGamePacket(data, clientAddress));
                }
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

    private void handleGameRegister(String token, SocketAddress address) {
        if (token == null || token.isEmpty()) {
            return;
        }
        ServerSession session = app.getSessionByToken(token.trim());
        if (session == null) {
            return;
        }

        String playerId = session.getPlayerId();
        gamePeersByPlayer.put(playerId, address);
        gamePlayersByAddress.put(address, playerId);

        sendUdpRaw(GameProtocol.createGameUdpPacket(Common.UDP_GAME_ACK, playerId), address);
    }

    private void handleGameInput(String payload, SocketAddress address) {
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

            player.left = left;
            player.right = right;
            player.up = up;
            player.down = down;
            player.pingMs = pingMs;

            room.updateAck(playerId, seq);
            room.updateAttackInput(playerId, attack);
        } catch (Exception ignored) {}
    }

    public void sendGameUdpPacket(String playerId, byte[] packetData) {
        SocketAddress address = gamePeersByPlayer.get(playerId);
        if (address != null) {
            sendUdpRaw(packetData, address);
        }
    }

    private void removeGamePeer(String playerId) {
        if (playerId == null) return;
        SocketAddress address = gamePeersByPlayer.remove(playerId);
        if (address != null) {
            gamePlayersByAddress.remove(address);
        }
    }

    // --- VOICE CHAT UDP HANDLING ---

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

    private boolean hasVoiceMagic(byte[] data) {
        for (int i = 0; i < VOICE_MAGIC.length; i++) {
            if (data[i] != VOICE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] createVoicePacket(byte type, byte[] payload) {
        byte[] packet = new byte[VOICE_HEADER_BYTES + payload.length];
        System.arraycopy(VOICE_MAGIC, 0, packet, 0, VOICE_MAGIC.length);
        packet[VOICE_MAGIC.length] = type;
        System.arraycopy(payload, 0, packet, VOICE_HEADER_BYTES, payload.length);
        return packet;
    }

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

    public class ClientHandler implements Runnable {
        private final Socket socket;
        private volatile String playerId;
        private volatile boolean inLobby = true;
        private volatile ServerGame currentRoom;
        private volatile ServerSession session;
        private PrintWriter out;

        private ClientHandler(Socket socket, String playerId) {
            this.socket = socket;
            this.playerId = playerId;
        }

        public boolean isInLobby() {
            return inLobby && !socket.isClosed();
        }

        public synchronized void send(String message) {
            if (out != null && !socket.isClosed()) {
                out.println(message);
                out.flush();
                if (out.checkError()) {
                    close();
                }
            }
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("[ServerNetwork] Loi dong client socket: " + e.getMessage());
            }
        }

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
                    handleCommand(line.trim());
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("[ServerNetwork] Loi client " + playerId + ": " + e.getMessage());
                }
            } finally {
                connectedClients.remove(this);
                removeVoicePeer(this);
                removeGamePeer(playerId);
                if (currentRoom != null) {
                    currentRoom.detachPlayerHandler(playerId, this);
                    currentRoom = null;
                    inLobby = true;
                }
                app.handleDisconnect(session, this);
                close();
            }
        }

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

            ServerGame room = new ServerGame(roomName, ServerNetwork.this);
            if (!app.addRoom(room)) {
                room.stop();
                send(Common.CMD_ERROR + ":Phong da ton tai!");
                return;
            }
            if (!room.addPlayer(new GameData.PlayerState(playerId, playerName), this)) {
                app.removeRoom(roomName);
                send(Common.CMD_ERROR + ":Phong da day!");
                return;
            }

            currentRoom = room;
            inLobby = false;
            session.setRoomId(roomName);
            send(Common.CMD_JOINED + ":" + roomName);
            broadcastRoomList();
        }

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

            leaveCurrentRoomIfAny();
            if (!room.addPlayer(new GameData.PlayerState(playerId, playerName), this)) {
                send(Common.CMD_ERROR + ":Phong da day!");
                return;
            }

            currentRoom = room;
            inLobby = false;
            session.setRoomId(roomName);
            send(Common.CMD_JOINED + ":" + roomName);
            broadcastRoomList();
        }

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

        private boolean isVoiceRoomMember(String roomId) {
            ServerSession activeSession = session;
            ServerGame room = currentRoom;
            return !socket.isClosed() && activeSession != null
                    && activeSession.getClientHandler() == this
                    && room != null && room.getRoomId().equals(roomId)
                    && roomId.equals(activeSession.getRoomId());
        }

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
