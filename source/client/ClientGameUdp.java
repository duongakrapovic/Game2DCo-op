package source.client;

import source.common.GameUdp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kênh UDP cho INPUT (gửi lên) và STATE (nhận xuống) của game — xem GameUdp.
 *
 * Quy trình: gửi REGISTER kèm token (lặp lại cho tới khi nhận REGISTERED) -> sẵn sàng (isReady) ->
 * ClientApp gửi INPUT mỗi tick bằng sendInput(). Nếu 3 giây không nghe được gì từ server thì isReady()
 * trả false và ClientApp tự quay về gửi INPUT qua TCP (server cũng tự quay về gửi STATE qua TCP).
 * Gói STATE cũ hơn gói đã nhận (đến sai thứ tự) bị bỏ.
 */
public class ClientGameUdp implements AutoCloseable {

    public interface Listener {
        /** text là chuỗi "STATE#PLAYERS:..." hoặc "STATE#MONSTERS:..." (gọi từ luồng nhận UDP). */
        void onState(String text);
    }

    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final byte[] registerPacket;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile boolean registered;
    private volatile long lastReceiveNanos;
    private int lastPlayersTick, lastMonstersTick;
    private boolean havePlayersTick, haveMonstersTick;

    public ClientGameUdp(String host, int port, String token, Listener listener) throws IOException {
        this.serverAddress = InetAddress.getByName(host);
        this.serverPort = port;
        this.listener = listener;
        this.registerPacket = GameUdp.register(token);
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(500);

        Thread receiver = new Thread(this::receiveLoop, "GameUdp-Recv");
        receiver.setDaemon(true);
        receiver.start();
        Thread registrar = new Thread(this::registerLoop, "GameUdp-Register");
        registrar.setDaemon(true);
        registrar.start();
    }

    /** Đã đăng ký và đang nghe được server qua UDP (trong 3 giây gần nhất). */
    public boolean isReady() {
        return registered && System.nanoTime() - lastReceiveNanos < GameUdp.UDP_ALIVE_MS * 1_000_000L;
    }

    public void sendInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
        send(GameUdp.input(seq, left, right, up, down, attack));
    }

    private void send(byte[] data) {
        if (!running.get()) return;
        try {
            socket.send(new DatagramPacket(data, data.length, serverAddress, serverPort));
        } catch (IOException e) {
            if (running.get()) System.err.println("[GameUdp] Loi gui: " + e.getMessage());
        }
    }

    private void registerLoop() {
        try {
            while (running.get()) {
                send(registerPacket);
                // Chưa sẵn sàng: thử lại nhanh; đã sẵn sàng: nhắc lại thưa để giữ ánh xạ NAT
                Thread.sleep(isReady() ? 2000 : 500);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[GameUdp.MAX_PACKET + 64];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
                int len = packet.getLength();
                if (!GameUdp.hasMagic(buffer, 0, len)) continue;
                byte type = GameUdp.type(buffer, 0);

                if (type == GameUdp.REGISTERED) {
                    registered = true;
                    lastReceiveNanos = System.nanoTime();
                } else if ((type == GameUdp.STATE_PLAYERS || type == GameUdp.STATE_MONSTERS)
                        && len > GameUdp.HEADER + 4) {
                    lastReceiveNanos = System.nanoTime();
                    int tick = GameUdp.getInt(buffer, GameUdp.HEADER);
                    if (!acceptTick(type, tick)) continue;
                    String text = new String(buffer, GameUdp.HEADER + 4, len - GameUdp.HEADER - 4,
                            StandardCharsets.UTF_8);
                    listener.onState(text);
                }
            } catch (SocketTimeoutException e) {
                // bình thường: chỉ để kiểm tra cờ running
            } catch (IOException e) {
                if (running.get()) System.err.println("[GameUdp] Loi nhan: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                System.err.println("[GameUdp] Goi khong hop le: " + e);
            }
        }
    }

    /** Bỏ gói STATE cũ hơn gói cùng loại đã nhận (UDP có thể đảo thứ tự). */
    private boolean acceptTick(byte type, int tick) {
        if (type == GameUdp.STATE_PLAYERS) {
            if (havePlayersTick && !GameUdp.newer(tick, lastPlayersTick)) return false;
            havePlayersTick = true;
            lastPlayersTick = tick;
        } else {
            if (haveMonstersTick && !GameUdp.newer(tick, lastMonstersTick)) return false;
            haveMonstersTick = true;
            lastMonstersTick = tick;
        }
        return true;
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            socket.close();
        }
    }
}
