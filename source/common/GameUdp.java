package source.common;

import java.nio.charset.StandardCharsets;

/**
 * GAMEUDP.JAVA - Định dạng gói UDP cho dữ liệu game thời gian thực (INPUT và STATE).
 *
 * UDP dùng chung cổng với TCP (Common.TCP_PORT) và chung socket với voice (magic "VCH1"):
 * gói game bắt đầu bằng magic "GAM1" để server phân biệt.
 *
 *   [ 'G','A','M','1' ][ type: 1 byte ][ payload ]
 *
 *   REGISTER        Client -> Server   payload = token phiên (UTF-8)
 *   REGISTERED      Server -> Client   (không có payload)
 *   INPUT           Client -> Server   payload = seq (int32) + cờ phím (1 byte, xem FLAG_*)
 *   STATE_PLAYERS   Server -> Client   payload = tick (int32) + chuỗi "STATE#PLAYERS:..." (UTF-8)
 *   STATE_MONSTERS  Server -> Client   payload = tick (int32) + chuỗi "STATE#MONSTERS:..." (UTF-8)
 *
 * Vì sao UDP: trạng thái thế giới liên tục bị gói mới hơn thay thế, nên mất một gói không đáng kể,
 * còn TCP sẽ chặn mọi gói sau nó khi phải gửi lại (gây giật). Các lệnh cần chắc chắn
 * (phòng, token, kết nối lại) vẫn đi bằng TCP.
 */
public final class GameUdp {
    private GameUdp() {}

    public static final byte[] MAGIC = {'G', 'A', 'M', '1'};
    public static final int HEADER = MAGIC.length + 1;

    public static final byte REGISTER = 1;
    public static final byte REGISTERED = 2;
    public static final byte INPUT = 3;
    public static final byte STATE_PLAYERS = 4;
    public static final byte STATE_MONSTERS = 5;

    public static final int FLAG_LEFT = 1, FLAG_RIGHT = 2, FLAG_UP = 4, FLAG_DOWN = 8, FLAG_ATTACK = 16;

    /** Giới hạn kích thước gói an toàn (dưới MTU thông thường). Gói lớn hơn sẽ đi bằng TCP. */
    public static final int MAX_PACKET = 1200;
    /** Quái chỉ gửi mỗi N tick (60/3 = 20 lần/giây); client nội suy nên vẫn mượt. */
    public static final int MONSTER_EVERY_TICKS = 3;
    /** Server coi UDP của client là "sống" nếu nghe được gì từ nó trong khoảng này. */
    public static final long UDP_ALIVE_MS = 3000;
    /** Server ngừng di chuyển người chơi nếu không nhận input UDP trong khoảng này (chống kẹt phím). */
    public static final long INPUT_TIMEOUT_MS = 300;

    public static boolean hasMagic(byte[] data, int off, int len) {
        if (len < HEADER) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[off + i] != MAGIC[i]) return false;
        }
        return true;
    }

    private static byte[] header(byte type, int payloadLen) {
        byte[] p = new byte[HEADER + payloadLen];
        System.arraycopy(MAGIC, 0, p, 0, MAGIC.length);
        p[MAGIC.length] = type;
        return p;
    }

    public static byte[] register(String token) {
        byte[] t = token.getBytes(StandardCharsets.UTF_8);
        byte[] p = header(REGISTER, t.length);
        System.arraycopy(t, 0, p, HEADER, t.length);
        return p;
    }

    public static byte[] registered() {
        return header(REGISTERED, 0);
    }

    public static byte[] input(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
        byte[] p = header(INPUT, 5);
        putInt(p, HEADER, seq);
        p[HEADER + 4] = (byte) ((left ? FLAG_LEFT : 0) | (right ? FLAG_RIGHT : 0)
                | (up ? FLAG_UP : 0) | (down ? FLAG_DOWN : 0) | (attack ? FLAG_ATTACK : 0));
        return p;
    }

    public static byte[] state(byte type, int tick, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] p = header(type, 4 + t.length);
        putInt(p, HEADER, tick);
        System.arraycopy(t, 0, p, HEADER + 4, t.length);
        return p;
    }

    public static byte type(byte[] data, int off) {
        return data[off + MAGIC.length];
    }

    public static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** a có mới hơn b không (so sánh tick/seq an toàn khi tràn số). */
    public static boolean newer(int a, int b) {
        return (a - b) > 0;
    }
}
