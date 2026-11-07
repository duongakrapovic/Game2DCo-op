package source.server;

import source.common.Common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ServerApp {

    private static final long RECONNECT_GRACE_SECONDS = 20;

    private final Map<String, ServerGame> rooms = new ConcurrentHashMap<>();
    private final Map<String, ServerSession> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> sessionExpirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Session-Expiry");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ServerNetwork serverNetwork;
    private volatile boolean running;

    public static void main(String[] args) {
        new ServerApp().start();
    }

    public void start() {
        System.out.println("==================================================");
        System.out.println("   DUNGEON CO-OP - DEDICATED GAME SERVER          ");
        System.out.println("==================================================");

        try {
            running = true;
            serverNetwork = new ServerNetwork(Common.TCP_PORT, this);
            serverNetwork.start();
            System.out.println("[ServerApp] TCP va UDP da mo tai Port: " + Common.TCP_PORT);
            System.out.println("[ServerApp] Server da san sang phuc vu! (Go 'exit' de dung)");

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while (running && (line = console.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[ServerApp] Loi khoi tao Server: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    public synchronized void shutdown() {
        if (!running && serverNetwork == null && rooms.isEmpty() && sessionsByToken.isEmpty()) {
            return;
        }

        running = false;
        System.out.println("[ServerApp] Dang dong Server...");
        ServerNetwork network = serverNetwork;
        serverNetwork = null;
        if (network != null) {
            network.stop();
        }
        for (ServerGame room : rooms.values()) {
            room.stop();
        }
        rooms.clear();
        sessionsByToken.clear();
        for (ScheduledFuture<?> expiration : sessionExpirations.values()) {
            expiration.cancel(false);
        }
        sessionExpirations.clear();
        sessionScheduler.shutdownNow();
        System.out.println("[ServerApp] Da tat Server hoan toan.");
    }

    public ServerGame getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public boolean addRoom(ServerGame room) {
        return rooms.putIfAbsent(room.getRoomId(), room) == null;
    }

    public void removeRoom(String roomId) {
        ServerGame room = rooms.remove(roomId);
        if (room != null) {
            room.stop();
        }
    }

    public ServerSession createSession(String playerId, String playerName) {
        String token = UUID.randomUUID().toString().replace("-", "");
        ServerSession session = new ServerSession(playerId, token, playerName);
        sessionsByToken.put(token, session);
        return session;
    }

    public ServerSession getSessionByToken(String token) {
        return sessionsByToken.get(token);
    }

    public synchronized ServerGame resumeSession(
            String token, ServerSession temporarySession, ServerNetwork.ClientHandler handler) {
        ServerSession session = sessionsByToken.get(token);
        if (session == null || session.getRoomId() == null) {
            return null;
        }

        ServerGame room = rooms.get(session.getRoomId());
        if (room == null || !room.getPlayers().containsKey(session.getPlayerId())) {
            return null;
        }

        ScheduledFuture<?> expiration = sessionExpirations.remove(session.getToken());
        if (expiration != null) {
            expiration.cancel(false);
        }
        if (temporarySession != null) {
            sessionsByToken.put(temporarySession.getToken(), session);
        }

        session.setClientHandler(handler);
        session.touch();
        room.updatePlayerHandler(session.getPlayerId(), handler);
        return room;
    }

    public synchronized void handleDisconnect(
            ServerSession session, ServerNetwork.ClientHandler handler) {
        if (session == null || session.getClientHandler() != handler) {
            return;
        }

        session.setClientHandler(null);
        String roomId = session.getRoomId();
        ServerGame room = roomId == null ? null : rooms.get(roomId);
        if (room == null || !room.getPlayers().containsKey(session.getPlayerId()) || !running) {
            removeSession(session);
            return;
        }

        room.detachPlayerHandler(session.getPlayerId(), handler);
        ScheduledFuture<?> nextExpiration = sessionScheduler.schedule(
                () -> expireSession(session), RECONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = sessionExpirations.put(session.getToken(), nextExpiration);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private synchronized void expireSession(ServerSession session) {
        sessionExpirations.remove(session.getToken());
        if (session.getClientHandler() != null) {
            return;
        }

        ServerGame room = session.getRoomId() == null ? null : rooms.get(session.getRoomId());
        if (room != null) {
            room.removePlayer(session.getPlayerId());
            if (room.isEmpty()) {
                removeRoom(room.getRoomId());
            }
        }
        removeSession(session);
    }

    private void removeSession(ServerSession session) {
        sessionsByToken.entrySet().removeIf(entry -> entry.getValue() == session);
        ScheduledFuture<?> expiration = sessionExpirations.remove(session.getToken());
        if (expiration != null) {
            expiration.cancel(false);
        }
    }

    public String getRoomListMessage() {
        StringBuilder message = new StringBuilder(Common.CMD_ROOM_LIST);
        for (ServerGame room : rooms.values()) {
            message.append(";")
                    .append(room.getRoomId())
                    .append(" (")
                    .append(room.getPlayerCount())
                    .append("/")
                    .append(Common.MAX_PLAYERS_PER_ROOM)
                    .append(")");
        }
        return message.toString();
    }
}
