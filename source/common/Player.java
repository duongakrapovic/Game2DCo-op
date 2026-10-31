package source.common;

public class Player {
    public String id;
    public String name;
    
    public float x = Common.SPAWN_X;
    public float y = Common.SPAWN_Y;
    
    public float vx = 0, vy = 0;
    
    // Nút bấm di chuyển Top-Down
    public boolean left = false, right = false, up = false, down = false;

    // Trường lưu hướng nhìn và frame bước chân
    public String direction = "down"; // "up", "down", "left", "right"
    public int spriteNum = 1;        // 1 hoặc 2
    public AnimationController anim = new AnimationController();

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }
    
    public void respawnToHome() {
        this.x = Common.SPAWN_X;
        this.y = Common.SPAWN_Y;
        this.vx = 0;
        this.vy = 0;
    }
}