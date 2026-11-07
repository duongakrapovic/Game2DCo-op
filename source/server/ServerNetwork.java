package source.server;

import source.common.Common;
import source.common.GameData;
import source.common.GameProtocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerNetwork {

    private final int port;
    private final ServerApp app;
    private final AtomicInteger playerCounter = new AtomicInteger(1);
    private final Set<ClientHandler> connectedClients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    public ServerNetwork(int port, ServerApp app) {
        this.port = port;
        this.app = app;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        serverSocket = listener;
        running = true;

        acceptThread = new Thread(this::acceptConnections, "TCP-Acceptor");
        acceptThread.start();
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

        for (ClientHandler client : connectedClients) {
            client.close();
        }
        connectedClients.clear();

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
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
                send("PONG:" + line.substring(5));
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

        private void leaveCurrentRoomIfAny() {
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
