package source.client;

import source.common.GameProtocol;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class ClientNetwork {
    public enum State { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

    public interface Listener {
        void onMessage(String line);
        void onStateChanged(State s, String info);
        void onSessionLost(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile State state = State.CLOSED;
    private volatile boolean running = false;

    private String myId = null;
    private String sessionToken = null;
    private long reconnectCount = 0;

    private double rttMs = 0;
    private double jitterMs = 0;
    private long lastPingSentTime = 0;

    private int simulatedLagMs = 0;
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

    private void connectionLoop() {
        int attempt = 0;
        while (running) {
            try {
                if (attempt > 0) {
                    changeState(State.RECONNECTING, "Mất kết nối - đang kết nối lại (lần thứ " + attempt + ")...");
                    Thread.sleep(Math.min(10000L * attempt, 3000L));
                }

                socket = new Socket(host, port);
                socket.setTcpNoDelay(true);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                if (sessionToken != null) {
                    out.print("RESUME:" + sessionToken + "\n");
                    out.flush();
                }

                changeState(State.CONNECTED, "Đã kết nối");
                attempt = 0;
                reconnectCount++;

                ScheduledExecutorService pingExec = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "Client-Ping");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    pingExec.scheduleAtFixedRate(() -> {
                        if (socket != null && socket.isConnected() && !socket.isClosed()) {
                            lastPingSentTime = System.nanoTime();
                            sendRaw("PING:" + lastPingSentTime);
                        }
                    }, 1000, 1000, TimeUnit.MILLISECONDS);

                    String line;
                    while ((line = GameProtocol.readLineLimited(in, GameProtocol.MAX_LINE_LENGTH_SERVER)) != null) {
                        final String msg = line;
                        if (msg.startsWith("WELCOME:")) {
                            String[] p = msg.split(":");
                            if (p.length >= 2) myId = p[1];
                            if (p.length >= 3) sessionToken = p[2];
                        } else if (msg.startsWith("PONG:")) {
                            try {
                                long sent = Long.parseLong(msg.substring(5));
                                double rtt = (System.nanoTime() - sent) / 1e6;
                                jitterMs = Math.abs(rtt - rttMs) * 0.2 + jitterMs * 0.8;
                                rttMs = rtt;
                            } catch (Exception ignored) {}
                        }

                        if (simulatedLagMs > 0) {
                            lagScheduler.schedule(() -> listener.onMessage(msg), simulatedLagMs, TimeUnit.MILLISECONDS);
                        } else {
                            listener.onMessage(msg);
                        }
                    }
                } finally {
                    pingExec.shutdownNow();
                }
            } catch (Exception e) {
                if (!running) break;
                attempt++;
            } finally {
                closeQuietly();
            }
        }
        changeState(State.CLOSED, "Đã đóng kết nối");
    }

    public void send(String msg) {
        if (simulatedLagMs > 0) {
            lagScheduler.schedule(() -> sendRaw(msg), simulatedLagMs, TimeUnit.MILLISECONDS);
        } else {
            sendRaw(msg);
        }
    }

    private synchronized void sendRaw(String msg) {
        if (out != null && socket != null && socket.isConnected()) {
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
    public long getReconnectCount() { return reconnectCount; }

    private void changeState(State s, String info) {
        this.state = s;
        listener.onStateChanged(s, info);
    }

    public synchronized void close() {
        running = false;
        closeQuietly();
        lagScheduler.shutdownNow();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}