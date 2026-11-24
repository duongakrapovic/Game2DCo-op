package source.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GAMEPROTOCOL.JAVA - Tiện ích mã hóa & giải mã chuỗi TCP Packet
 */
/*
 * Đặc tả dữ liệu dùng chung hai phía: TCP theo dòng, UDP gameplay GAM1, encode/decode snapshot và các hàm
 * lọc tên. Luồng online hiện dùng binary v2; serializeSnapshot/parseSnapshot văn bản vẫn có trong source
 * nhưng không là đường STATE sản xuất của ServerGame.
 * Snapshot chia nhóm POSITION=1, HP=2, ACTION_STATE=4, IDENTITY=8, NETWORK=16, REMOVE=32. Datagram tối đa
 * 499 byte ứng dụng: GAM1/opcode 5 + header mảnh 29 + tối đa 465 body. SnapshotDecoder giữ baseline và tối
 * đa tám assembly chờ; Assembly giữ các mảnh của một sequence.
 */
public class GameProtocol {

    /*
     * MAX_STATE_PACKET_BYTES: Giới hạn datagram STATE ở tầng ứng dụng.
     */
    public static final int MAX_STATE_PACKET_BYTES = 499;
    /*
     * SNAPSHOT_VERSION: Phiên bản định dạng snapshot nhị phân.
     */
    public static final byte SNAPSHOT_VERSION = 2;
    /*
     * BINARY_HEADER_BYTES: Số byte header nhị phân snapshot, chưa gồm header GAM1.
     */
    private static final int BINARY_HEADER_BYTES = 29;
    /*
     * POSITION: Bit mask nhóm tọa độ.
     * HP: Bit mask nhóm máu.
     * ACTION_STATE: Bit mask nhóm hoạt ảnh.
     * IDENTITY: Bit mask nhóm ID/tên/loại.
     * NETWORK: Bit mask nhóm ACK và ping.
     * REMOVE: Bit mask đánh dấu xóa entity.
     */
    private static final int POSITION = 1, HP = 2, ACTION_STATE = 4, IDENTITY = 8, NETWORK = 16, REMOVE = 32;
    /*
     * FULL_PLAYER: Tổ hợp bit mask gửi đầy đủ thuộc tính entity khi không có baseline cũ.
     */
    private static final int FULL_PLAYER = POSITION | HP | ACTION_STATE | IDENTITY | NETWORK;
    /*
     * FULL_ITEM: Tổ hợp bit mask gửi đầy đủ thuộc tính entity khi không có baseline cũ.
     */
    private static final int FULL_ITEM = POSITION | HP | ACTION_STATE | IDENTITY;
    /*
     * FULL_MONSTER: Tổ hợp bit mask gửi đầy đủ thuộc tính entity khi không có baseline cũ.
     */
    private static final int FULL_MONSTER = POSITION | HP | ACTION_STATE | IDENTITY;

    /** Deltas reference the last full baseline, so losing a delta never poisons later ticks. */
    /*
     * serializeSnapshotBinary()
     * Nhận sequence, stream, baseline sequence, snapshot hiện tại và baseline hoặc null. Tính các record
     * thay đổi/thêm/xóa theo ID; ghi dữ liệu vào ByteBuffer big-endian, chia body thành mảnh với
     * version/stream/sequence/baseline/part/count rồi bọc GAM1 STATE. Trả List<byte[]> để server gửi từng
     * datagram; delta so với full baseline, không phụ thuộc delta liền trước.
     * Duyệt player, quái, item; so với full baseline để chọn bit mask thuộc tính thay đổi. Entity mới nhận
     * nhóm FULL; ID đã biến mất phát REMOVE. Sao chép dữ liệu vào ByteBuffer, chia thành mảnh có version,
     * stream, stateSeq, baselineSeq và chỉ số mảnh. Không gửi sprite hoặc file map trong các mảnh này.
     */
    public static List<byte[]> serializeSnapshotBinary(long stateSeq, long streamId, long baselineSeq,
            GameData.WorldSnapshot current, GameData.WorldSnapshot baseline) {
        if (current.players.size() > 4 || current.monsters.size() > 64 || current.items.size() > ItemSystem.MAX_ITEMS) throw new IllegalArgumentException("Entity limit");
        ByteBuffer body = ByteBuffer.allocate(32768);
        Map<String, GameData.PlayerState> oldPlayers = playersById(baseline);
        Map<String, GameData.MonsterState> oldMonsters = monstersById(baseline);
        int playerCountIndex = body.position(); body.put((byte) 0);
        int playerCount = 0;
        for (GameData.PlayerState player : current.players) {
            GameData.PlayerState old = oldPlayers.remove(player.id);
            /*
             * Entity mới gửi đủ nhóm thuộc tính; entity cũ chỉ gửi nhóm khác với full baseline.
             */
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
        /*
         * Các ID còn lại trong bản cũ đã biến mất ở current, nên phát dấu REMOVE.
         */
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
        Map<String, GameData.ItemState> oldItems = itemsById(baseline);
        int itemCountIndex = body.position(); body.putShort((short) 0);
        int itemCount = 0;
        for (GameData.ItemState item : current.items) {
            GameData.ItemState old = oldItems.remove(item.id);
            int flags = old == null ? FULL_ITEM : itemFlags(item, old);
            if (flags == 0) continue;
            itemCount++;
            putString(body, item.id); body.put((byte) flags);
            if ((flags & IDENTITY) != 0) putString(body, item.type);
            if ((flags & POSITION) != 0) body.putFloat(item.x).putFloat(item.y);
            if ((flags & HP) != 0) body.putShort((short) item.healAmount);
            if ((flags & ACTION_STATE) != 0) body.put((byte) (item.collected ? 1 : 0));
        }
        for (String id : oldItems.keySet()) { itemCount++; putString(body, id); body.put((byte) REMOVE); }
        body.putShort(itemCountIndex, (short) itemCount);
        int length = body.position();
        /*
         * Mỗi datagram STATE tối đa 499 byte ứng dụng; trừ header để tính dung lượng thân mảnh.
         */
        int chunkBytes = MAX_STATE_PACKET_BYTES - UDP_HEADER_BYTES - BINARY_HEADER_BYTES;
        int count = (length + chunkBytes - 1) / chunkBytes;
        List<byte[]> packets = new ArrayList<>(count);
        for (int part = 0; part < count; part++) {
            int offset = part * chunkBytes, bytes = Math.min(chunkBytes, length - offset);
            ByteBuffer fragment = ByteBuffer.allocate(BINARY_HEADER_BYTES + bytes);
            fragment.put(SNAPSHOT_VERSION).putLong(streamId).putLong(stateSeq).putLong(baselineSeq);
            fragment.putShort((short) part).putShort((short) count);
            fragment.put(body.array(), offset, bytes);
            packets.add(createGameUdpPacket(Common.UDP_GAME_STATE, fragment.array()));
        }
        return packets;
    }

    /*
     * itemFlags()
     * So một item với old đã tồn tại để bật nhóm vị trí, lượng hồi, collected hoặc identity thay đổi.
     * Helper không nhận old=null an toàn; serializeSnapshotBinary tự chọn FULL_ITEM khi ID mới trước khi
     * gọi helper.
     */
    private static int itemFlags(GameData.ItemState item, GameData.ItemState old) {
        int flags = 0;
        if (!Objects.equals(item.type, old.type)) flags |= IDENTITY;
        if (item.x != old.x || item.y != old.y) flags |= POSITION;
        if (item.healAmount != old.healAmount) flags |= HP;
        if (item.collected != old.collected) flags |= ACTION_STATE;
        return flags;
    }

    /*
     * itemsById()
     * Tạo map ID → ItemState từ danh sách item của snapshot; null snapshot tạo map rỗng. Được dùng khi so
     * baseline và dựng lại item sau delta.
     */
    private static Map<String, GameData.ItemState> itemsById(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.ItemState> items = new LinkedHashMap<>();
        if (snapshot != null) for (GameData.ItemState item : snapshot.items) items.put(item.id, item);
        return items;
    }

    /*
     * playerFlags()
     * So player với old đã có: x/y, HP/maxHp, hướng/frame/đánh/attackTicks, tên và ACK/ping. Helper trả
     * các bit thay đổi; encoder tự chọn FULL_PLAYER cho ID mới, không serialize mọi trường runtime/phím.
     */
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

    /*
     * monsterFlags()
     * So quái với old đã có, trả cờ vị trí, HP, hướng/frame/dead và type khác nhau. Encoder xử lý ID mới
     * bằng FULL_MONSTER, không gọi helper với null.
     */
    private static int monsterFlags(GameData.MonsterState m, GameData.MonsterState old) {
        int flags = 0;
        if (!Objects.equals(m.type, old.type)) flags |= IDENTITY;
        if (m.x != old.x || m.y != old.y) flags |= POSITION;
        if (m.hp != old.hp || m.maxHp != old.maxHp) flags |= HP;
        if (!Objects.equals(m.direction, old.direction) || m.spriteNum != old.spriteNum || m.isDead != old.isDead) flags |= ACTION_STATE;
        return flags;
    }

    /*
     * action()
     * Gói hướng vào hai bit thấp, sprite 1/2 vào bit kế và cờ active vào bit sau trong một byte. Ý nghĩa
     * active do loại record quyết định, ví dụ player đánh hoặc quái chết.
     */
    private static byte action(String direction, int sprite, boolean active) {
        int d = "up".equals(direction) ? 1 : "left".equals(direction) ? 2 : "right".equals(direction) ? 3 : 0;
        return (byte) (d | (sprite == 2 ? 4 : 0) | (active ? 8 : 0));
    }

    /*
     * direction()
     * Giải mã hai bit thấp của action về chuỗi down/up/left/right. Các bit frame/active được caller đọc
     * riêng.
     */
    private static String direction(int action) {
        return switch (action & 3) { case 1 -> "up"; case 2 -> "left"; case 3 -> "right"; default -> "down"; };
    }

    /*
     * putString()
     * Chuyển chuỗi thành UTF-8, kiểm độ dài giới hạn 128 byte và ghi một byte độ dài trước nội dung. Độ
     * dài trên dây tính theo byte, không phải số ký tự Việt.
     */
    private static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) throw new IllegalArgumentException("String too long");
        buffer.put((byte) bytes.length).put(bytes);
    }

    /*
     * getString()
     * Đọc độ dài không dấu, kiểm không vượt 128 hoặc số byte còn lại, rồi giải mã UTF-8. Dữ liệu thiếu/sai
     * gây exception để decoder từ chối packet.
     */
    private static String getString(ByteBuffer buffer) {
        int length = Byte.toUnsignedInt(buffer.get());
        if (length > 128 || length > buffer.remaining()) throw new IllegalArgumentException("String length");
        byte[] bytes = new byte[length]; buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /*
     * playersById()
     * Chuyển danh sách player thành map ID để tính delta hoặc cập nhật/xóa nhanh trong parser; baseline
     * null được coi là map rỗng.
     */
    private static Map<String, GameData.PlayerState> playersById(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.PlayerState> result = new LinkedHashMap<>();
        if (snapshot != null) for (GameData.PlayerState p : snapshot.players) result.put(p.id, p);
        return result;
    }

    /*
     * monstersById()
     * Tạo map ID của quái cho so sánh và áp record nhị phân; không tự chạy AI hoặc lọc theo khoảng cách.
     */
    private static Map<String, GameData.MonsterState> monstersById(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.MonsterState> result = new LinkedHashMap<>();
        if (snapshot != null) for (GameData.MonsterState m : snapshot.monsters) result.put(m.id, m);
        return result;
    }

    /*
     * copySnapshot()
     * Tạo snapshot và các entity mới, chép những trường cần cho hiển thị/baseline. Giữ timestamp, HP, vị
     * trí, hành động và dữ liệu mạng; không sao toàn bộ AI/path/handler. Bản copy giúp delta không làm
     * biến đổi baseline và UI không dùng object mô phỏng đang sửa.
     */
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
        for (GameData.ItemState item : snapshot.items) {
            GameData.ItemState c = new GameData.ItemState(item.id, item.type, item.x, item.y);
            c.healAmount = item.healAmount; c.collected = item.collected; c.respawnTimer = item.respawnTimer;
            copy.items.add(c);
        }
        return copy;
    }

    /** Parses an assembled body into a detached snapshot, without mutating its baseline. */
    /*
     * parseSnapshotBinary()
     * Đọc body đã ghép; dựng snapshot rỗng với full hoặc copy baseline cho delta. Áp record theo flags, xử
     * lý REMOVE và yêu cầu entity mới có đủ nhóm. Kiểm ID lặp, cờ lạ, tọa độ hữu hạn, count, số byte thừa
     * và giới hạn cuối bốn player/64 quái/60 item. Trả snapshot tách biệt; dữ liệu không hợp lệ ném
     * exception.
     */
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
        Map<String, GameData.ItemState> items = itemsById(snapshot);
        count = Short.toUnsignedInt(buffer.getShort());
        if (count > ItemSystem.MAX_ITEMS * 2) throw new IllegalArgumentException("Item count");
        seen.clear();
        for (int i = 0; i < count; i++) {
            String id = getString(buffer); int flags = Byte.toUnsignedInt(buffer.get());
            if (!seen.add(id) || flags == 0 || (flags & ~(FULL_ITEM | REMOVE)) != 0) throw new IllegalArgumentException("Item flags");
            if (flags == REMOVE) { items.remove(id); continue; }
            if ((flags & REMOVE) != 0) throw new IllegalArgumentException("Item remove flags");
            GameData.ItemState item = items.get(id);
            if (item == null) {
                if (flags != FULL_ITEM) throw new IllegalArgumentException("Missing item baseline");
                item = new GameData.ItemState(id, "HEAL", 0, 0); items.put(id, item);
            }
            if ((flags & IDENTITY) != 0) item.type = getString(buffer);
            if ((flags & POSITION) != 0) { item.x = finite(buffer.getFloat()); item.y = finite(buffer.getFloat()); }
            if ((flags & HP) != 0) item.healAmount = Short.toUnsignedInt(buffer.getShort());
            if ((flags & ACTION_STATE) != 0) {
                int collected = Byte.toUnsignedInt(buffer.get());
                if (collected > 1) throw new IllegalArgumentException("Collected flag");
                item.collected = collected == 1;
            }
        }
        if (items.size() > ItemSystem.MAX_ITEMS) throw new IllegalArgumentException("Item limit");
        snapshot.items = new ArrayList<>(items.values());
        if (buffer.hasRemaining() || players.size() > 4 || monsters.size() > 64) throw new IllegalArgumentException("Invalid snapshot size");
        snapshot.players = new ArrayList<>(players.values()); snapshot.monsters = new ArrayList<>(monsters.values());
        return snapshot;
    }

    /*
     * finite()
     * Trả lại float nếu hữu hạn; NaN hoặc Infinity gây IllegalArgumentException để chặn tọa độ lỗi trong
     * snapshot.
     */
    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite coordinate");
        return value;
    }

    /** Bounded fragment assembly; a frame is exposed only after every part arrives. */
    /*
     * Ghép mảnh STATE và áp full/delta; chỉ xuất snapshot đầy đủ, đúng stream và baseline.
     */
    public static final class SnapshotDecoder {
        /*
         * Giữ các mảnh của một frame trong lúc chờ đủ; không vẽ dữ liệu dở dang.
         */
        private static final class Assembly {
            /*
             * stream: ID nguồn snapshot của phòng và cờ decoder đã khóa stream.
             * sequence: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
             * baseline: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
             * created: Các assembly đang ghép, mảnh byte, số mảnh đã nhận và thời điểm tạo assembly.
             */
            final long stream, sequence, baseline, created = System.nanoTime();
            /*
             * pieces: Các assembly đang ghép, mảnh byte, số mảnh đã nhận và thời điểm tạo assembly.
             */
            final byte[][] pieces;
            /*
             * received: Các assembly đang ghép, mảnh byte, số mảnh đã nhận và thời điểm tạo assembly.
             */
            int received;
            /*
             * Khởi tạo Assembly
             * Lưu stream/sequence/baseline và cấp mảng byte[count][] cho một frame. created được gán khi
             * khởi tạo để decoder loại assembly chờ quá lâu.
             */
            Assembly(long stream, long sequence, long baseline, int count) {
                this.stream = stream; this.sequence = sequence; this.baseline = baseline;
                pieces = new byte[count][];
            }
            /*
             * body()
             * Cộng chiều dài các pieces rồi ghép theo thứ tự partIndex vào một byte[] hoàn chỉnh. Caller
             * chỉ gọi khi đã đủ mảnh; hàm không tự xin truyền lại mảnh thiếu.
             */
            byte[] body() {
                int size = 0; for (byte[] piece : pieces) size += piece.length;
                ByteBuffer result = ByteBuffer.allocate(size);
                for (byte[] piece : pieces) result.put(piece);
                return result.array();
            }
        }
        /*
         * pending: Các assembly đang ghép, mảnh byte, số mảnh đã nhận và thời điểm tạo assembly.
         */
        private final Map<Long, Assembly> pending = new LinkedHashMap<>();
        /*
         * baseline: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
         */
        private GameData.WorldSnapshot baseline;
        /*
         * baselineSequence: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
         * lastSequence: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
         * stream: ID nguồn snapshot của phòng và cờ decoder đã khóa stream.
         */
        private long baselineSequence = -1, lastSequence = -1, stream;
        /*
         * hasStream: ID nguồn snapshot của phòng và cờ decoder đã khóa stream.
         */
        private boolean hasStream;

        /*
         * reset()
         * Xóa pending, baseline, các sequence và cờ stream của SnapshotDecoder. Được gọi khi rời/vào phòng
         * hoặc thay thế hệ gameplay để dữ liệu cũ không lẫn vào.
         */
        public synchronized void reset() {
            pending.clear(); baseline = null; baselineSequence = lastSequence = -1; hasStream = false;
        }

        /*
         * expectStream()
         * Nếu stream mong đợi đã trùng thì giữ decoder; nếu khác thì reset và đặt stream mới. Stream do
         * ACK đăng ký UDP cung cấp, không tự lấy tên phòng làm stream.
         */
        public synchronized void expectStream(long streamId) {
            if (hasStream && stream == streamId) return;
            reset(); stream = streamId; hasStream = true;
        }

        /*
         * accept()
         * Nhận vùng byte của một mảnh STATE, kiểm kích thước/version/stream/sequence/baseline/count, bỏ
         * gói cũ và assembly quá hai giây. Lưu mảnh chưa có, chỉ parse khi đủ; full cập nhật baseline,
         * delta cần đúng baseline. Có thể áp delta đã đủ từ trước khi full vừa tới, dọn frame cũ và trả
         * snapshot mới nhất hoặc null nếu chưa đủ/không hợp lệ. Không phát thế giới nửa chừng.
         * Trình tự nhận: kiểm kích thước/version → kiểm sequence/baseline/stream/count/index → dọn
         * assembly quá hạn → thêm mảnh nếu chưa có → chỉ giải mã khi đủ. Full tạo bản copy baseline; delta
         * cần đúng baseline đã giữ. Nếu delta đủ trước full, decoder có thể xử lý nó khi full tới.
         * Không có retransmit mảnh trong decoder. Thiếu full baseline có thể làm client phải chờ full tiếp
         * theo. Gói cũ, trùng hoặc sai định dạng trả null; caller phải coi null là chưa có snapshot để áp,
         * không phải thế giới rỗng.
         */
        public synchronized GameData.WorldSnapshot accept(byte[] data, int offset, int length) {
            if (length <= BINARY_HEADER_BYTES || length + UDP_HEADER_BYTES > MAX_STATE_PACKET_BYTES) return null;
            try {
                ByteBuffer in = ByteBuffer.wrap(data, offset, length);
                /*
                 * Version kiểm định dạng snapshot; stream kiểm phòng nguồn, sequence kiểm frame mới/cũ.
                 */
                if (in.get() != SNAPSHOT_VERSION) return null;
                long source = in.getLong(), sequence = in.getLong(), base = in.getLong();
                int part = Short.toUnsignedInt(in.getShort()), count = Short.toUnsignedInt(in.getShort());
                if (sequence < 0 || base < 0 || base > sequence || sequence <= lastSequence
                        || count < 1 || count > 64 || part >= count || (hasStream && source != stream)) return null;
                long now = System.nanoTime();
                /*
                 * Callback/lambda: Predicate removeIf dọn assembly quá 2 giây khi xử lý gói đến; không
                 * phải thread timeout riêng.
                 */
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
                /*
                 * Chưa đủ mảnh thì chưa công bố thế giới; không dựng một nửa snapshot.
                 */
                if (a.received != count) return null;
                GameData.WorldSnapshot result;
                /*
                 * Full tự làm mốc; delta chỉ được giải mã khi còn đúng full baseline mà nó tham chiếu.
                 */
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
                /*
                 * Delta có thể đến đủ trước full; khi full tới mới xử lý các frame đang chờ phù hợp.
                 */
                for (Assembly ready : pending.values()) {
                    if (ready.stream == stream && ready.received == ready.pieces.length
                            && ready.baseline == baselineSequence && ready.sequence > lastSequence) {
                        result = parseSnapshotBinary(ready.body(), baseline); lastSequence = ready.sequence;
                    }
                }
                /*
                 * Callback/lambda: Predicate xóa các frame đang pending có sequence không mới hơn frame đã
                 * xuất.
                 */
                pending.values().removeIf(frame -> frame.sequence <= lastSequence);
                return result;
            } catch (RuntimeException invalidPacket) {
                return null;
            }
        }
    }

    /*
     * MAX_LINE_LENGTH_CLIENT: Giới hạn dòng TCP server nhận từ client.
     */
    public static final int MAX_LINE_LENGTH_CLIENT = 512;
    /*
     * MAX_LINE_LENGTH_SERVER: Giới hạn dòng TCP client nhận từ server.
     */
    public static final int MAX_LINE_LENGTH_SERVER = 2048;

    /*
     * readLineLimited()
     * Đọc BufferedReader từng ký tự tới LF, bỏ CR, chặn độ dài vượt giới hạn bằng IOException. EOF chưa có
     * ký tự trả null; EOF sau dữ liệu còn trả dòng cuối. Hàm chia message TCP, không đọc một gói IP nguyên
     * vẹn.
     * TCP là dòng byte nên không dùng một lần read làm ranh giới packet. Hàm đọc ký tự tới LF, bỏ CR, giới
     * hạn số ký tự; EOF khi chưa có chữ trả null, EOF sau một phần dòng trả phần đã đọc. Vượt giới hạn ném
     * IOException để tầng kết nối xử lý.
     */
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

    /*
     * sanitizeName()
     * Loại dấu ; , : | và khoảng trắng, dùng Player nếu rỗng, cắt tối đa 16 ký tự Java. Bảo vệ cú pháp tên
     * trong protocol; không chứng minh danh tính hoặc quyền sở hữu save.
     */
    public static String sanitizeName(String name) {
        if (name == null) return "Player";
        String clean = name.replaceAll("[;,:|\\s]", "").trim();
        if (clean.isEmpty()) return "Player";
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    /*
     * sanitizeRoom()
     * Thay khoảng trắng thành gạch dưới, bỏ dấu phân cách, dùng Room1 nếu rỗng và cắt tối đa 20 ký tự
     * Java. Đây chưa phải xác thực tài khoản hay kiểm soát toàn diện đường dẫn file.
     */
    public static String sanitizeRoom(String room) {
        if (room == null) return "Room1";
        String clean = room.replaceAll("\\s+", "_").replaceAll("[;,:|]", "").trim();
        if (clean.isEmpty()) return "Room1";
        return clean.length() > 20 ? clean.substring(0, 20) : clean;
    }

    /*
     * fmt2()
     * Làm tròn bằng Math.round(v × 100) / 100 rồi Float.toString. Kết quả có tối đa độ chính xác hai chữ
     * số thập phân theo phép làm tròn đó, không cố định đủ hai số 0 ở cuối như String.format %.2f; dùng
     * trong tiện ích snapshot văn bản.
     */
    public static String fmt2(float v) {
        return Float.toString(Math.round(v * 100f) / 100f);
    }

    /*
     * UDP_HEADER_BYTES: Số byte magic GAM1 cộng opcode.
     */
    public static final int UDP_HEADER_BYTES = 5;

    /*
     * createGameUdpPacket()
     * Nhận payload chuỗi, chuyển UTF-8 rồi gọi bản byte[]. Dùng cho REGISTER/INPUT/PING/PONG/ACK có
     * payload văn bản.
     */
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

    /*
     * createGameUdpPacket()
     * Cấp byte[] dài 5 + payload, ghi GAM1, opcode và dữ liệu nguyên byte. Đây là wrapper chung cho cả
     * binary STATE, không tự cung cấp độ tin cậy UDP.
     */
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

    /*
     * isGameUdpPacket()
     * Kiểm có tối thiểu header và bốn byte magic GAM1. Đúng magic chưa đồng nghĩa payload đã hợp lệ hoặc
     * người gửi đã được xác thực.
     */
    public static boolean isGameUdpPacket(byte[] data, int length) {
        if (data == null || length < UDP_HEADER_BYTES) return false;
        return data[0] == Common.UDP_MAGIC_GAME_BYTES[0]
            && data[1] == Common.UDP_MAGIC_GAME_BYTES[1]
            && data[2] == Common.UDP_MAGIC_GAME_BYTES[2]
            && data[3] == Common.UDP_MAGIC_GAME_BYTES[3];
    }

    /*
     * getGameUdpType()
     * Lấy byte opcode ở vị trí sau magic; caller cần bảo đảm header đã được kiểm trước.
     */
    public static byte getGameUdpType(byte[] data) {
        return data[4];
    }

    /*
     * getGameUdpPayload()
     * Giải mã phần sau header trong vùng offset/length thành UTF-8. Không dùng hàm này để giải mã body
     * STATE nhị phân.
     */
    public static String getGameUdpPayload(byte[] data, int offset, int length) {
        int payloadLen = length - UDP_HEADER_BYTES;
        if (payloadLen <= 0) return "";
        return new String(data, offset + UDP_HEADER_BYTES, payloadLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Đóng gói Snapshot từ Server thành Chuỗi Message
    /*
     * serializeSnapshot()
     * Chuyển player/quái/item thành chuỗi STATE phục vụ tiện ích, callback tương thích và test. Có làm
     * tròn tọa độ và không giữ đầy đủ mọi trường như binary; ServerGame hiện không gửi STATE qua TCP bằng
     * hàm này.
     */
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

        sb.append("#ITEMS:");
        for (GameData.ItemState item : snapshot.items) {
            sb.append(item.id).append(',').append(item.type).append(',').append(fmt2(item.x)).append(',')
                    .append(fmt2(item.y)).append(',').append(item.collected ? 1 : 0).append(',')
                    .append(item.healAmount).append(';');
        }
        return sb.toString();
    }

    // Giải mã Chuỗi Message thành WorldSnapshot trên Client
    /*
     * parseSnapshot()
     * Tách chuỗi snapshot văn bản thành ba danh sách entity, kiểm/cố gắng xử lý các record và bỏ phần
     * không parse được theo logic trong hàm. Dùng trong đường tương thích/test; khác parseSnapshotBinary
     * đang đi qua UDP online.
     */
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
            } else if (sec.startsWith("ITEMS:")) {
                for (String row : sec.substring(6).split(";")) {
                    if (row.isBlank()) continue;
                    String[] fields = row.split(",", -1);
                    if (fields.length < 5) continue;
                    try {
                        GameData.ItemState item = new GameData.ItemState(fields[0], fields[1],
                                finite(Float.parseFloat(fields[2])), finite(Float.parseFloat(fields[3])));
                        item.collected = "1".equals(fields[4]);
                        if (fields.length > 5) item.healAmount = Integer.parseInt(fields[5]);
                        snapshot.items.add(item);
                    } catch (NumberFormatException invalidItem) { /* Ignore malformed legacy item records. */ }
                }
            }
        }
        return snapshot;
    }
}
