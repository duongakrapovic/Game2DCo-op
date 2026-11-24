package source.server;

import source.common.GameData;
import source.common.SaveManager;
import java.util.HashMap;
import java.util.Map;

/** Owns slot identity; disconnected slots retain progress and EMPTY slots stay empty. */
/*
 * Quản lý quyền nhận lại bốn slot JSON cho một phòng, tách khỏi loop gameplay. Tên không phân biệt hoa
 * thường đang được dùng làm khóa nhận lại; vì vậy slot lịch sử và số người online là hai giới hạn khác
 * nhau.
 * data là OnlineSaveData; slotsById ánh xạ ID runtime tới slot, connected ghi các ID có handler hoạt động;
 * persist là callback ghi data. Cấu trúc này không thay thế tài khoản xác thực.
 */
public final class RoomSaveManager {
    /*
     * data: DTO save hoặc dữ liệu do lớp giữ; xem kiểu và chủ sở hữu ở cột bên trái.
     */
    private final SaveManager.OnlineSaveData data;
    /*
     * map: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    private final source.common.GameMap map;
    /*
     * persist: Callback ghi DTO save; bản sản xuất trỏ SaveManager.saveOnline, test có thể thay bằng bộ
     * đếm.
     */
    private final java.util.function.Consumer<SaveManager.OnlineSaveData> persist;
    /*
     * slotsById: Tập ID đang kết nối hoặc ánh xạ player runtime tới slot save.
     */
    private final Map<String, SaveManager.PlayerSlotData> slotsById = new HashMap<>();
    /*
     * connected: Tập ID đang kết nối hoặc ánh xạ player runtime tới slot save.
     */
    private final java.util.Set<String> connected = new java.util.HashSet<>();

    /*
     * Khởi tạo RoomSaveManager
     * Đọc hoặc tạo online save theo tên phòng và map, rồi gọi bản constructor tiêm dữ liệu với callback
     * SaveManager.saveOnline. Đây là đường dùng sản xuất.
     */
    public RoomSaveManager(String roomName, source.common.GameMap map) {
        this(SaveManager.loadOrCreateOnlineSave(roomName, map), map, SaveManager::saveOnline);
    }

    /*
     * Khởi tạo RoomSaveManager
     * Nhận DTO/map/callback sẵn có, lưu chúng và chuyển các slot không EMPTY về DISCONNECTED. Dùng cho cả
     * khởi tạo thực và test in-memory để không ghi save thật.
     */
    RoomSaveManager(SaveManager.OnlineSaveData data, source.common.GameMap map,
            java.util.function.Consumer<SaveManager.OnlineSaveData> persist) {
        this.map = map;
        this.data = data;
        this.persist = persist;
        for (SaveManager.PlayerSlotData slot : data.slots) {
            if (!"EMPTY".equals(slot.status)) slot.status = "DISCONNECTED";
        }
    }

    /*
     * join()
     * Tìm slot cùng tên không phân biệt hoa thường. Nếu slot còn được ID giữ thì báo đang online hoặc đang
     * chờ reconnect; nếu không có tên cũ thì tìm EMPTY, hết slot báo rõ bốn tên lịch sử. Nạp vị trí/HP khi
     * phù hợp, kiểm spawn, ánh xạ ID, đánh dấu ACTIVE, gọi persist và trả true. Không tự xóa chủ cũ để
     * nhét người mới.
     * Đầu tiên tìm slot không EMPTY trùng tên không phân biệt hoa thường. Nếu slot đó đã có ID runtime sở
     * hữu thì từ chối tên đang online hoặc đang giữ reconnect. Không có tên khớp thì chỉ dùng slot EMPTY;
     * bốn slot có chủ làm join lỗi dù số runtime nhỏ hơn bốn.
     * Với slot cũ, khôi phục tọa độ và HP theo điều kiện trong code; xác thực vị trí qua GameMap; gắn
     * player.id vào slot, thêm connected, đổi ACTIVE và persist. Không có account ID hoặc password để
     * chứng minh người nhập tên chính là chủ cũ.
     */
    public boolean join(GameData.PlayerState player) {
        SaveManager.PlayerSlotData match = null;
        for (SaveManager.PlayerSlotData slot : data.slots) {
            if (!"EMPTY".equals(slot.status) && player.name.equalsIgnoreCase(slot.playerName)) {
                if (slotsById.containsValue(slot)) {
                    boolean active = slotsById.entrySet().stream()
                            /*
                             * Callback/lambda: Predicate anyMatch kiểm slot đang xét có owner runtime
                             * thuộc tập connected hay không để chọn lý do từ chối tên.
                             */
                            .anyMatch(entry -> entry.getValue() == slot && connected.contains(entry.getKey()));
                    throw new IllegalStateException(active
                            ? "Ten nguoi choi nay dang o trong phong. Hay dung ten khac."
                            : "Ten nguoi choi nay dang duoc giu cho ket noi lai (toi da 20 giay). Hay thu lai sau.");
                }
                match = slot; break;
            }
        }
        /*
         * Không khớp tên cũ thì chỉ được lấy slot EMPTY; số người online ít chưa chắc còn slot save trống.
         */
        if (match == null) {
            for (SaveManager.PlayerSlotData slot : data.slots) {
                if ("EMPTY".equals(slot.status)) { match = slot; break; }
            }
            if (match == null) {
                /*
                 * Callback/lambda: Hàm map lấy playerName từng slot để ghép thông báo bốn slot đã có chủ.
                 */
                String names = data.slots.stream().map(slot -> slot.playerName)
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Phong chua day nguoi online, nhung 4 slot save da co chu: "
                        + names + ". Hay dung dung ten cu hoac tao phong voi ten moi; save cu duoc giu nguyen.");
            }
            match.playerName = player.name;
            update(match, player);
        } else {
            if (match.x > 0 || match.y > 0) { player.x = match.x; player.y = match.y; }
            if (match.hp > 0) { player.hp = match.hp; player.maxHp = match.maxHp; }
        }
        /*
         * Kiểm lại tọa độ khôi phục để không xuất hiện trên vật cản hoặc ngoài map.
         */
        java.awt.geom.Point2D.Float spawn = map.validatedPlayerSpawn(player.x, player.y);
        player.x = spawn.x; player.y = spawn.y;
        update(match, player);
        slotsById.put(player.id, match);
        connected.add(player.id);
        match.status = "ACTIVE";
        persist.accept(data);
        return true;
    }

    /*
     * attach()
     * Thêm ID vào connected và chuyển slot liên kết về ACTIVE nếu có; dùng lúc RESUME. Không tự ghi file
     * ngay trong hàm này.
     */
    public void attach(String id) {
        connected.add(id);
        SaveManager.PlayerSlotData slot = slotsById.get(id);
        if (slot != null) slot.status = "ACTIVE";
    }

    /*
     * detach()
     * Bỏ ID khỏi connected, chép tiến trình và đánh dấu slot DISCONNECTED. remove=false giữ liên kết ID
     * cho grace; remove=true xóa liên kết ID khi rời/hết hạn. Cả hai vẫn giữ tên/tiến trình slot và gọi
     * persist.
     * remove=false giữ ánh xạ ID tới slot để RESUME trong cửa sổ giữ phiên; remove=true bỏ owner runtime
     * để lần vào sau có thể nhận lại slot theo tên. Cả hai vẫn giữ tên và tiến trình trong DTO, đổi
     * DISCONNECTED rồi ghi. Rời phòng không có nghĩa xóa save hoặc đưa slot về EMPTY.
     */
    public void detach(GameData.PlayerState player, boolean remove) {
        SaveManager.PlayerSlotData slot = slotsById.get(player.id);
        connected.remove(player.id);
        if (slot != null) {
            update(slot, player); slot.status = "DISCONNECTED";
            /*
             * remove=false giữ owner cho reconnect; remove=true bỏ owner runtime nhưng vẫn giữ tiến trình
             * slot.
             */
            if (remove) slotsById.remove(player.id);
            persist.accept(data);
        }
    }

    /*
     * syncAndSaveOnline()
     * Duyệt player runtime, cập nhật slot đã gắn và đặt ACTIVE/DISCONNECTED theo connected rồi persist
     * toàn bộ data. Không tạo slot mới cho người chưa join.
     */
    public void syncAndSaveOnline(Iterable<GameData.PlayerState> players) {
        for (GameData.PlayerState player : players) {
            SaveManager.PlayerSlotData slot = slotsById.get(player.id);
            if (slot != null) {
                update(slot, player);
                slot.status = connected.contains(player.id) ? "ACTIVE" : "DISCONNECTED";
            }
        }
        persist.accept(data);
    }

    /*
     * update()
     * Chép x, y, hp và maxHp từ player sang DTO slot. Không đổi tên, slotIndex hoặc tự quyết định status.
     */
    private static void update(SaveManager.PlayerSlotData slot, GameData.PlayerState p) {
        slot.x = p.x; slot.y = p.y; slot.hp = p.hp; slot.maxHp = p.maxHp;
    }
}
