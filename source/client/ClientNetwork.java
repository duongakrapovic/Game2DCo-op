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
public class ClientNetwork {
    public enum State { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

    public interface Listener {
        void onMessage(String line);
        default void onSnapshot(GameData.WorldSnapshot snapshot) { onMessage(GameProtocol.serializeSnapshot(snapshot)); }
        void onStateChanged(State s, String info);
        void onSessionLost(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private volatile Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile State state = State.CLOSED;
    private volatile boolean running = false;

    // UDP Gameplay socket & threads
    private volatile DatagramSocket gameUdpSocket;
    private final GameProtocol.SnapshotDecoder snapshotDecoder = new GameProtocol.SnapshotDecoder();
    private volatile boolean gameplayActive;
    private volatile boolean resuming;
    private volatile boolean tcpReady;
    private InetAddress serverAddress;
    private Thread udpReceiverThread;
    private Thread udpHeartbeatThread;
    private volatile boolean udpRegistered = false;
    private volatile long gameplayGeneration;
    private volatile boolean roomJoined;

    private volatile String myId = null;
    private volatile String sessionToken = null;
    private long reconnectCount = 0;

    private volatile double rttMs = 0;
    private volatile double jitterMs = 0;

    private volatile int simulatedLagMs = 0;
    private final ScheduledExecutorService lagScheduler = Executors.newScheduledThreadPool(1);

    public ClientNetwork(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void connect() {
        if (running) return;
        running = true;
        changeState(State.CONNECTING, "Đang kết nối tới " + host + ":" + port);

        new Thread(this::connectionLoop, "NetClient-Loop").start();
    }

    private synchronized void initializeUdp() throws SocketException {
        if (gameUdpSocket != null && !gameUdpSocket.isClosed()) return;
        gameUdpSocket = new DatagramSocket();
        gameUdpSocket.connect(serverAddress, port);
        udpReceiverThread = new Thread(this::udpReceiveLoop, "UDP-Game-Receiver");
        udpReceiverThread.setDaemon(true); udpReceiverThread.start();
        udpHeartbeatThread = new Thread(this::udpHeartbeatLoop, "UDP-Game-Heartbeat");
        udpHeartbeatThread.setDaemon(true); udpHeartbeatThread.start();
    }

    public boolean isReady() { return tcpReady && running; }

    public void requestGameUdpRegistration() {
        if (!isReady() || resuming || !roomJoined || sessionToken == null) return;
        udpRegistered = false;
        sendUdpRaw(GameProtocol.createGameUdpPacket(Common.UDP_GAME_REGISTER,
                sessionToken + "|" + gameplayGeneration));
    }

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
                connected.connect(new java.net.InetSocketAddress(host, port), 5000);
                if (!running) { connected.close(); break; }
                socket.setTcpNoDelay(true);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                serverAddress = connected.getInetAddress();
                try { initializeUdp(); }
                catch (SocketException udpError) { System.err.println("[ClientNetwork] UDP: " + udpError.getMessage()); }
                tcpReady = false;
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

    private void udpHeartbeatLoop() {
        long lastRegisterAttempt = 0;
        long lastPingAttempt = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
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

    public void sendGameInputUdp(String inputPayload) {
        if (serverAddress == null || gameUdpSocket == null || gameUdpSocket.isClosed()) return;
        int ping = (int) Math.round(rttMs);
        String fullPayload = inputPayload + ":" + ping;
        byte[] packetData = GameProtocol.createGameUdpPacket(Common.UDP_GAME_INPUT, fullPayload);
        if (simulatedLagMs > 0) {
            lagScheduler.schedule(() -> sendUdpRaw(packetData), simulatedLagMs, TimeUnit.MILLISECONDS);
        } else {
            sendUdpRaw(packetData);
        }
    }

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

    public void send(String msg) {
        if (simulatedLagMs > 0) {
            lagScheduler.schedule(() -> sendRaw(msg), simulatedLagMs, TimeUnit.MILLISECONDS);
        } else {
            sendRaw(msg);
        }
    }

    private synchronized void sendRaw(String msg) {
        if (running && out != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            out.print(msg + "\n");
            out.flush();
        }
    }

    public void simulateConnectionDrop() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    public void setLagMs(int ms) { this.simulatedLagMs = ms; }
    public int getLagMs() { return simulatedLagMs; }
    public double getRttMs() { return rttMs; }
    public double getJitterMs() { return jitterMs; }

    public String getMyId() { return myId; }
    public String getSessionToken() { return sessionToken; }
    public State getState() { return state; }
    public boolean isGameplayActive() { return gameplayActive; }
    public long getGameplayGeneration() { return gameplayGeneration; }
    public long getReconnectCount() { return reconnectCount; }

    private void changeState(State s, String info) {
        this.state = s;
        listener.onStateChanged(s, info);
    }

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
