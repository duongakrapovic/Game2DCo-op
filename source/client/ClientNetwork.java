package source.client;

import source.common.GameProtocol;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private volatile String sessionToken = null;
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Network-Heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public ClientNetwork(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void connect() {
        if (running) return;
        running = true;
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (state == State.CONNECTED) {
                sendRaw("PING:" + System.nanoTime());
            }
        }, 1, 1, TimeUnit.SECONDS);
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

                String line;
                while ((line = GameProtocol.readLineLimited(in, GameProtocol.MAX_LINE_LENGTH_SERVER)) != null) {
                    final String msg = line;
                    if (msg.startsWith("WELCOME:")) {
                        String[] p = msg.split(":");
                        if (p.length >= 2) myId = p[1];
                        if (p.length >= 3) sessionToken = p[2];
                    }

                    listener.onMessage(msg);
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
        sendRaw(msg);
    }

    private synchronized void sendRaw(String msg) {
        if (out != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            out.print(msg + "\n");
            out.flush();
            if (out.checkError()) closeQuietly();
        }
    }

    public String getMyId() { return myId; }
    public String getSessionToken() { return sessionToken; }
    public State getState() { return state; }

    private void changeState(State s, String info) {
        this.state = s;
        listener.onStateChanged(s, info);
    }

    public synchronized void close() {
        running = false;
        heartbeatScheduler.shutdownNow();
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}