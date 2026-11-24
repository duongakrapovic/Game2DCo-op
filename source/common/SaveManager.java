package source.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SAVEMANAGER.JAVA - Hệ thống lưu trữ save game đa chế độ (Offline & Online)
 * Sử dụng thuần Java chuẩn (không thư viện ngoài).
 */
/*
 * Định nghĩa DTO save và đọc/ghi JSON. Có một static initializer tạo thư mục saves khi lớp được nạp.
 * Parser hiện dựa regex và cắt chuỗi; ghi trực tiếp chưa nguyên tử. Không lưu socket, token, toàn bộ quái
 * hay drop.
 * OfflineSaveData chứa một nhân vật; PlayerSlotData chứa slotIndex/status/playerName/x/y/hp/maxHp;
 * OnlineSaveData có roomName/mode/maxPlayers/lastUpdated và bốn slot. Trường static SAVES_DIR trỏ saves
 * tương đối working directory, không phải database.
 */
public class SaveManager {

    /*
     * SAVES_DIR: Thư mục dữ liệu save tương đối gốc chạy chương trình.
     */
    private static final Path SAVES_DIR = Paths.get("saves");

    static {
        try {
            if (!Files.exists(SAVES_DIR)) {
                Files.createDirectories(SAVES_DIR);
            }
        } catch (IOException e) {
            System.err.println("[SaveManager] Khong the khoi tao thu muc saves: " + e.getMessage());
        }
    }

    /*
     * DTO tiến trình offline; không giữ toàn bộ quái hoặc item của thế giới.
     */
    public static class OfflineSaveData {
        /*
         * mode: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời điểm
         * ghi.
         */
        public String mode = "OFFLINE";
        /*
         * lastUpdated: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời
         * điểm ghi.
         */
        public String lastUpdated = "";
        /*
         * playerName: Tên hiển thị; save online hiện dùng tên để tìm lại slot, chưa là tài khoản xác thực.
         */
        public String playerName = "Player";
        /*
         * x: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float x = Common.SPAWN_X;
        /*
         * y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float y = Common.SPAWN_Y;
        /*
         * hp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int hp = 100;
        /*
         * maxHp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int maxHp = 100;
    }

    /*
     * Một slot tiến trình trong JSON; khác với ID player của phiên TCP.
     */
    public static class PlayerSlotData {
        /*
         * slotIndex: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời
         * điểm ghi.
         */
        public int slotIndex;
        /*
         * status: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời điểm
         * ghi.
         */
        public String status = "EMPTY"; // "ACTIVE", "DISCONNECTED", "EMPTY"
        /*
         * playerName: Tên hiển thị; save online hiện dùng tên để tìm lại slot, chưa là tài khoản xác thực.
         */
        public String playerName = "";
        /*
         * x: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float x = 0.0f;
        /*
         * y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float y = 0.0f;
        /*
         * hp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int hp = 0;
        /*
         * maxHp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int maxHp = 100;

        /*
         * Khởi tạo PlayerSlotData
         * Constructor rỗng không có lệnh trong body; field initializer vẫn đặt status EMPTY, tên rỗng,
         * x/y/HP=0 và maxHp=100.
         */
        public PlayerSlotData() {}

        /*
         * Khởi tạo PlayerSlotData
         * Gán chỉ số slot và các giá trị rỗng. Tạo một chỗ lưu chưa có chủ, không sinh player vào phòng.
         */
        public PlayerSlotData(int slotIndex) {
            this.slotIndex = slotIndex;
            this.status = "EMPTY";
            this.playerName = "";
            this.x = 0.0f;
            this.y = 0.0f;
            this.hp = 0;
            this.maxHp = 100;
        }
    }

    /*
     * DTO save của phòng, giữ bốn slot; không phải room đang chạy trong RAM.
     */
    public static class OnlineSaveData {
        /*
         * mode: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời điểm
         * ghi.
         */
        public String mode = "ONLINE";
        /*
         * roomName: Định danh hoặc tên phòng; không phải IP server hay token phiên.
         */
        public String roomName = "Room1";
        /*
         * maxPlayers: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời
         * điểm ghi.
         */
        public int maxPlayers = 4;
        /*
         * lastUpdated: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời
         * điểm ghi.
         */
        public String lastUpdated = "";
        /*
         * slots: Thông tin bản lưu JSON: các slot, chỉ số slot, trạng thái, chế độ, sức chứa và thời điểm
         * ghi.
         */
        public List<PlayerSlotData> slots = new ArrayList<>(4);

        /*
         * Khởi tạo OnlineSaveData
         * Tạo bốn PlayerSlotData có chỉ số 0–3 với cấu hình mặc định; chưa đọc file và chưa đặt timestamp
         * hiện tại như bản nhận roomName.
         */
        public OnlineSaveData() {
            for (int i = 0; i < 4; i++) {
                slots.add(new PlayerSlotData(i));
            }
        }

        /*
         * Khởi tạo OnlineSaveData
         * Gán tên phòng, lấy LocalDateTime hiện tại dạng ISO_LOCAL_DATE_TIME và tạo đủ bốn slot EMPTY. Đây
         * là dữ liệu save mới trong RAM.
         */
        public OnlineSaveData(String roomName) {
            this.roomName = roomName;
            this.lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            for (int i = 0; i < 4; i++) {
                slots.add(new PlayerSlotData(i));
            }
        }
    }

    // ==========================================
    // OFFLINE SAVE & LOAD
    // ==========================================

    /*
     * saveOffline()
     * Nhận tên/vị trí/HP và ghi JSON offline_save.json, cập nhật thời gian lưu, dùng UTF-8 và định dạng số
     * có dấu chấm. Ghi lỗi chỉ log stderr; void không báo một kết quả thành công rõ cho UI.
     */
    public static synchronized void saveOffline(String playerName, float x, float y, int hp, int maxHp) {
        try {
            if (!Files.exists(SAVES_DIR)) {
                Files.createDirectories(SAVES_DIR);
            }
            Path file = SAVES_DIR.resolve("offline_save.json");
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"mode\": \"OFFLINE\",\n");
            sb.append("  \"lastUpdated\": \"").append(timeStr).append("\",\n");
            sb.append("  \"player\": {\n");
            sb.append("    \"name\": \"").append(escapeJson(playerName)).append("\",\n");
            sb.append("    \"x\": ").append(fmt(x)).append(",\n");
            sb.append("    \"y\": ").append(fmt(y)).append(",\n");
            sb.append("    \"hp\": ").append(hp).append(",\n");
            sb.append("    \"maxHp\": ").append(maxHp).append("\n");
            sb.append("  }\n");
            sb.append("}\n");

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[SaveManager] Loi ghi offline_save.json: " + e.getMessage());
        }
    }

    /*
     * loadOfflineSave()
     * Gọi bản đọc không nhận map; nếu không có/đọc lỗi trả null thì tạo OfflineSaveData mặc định. Sau đó
     * chuẩn hóa x/y qua validatedPlayerSpawn và trả DTO đã kiểm. Overload nhận GameMap luôn cố có dữ liệu
     * mặc định, khác bản không tham số có thể trả null.
     */
    public static OfflineSaveData loadOfflineSave(GameMap map) {
        OfflineSaveData data = loadOfflineSave();
        if (data == null) data = new OfflineSaveData();
        java.awt.geom.Point2D.Float spawn = map.validatedPlayerSpawn(data.x, data.y);
        data.x = spawn.x; data.y = spawn.y;
        return data;
    }

    /*
     * loadOrCreateOnlineSave()
     * Gọi bản nhận roomName để đọc/tạo file rồi kiểm lại vị trí các slot không EMPTY bằng map. Slot rỗng
     * được giữ rỗng; không tạo bốn bot tại spawn.
     */
    public static OnlineSaveData loadOrCreateOnlineSave(String roomName, GameMap map) {
        OnlineSaveData data = loadOrCreateOnlineSave(roomName);
        for (PlayerSlotData slot : data.slots) {
            if ("EMPTY".equals(slot.status)) continue;
            java.awt.geom.Point2D.Float spawn = map.validatedPlayerSpawn(slot.x, slot.y);
            slot.x = spawn.x; slot.y = spawn.y;
        }
        return data;
    }

    /*
     * loadOfflineSave()
     * Đọc JSON offline nếu file có, trích các trường với giá trị mặc định khi cần. Không có file trả null;
     * lỗi parse/I/O log và trả null. Không tải quái hoặc item.
     */
    public static synchronized OfflineSaveData loadOfflineSave() {
        Path file = SAVES_DIR.resolve("offline_save.json");
        if (!Files.exists(file)) {
            return null;
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            OfflineSaveData data = new OfflineSaveData();

            data.mode = extractString(json, "\"mode\"\\s*:\\s*\"([^\"]*)\"", "OFFLINE");
            data.lastUpdated = extractString(json, "\"lastUpdated\"\\s*:\\s*\"([^\"]*)\"", "");
            data.playerName = extractString(json, "\"name\"\\s*:\\s*\"([^\"]*)\"", "Player");
            data.x = extractFloat(json, "\"x\"\\s*:\\s*([-0-9.]+)", Common.SPAWN_X);
            data.y = extractFloat(json, "\"y\"\\s*:\\s*([-0-9.]+)", Common.SPAWN_Y);
            data.hp = extractInt(json, "\"hp\"\\s*:\\s*([0-9]+)", 100);
            data.maxHp = extractInt(json, "\"maxHp\"\\s*:\\s*([0-9]+)", 100);

            return data;
        } catch (Exception e) {
            System.err.println("[SaveManager] Loi doc offline_save.json: " + e.getMessage());
            return null;
        }
    }

    // ==========================================
    // ONLINE SAVE & LOAD
    // ==========================================

    /*
     * saveOnline()
     * Ghi saves/online_<roomName>.json từ OnlineSaveData, cập nhật lastUpdated và xuất đủ slot. Hàm
     * synchronized nên nhiều phòng có thể chờ nhau; ghi truncate trực tiếp, lỗi chỉ log, chưa có
     * backup/atomic move.
     * Dựng JSON luôn gồm bốn slot và cập nhật lastUpdated, rồi ghi UTF-8 theo CREATE và TRUNCATE_EXISTING.
     * Đây là ghi thẳng vào file, chưa có ghi file tạm rồi atomic rename hoặc bản backup tự động. Khóa
     * static synchronized tuần tự hóa các lời gọi cùng class, nhưng I/O chậm vẫn có thể giữ room loop khi
     * caller đang giữ khóa phòng.
     */
    public static synchronized void saveOnline(OnlineSaveData data) {
        if (data == null || data.roomName == null || data.roomName.isEmpty()) {
            return;
        }

        try {
            if (!Files.exists(SAVES_DIR)) {
                Files.createDirectories(SAVES_DIR);
            }
            Path file = SAVES_DIR.resolve("online_" + data.roomName + ".json");
            data.lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"mode\": \"ONLINE\",\n");
            sb.append("  \"roomName\": \"").append(escapeJson(data.roomName)).append("\",\n");
            sb.append("  \"maxPlayers\": 4,\n");
            sb.append("  \"lastUpdated\": \"").append(data.lastUpdated).append("\",\n");
            sb.append("  \"slots\": [\n");

            // Luôn đảm bảo đúng 4 slot
            for (int i = 0; i < 4; i++) {
                PlayerSlotData slot = (i < data.slots.size()) ? data.slots.get(i) : new PlayerSlotData(i);
                sb.append("    {\n");
                sb.append("      \"slotIndex\": ").append(i).append(",\n");
                sb.append("      \"status\": \"").append(escapeJson(slot.status)).append("\",\n");
                sb.append("      \"playerName\": \"").append(escapeJson(slot.playerName)).append("\",\n");
                sb.append("      \"x\": ").append(fmt(slot.x)).append(",\n");
                sb.append("      \"y\": ").append(fmt(slot.y)).append(",\n");
                sb.append("      \"hp\": ").append(slot.hp).append(",\n");
                sb.append("      \"maxHp\": ").append(slot.maxHp).append("\n");
                sb.append("    }").append(i < 3 ? ",\n" : "\n");
            }

            sb.append("  ]\n");
            sb.append("}\n");

            /*
             * Ghi thẳng JSON UTF-8 và truncate file cũ; chưa có file tạm, atomic rename hoặc backup tự
             * động.
             */
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[SaveManager] Loi ghi online_" + data.roomName + ".json: " + e.getMessage());
        }
    }

    /*
     * loadOrCreateOnlineSave()
     * Nếu không có file thì tạo DTO bốn slot và lưu; nếu có thì trích thông tin phòng/slot bằng regex và
     * cấu trúc chuỗi. Lỗi đọc có nhánh tạo/lưu fallback nên cần thận trọng với nguy cơ ghi đè save hỏng.
     * Hàm không xác thực quyền sở hữu người chơi.
     * Không có file thì tạo DTO bốn slot mới và lưu. Có file thì dùng regex cùng tách chuỗi để đọc các
     * trường, giữ mặc định cho phần thiếu. Đây không phải parser JSON tổng quát; khi parse ném exception,
     * code tạo save fallback và ghi lại, nên cơ chế hiện tại chưa bảo toàn file hỏng để khôi phục thủ
     * công.
     */
    public static synchronized OnlineSaveData loadOrCreateOnlineSave(String roomName) {
        Path file = SAVES_DIR.resolve("online_" + roomName + ".json");
        if (!Files.exists(file)) {
            OnlineSaveData fresh = new OnlineSaveData(roomName);
            saveOnline(fresh);
            return fresh;
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            OnlineSaveData data = new OnlineSaveData(roomName);

            data.mode = extractString(json, "\"mode\"\\s*:\\s*\"([^\"]*)\"", "ONLINE");
            data.lastUpdated = extractString(json, "\"lastUpdated\"\\s*:\\s*\"([^\"]*)\"", "");

            // Parse từng block slot trong mảng "slots"
            int slotsStart = json.indexOf("\"slots\"");
            if (slotsStart != -1) {
                int arrayStart = json.indexOf('[', slotsStart);
                int arrayEnd = json.indexOf(']', arrayStart);
                if (arrayStart != -1 && arrayEnd != -1) {
                    String slotsArrayContent = json.substring(arrayStart + 1, arrayEnd);
                    Pattern blockPattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher m = blockPattern.matcher(slotsArrayContent);
                    int count = 0;
                    while (m.find() && count < 4) {
                        String block = m.group(1);
                        PlayerSlotData slot = data.slots.get(count);
                        slot.slotIndex = extractInt(block, "\"slotIndex\"\\s*:\\s*([0-9]+)", count);
                        slot.status = extractString(block, "\"status\"\\s*:\\s*\"([^\"]*)\"", "EMPTY");
                        slot.playerName = extractString(block, "\"playerName\"\\s*:\\s*\"([^\"]*)\"", "");
                        slot.x = extractFloat(block, "\"x\"\\s*:\\s*([-0-9.]+)", 0.0f);
                        slot.y = extractFloat(block, "\"y\"\\s*:\\s*([-0-9.]+)", 0.0f);
                        slot.hp = extractInt(block, "\"hp\"\\s*:\\s*([0-9]+)", 0);
                        slot.maxHp = extractInt(block, "\"maxHp\"\\s*:\\s*([0-9]+)", 100);
                        count++;
                    }
                }
            }

            return data;
        } catch (Exception e) {
            System.err.println("[SaveManager] Loi doc online_" + roomName + ".json: " + e.getMessage());
            OnlineSaveData fallback = new OnlineSaveData(roomName);
            saveOnline(fallback);
            return fallback;
        }
    }

    // ==========================================
    // TIỆN ÍCH HELPER
    // ==========================================

    /*
     * escapeJson()
     * Escape dấu gạch chéo ngược và dấu nháy kép trước khi ghi chuỗi. Chưa xử lý đầy đủ mọi ký tự điều
     * khiển như một thư viện JSON chuẩn.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /*
     * fmt()
     * Làm tròn bằng Math.round(v × 100) / 100 rồi Float.toString để xuất số tọa độ. Không cố định hai chữ
     * số sau dấu chấm; save có mất phần lẻ nhỏ hơn mức làm tròn.
     */
    private static String fmt(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }

    /*
     * extractString()
     * Chạy regex, trả nhóm bắt được hoặc def khi không khớp. Đây là helper parser thủ công, không xác minh
     * cả JSON theo schema.
     */
    private static String extractString(String text, String regex, String def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return def;
    }

    /*
     * extractInt()
     * Tìm nhóm regex rồi parse integer; lỗi hoặc không khớp trả def. Caller chịu trách nhiệm giới hạn
     * nghiệp vụ như số slot.
     */
    private static int extractInt(String text, String regex, int def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /*
     * extractFloat()
     * Tìm nhóm regex và parse float, không đọc được trả def. Kiểm vị trí/float hợp lệ khi load theo map
     * nằm ở overload nhận GameMap.
     */
    private static float extractFloat(String text, String regex, float def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            try {
                return Float.parseFloat(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
