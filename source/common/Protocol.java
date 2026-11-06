package source.common;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Tiện ích xử lý giao thức mạng, giới hạn dòng, đọc an toàn và chuẩn hóa tên/phòng.
 */
public class Protocol {
    public static final int MAX_LINE_LENGTH_CLIENT = 512;
    public static final int MAX_LINE_LENGTH_SERVER = 1024;

    /** Đọc một dòng từ BufferedReader nhưng giới hạn tối đa số ký tự để chống tấn công rác */
    public static String readLineLimited(BufferedReader in, int maxLength) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        int count = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            sb.append((char) c);
            count++;
            if (count > maxLength) {
                throw new IOException("Line is too long (exceeds " + maxLength + " characters)");
            }
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    /** Lọc ký tự cấm trong tên người chơi (không cho chứa ;,:|, khoảng trắng thừa) */
    public static String sanitizeName(String name) {
        if (name == null) return "Player";
        String clean = name.replaceAll("[;,:|\\s]", "").trim();
        if (clean.isEmpty()) return "Player";
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    /** Lọc tên phòng */
    public static String sanitizeRoom(String room) {
        if (room == null) return "Room1";
        // Thay khoảng trắng thành _ và xóa các ký tự cấm phân cách
        String clean = room.replaceAll("\\s+", "_").replaceAll("[;,:|]", "").trim();
        if (clean.isEmpty()) return "Room1";
        return clean.length() > 20 ? clean.substring(0, 20) : clean;
    }

    /** Format số thực 2 chữ số thập phân cho tọa độ gửi qua mạng */
    public static String fmt2(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }
}