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
public class SaveManager {

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

    public static class OfflineSaveData {
        public String mode = "OFFLINE";
        public String lastUpdated = "";
        public String playerName = "Player";
        public float x = Common.SPAWN_X;
        public float y = Common.SPAWN_Y;
        public int hp = 100;
        public int maxHp = 100;
    }

    public static class PlayerSlotData {
        public int slotIndex;
        public String status = "EMPTY"; // "ACTIVE", "DISCONNECTED", "EMPTY"
        public String playerName = "";
        public float x = 0.0f;
        public float y = 0.0f;
        public int hp = 0;
        public int maxHp = 100;

        public PlayerSlotData() {}

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

    public static class OnlineSaveData {
        public String mode = "ONLINE";
        public String roomName = "Room1";
        public int maxPlayers = 4;
        public String lastUpdated = "";
        public List<PlayerSlotData> slots = new ArrayList<>(4);

        public OnlineSaveData() {
            for (int i = 0; i < 4; i++) {
                slots.add(new PlayerSlotData(i));
            }
        }

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

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[SaveManager] Loi ghi online_" + data.roomName + ".json: " + e.getMessage());
        }
    }

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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }

    private static String extractString(String text, String regex, String def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return def;
    }

    private static int extractInt(String text, String regex, int def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

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
