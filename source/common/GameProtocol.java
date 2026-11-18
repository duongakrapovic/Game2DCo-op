package source.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GAMEPROTOCOL.JAVA - Tiện ích mã hóa & giải mã chuỗi TCP Packet
 */
public class GameProtocol {

    public static final int MAX_STATE_PACKET_BYTES = 499;
    private static final int BINARY_HEADER_BYTES = 29;
    private static final int POSITION = 1, HP = 2, ACTION_STATE = 4, IDENTITY = 8, NETWORK = 16, REMOVE = 32;
    private static final int FULL_PLAYER = POSITION | HP | ACTION_STATE | IDENTITY | NETWORK;
    private static final int FULL_MONSTER = POSITION | HP | ACTION_STATE | IDENTITY;

    /** Deltas reference the last full baseline, so losing a delta never poisons later ticks. */
    public static List<byte[]> serializeSnapshotBinary(long stateSeq, long streamId, long baselineSeq,
            GameData.WorldSnapshot current, GameData.WorldSnapshot baseline) {
        if (current.players.size() > 4 || current.monsters.size() > 64) throw new IllegalArgumentException("Entity limit");
        ByteBuffer body = ByteBuffer.allocate(32768);
        Map<String, GameData.PlayerState> oldPlayers = playersById(baseline);
        Map<String, GameData.MonsterState> oldMonsters = monstersById(baseline);
        int playerCountIndex = body.position(); body.put((byte) 0);
        int playerCount = 0;
        for (GameData.PlayerState player : current.players) {
            GameData.PlayerState old = oldPlayers.remove(player.id);
            int flags = old == null ? FULL_PLAYER : playerFlags(player, old);
            if (flags == 0) continue;
            playerCount++;
            putString(body, player.id); body.put((byte) flags);
            if ((flags & IDENTITY) != 0) putString(body, player.name);
            if ((flags & POSITION) != 0) body.putFloat(player.x).putFloat(player.y);
            if ((flags & HP) != 0) body.putShort((short) player.hp).putShort((short) player.maxHp);
            if ((flags & ACTION_STATE) != 0) body.put(action(player.direction, player.spriteNum, player.isAttacking)).put((byte) player.attackTicks);
            if ((flags & NETWORK) != 0) body.putInt(player.lastAckSeq).putShort((short) player.pingMs);
        }
        for (String id : oldPlayers.keySet()) { playerCount++; putString(body, id); body.put((byte) REMOVE); }
        body.put(playerCountIndex, (byte) playerCount);
        int monsterCountIndex = body.position(); body.put((byte) 0);
        int monsterCount = 0;
        for (GameData.MonsterState monster : current.monsters) {
            GameData.MonsterState old = oldMonsters.remove(monster.id);
            int flags = old == null ? FULL_MONSTER : monsterFlags(monster, old);
            if (flags == 0) continue;
            monsterCount++;
            putString(body, monster.id); body.put((byte) flags);
            if ((flags & IDENTITY) != 0) putString(body, monster.type);
            if ((flags & POSITION) != 0) body.putFloat(monster.x).putFloat(monster.y);
            if ((flags & HP) != 0) body.putShort((short) monster.hp).putShort((short) monster.maxHp);
            if ((flags & ACTION_STATE) != 0) body.put(action(monster.direction, monster.spriteNum, monster.isDead));
        }
        for (String id : oldMonsters.keySet()) { monsterCount++; putString(body, id); body.put((byte) REMOVE); }
        body.put(monsterCountIndex, (byte) monsterCount);
        int length = body.position();
        int chunkBytes = MAX_STATE_PACKET_BYTES - UDP_HEADER_BYTES - BINARY_HEADER_BYTES;
        int count = (length + chunkBytes - 1) / chunkBytes;
        List<byte[]> packets = new ArrayList<>(count);
        for (int part = 0; part < count; part++) {
            int offset = part * chunkBytes, bytes = Math.min(chunkBytes, length - offset);
            ByteBuffer fragment = ByteBuffer.allocate(BINARY_HEADER_BYTES + bytes);
            fragment.put((byte) 1).putLong(streamId).putLong(stateSeq).putLong(baselineSeq);
            fragment.putShort((short) part).putShort((short) count);
            fragment.put(body.array(), offset, bytes);
            packets.add(createGameUdpPacket(Common.UDP_GAME_STATE, fragment.array()));
        }
        return packets;
    }

    private static int playerFlags(GameData.PlayerState p, GameData.PlayerState old) {
        int flags = 0;
        if (!Objects.equals(p.name, old.name)) flags |= IDENTITY;
        if (p.x != old.x || p.y != old.y) flags |= POSITION;
        if (p.hp != old.hp || p.maxHp != old.maxHp) flags |= HP;
        if (!Objects.equals(p.direction, old.direction) || p.spriteNum != old.spriteNum
                || p.isAttacking != old.isAttacking || p.attackTicks != old.attackTicks) flags |= ACTION_STATE;
        if (p.lastAckSeq != old.lastAckSeq || p.pingMs != old.pingMs) flags |= NETWORK;
        return flags;
    }

    private static int monsterFlags(GameData.MonsterState m, GameData.MonsterState old) {
        int flags = 0;
        if (!Objects.equals(m.type, old.type)) flags |= IDENTITY;
        if (m.x != old.x || m.y != old.y) flags |= POSITION;
        if (m.hp != old.hp || m.maxHp != old.maxHp) flags |= HP;
        if (!Objects.equals(m.direction, old.direction) || m.spriteNum != old.spriteNum || m.isDead != old.isDead) flags |= ACTION_STATE;
        return flags;
    }

    private static byte action(String direction, int sprite, boolean active) {
        int d = "up".equals(direction) ? 1 : "left".equals(direction) ? 2 : "right".equals(direction) ? 3 : 0;
        return (byte) (d | (sprite == 2 ? 4 : 0) | (active ? 8 : 0));
    }

    private static String direction(int action) {
        return switch (action & 3) { case 1 -> "up"; case 2 -> "left"; case 3 -> "right"; default -> "down"; };
    }

    private static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) throw new IllegalArgumentException("String too long");
        buffer.put((byte) bytes.length).put(bytes);
    }

    private static String getString(ByteBuffer buffer) {
        int length = Byte.toUnsignedInt(buffer.get());
        if (length > 128 || length > buffer.remaining()) throw new IllegalArgumentException("String length");
        byte[] bytes = new byte[length]; buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, GameData.PlayerState> playersById(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.PlayerState> result = new LinkedHashMap<>();
        if (snapshot != null) for (GameData.PlayerState p : snapshot.players) result.put(p.id, p);
        return result;
    }

    private static Map<String, GameData.MonsterState> monstersById(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.MonsterState> result = new LinkedHashMap<>();
        if (snapshot != null) for (GameData.MonsterState m : snapshot.monsters) result.put(m.id, m);
        return result;
    }

    public static GameData.WorldSnapshot copySnapshot(GameData.WorldSnapshot snapshot) {
        GameData.WorldSnapshot copy = new GameData.WorldSnapshot();
        copy.timestamp = snapshot.timestamp;
        for (GameData.PlayerState p : snapshot.players) {
            GameData.PlayerState c = new GameData.PlayerState(p.id, p.name);
            c.x = p.x; c.y = p.y; c.vx = p.vx; c.vy = p.vy;
            c.direction = p.direction; c.spriteNum = p.spriteNum;
            c.hp = p.hp; c.maxHp = p.maxHp; c.isAttacking = p.isAttacking;
            c.attackTicks = p.attackTicks; c.lastAckSeq = p.lastAckSeq; c.pingMs = p.pingMs;
            c.flashUntil = p.flashUntil; c.invincibleUntil = p.invincibleUntil;
            copy.players.add(c);
        }
        for (GameData.MonsterState m : snapshot.monsters) {
            GameData.MonsterState c = new GameData.MonsterState(m.id, m.x, m.y);
            c.type = m.type; c.direction = m.direction; c.spriteNum = m.spriteNum;
            c.hp = m.hp; c.maxHp = m.maxHp; c.isDead = m.isDead;
            c.flashUntil = m.flashUntil;
            copy.monsters.add(c);
        }
        return copy;
    }

    /** Parses an assembled body into a detached snapshot, without mutating its baseline. */
    public static GameData.WorldSnapshot parseSnapshotBinary(byte[] body, GameData.WorldSnapshot baseline) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        GameData.WorldSnapshot snapshot = baseline == null ? new GameData.WorldSnapshot() : copySnapshot(baseline);
        Map<String, GameData.PlayerState> players = playersById(snapshot);
        Map<String, GameData.MonsterState> monsters = monstersById(snapshot);
        int count = Byte.toUnsignedInt(buffer.get());
        if (count > 8) throw new IllegalArgumentException("Player count");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String id = getString(buffer); int flags = Byte.toUnsignedInt(buffer.get());
            if (!seen.add(id) || flags == 0 || (flags & ~(FULL_PLAYER | REMOVE)) != 0) throw new IllegalArgumentException("Player flags");
            if (flags == REMOVE) { players.remove(id); continue; }
            if ((flags & REMOVE) != 0) throw new IllegalArgumentException("Remove flags");
            GameData.PlayerState p = players.get(id);
            if (p == null) {
                if (flags != FULL_PLAYER) throw new IllegalArgumentException("Missing player baseline");
                p = new GameData.PlayerState(id, "Player"); players.put(id, p);
            }
            if ((flags & IDENTITY) != 0) p.name = getString(buffer);
            if ((flags & POSITION) != 0) { p.x = finite(buffer.getFloat()); p.y = finite(buffer.getFloat()); }
            if ((flags & HP) != 0) { p.hp = Short.toUnsignedInt(buffer.getShort()); p.maxHp = Short.toUnsignedInt(buffer.getShort()); }
            if ((flags & ACTION_STATE) != 0) {
                int a = Byte.toUnsignedInt(buffer.get()); p.direction = direction(a);
                p.spriteNum = (a & 4) != 0 ? 2 : 1; p.isAttacking = (a & 8) != 0;
                p.attackTicks = Byte.toUnsignedInt(buffer.get());
            }
            if ((flags & NETWORK) != 0) { p.lastAckSeq = buffer.getInt(); p.pingMs = Short.toUnsignedInt(buffer.getShort()); }
        }
        count = Byte.toUnsignedInt(buffer.get());
        if (count > 128) throw new IllegalArgumentException("Monster count");
        seen.clear();
        for (int i = 0; i < count; i++) {
            String id = getString(buffer); int flags = Byte.toUnsignedInt(buffer.get());
            if (!seen.add(id) || flags == 0 || (flags & ~(FULL_MONSTER | REMOVE)) != 0) throw new IllegalArgumentException("Monster flags");
            if (flags == REMOVE) { monsters.remove(id); continue; }
            if ((flags & REMOVE) != 0) throw new IllegalArgumentException("Remove flags");
            GameData.MonsterState m = monsters.get(id);
            if (m == null) {
                if (flags != FULL_MONSTER) throw new IllegalArgumentException("Missing monster baseline");
                m = new GameData.MonsterState(id, 0, 0); monsters.put(id, m);
            }
            if ((flags & IDENTITY) != 0) m.type = getString(buffer);
            if ((flags & POSITION) != 0) { m.x = finite(buffer.getFloat()); m.y = finite(buffer.getFloat()); }
            if ((flags & HP) != 0) { m.hp = Short.toUnsignedInt(buffer.getShort()); m.maxHp = Short.toUnsignedInt(buffer.getShort()); }
            if ((flags & ACTION_STATE) != 0) {
                int a = Byte.toUnsignedInt(buffer.get()); m.direction = direction(a);
                m.spriteNum = (a & 4) != 0 ? 2 : 1; m.isDead = (a & 8) != 0;
            }
        }
        if (buffer.hasRemaining() || players.size() > 4 || monsters.size() > 64) throw new IllegalArgumentException("Invalid snapshot size");
        snapshot.players = new ArrayList<>(players.values()); snapshot.monsters = new ArrayList<>(monsters.values());
        return snapshot;
    }

    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite coordinate");
        return value;
    }

    /** Bounded fragment assembly; a frame is exposed only after every part arrives. */
    public static final class SnapshotDecoder {
        private static final class Assembly {
            final long stream, sequence, baseline, created = System.nanoTime();
            final byte[][] pieces;
            int received;
            Assembly(long stream, long sequence, long baseline, int count) {
                this.stream = stream; this.sequence = sequence; this.baseline = baseline;
                pieces = new byte[count][];
            }
            byte[] body() {
                int size = 0; for (byte[] piece : pieces) size += piece.length;
                ByteBuffer result = ByteBuffer.allocate(size);
                for (byte[] piece : pieces) result.put(piece);
                return result.array();
            }
        }
        private final Map<Long, Assembly> pending = new LinkedHashMap<>();
        private GameData.WorldSnapshot baseline;
        private long baselineSequence = -1, lastSequence = -1, stream;
        private boolean hasStream;

        public synchronized void reset() {
            pending.clear(); baseline = null; baselineSequence = lastSequence = -1; hasStream = false;
        }

        public synchronized void expectStream(long streamId) {
            if (hasStream && stream == streamId) return;
            reset(); stream = streamId; hasStream = true;
        }

        public synchronized GameData.WorldSnapshot accept(byte[] data, int offset, int length) {
            if (length <= BINARY_HEADER_BYTES || length + UDP_HEADER_BYTES > MAX_STATE_PACKET_BYTES) return null;
            try {
                ByteBuffer in = ByteBuffer.wrap(data, offset, length);
                if (in.get() != 1) return null;
                long source = in.getLong(), sequence = in.getLong(), base = in.getLong();
                int part = Short.toUnsignedInt(in.getShort()), count = Short.toUnsignedInt(in.getShort());
                if (sequence < 0 || base < 0 || base > sequence || sequence <= lastSequence
                        || count < 1 || count > 64 || part >= count || (hasStream && source != stream)) return null;
                long now = System.nanoTime();
                pending.values().removeIf(a -> now - a.created > 2_000_000_000L);
                Assembly a = pending.get(sequence);
                if (a == null) {
                    if (pending.size() >= 8) pending.remove(pending.keySet().iterator().next());
                    a = new Assembly(source, sequence, base, count); pending.put(sequence, a);
                }
                if (a.stream != source || a.baseline != base || a.pieces.length != count) return null;
                if (a.pieces[part] == null) {
                    byte[] bytes = new byte[in.remaining()]; in.get(bytes);
                    a.pieces[part] = bytes; a.received++;
                }
                if (a.received != count) return null;
                GameData.WorldSnapshot result;
                if (base == sequence) {
                    result = parseSnapshotBinary(a.body(), null);
                    baseline = copySnapshot(result); baselineSequence = sequence;
                    stream = source; hasStream = true;
                } else {
                    if (baseline == null || base != baselineSequence) return null;
                    result = parseSnapshotBinary(a.body(), baseline);
                }
                lastSequence = sequence;
                // Deltas may have completed just before their full baseline arrived.
                for (Assembly ready : pending.values()) {
                    if (ready.stream == stream && ready.received == ready.pieces.length
                            && ready.baseline == baselineSequence && ready.sequence > lastSequence) {
                        result = parseSnapshotBinary(ready.body(), baseline); lastSequence = ready.sequence;
                    }
                }
                pending.values().removeIf(frame -> frame.sequence <= lastSequence);
                return result;
            } catch (RuntimeException invalidPacket) {
                return null;
            }
        }
    }

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
