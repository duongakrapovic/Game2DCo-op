package source.server;

import source.common.Common;
import source.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Luồng xử lý MỘT kết nối client: đọc từng dòng lệnh, kiểm tra hợp lệ, rồi gọi ServerApp / GameRoom.
 *
 * Nguyên tắc: không tin dữ liệu từ client — dòng quá dài, thiếu tham số, sai kiểu, quá nhiều message/giây
 * đều bị từ chối (ERROR) hoặc ngắt kết nối, KHÔNG làm chết luồng như trước.
 */
class ClientHandler implements Runnable {
    private final Socket socket;
    private Session session;           // có thể đổi sang phiên cũ khi RESUME thành công
    private OutChannel channel;

    // chống flood
    private long windowStart = System.currentTimeMillis();
    private int windowCount = 0;

    ClientHandler(Socket socket, String pId) {
        this.socket = socket;
        this.session = new Session(ServerApp.newToken(), pId);
    }

    @Override
    public void run() {
        ServerStats.activeClients.incrementAndGet();
        String who = session.pId;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            channel = new OutChannel(socket, session.pId);
            session.channel = channel;
            session.socket = socket;
            session.connected = true;
            ServerApp.sessions.put(session.token, session);

            channel.sendReliable(Common.CMD_WELCOME + ":" + session.pId + ":" + session.token);
            channel.sendReliable(ServerApp.roomListMessage());

            String line;
            while ((line = Protocol.readLineLimited(in, Protocol.MAX_LINE_LENGTH_SERVER)) != null) {
                ServerStats.msgsIn.incrementAndGet();
                ServerStats.bytesIn.addAndGet(line.length() + 1L);
                if (isFlooding()) {
                    System.out.println("[" + who + "] gửi quá nhiều message/giây -> ngắt kết nối");
                    break;
                }
                try {
                    handle(line);
                } catch (RuntimeException e) { // lỗi bất ngờ trong 1 lệnh không được làm sập cả kết nối
                    System.err.println("[" + who + "] lỗi xử lý '" + shorten(line) + "': " + e);
                    channel.sendReliable(Common.CMD_ERROR + ":Lệnh không hợp lệ");
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[" + who + "] timeout (không có dữ liệu " + Protocol.SERVER_IDLE_TIMEOUT_MS / 1000 + "s)");
        } catch (IOException e) {
            System.out.println("[" + who + "] ngắt kết nối: " + e.getMessage());
        } finally {
            ServerStats.activeClients.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) {}
            if (channel != null) {
                ServerApp.onSocketClosed(session, channel);
                channel.close();
            }
        }
    }

    private boolean isFlooding() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000) { windowStart = now; windowCount = 0; }
        return ++windowCount > Protocol.MAX_MSGS_PER_SEC;
    }

    private void handle(String line) {
        String[] parts = line.split(":", -1);
        String cmd = parts[0];

        switch (cmd) {
            case Common.CMD_PING:
                if (parts.length >= 2 && parts[1].length() <= 24) {
                    channel.sendReliable(Common.CMD_PONG + ":" + parts[1]);
                }
                break;

            case Common.CMD_ROOM_LIST:
                channel.sendReliable(ServerApp.roomListMessage());
                break;

            case Common.CMD_CREATE_ROOM:
            case Common.CMD_JOIN_ROOM: {
                if (parts.length < 3) { error("Thiếu tham số: " + cmd + ":<tênPhòng>:<tênNgườiChơi>"); break; }
                String room = Protocol.sanitizeRoom(parts[1]);
                String name = Protocol.sanitizeName(parts[2], "Player_" + session.pId);
                if (room.isEmpty()) { error("Tên phòng không hợp lệ"); break; }
                String err = cmd.equals(Common.CMD_CREATE_ROOM)
                        ? ServerApp.createRoom(session, room, name)
                        : ServerApp.joinRoom(session, room, name);
                if (err != null) error(err);
                else channel.sendReliable(Common.CMD_JOINED + ":" + room);
                break;
            }

            case Common.CMD_LEAVE_ROOM:
                if (session.room != null) {
                    ServerApp.leaveRoom(session);
                    channel.sendReliable(Common.CMD_LEFT);
                    channel.sendReliable(ServerApp.roomListMessage());
                }
                break;

            case Common.CMD_INPUT: {
                GameRoom room = session.room;
                if (room == null || parts.length < 5) break;
                Boolean l = parseBool(parts[1]), r = parseBool(parts[2]), u = parseBool(parts[3]), d = parseBool(parts[4]);
                if (l == null || r == null || u == null || d == null) break;
                int seq = -1;
                if (parts.length >= 6) {
                    try { seq = Integer.parseInt(parts[5]); } catch (NumberFormatException ignored) { break; }
                }
                room.submitInput(session.pId, l, r, u, d, seq);
                break;
            }

            case Common.CMD_RESUME:
                resume(parts);
                break;

            default: {
                GameRoom room = session.room;
                if (room == null || !room.onCommand(session.pId, cmd, parts)) {
                    error("Lệnh không hợp lệ");
                }
            }
        }
    }

    /** RESUME:<token> — kết nối mới xin kế thừa phiên cũ (và chỗ trong phòng) sau khi rớt mạng. */
    private void resume(String[] parts) {
        if (parts.length < 2 || session.room != null) { channel.sendReliable(Common.CMD_RESUME_FAIL); return; }
        Session old = ServerApp.sessions.get(parts[1]);
        if (old == null || old == session) { channel.sendReliable(Common.CMD_RESUME_FAIL); return; }

        OutChannel oldChannel;
        Socket oldSocket;
        synchronized (ServerApp.LOCK) {
            GameRoom room = old.room;
            if (room == null) { channel.sendReliable(Common.CMD_RESUME_FAIL); return; }
            if (old.graceTask != null) old.graceTask.cancel(false);
            oldChannel = old.channel;
            oldSocket = old.socket;

            old.channel = channel;
            old.socket = socket;
            old.connected = true;
            room.attachWriter(old.pId, channel);

            ServerApp.sessions.remove(session.token); // bỏ phiên tạm của kết nối này
            session = old;
        }
        // Nếu kết nối cũ vẫn "treo" (chưa bị phát hiện là chết) thì đóng nó đi
        if (oldChannel != null && oldChannel != channel) oldChannel.close();
        if (oldSocket != null && oldSocket != socket) {
            try { oldSocket.close(); } catch (IOException ignored) {}
        }
        System.out.println("[" + session.pId + "] kết nối lại thành công, phòng " + session.room.roomName);
        channel.sendReliable(Common.CMD_WELCOME + ":" + session.pId + ":" + session.token);
        channel.sendReliable(Common.CMD_RESUMED + ":" + session.room.roomName);
    }

    private void error(String msg) {
        channel.sendReliable(Common.CMD_ERROR + ":" + msg);
    }

    private static Boolean parseBool(String s) {
        if (s.equals("true")) return Boolean.TRUE;
        if (s.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
