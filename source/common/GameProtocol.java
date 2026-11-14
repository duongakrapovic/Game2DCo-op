package source.common;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * GAMEPROTOCOL.JAVA - Tiện ích mã hóa & giải mã chuỗi TCP Packet
 */
public class GameProtocol {

    public static final int MAX_LINE_LENGTH_CLIENT = 512;
    public static final int MAX_LINE_LENGTH_SERVER = 2048;

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
                throw new IOException("Dòng lệnh quá dài (Vượt quá " + maxLength + " ký tự)");
            }
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    public static String sanitizeName(String name) {
        if (name == null) return "Player";
        String clean = name.replaceAll("[;,:|\\s]", "").trim();
        if (clean.isEmpty()) return "Player";
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    public static String sanitizeRoom(String room) {
        if (room == null) return "Room1";
        String clean = room.replaceAll("\\s+", "_").replaceAll("[;,:|]", "").trim();
        if (clean.isEmpty()) return "Room1";
        return clean.length() > 20 ? clean.substring(0, 20) : clean;
    }

    public static String fmt2(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }

    public static final int UDP_HEADER_BYTES = 5;

    public static byte[] createGameUdpPacket(byte type, String payload) {
        byte[] payloadBytes = payload == null ? new byte[0] : payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] packet = new byte[UDP_HEADER_BYTES + payloadBytes.length];
        System.arraycopy(Common.UDP_MAGIC_GAME_BYTES, 0, packet, 0, 4);
        packet[4] = type;
        if (payloadBytes.length > 0) {
            System.arraycopy(payloadBytes, 0, packet, UDP_HEADER_BYTES, payloadBytes.length);
        }
        return packet;
    }

    public static byte[] createGameUdpPacket(byte type, byte[] payloadBytes) {
        if (payloadBytes == null) payloadBytes = new byte[0];
        byte[] packet = new byte[UDP_HEADER_BYTES + payloadBytes.length];
        System.arraycopy(Common.UDP_MAGIC_GAME_BYTES, 0, packet, 0, 4);
        packet[4] = type;
        if (payloadBytes.length > 0) {
            System.arraycopy(payloadBytes, 0, packet, UDP_HEADER_BYTES, payloadBytes.length);
        }
        return packet;
    }

    public static boolean isGameUdpPacket(byte[] data, int length) {
        if (data == null || length < UDP_HEADER_BYTES) return false;
        return data[0] == Common.UDP_MAGIC_GAME_BYTES[0]
            && data[1] == Common.UDP_MAGIC_GAME_BYTES[1]
            && data[2] == Common.UDP_MAGIC_GAME_BYTES[2]
            && data[3] == Common.UDP_MAGIC_GAME_BYTES[3];
    }

    public static byte getGameUdpType(byte[] data) {
        return data[4];
    }

    public static String getGameUdpPayload(byte[] data, int offset, int length) {
        int payloadLen = length - UDP_HEADER_BYTES;
        if (payloadLen <= 0) return "";
        return new String(data, offset + UDP_HEADER_BYTES, payloadLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Đóng gói Snapshot từ Server thành Chuỗi Message
    public static String serializeSnapshot(GameData.WorldSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(Common.CMD_STATE);

        // Khối Player
        sb.append("#PLAYERS:");
        for (GameData.PlayerState p : snapshot.players) {
            sb.append(p.id).append(",")
              .append(p.name).append(",")
              .append(fmt2(p.x)).append(",")
              .append(fmt2(p.y)).append(",")
              .append(p.direction).append(",")
              .append(p.spriteNum).append(",")
              .append(p.hp).append(",")
              .append(p.maxHp).append(",")
              .append(p.isAttacking ? "1" : "0").append(",")
              .append(p.lastAckSeq).append(",")
              .append(p.pingMs).append(";");
        }

        // Khối Quái vật
        sb.append("#MONSTERS:");
        for (GameData.MonsterState m : snapshot.monsters) {
            sb.append(m.id).append(",")
              .append(m.type).append(",")
              .append(fmt2(m.x)).append(",")
              .append(fmt2(m.y)).append(",")
              .append(m.direction).append(",")
              .append(m.spriteNum).append(",")
              .append(m.hp).append(",")
              .append(m.maxHp).append(",")
              .append(m.isDead ? "1" : "0").append(";");
        }

        return sb.toString();
    }

    // Giải mã Chuỗi Message thành WorldSnapshot trên Client
    public static GameData.WorldSnapshot parseSnapshot(String line) {
        GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
        if (!line.startsWith(Common.CMD_STATE)) return snapshot;

        String[] sections = line.split("#");
        for (String sec : sections) {
            if (sec.startsWith("PLAYERS:")) {
                String raw = sec.substring(8);
                String[] items = raw.split(";");
                for (String item : items) {
                    if (item.trim().isEmpty()) continue;
                    String[] pData = item.split(",");
                    if (pData.length >= 10) {
                        GameData.PlayerState p = new GameData.PlayerState();
                        p.id = pData[0];
                        p.name = pData[1];
                        p.x = Float.parseFloat(pData[2]);
                        p.y = Float.parseFloat(pData[3]);
                        p.direction = pData[4];
                        p.spriteNum = Integer.parseInt(pData[5]);
                        p.hp = Integer.parseInt(pData[6]);
                        p.maxHp = Integer.parseInt(pData[7]);
                        p.isAttacking = "1".equals(pData[8]);
                        p.lastAckSeq = Integer.parseInt(pData[9]);
                        p.pingMs = pData.length >= 11 ? Integer.parseInt(pData[10]) : 0;
                        snapshot.players.add(p);
                    }
                }
            } else if (sec.startsWith("MONSTERS:")) {
                String raw = sec.substring(9);
                String[] items = raw.split(";");
                for (String item : items) {
                    if (item.trim().isEmpty()) continue;
                    String[] mData = item.split(",");
                    if (mData.length >= 9) {
                        GameData.MonsterState m = new GameData.MonsterState(mData[0], Float.parseFloat(mData[2]), Float.parseFloat(mData[3]));
                        m.type = mData[1];
                        m.direction = mData[4];
                        m.spriteNum = Integer.parseInt(mData[5]);
                        m.hp = Integer.parseInt(mData[6]);
                        m.maxHp = Integer.parseInt(mData[7]);
                        m.isDead = "1".equals(mData[8]);
                        snapshot.monsters.add(m);
                    }
                }
            }
        }
        return snapshot;
    }
}