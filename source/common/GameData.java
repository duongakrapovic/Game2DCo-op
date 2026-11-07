package source.common;

import java.util.ArrayList;
import java.util.List;

public class GameData {

    public static class PlayerState {
        public String id;
        public String name;
        public float x = Common.SPAWN_X;
        public float y = Common.SPAWN_Y;
        public float vx = 0;
        public float vy = 0;
        public String direction = "down";
        public int spriteNum = 1;
        public int animCounter = 0;
        public int hp = 100;
        public int maxHp = 100;
        public boolean isAttacking = false;
        public int attackTicks = 0;
        public int lastAckSeq = -1;
        public long flashUntil = 0;
        public long invincibleUntil = 0;

        public volatile boolean left = false;
        public volatile boolean right = false;
        public volatile boolean up = false;
        public volatile boolean down = false;

        public PlayerState() {}

        public PlayerState(String id, String name) {
            this.id = id;
            this.name = name;
        }

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

    public static class MonsterState {
        public String id;
        public String type = "SLIME";
        public float x;
        public float y;
        public float vx = 0;
        public float vy = 0;
        public String direction = "down";
        public int spriteNum = 1;
        public int animCounter = 0;
        public int attackCooldown = 0;
        public int respawnTimer = 0; // Đã thêm biến đếm hồi sinh
        public int hp = 30;
        public int maxHp = 30;
        public boolean isDead = false;
        public long flashUntil = 0;
        public long nextPathAt = 0;
        public int[][] currentPath = new int[0][2];
        public int pathIndex = 0;

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

    public static class PlayerInput {
        public int seq;
        public boolean left, right, up, down;
        public boolean attack;

        public PlayerInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
            this.seq = seq;
            this.left = left;
            this.right = right;
            this.up = up;
            this.down = down;
            this.attack = attack;
        }
    }

    public static class WorldSnapshot {
        public long timestamp;
        public List<PlayerState> players = new ArrayList<>();
        public List<MonsterState> monsters = new ArrayList<>();

        public WorldSnapshot() {
            this.timestamp = System.currentTimeMillis();
        }
    }
}
