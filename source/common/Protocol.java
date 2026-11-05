package source.common;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Hằng số và hàm tiện ích của giao thức mạng (dùng chung cho server, client và tools).
 *
 * Giao thức: TCP, text, mỗi message là 1 dòng kết thúc bằng '\n', mã hóa UTF-8.
 * Các trường phân cách bằng ':' (lệnh) và ';' ',' (trạng thái) nên tên người chơi / tên phòng
 * KHÔNG được chứa các ký tự này -> luôn đi qua sanitize*() trước khi gửi hoặc xử lý.
 */
public final class Protocol {
    private Protocol() {}

    public static final int MAX_LINE_LENGTH_SERVER = 512;      // Client -> Server chỉ gửi lệnh ngắn
    public static final int MAX_LINE_LENGTH_CLIENT = 64 * 1024; // Server -> Client có thể gửi STATE dài
    public static final int MAX_NAME_LENGTH = 16;
    public static final int MAX_ROOM_NAME_LENGTH = 20;

    public static final int PING_INTERVAL_MS = 1_000;
    public static final int SERVER_IDLE_TIMEOUT_MS = 10_000; // Server không nhận gì trong 10s -> coi là rớt
    public static final int CLIENT_IDLE_TIMEOUT_MS = 6_000;  // Client không nhận gì trong 6s -> coi là rớt
    public static final int RECONNECT_GRACE_MS = 20_000;     // Server giữ chỗ người chơi rớt mạng 20s
    public static final int CLIENT_RECONNECT_WINDOW_MS = 18_000;
    public static final int MAX_MSGS_PER_SEC = 300;          // Chống flood

    /** Tên người chơi: bỏ ký tự điều khiển và ký tự phân cách giao thức, cắt độ dài. */
    public static String sanitizeName(String raw, String fallback) {
        String s = clean(raw, false);
        if (s.length() > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Tên phòng: như tên người chơi nhưng không có khoảng trắng (client tách tên phòng bằng dấu cách). */
    public static String sanitizeRoom(String raw) {
        String s = clean(raw, true);
        if (s.length() > MAX_ROOM_NAME_LENGTH) s = s.substring(0, MAX_ROOM_NAME_LENGTH);
        return s;
    }

    private static String clean(String raw, boolean noSpaces) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ';' || c == ',' || c == ':' || c == '|') continue;
            if (Character.isWhitespace(c)) {
                sb.append(noSpaces ? '_' : ' ');
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** Số thực 2 chữ số thập phân, dùng khi gửi tọa độ (đủ chính xác để client reconcile). */
    public static String fmt2(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }

    /**
     * readLine có giới hạn độ dài — chặn client gửi một "dòng" khổng lồ làm tràn bộ nhớ server.
     * @return dòng đọc được, hoặc null khi hết luồng. Ném IOException nếu dòng vượt quá max.
     */
    public static String readLineLimited(BufferedReader in, int max) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= max) throw new IOException("Dòng quá dài (> " + max + " ký tự)");
            sb.append((char) c);
        }
        return null;
    }
}
