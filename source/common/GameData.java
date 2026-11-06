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

        public MonsterState(String id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

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