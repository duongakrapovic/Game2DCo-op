package source.common;

/**
 * ============================================================================
 * MODULE PLAYER & ANIMATION CONTROLLER (SOURCE/COMMON/PLAYER.JAVA)
 * ============================================================================
 * TÁC DỤNG:
 *  - Đại diện cho đối tượng Người chơi (Entity) dùng chung ở cả Server và Client.
 *  - Sử dụng các cờ nút bấm (volatile) để truyền tín hiệu phím bấm tức thì
 *    giữa Luồng nhận dữ liệu Mạng (TCP/UDP Thread) và Luồng Tính Toán Vật Lý (Physics Engine Thread).
 *  - Tích hợp AnimationController làm Inner Class để quản lý nhịp đổi frame bước chân.
 * ============================================================================
 */
public class Player {

    public String id;          // ID duy nhất của người chơi (Server cấp)
    public String name;        // Tên hiển thị người chơi

    // Tọa độ thực trên bản đồ World (Pixel)
    public float x = Common.SPAWN_X;
    public float y = Common.SPAWN_Y;

    // Vận tốc di chuyển tức thời (Pixel / Tick)
    public float vx = 0;
    public float vy = 0;

    // Các cờ phím bấm di chuyển (volatile đảm bảo tính đồng bộ dữ liệu giữa các luồng)
    public volatile boolean left  = false;
    public volatile boolean right = false;
    public volatile boolean up    = false;
    public volatile boolean down  = false;

    // Trạng thái hướng nhìn và nhịp Animation bước chân
    public String direction = "down"; // Hướng mặc định: "up", "down", "left", "right"
    public int spriteNum = 1;        // Frame hiện tại: 1 hoặc 2
    public AnimationController anim = new AnimationController();

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Đưa nhân vật về vị trí Spawn mặc định và reset vận tốc */
    public void respawnToHome() {
        this.x = Common.SPAWN_X;
        this.y = Common.SPAWN_Y;
        this.vx = 0;
        this.vy = 0;
    }

    // ========================================================================
    // INNER CLASS: QUẢN LÝ NHỊP ĐỔI FRAME BƯỚC CHÂN (ANIMATION)
    // ========================================================================
    public static class AnimationController {
        private int spriteCounter = 0;
        private int ticksPerFrame = 6; // Đổi frame bước chân sau mỗi 6 tick (~100ms ở 60 FPS)

        public AnimationController() {}

        public AnimationController(int ticksPerFrame) {
            this.ticksPerFrame = ticksPerFrame;
        }

        /**
         * Cập nhật frame bước chân theo thời gian di chuyển thực tế.
         * @param p Nhân vật cần cập nhật
         * @param isMoving Cờ báo nhân vật đang di chuyển hay đứng yên
         */
        public void update(Player p, boolean isMoving) {
            if (isMoving) {
                spriteCounter++;
                if (spriteCounter >= ticksPerFrame) {
                    p.spriteNum = (p.spriteNum == 1) ? 2 : 1;
                    spriteCounter = 0;
                }
            } else {
                p.spriteNum = 1; // Về tư thế đứng yên (Frame 1)
                spriteCounter = 0;
            }
        }

        /** Reset đếm frame */
        public void reset() {
            spriteCounter = 0;
        }
    }
}