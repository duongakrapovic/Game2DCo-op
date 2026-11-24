package source.common;

import java.util.ArrayList;
import java.util.List;

/*
 * Định nghĩa model truyền giữa các hệ thống: PlayerState, MonsterState, ItemState, PlayerInput và
 * WorldSnapshot. Đây là dữ liệu trong RAM, khác với DTO lưu JSON; không phải mọi trường đều có trên dây
 * hoặc trong save.
 * PlayerState có ID/tên, vị trí/vận tốc, phím, hướng/frame, HP, animation đánh, ACK/ping và thời điểm
 * flash/bất tử. MonsterState có HP/dead/respawn/path/workspace. ItemState có ID/type, vị trí, healAmount,
 * collected và timer. PlayerInput dùng final cho một ý định bất biến. WorldSnapshot nhóm ba danh sách
 * entity.
 */
public class GameData {

    /*
     * Trạng thái player dùng cho mô phỏng, snapshot và hiển thị; ID runtime khác tên người chơi.
     */
    public static class PlayerState {
        /*
         * id: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
         */
        public String id;
        /*
         * name: Tên hiển thị hoặc tên local; đọc theo lớp sở hữu, không phải định danh xác thực.
         */
        public String name;
        /*
         * x: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float x = Common.SPAWN_X;
        /*
         * y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float y = Common.SPAWN_Y;
        /*
         * vx: Độ dịch chuyển theo từng trục trong bước mô phỏng; collision kiểm riêng trục X và Y.
         */
        public float vx = 0;
        /*
         * vy: Độ dịch chuyển theo từng trục trong bước mô phỏng; collision kiểm riêng trục X và Y.
         */
        public float vy = 0;
        /*
         * direction: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public String direction = "down";
        /*
         * spriteNum: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public int spriteNum = 1;
        /*
         * animCounter: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public int animCounter = 0;
        /*
         * hp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int hp = 100;
        /*
         * maxHp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int maxHp = 100;
        /*
         * isAttacking: Cờ và tiến độ hoạt ảnh đánh; cạnh nhấn và việc tính sát thương được xử lý riêng.
         */
        public boolean isAttacking = false;
        /*
         * attackTicks: Cờ và tiến độ hoạt ảnh đánh; cạnh nhấn và việc tính sát thương được xử lý riêng.
         */
        public int attackTicks = 0;
        /*
         * lastAckSeq: Sequence input cao nhất server đã áp nhận cho player; phục vụ loại pending ở client.
         */
        public int lastAckSeq = -1;
        /*
         * pingMs: RTT tính bằng mili giây; dữ liệu input được chặn trong PlayerInput.
         */
        public int pingMs = 0;
        /*
         * flashUntil: Mốc thời gian mili giây cho nháy khi trúng đòn hoặc miễn sát thương tạm thời.
         */
        public long flashUntil = 0;
        /*
         * invincibleUntil: Mốc thời gian mili giây cho nháy khi trúng đòn hoặc miễn sát thương tạm thời.
         */
        public long invincibleUntil = 0;

        /*
         * left: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public volatile boolean left = false;
        /*
         * right: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public volatile boolean right = false;
        /*
         * up: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public volatile boolean up = false;
        /*
         * down: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public volatile boolean down = false;

        /*
         * Khởi tạo PlayerState
         * Constructor không tham số giữ spawn mặc định trong Common. Các field initializer cung cấp 100
         * HP, hướng down và trạng thái phím trung tính; chưa gán ID/tên.
         */
        public PlayerState() {
            this.x = Common.SPAWN_X;
            this.y = Common.SPAWN_Y;
        }

        /*
         * Khởi tạo PlayerState
         * Gọi constructor không tham số rồi gán id và name. Được dùng khi server tạo player hoặc decoder
         * dựng một entity mới.
         */
        public PlayerState(String id, String name) {
            this();
            this.id = id;
            this.name = name;
        }

        /*
         * respawn()
         * Đưa player về spawn mặc định, hồi HP về maxHp, xóa vận tốc/phím/đánh/flash và đặt
         * invincibleUntil bằng thời gian hiện tại cộng 1000 ms. Không tự gửi message hoặc ghi save.
         */
        public void respawn() {
            this.x = Common.SPAWN_X;
            this.y = Common.SPAWN_Y;
            this.hp = maxHp;
            this.vx = 0;
            this.vy = 0;
            this.isAttacking = false;
            this.attackTicks = 0;
            this.left = false;
            this.right = false;
            this.up = false;
            this.down = false;
            this.flashUntil = 0;
            this.invincibleUntil = System.currentTimeMillis() + 1000;
        }
    }

    /*
     * Trạng thái quái: HP, di chuyển, hồi sinh và workspace tìm đường.
     */
    public static class MonsterState {
        /*
         * id: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
         */
        public String id;
        /*
         * type: Loại entity hoặc thông điệp tùy lớp; quyết định cách diễn giải dữ liệu.
         */
        public String type = "SLIME";
        /*
         * x: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float x;
        /*
         * y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float y;
        /*
         * vx: Độ dịch chuyển theo từng trục trong bước mô phỏng; collision kiểm riêng trục X và Y.
         */
        public float vx = 0;
        /*
         * vy: Độ dịch chuyển theo từng trục trong bước mô phỏng; collision kiểm riêng trục X và Y.
         */
        public float vy = 0;
        /*
         * direction: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public String direction = "down";
        /*
         * spriteNum: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public int spriteNum = 1;
        /*
         * animCounter: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public int animCounter = 0;
        /*
         * attackCooldown: Bộ đếm tick giữa các lần quái tấn công.
         */
        public int attackCooldown = 0;
        /*
         * respawnTimer: Cờ quái chết hoặc số tick chờ hồi sinh/despawn của entity.
         */
        public int respawnTimer = 0; // Đã thêm biến đếm hồi sinh
        /*
         * hp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int hp = 30;
        /*
         * maxHp: Máu hiện tại hoặc giới hạn máu; server/local simulation cập nhật, renderer chỉ đọc.
         */
        public int maxHp = 30;
        /*
         * isDead: Cờ quái chết hoặc số tick chờ hồi sinh/despawn của entity.
         */
        public boolean isDead = false;
        /*
         * flashUntil: Mốc thời gian mili giây cho nháy khi trúng đòn hoặc miễn sát thương tạm thời.
         */
        public long flashUntil = 0;
        /*
         * nextPathAt: Đường A*, vị trí node đang đi và mốc mili giây được tìm đường lại.
         */
        public long nextPathAt = 0;
        /*
         * currentPath: Đường A*, vị trí node đang đi và mốc mili giây được tìm đường lại.
         */
        public int[][] currentPath = new int[0][2];
        /*
         * pathIndex: Đường A*, vị trí node đang đi và mốc mili giây được tìm đường lại.
         */
        public int pathIndex = 0;
        /*
         * pathfinderWorkspace: Workspace A* được tái sử dụng riêng cho quái để giảm cấp phát.
         */
        public MonsterAI.PathfinderWorkspace pathfinderWorkspace;

        /*
         * Khởi tạo MonsterState
         * Gán ID và x/y ban đầu; các giá trị mặc định còn lại là loại SLIME, 30 HP, chưa chết, chưa có
         * đường đi. Không tự tìm điểm spawn hoặc chạy AI.
         */
        public MonsterState(String id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    /*
     * To add another monster kind (for example SOLDIER, SKELETON, or BOSS),
     * keep its per-instance state here and initialize it in the constructor or
     * in a factory: type, hp/maxHp, movement speed, attack range, damage, and
     * attack cooldown. Put kind-specific behavior behind a small definition or
     * switch in GamePhysics.updateMonster; do not add network fields unless the
     * snapshot format is intentionally versioned. Keep generic runtime state
     * such as position, current A* path, path index, and hit flash on this
     * object so the server and offline simulation can use the same code.
     */

    /*
     * Trạng thái bình máu để mô phỏng nhặt/hồi sinh và đồng bộ sang client.
     */
    public static class ItemState {
        /*
         * id: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
         */
        public String id;
        /*
         * type: Loại entity hoặc thông điệp tùy lớp; quyết định cách diễn giải dữ liệu.
         */
        public String type;
        /*
         * x, y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public float x, y;
        /*
         * healAmount: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
         */
        public int healAmount = 30;
        /*
         * collected: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
         */
        public boolean collected;
        /*
         * respawnTimer: Cờ quái chết hoặc số tick chờ hồi sinh/despawn của entity.
         */
        public int respawnTimer;
        /*
         * Khởi tạo ItemState
         * Gán ID, loại item và vị trí. Mặc định healAmount là 30, chưa collected và timer bằng 0;
         * ItemSystem quyết định quy tắc sống/hồi lại sau đó.
         */
        public ItemState(String id, String type, float x, float y) {
            this.id = id; this.type = type; this.x = x; this.y = y;
        }
    }

    /*
     * Một mẫu ý định điều khiển có sequence; không chứa tọa độ client tự quyết định.
     */
    public static class PlayerInput {
        /*
         * seq: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
         */
        public final int seq;
        /*
         * left, right, up, down: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public final boolean left, right, up, down;
        /*
         * attack: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public final boolean attack;
        /*
         * pingMs: RTT tính bằng mili giây; dữ liệu input được chặn trong PlayerInput.
         */
        public final int pingMs;

        /*
         * Khởi tạo PlayerInput
         * Constructor không nhận ping chuyển sang bản đầy đủ với pingMs=0. Sequence và các phím/attack vẫn
         * được giữ nguyên.
         */
        public PlayerInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
            this(seq, left, right, up, down, attack, 0);
        }

        /*
         * Khởi tạo PlayerInput
         * Tạo input bất biến từ sequence và các boolean; clamp pingMs vào 0–200. Constructor không xác
         * minh sequence mới/cũ; ServerGame.bufferPlayerInput làm việc đó.
         */
        public PlayerInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack, int pingMs) {
            this.pingMs = Math.max(0, Math.min(200, pingMs));
            this.seq = seq;
            this.left = left;
            this.right = right;
            this.up = up;
            this.down = down;
            this.attack = attack;
        }
    }

    /*
     * Bản dữ liệu thế giới tại một nhịp; không phải ảnh màn hình hoặc file save.
     */
    public static class WorldSnapshot {
        /*
         * timestamp: Thời gian snapshot hoặc mẫu nội suy được tạo; không thay thế sequence.
         */
        public long timestamp;
        /*
         * players: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
         * xuống JSON.
         */
        public List<PlayerState> players = new ArrayList<>();
        /*
         * monsters: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
         * xuống JSON.
         */
        public List<MonsterState> monsters = new ArrayList<>();
        /*
         * items: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
         * xuống JSON.
         */
        public List<ItemState> items = new ArrayList<>();

        /*
         * Khởi tạo WorldSnapshot
         * Ghi timestamp bằng System.currentTimeMillis; các danh sách players/monsters/items được field
         * initializer tạo rỗng. Hàm không tự chụp thế giới mà caller phải thêm dữ liệu.
         */
        public WorldSnapshot() {
            this.timestamp = System.currentTimeMillis();
        }
    }
}
