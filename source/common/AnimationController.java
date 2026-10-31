package source.common;

/**
 * Quản lý nhịp bước chân (animation frame) của nhân vật.
 * Tách biệt hoàn toàn khỏi logic vật lý và render.
 */
public class AnimationController {
    private int spriteCounter = 0;
    private int ticksPerFrame = 6; // Đổi frame bước chân sau mỗi 6 tick (~100ms ở 60 FPS)

    public AnimationController() {}

    public AnimationController(int ticksPerFrame) {
        this.ticksPerFrame = ticksPerFrame;
    }

    /**
     * Cập nhật frame bước chân của người chơi dựa trên trạng thái di chuyển.
     * @param p Người chơi cần cập nhật spriteNum
     * @param isMoving Trạng thái người chơi có đang di chuyển không
     */
    public void update(Player p, boolean isMoving) {
        if (isMoving) {
            spriteCounter++;
            if (spriteCounter >= ticksPerFrame) {
                p.spriteNum = (p.spriteNum == 1) ? 2 : 1;
                spriteCounter = 0;
            }
        } else {
            p.spriteNum = 1; // Về dáng đứng yên (frame 1)
            spriteCounter = 0;
        }
    }

    public void reset() {
        spriteCounter = 0;
    }
}
