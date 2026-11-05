package source.common;

public class Player {
    public static final int MAX_HEALTH = 100;
    public static final long INVINCIBILITY_MS = 300;
    public static final long HIT_FLASH_MS = 150;

    public String id;
    public String name;
    
    public float x = Common.SPAWN_X;
    public float y = Common.SPAWN_Y;
    
    public float vx = 0, vy = 0;
    public int health = MAX_HEALTH;
    public volatile boolean attack = false;
    public long invincibleUntil;
    public long flashUntil;
    public long respawnAt;
    public long nextAttackAt;
    
    // Nút bấm di chuyển Top-Down
    public volatile boolean left = false, right = false, up = false, down = false;

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
        this.health = MAX_HEALTH;
        this.invincibleUntil = System.currentTimeMillis() + INVINCIBILITY_MS;
        this.respawnAt = 0;
    }

    public boolean isAlive() {
        return health > 0;
    }

    public boolean isInvincible(long now) {
        return now < invincibleUntil;
    }

    public boolean takeDamage(int damage, float sourceX, float sourceY, MapManager map, long now) {
        if (!isAlive() || isInvincible(now)) return false;
        health = Math.max(0, health - Math.max(0, damage));
        invincibleUntil = now + INVINCIBILITY_MS;
        flashUntil = now + HIT_FLASH_MS;
        applyKnockback(sourceX, sourceY, map);
        if (health == 0) respawnAt = now + 1000;
        return true;
    }

    public void updateLife(long now) {
        if (health == 0 && respawnAt > 0 && now >= respawnAt) respawnToHome();
    }

    private void applyKnockback(float sourceX, float sourceY, MapManager map) {
        float dx = x - sourceX;
        float dy = y - sourceY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            dx = 1f;
            length = 1f;
        }
        float pushX = dx / length * 12f;
        float pushY = dy / length * 12f;
        int size = (int) (16 * Common.SCALE);
        float nextX = x + pushX;
        float nextY = y + pushY;
        if (!blocked(nextX, y, size, map)) x = nextX;
        if (!blocked(x, nextY, size, map)) y = nextY;
    }

    private boolean blocked(float px, float py, int size, MapManager map) {
        int left = (int) px + 3, right = (int) px + size - 3;
        int top = (int) py + 3, bottom = (int) py + size - 3;
        return map.isCollisionAtWorld(left, top) || map.isCollisionAtWorld(right, top)
                || map.isCollisionAtWorld(left, bottom) || map.isCollisionAtWorld(right, bottom);
    }
}
