package source.server;

import source.common.Common;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ============================================================================
 * TCP LOBBY & GAME SERVER (SOURCE/SERVER/TCPLOBBYSERVER.JAVA)
 * ============================================================================
 * Xử lý kết nối TCP toàn diện cho Sảnh chờ và nhận Input di chuyển trong game.
 * ============================================================================
 */
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
        private final String playerId;
        private PrintWriter out;
        private volatile boolean inLobby = true;
        private GameRoom currentRoom = null;

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

                send(Common.CMD_WELCOME + ":" + playerId);
                send(app.getRoomListMessage());

                String line;
                while ((line = in.readLine()) != null) {
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
            String[] parts = line.split(":");
            String cmd = parts[0];

            switch (cmd) {
                case Common.CMD_ROOM_LIST:
                    send(app.getRoomListMessage());
                    break;

                case Common.CMD_CREATE_ROOM:
                    if (parts.length >= 3) {
                        String rName = parts[1];
                        String pName = parts[2];
                        if (app.getRoom(rName) != null) {
                            send(Common.CMD_ERROR + ":Phong da ton tai!");
                        } else {
                            GameRoom newRoom = new GameRoom(rName);
                            app.addRoom(newRoom);
                            Common.Player player = new Common.Player(playerId, pName);
                            newRoom.addPlayer(player, this);
                            currentRoom = newRoom;
                            inLobby = false;
                            send(Common.CMD_JOINED + ":" + rName);
                            broadcastRoomList();
                        }
                    }
                    break;

                case Common.CMD_JOIN_ROOM:
                    if (parts.length >= 3) {
                        String rName = parts[1];
                        String pName = parts[2];
                        GameRoom targetRoom = app.getRoom(rName);
                        if (targetRoom == null) {
                            send(Common.CMD_ERROR + ":Phong khong ton tai!");
                        } else if (targetRoom.isFull()) {
                            send(Common.CMD_ERROR + ":Phong da day!");
                        } else {
                            Common.Player player = new Common.Player(playerId, pName);
                            targetRoom.addPlayer(player, this);
                            currentRoom = targetRoom;
                            inLobby = false;
                            send(Common.CMD_JOINED + ":" + rName);
                            broadcastRoomList();
                        }
                    }
                    break;

                case Common.CMD_INPUT:
                    // Nhận lệnh phím bấm di chuyển qua TCP: INPUT:left:right:up:down
                    if (currentRoom != null && parts.length >= 5) {
                        source.common.Player p = currentRoom.getPlayers().get(playerId);
                        if (p != null) {
                            p.left  = Boolean.parseBoolean(parts[1]);
                            p.right = Boolean.parseBoolean(parts[2]);
                            p.up    = Boolean.parseBoolean(parts[3]);
                            p.down  = Boolean.parseBoolean(parts[4]);
                        }
                    }
                    break;

                case Common.CMD_LEAVE_ROOM:
                    if (currentRoom != null) {
                        currentRoom.removePlayer(playerId);
                        if (currentRoom.isEmpty()) {
                            currentRoom.stop();
                            app.removeRoom(currentRoom.getRoomId());
                        }
                        currentRoom = null;
                        inLobby = true;
                        send(app.getRoomListMessage());
                        broadcastRoomList();
                    }
                    break;
            }
        }
    }
}