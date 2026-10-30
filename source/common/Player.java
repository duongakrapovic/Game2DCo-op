package source.common;

public class Player {
    public String id;
    public String name;
    
    public float x = Common.SPAWN_X;
    public float y = Common.SPAWN_Y;
    
    public float vx = 0, vy = 0;
    
    // Nút bấm di chuyển Top-Down
    public boolean left = false, right = false, up = false, down = false;

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }
}