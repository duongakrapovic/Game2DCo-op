package source.client;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;
import source.common.GameProtocol;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class ClientGame extends JPanel {

    private final Map<String, Image[]> spriteMap = new HashMap<>();
    private final Map<String, Image[]> axeSpriteMap = new HashMap<>();
    private final Image[] slimeSprites = new Image[2];

    private volatile Map<String, GameData.PlayerState> renderPlayers = new ConcurrentHashMap<>();
    private volatile Map<String, GameData.MonsterState> renderMonsters = new ConcurrentHashMap<>();

    private final GameMap mapManager;
    private final ClientMovement movement;

    private GameData.PlayerState localPlayer;
    private String myName = "Player";
    private Thread localGameThread;
    private volatile boolean isLocalRunning = false;
    private final Random rand = new Random();
    private boolean previousLocalAttack;

    public volatile boolean left = false, right = false, up = false, down = false, attack = false;
    private ClientApp clientAppRef = null;

    private boolean showHud = true;
    private String hudLine1 = "";
    private String hudLine2 = "";
    private String hudLine3 = "";
    private String bannerMessage = null;

    public ClientGame(GameMap mapManager, ClientMovement movement) {
        this.mapManager = mapManager;
        this.movement = movement;
        setFocusable(true);
        loadAssets();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(true);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(false);
                }
            }
        });
    }

    public void setClientAppRef(ClientApp app) {
        this.clientAppRef = app;
    }

    private void triggerAttack(boolean isPressed) {
        this.attack = isPressed;
        if (clientAppRef != null) {
            clientAppRef.sendAttackInput(isPressed);
        }
    }

    private void loadAssets() {
        String pPath = "source/content/player/";
        String mPath = "source/content/monster/";
        String[] directions = {"down", "up", "left", "right"};

        try {
            for (String dir : directions) {
                Image[] frames = new Image[2];
                frames[0] = ImageIO.read(new File(pPath + "boy_" + dir + "_1.png"));
                frames[1] = ImageIO.read(new File(pPath + "boy_" + dir + "_2.png"));
                spriteMap.put(dir, frames);
            }

            for (String dir : directions) {
                Image[] axeFrames = new Image[2];
                axeFrames[0] = ImageIO.read(new File(pPath + "boy_axe_" + dir + "_1.png"));
                axeFrames[1] = ImageIO.read(new File(pPath + "boy_axe_" + dir + "_2.png"));
                axeSpriteMap.put(dir, axeFrames);
            }

            slimeSprites[0] = ImageIO.read(new File(mPath + "greenslime_down_1.png"));
            slimeSprites[1] = ImageIO.read(new File(mPath + "greenslime_down_2.png"));

        } catch (Exception e) {
            System.err.println("[ClientGame] Lỗi nạp hình ảnh: " + e.getMessage());
        }
    }

    private Point getRandomMapPoint() {
        int maxW = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        int maxH = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE - GamePhysics.ENTITY_SIZE;
        for (int attempt = 0; attempt < 2000; attempt++) {
            int x = rand.nextInt(Math.max(1, maxW));
            int y = rand.nextInt(Math.max(1, maxH));
            int right = x + GamePhysics.ENTITY_SIZE - 3;
            int bottom = y + GamePhysics.ENTITY_SIZE - 3;
            boolean walkable = !mapManager.isCollisionAtWorld(x + 3, y + 3)
                    && !mapManager.isCollisionAtWorld(right, y + 3)
                    && !mapManager.isCollisionAtWorld(x + 3, bottom)
                    && !mapManager.isCollisionAtWorld(right, bottom);
            if (walkable && Math.hypot(x - Common.SPAWN_X, y - Common.SPAWN_Y)
                    >= GamePhysics.ENTITY_SIZE * 3) {
                return new Point(x, y);
            }
        }
        return new Point((int) Common.SPAWN_X + GamePhysics.ENTITY_SIZE * 4,
                (int) Common.SPAWN_Y);
    }

    public void startOfflineMode(String playerName) {
        stopOfflineMode();
        this.myName = playerName;

        localPlayer = new GameData.PlayerState("LOCAL_1", myName);
        previousLocalAttack = false;
        renderPlayers = new ConcurrentHashMap<>();
        renderMonsters = new ConcurrentHashMap<>();

        for (int i = 1; i <= 15; i++) {
            Point pt;
            do {
                pt = getRandomMapPoint();
            } while (Math.hypot(pt.x - Common.SPAWN_X, pt.y - Common.SPAWN_Y) < 150);

            renderMonsters.put("M" + i, new GameData.MonsterState("M" + i, pt.x, pt.y));
        }

        renderPlayers.put("LOCAL_1", localPlayer);

        isLocalRunning = true;
        localGameThread = new Thread(() -> {
            final double TARGET_FPS = 60.0;
            final double OPTIMAL_NS = 1_000_000_000.0 / TARGET_FPS;
            long lastTime = System.nanoTime();

            while (isLocalRunning) {
                long now = System.nanoTime();
                float dt = (float)((now - lastTime) / 1_000_000_000.0);
                lastTime = now;
                if (dt > 0.1f) dt = 0.1f;

                localPlayer.left = left;
                localPlayer.right = right;
                localPlayer.up = up;
                localPlayer.down = down;

                // XỬ LÝ HOẠT ẢNH CHÉM: Tách riêng nhịp chém (tổng 36 ticks)
                long wallNow = System.currentTimeMillis();
                if (attack && !previousLocalAttack && !localPlayer.isAttacking) {
                    localPlayer.isAttacking = true;
                    localPlayer.attackTicks = 0;
                    Rectangle attackBox = GamePhysics.getAttackHitbox(
                            localPlayer.x, localPlayer.y, localPlayer.direction);
                    for (GameData.MonsterState m : renderMonsters.values()) {
                        if (!m.isDead && attackBox.intersects(GamePhysics.getEntityBounds(m.x, m.y))) {
                            m.hp = Math.max(0, m.hp - 15);
                            m.flashUntil = wallNow + 150;
                            GamePhysics.applyKnockback(m, localPlayer.x, localPlayer.y, 16.0f, mapManager);
                            if (m.hp == 0) {
                                m.isDead = true;
                                m.respawnTimer = 180;
                                m.currentPath = new int[0][2];
                                m.pathIndex = 0;
                            }
                        }
                    }
                }
                previousLocalAttack = attack;

                GamePhysics.updatePlayer(localPlayer, mapManager, dt);

                for (GameData.MonsterState m : renderMonsters.values()) {
                    if (m.isDead) {
                        m.respawnTimer--;
                        if (m.respawnTimer <= 0) {
                            m.isDead = false;
                            m.hp = m.maxHp;
                            m.flashUntil = 0;
                            m.nextPathAt = 0;
                            m.currentPath = new int[0][2];
                            m.pathIndex = 0;
                            Point pt = getRandomMapPoint();
                            m.x = pt.x;
                            m.y = pt.y;
                        }
                    } else {
                        GamePhysics.updateMonster(m, localPlayer, mapManager, dt);
                    }
                }

                if (localPlayer.hp <= 0) {
                    localPlayer.respawn();
                }

                repaint();

                long sleepTimeMs = (long)((lastTime - System.nanoTime() + OPTIMAL_NS) / 1_000_000.0);
                if (sleepTimeMs > 0) {
                    try { Thread.sleep(sleepTimeMs); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "Offline-Loop");
        localGameThread.start();
    }

    public void stopOfflineMode() {
        isLocalRunning = false;
        if (localGameThread != null) {
            localGameThread.interrupt();
            localGameThread = null;
        }
    }

    public void updateOnlineWorld(String stateLine, String myCurrentName) {
        this.myName = myCurrentName;
        GameData.WorldSnapshot snapshot = GameProtocol.parseSnapshot(stateLine);
        long now = System.currentTimeMillis();
        Map<String, GameData.PlayerState> previousPlayers = renderPlayers;
        Map<String, GameData.PlayerState> updatedPlayers = new ConcurrentHashMap<>();

        for (GameData.PlayerState p : snapshot.players) {
            GameData.PlayerState previous = previousPlayers.get(p.id);
            if (previous != null) {
                if (p.hp < previous.hp) p.flashUntil = now + 150;
                p.attackTicks = previous.isAttacking ? previous.attackTicks + 1 : 0;
            }
            updatedPlayers.put(p.id, p);
            if (p.name.equals(myName)) {
                movement.onServerState(p.x, p.y, p.lastAckSeq);
            }
        }
        renderPlayers = updatedPlayers;

        Map<String, GameData.MonsterState> previousMonsters = renderMonsters;
        Map<String, GameData.MonsterState> updatedMonsters = new ConcurrentHashMap<>();
        for (GameData.MonsterState m : snapshot.monsters) {
            GameData.MonsterState previous = previousMonsters.get(m.id);
            if (previous != null && m.hp < previous.hp) m.flashUntil = now + 150;
            updatedMonsters.put(m.id, m);
        }
        renderMonsters = updatedMonsters;

        repaint();
    }

    public void setHudInfo(String l1, String l2, String l3) {
        this.hudLine1 = l1; this.hudLine2 = l2; this.hudLine3 = l3;
    }
    public void toggleHud() { this.showHud = !this.showHud; }
    public void setBannerMessage(String msg) { this.bannerMessage = msg; }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        Map<String, GameData.PlayerState> playersSnapshot = renderPlayers;
        Map<String, GameData.MonsterState> monstersSnapshot = renderMonsters;

        int myX = (int) Common.SPAWN_X;
        int myY = (int) Common.SPAWN_Y;
        int myHp = 100;
        int myMaxHp = 100;

        boolean foundMe = false;
        for (GameData.PlayerState p : playersSnapshot.values()) {
            if (p.name.equals(myName)) {
                myX = (int) p.x;
                myY = (int) p.y;
                myHp = p.hp;
                myMaxHp = p.maxHp;
                foundMe = true;
                break;
            }
        }

        mapManager.draw(g2, myX, myY, getWidth(), getHeight());

        int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

        int camX = myX - getWidth() / 2;
        int camY = myY - getHeight() / 2;
        if (totalMapWidth > getWidth()) camX = Math.max(0, Math.min(camX, totalMapWidth - getWidth()));
        if (totalMapHeight > getHeight()) camY = Math.max(0, Math.min(camY, totalMapHeight - getHeight()));

        int entitySize = GamePhysics.ENTITY_SIZE;

        // 1. Vẽ Quái Slime
        for (GameData.MonsterState m : monstersSnapshot.values()) {
            if (m.isDead) continue;
            int screenX = (int) m.x - camX;
            int screenY = (int) m.y - camY;

            Image currentSlime = slimeSprites[(m.spriteNum == 2) ? 1 : 0];
            if (currentSlime != null) {
                g2.drawImage(currentSlime, screenX, screenY, entitySize, entitySize, null);
            } else {
                g2.setColor(Color.GREEN);
                g2.fillRoundRect(screenX, screenY, entitySize, entitySize, 12, 12);
            }

            if (System.currentTimeMillis() < m.flashUntil) {
                g2.setColor(new Color(255, 25, 25, 140));
                g2.fillRect(screenX, screenY, entitySize, entitySize);
            }

            if (m.hp < m.maxHp) {
                g2.setColor(new Color(45, 45, 50));
                g2.fillRect(screenX, screenY - 6, entitySize, 4);
                float hpRatio = m.maxHp <= 0 ? 0 : Math.max(0, Math.min(1, (float) m.hp / m.maxHp));
                g2.setColor(hpRatio > 0.3f ? new Color(65, 190, 85) : new Color(225, 55, 55));
                g2.fillRect(screenX, screenY - 6, (int) (entitySize * hpRatio), 4);
            }
        }

        // 2. Vẽ Players & Animation đòn chém
        for (GameData.PlayerState p : playersSnapshot.values()) {
            int screenX = (int) p.x - camX;
            int screenY = (int) p.y - camY;

            Image currentSprite = null;

            if (p.isAttacking) {
                Image[] axeFrames = axeSpriteMap.get(p.direction);
                if (axeFrames != null && axeFrames.length > 0) {
                    // 8 tick đầu giơ rìu lên (ảnh 1), 16 tick sau giáng rìu xuống (ảnh 2)
                    int axeIdx = (p.attackTicks <= 8) ? 0 : 1;
                    currentSprite = axeFrames[axeIdx];
                }
            } else {
                Image[] walkFrames = spriteMap.get(p.direction);
                if (walkFrames != null && walkFrames.length > 0) {
                    int frameIdx = (p.spriteNum == 2 && walkFrames.length > 1) ? 1 : 0;
                    currentSprite = walkFrames[frameIdx];
                }
            }

            if (currentSprite != null) {
                if (p.isAttacking) {
                    int renderW = entitySize;
                    int renderH = entitySize;
                    int drawX = screenX;
                    int drawY = screenY;

                    // Căn chuẩn tỉ lệ cho ảnh 16x32 (dọc) và 32x16 (ngang)
                    switch (p.direction) {
                        case "up":
                            renderH = entitySize * 2;
                            drawY = screenY - entitySize;
                            break;
                        case "down":
                            renderH = entitySize * 2;
                            break;
                        case "left":
                            renderW = entitySize * 2;
                            drawX = screenX - entitySize;
                            break;
                        case "right":
                            renderW = entitySize * 2;
                            break;
                    }
                    g2.drawImage(currentSprite, drawX, drawY, renderW, renderH, null);
                } else {
                    g2.drawImage(currentSprite, screenX, screenY, entitySize, entitySize, null);
                }
            } else {
                g2.setColor(p.name.equals(myName) ? Color.CYAN : Color.BLUE);
                g2.fillRect(screenX, screenY, entitySize, entitySize);
            }

            if (System.currentTimeMillis() < p.flashUntil) {
                g2.setColor(new Color(255, 25, 25, 140));
                g2.fillRect(screenX, screenY, entitySize, entitySize);
            }

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 12));
            g2.drawString(p.name, screenX - 5, screenY - 10);

            g2.setColor(Color.RED);
            g2.fillRect(screenX, screenY - 5, entitySize, 4);
            g2.setColor(Color.CYAN);
            g2.fillRect(screenX, screenY - 5, (int)(entitySize * ((float)p.hp / p.maxHp)), 4);
        }

        int safeMaxHp = Math.max(1, myMaxHp);
        g2.setColor(new Color(15, 18, 26, 210));
        g2.fillRoundRect(16, 16, 240, 60, 12, 12);
        g2.setColor(new Color(80, 95, 125));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(16, 16, 240, 60, 12, 12);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 13));
        g2.drawString("HP: " + Math.max(0, myHp) + " / " + safeMaxHp, 28, 36);
        g2.setColor(new Color(45, 52, 70));
        g2.fillRoundRect(28, 44, 216, 14, 8, 8);
        int currentBarW = Math.max(0, Math.min(216, (int) (216 * ((float) myHp / safeMaxHp))));
        g2.setColor(myHp > safeMaxHp * 0.3f ? new Color(225, 55, 55) : new Color(255, 120, 0));
        g2.fillRoundRect(28, 44, currentBarW, 14, 8, 8);

        if (showHud && !hudLine1.isEmpty()) {
            int hudX = 270;
            int hudWidth = Math.max(0, getWidth() - hudX - 10);
            g2.setColor(new Color(0, 40, 0, 200));
            g2.fillRoundRect(hudX, 10, hudWidth, 65, 10, 10);
            g2.setColor(Color.GREEN);
            g2.setFont(new Font("Monospaced", Font.BOLD, 13));
            g2.drawString(hudLine1, hudX + 10, 28);
            g2.drawString(hudLine2, hudX + 10, 46);
            g2.drawString(hudLine3, hudX + 10, 64);
        }

        if (bannerMessage != null) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, getHeight() / 2 - 40, getWidth(), 80);
            g2.setColor(Color.ORANGE);
            g2.setFont(new Font("Arial", Font.BOLD, 22));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (getWidth() - fm.stringWidth(bannerMessage)) / 2;
            g2.drawString(bannerMessage, tx, getHeight() / 2 + 8);
        }
    }
}
