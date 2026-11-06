package source.server;

import source.common.Common;
import source.common.Protocol;
import source.common.Player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpLobbyServer {

    private final int port;
    private final ServerApp app;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;

    private final AtomicInteger playerCounter = new AtomicInteger(1);
    private final Set<ClientHandler> connectedClients = ConcurrentHashMap.newKeySet();

    public TcpLobbyServer(int port, ServerApp app) {
        this.port = port;
        this.app = app;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        isRunning = true;

        Thread acceptThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    String playerId = "P" + playerCounter.getAndIncrement();
                    ClientHandler handler = new ClientHandler(socket, playerId);
                    connectedClients.add(handler);
                    new Thread(handler, "TCP-Client-" + playerId).start();
                } catch (IOException e) {
                    if (!isRunning) break;
                    System.err.println("[TcpLobby] Loi accept: " + e.getMessage());
                }
            }
        }, "TCP-Acceptor");
        acceptThread.start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        for (ClientHandler client : connectedClients) {
            client.close();
        }
        connectedClients.clear();
    }

    public void broadcastRoomList() {
        String msg = app.getRoomListMessage();
        for (ClientHandler client : connectedClients) {
            if (client.isInLobby()) {
                client.send(msg);
            }
        }
    }

    public class ClientHandler implements Runnable {
        private final Socket socket;
        private String playerId;
        private PrintWriter out;
        private volatile boolean inLobby = true;
        private GameRoom currentRoom = null;
        private ServerApp.Session session = null;

        public ClientHandler(Socket socket, String playerId) {
            this.socket = socket;
            this.playerId = playerId;
        }

        public boolean isInLobby() { return inLobby && socket.isConnected(); }

        public synchronized void send(String msg) {
            if (out != null) {
                out.println(msg);
                out.flush();
            }
        }

        public void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                session = app.createSession(playerId, "Player");
                send(Common.CMD_WELCOME + ":" + playerId + ":" + session.token);
                send(app.getRoomListMessage());

                String line;
                while ((line = Protocol.readLineLimited(in, Protocol.MAX_LINE_LENGTH_CLIENT)) != null) {
                    handleCommand(line.trim());
                }
            } catch (IOException ignored) {
            } finally {
                connectedClients.remove(this);
                if (currentRoom != null) {
                    currentRoom.removePlayer(playerId);
                    if (currentRoom.isEmpty()) {
                        currentRoom.stop();
                        app.removeRoom(currentRoom.getRoomId());
                    }
                    broadcastRoomList();
                }
                close();
            }
        }

        private void handleCommand(String line) {
            if (line.isEmpty()) return;

            if (line.startsWith("PING:")) {
                send("PONG:" + line.substring(5));
                return;
            }

            if (line.startsWith("RESUME:")) {
                String token = line.substring(7).trim();
                ServerApp.Session oldSession = app.getSessionByToken(token);
                if (oldSession != null && oldSession.roomId != null) {
                    GameRoom room = app.getRoom(oldSession.roomId);
                    if (room != null && room.getPlayers().containsKey(oldSession.playerId)) {
                        this.playerId = oldSession.playerId;
                        this.session = oldSession;
                        this.currentRoom = room;
                        this.inLobby = false;
                        room.updatePlayerHandler(playerId, this);
                        send("RESUMED:" + room.getRoomId());
                        return;
                    }
                }
                send("RESUME_FAIL");
                return;
            }

            String[] parts = line.split(":");
            String cmd = parts[0];

            switch (cmd) {
                case Common.CMD_ROOM_LIST:
                    send(app.getRoomListMessage());
                    break;

                case Common.CMD_CREATE_ROOM:
                    if (parts.length < 3) {
                        send(Common.CMD_ERROR + ":Lệnh thiếu tham số!");
                        return;
                    }
                    String rName = Protocol.sanitizeRoom(parts[1]);
                    String pName = Protocol.sanitizeName(parts[2]);

                    leaveCurrentRoomIfAny();

                    if (app.getRoom(rName) != null) {
                        send(Common.CMD_ERROR + ":Phòng đã tồn tại!");
                    } else {
                        GameRoom newRoom = new GameRoom(rName);
                        app.addRoom(newRoom);
                        Player player = new Player(playerId, pName);
                        newRoom.addPlayer(player, this);
                        currentRoom = newRoom;
                        inLobby = false;
                        session.roomId = rName;
                        send(Common.CMD_JOINED + ":" + rName);
                        broadcastRoomList();
                    }
                    break;

                case Common.CMD_JOIN_ROOM:
                    if (parts.length < 3) {
                        send(Common.CMD_ERROR + ":Lệnh thiếu tham số!");
                        return;
                    }
                    String targetRName = Protocol.sanitizeRoom(parts[1]);
                    String targetPName = Protocol.sanitizeName(parts[2]);

                    GameRoom targetRoom = app.getRoom(targetRName);
                    if (targetRoom == null) {
                        send(Common.CMD_ERROR + ":Phòng không tồn tại!");
                    } else if (targetRoom.isFull()) {
                        send(Common.CMD_ERROR + ":Phòng đã đầy!");
                    } else {
                        leaveCurrentRoomIfAny();
                        Player player = new Player(playerId, targetPName);
                        targetRoom.addPlayer(player, this);
                        currentRoom = targetRoom;
                        inLobby = false;
                        session.roomId = targetRName;
                        send(Common.CMD_JOINED + ":" + targetRName);
                        broadcastRoomList();
                    }
                    break;

                case Common.CMD_INPUT:
                    if (currentRoom != null && parts.length >= 5) {
                        Player p = currentRoom.getPlayers().get(playerId);
                        if (p != null) {
                            p.left  = Boolean.parseBoolean(parts[1]);
                            p.right = Boolean.parseBoolean(parts[2]);
                            p.up    = Boolean.parseBoolean(parts[3]);
                            p.down  = Boolean.parseBoolean(parts[4]);

                            if (parts.length >= 6) {
                                try {
                                    int seq = Integer.parseInt(parts[5]);
                                    currentRoom.updateAck(playerId, seq);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                    break;

                case Common.CMD_LEAVE_ROOM:
                    leaveCurrentRoomIfAny();
                    send("LEFT");
                    send(app.getRoomListMessage());
                    broadcastRoomList();
                    break;

                default:
                    send(Common.CMD_ERROR + ":Lệnh không hợp lệ!");
                    break;
            }
        }

        private void leaveCurrentRoomIfAny() {
            if (currentRoom != null) {
                currentRoom.removePlayer(playerId);
                if (currentRoom.isEmpty()) {
                    currentRoom.stop();
                    app.removeRoom(currentRoom.getRoomId());
                }
                if (session != null) session.roomId = null;
                currentRoom = null;
                inLobby = true;
            }
        }
    }
}