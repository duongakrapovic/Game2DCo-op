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

    private final Map<String, GameData.PlayerState> renderPlayers = new ConcurrentHashMap<>();
    private final Map<String, GameData.MonsterState> renderMonsters = new ConcurrentHashMap<>();
    
    private final GameMap mapManager;
    private final ClientMovement movement;

    private GameData.PlayerState localPlayer;
    private String myName = "Player";
    private Thread localGameThread;
    private volatile boolean isLocalRunning = false;
    private final Random rand = new Random();

    public boolean left = false, right = false, up = false, down = false, attack = false;
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
        int maxW = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE - 100;
        int maxH = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE - 100;
        int rx = 100 + rand.nextInt(Math.max(1, maxW - 100));
        int ry = 100 + rand.nextInt(Math.max(1, maxH - 100));
        return new Point(rx, ry);
    }

    public void startOfflineMode(String playerName) {
        stopOfflineMode();
        this.myName = playerName;

        localPlayer = new GameData.PlayerState("LOCAL_1", myName);
        renderPlayers.clear();
        renderMonsters.clear();

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
                if (attack || localPlayer.isAttacking) {
                    if (!localPlayer.isAttacking) {
                        localPlayer.isAttacking = true;
                        localPlayer.attackTicks = 0;

                        Rectangle atkBox = GamePhysics.getAttackHitbox(localPlayer.x, localPlayer.y, localPlayer.direction);
                        for (GameData.MonsterState m : renderMonsters.values()) {
                            if (!m.isDead && atkBox.intersects(GamePhysics.getEntityBounds(m.x, m.y))) {
                                m.hp -= 15;
                                if (m.hp <= 0) {
                                    m.isDead = true;
                                    m.respawnTimer = 180;
                                }
                            }
                        }
                    }

                    localPlayer.attackTicks++;

                    if (localPlayer.attackTicks >= 36) {
                        localPlayer.isAttacking = false;
                        localPlayer.attackTicks = 0;
                    }
                }

                GamePhysics.updatePlayer(localPlayer, mapManager, dt);

                for (GameData.MonsterState m : renderMonsters.values()) {
                    if (m.isDead) {
                        m.respawnTimer--;
                        if (m.respawnTimer <= 0) {
                            m.isDead = false;
                            m.hp = m.maxHp;
                            Point pt = getRandomMapPoint();
                            m.x = pt.x;
                            m.y = pt.y;
                        }
                    } else {
                        GamePhysics.updateMonster(m, localPlayer, mapManager);
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

        renderPlayers.clear();
        for (GameData.PlayerState p : snapshot.players) {
            renderPlayers.put(p.id, p);
            if (p.name.equals(myName)) {
                movement.onServerState(p.x, p.y, p.lastAckSeq);
            }
        }

        renderMonsters.clear();
        for (GameData.MonsterState m : snapshot.monsters) {
            renderMonsters.put(m.id, m);
        }

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

        int myX = (int) Common.SPAWN_X;
        int myY = (int) Common.SPAWN_Y;

        boolean foundMe = false;
        for (GameData.PlayerState p : renderPlayers.values()) {
            if (p.name.equals(myName)) {
                myX = (int) p.x;
                myY = (int) p.y;
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
        for (GameData.MonsterState m : renderMonsters.values()) {
            if (m.isDead) continue;
            int screenX = (int) m.x - camX;
            int screenY = (int) m.y - camY;

            Image currentSlime = slimeSprites[(m.spriteNum == 2) ? 1 : 0];
            if (currentSlime != null) {
                g2.drawImage(currentSlime, screenX, screenY, entitySize, entitySize, null);
            } else {
                g2.setColor(Color.GREEN);
                g2.fillOval(screenX, screenY, entitySize, entitySize);
            }

            g2.setColor(Color.RED);
            g2.fillRect(screenX, screenY - 6, entitySize, 4);
            g2.setColor(Color.GREEN);
            g2.fillRect(screenX, screenY - 6, (int)(entitySize * ((float)m.hp / m.maxHp)), 4);
        }

        // 2. Vẽ Players & Animation đòn chém
        for (GameData.PlayerState p : renderPlayers.values()) {
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

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 12));
            g2.drawString(p.name, screenX - 5, screenY - 10);

            g2.setColor(Color.RED);
            g2.fillRect(screenX, screenY - 5, entitySize, 4);
            g2.setColor(Color.CYAN);
            g2.fillRect(screenX, screenY - 5, (int)(entitySize * ((float)p.hp / p.maxHp)), 4);
        }

        if (showHud && !hudLine1.isEmpty()) {
            g2.setColor(new Color(0, 40, 0, 200));
            g2.fillRect(10, 10, getWidth() - 20, 65);
            g2.setColor(Color.GREEN);
            g2.setFont(new Font("Monospaced", Font.BOLD, 13));
            g2.drawString(hudLine1, 20, 28);
            g2.drawString(hudLine2, 20, 46);
            g2.drawString(hudLine3, 20, 64);
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