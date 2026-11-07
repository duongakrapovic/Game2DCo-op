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
import java.util.concurrent.atomic.AtomicInteger;

public class ServerNetwork {

    private final int port;
    private final ServerApp app;
    private final AtomicInteger playerCounter = new AtomicInteger(1);
    private final Set<ClientHandler> connectedClients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, SocketAddress> voicePeersByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, String> voicePlayersByAddress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> voiceRoomsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientHandler> voiceHandlersByPlayer = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket voiceSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private Thread voiceThread;

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

        DatagramSocket voiceListener = new DatagramSocket(null);
        ServerSocket listener = null;
        try {
            voiceListener.setReuseAddress(true);
            voiceListener.bind(new InetSocketAddress(port));

            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
            serverSocket = listener;
            voiceSocket = voiceListener;
            running = true;

            acceptThread = new Thread(this::acceptConnections, "TCP-Acceptor");
            acceptThread.start();
            voiceThread = new Thread(this::receiveVoicePackets, "UDP-Voice-Relay");
            voiceThread.start();
        } catch (IOException e) {
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            voiceListener.close();
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
        DatagramSocket voiceListener = voiceSocket;
        voiceSocket = null;
        if (voiceListener != null) voiceListener.close();

        for (ClientHandler client : connectedClients) {
            client.close();
        }
        connectedClients.clear();

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (voiceThread != null) {
            voiceThread.interrupt();
            voiceThread = null;
        }
        voicePlayersByAddress.clear();
        voicePeersByPlayer.clear();
        voiceRoomsByPlayer.clear();
        voiceHandlersByPlayer.clear();
    }

    private void receiveVoicePackets() {
        byte[] buffer = new byte[MAX_VOICE_PACKET_BYTES];
        while (running) {
            DatagramSocket socket = voiceSocket;
            if (socket == null) return;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (packet.getLength() < VOICE_HEADER_BYTES || !hasVoiceMagic(packet.getData())) {
                    continue;
                }

                byte type = packet.getData()[VOICE_MAGIC.length];
                if (type == VOICE_REGISTER) {
                    registerVoiceEndpoint(packet);
                } else if (type == VOICE_AUDIO && packet.getLength() > VOICE_HEADER_BYTES
                        && packet.getLength() <= MAX_VOICE_PACKET_BYTES) {
                    relayVoicePacket(packet);
                }
            } catch (SocketException e) {
                if (running) System.err.println("[ServerNetwork] Loi UDP voice: " + e.getMessage());
                return;
            } catch (IOException e) {
                if (running) System.err.println("[ServerNetwork] Loi UDP voice: " + e.getMessage());
            }
        }
    }

    private void registerVoiceEndpoint(DatagramPacket packet) {
        int tokenLength = packet.getLength() - VOICE_HEADER_BYTES;
        if (tokenLength <= 0 || tokenLength > 128) return;
        String token = new String(packet.getData(), packet.getOffset() + VOICE_HEADER_BYTES,
                tokenLength, StandardCharsets.UTF_8);
        ServerSession session = app.getSessionByToken(token);
        if (session == null) return;

        ClientHandler handler = session.getClientHandler();
        String roomId = session.getRoomId();
        if (handler == null || roomId == null || !handler.isVoiceRoomMember(roomId)) return;

        String playerId = session.getPlayerId();
        SocketAddress address = packet.getSocketAddress();
        synchronized (this) {
            SocketAddress previousAddress = voicePeersByPlayer.put(playerId, address);
            if (previousAddress != null) voicePlayersByAddress.remove(previousAddress, playerId);
            String previousPlayer = voicePlayersByAddress.put(address, playerId);
            if (previousPlayer != null && !previousPlayer.equals(playerId)) {
                voicePeersByPlayer.remove(previousPlayer, address);
                voiceRoomsByPlayer.remove(previousPlayer);
                voiceHandlersByPlayer.remove(previousPlayer);
            }
            voiceRoomsByPlayer.put(playerId, roomId);
            voiceHandlersByPlayer.put(playerId, handler);
        }
        sendVoicePacket(createVoicePacket(VOICE_ACK, new byte[0]), address);
    }

    private void relayVoicePacket(DatagramPacket packet) {
        String senderId = voicePlayersByAddress.get(packet.getSocketAddress());
        String senderRoom = senderId == null ? null : voiceRoomsByPlayer.get(senderId);
        ClientHandler senderHandler = senderId == null ? null : voiceHandlersByPlayer.get(senderId);
        if (senderRoom == null || senderHandler == null
                || !senderHandler.isVoiceRoomMember(senderRoom)) return;

        byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                packet.getOffset() + packet.getLength());
        for (var peer : voicePeersByPlayer.entrySet()) {
            String playerId = peer.getKey();
            String roomId = voiceRoomsByPlayer.get(playerId);
            ClientHandler handler = voiceHandlersByPlayer.get(playerId);
            if (!playerId.equals(senderId) && senderRoom.equals(roomId)
                    && handler != null && handler.isVoiceRoomMember(roomId)) {
                sendVoicePacket(data, peer.getValue());
            }
        }
    }

    private void sendVoicePacket(byte[] data, SocketAddress address) {
        DatagramSocket socket = voiceSocket;
        if (socket == null || socket.isClosed()) return;
        try {
            socket.send(new DatagramPacket(data, data.length, address));
        } catch (IOException e) {
            if (running) System.err.println("[ServerNetwork] Loi gui UDP voice: " + e.getMessage());
        }
    }

    private boolean hasVoiceMagic(byte[] data) {
        for (int i = 0; i < VOICE_MAGIC.length; i++) {
            if (data[i] != VOICE_MAGIC[i]) return false;
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
            if (address != null) voicePlayersByAddress.remove(address, playerId);
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

            if (line.startsWith("PING:")) {
                send("PONG:" + line.substring("PING:".length()));
                return;
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
                case Common.CMD_INPUT:
                    handleInput(parts);
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

            ServerGame room = new ServerGame(roomName);
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

        private void handleInput(String[] parts) {
            if (currentRoom == null || parts.length < 5) {
                return;
            }
            if (!isBoolean(parts[1]) || !isBoolean(parts[2])
                    || !isBoolean(parts[3]) || !isBoolean(parts[4])) {
                send(Common.CMD_ERROR + ":INPUT khong hop le!");
                return;
            }

            Integer sequence = null;
            if (parts.length >= 6 && !parts[5].isEmpty()) {
                try {
                    sequence = Integer.parseInt(parts[5]);
                } catch (NumberFormatException e) {
                    send(Common.CMD_ERROR + ":Sequence INPUT khong hop le!");
                    return;
                }
            }
            Boolean attackPressed = null;
            if (parts.length >= 7 && !parts[6].isEmpty()) {
                if (!isBoolean(parts[6])) {
                    send(Common.CMD_ERROR + ":Trang thai attack khong hop le!");
                    return;
                }
                attackPressed = Boolean.parseBoolean(parts[6]);
            }

            GameData.PlayerState player = currentRoom.getPlayers().get(playerId);
            if (player == null) {
                return;
            }
            player.left = Boolean.parseBoolean(parts[1]);
            player.right = Boolean.parseBoolean(parts[2]);
            player.up = Boolean.parseBoolean(parts[3]);
            player.down = Boolean.parseBoolean(parts[4]);

            if (sequence != null) {
                currentRoom.updateAck(playerId, sequence);
            }
            if (attackPressed != null) {
                currentRoom.updateAttackInput(playerId, attackPressed);
            }
        }

        private boolean isBoolean(String value) {
            return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
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
