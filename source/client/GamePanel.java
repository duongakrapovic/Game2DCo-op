package source.client;

import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GamePanel extends JPanel {

    public static class RenderPlayer {
        public String name;
        public int x, y;
        public String direction = "down";
        public int spriteNum = 1;

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
        }
    }

    private final Map<String, Image[]> spriteMap = new HashMap<>();
    private final Map<String, RenderPlayer> renderPlayers = new ConcurrentHashMap<>();
    private final MapManager mapManager;

    private boolean isOfflineMode = false;
    private Player localPlayer;
    private String myName = "Player";
    private Thread localGameThread;
    private volatile boolean isLocalRunning = false;

    public boolean left = false, right = false, up = false, down = false;

    public GamePanel(MapManager mapManager) {
        this.mapManager = mapManager;
        setFocusable(true);
        loadAssets();
    }

    private void loadAssets() {
        String path = "source/content/player/";
        String[] directions = {"down", "up", "left", "right"};
        try {
            for (String dir : directions) {
                Image[] frames = new Image[2];
                frames[0] = ImageIO.read(new File(path + "boy_" + dir + "_1.png"));
                frames[1] = ImageIO.read(new File(path + "boy_" + dir + "_2.png"));
                spriteMap.put(dir, frames);
            }
            System.out.println("[GamePanel] Nap thanh cong bo sprite nhan vat boy_!");
        } catch (Exception e) {
            System.err.println("[GamePanel] Khong tim thay du sprite trong " + path + ": " + e.getMessage());
        }
    }

    public void startOfflineMode(String playerName) {
        stopOfflineMode();
        isOfflineMode = true;
        this.myName = playerName;

        localPlayer = new Player("LOCAL_1", myName);
        renderPlayers.clear();
        renderPlayers.put("LOCAL_1", new RenderPlayer(myName, (int)localPlayer.x, (int)localPlayer.y, localPlayer.direction, localPlayer.spriteNum));

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

                PhysicsEngine.updatePlayer(localPlayer, mapManager, dt);

                renderPlayers.put("LOCAL_1", new RenderPlayer(myName, (int)localPlayer.x, (int)localPlayer.y, localPlayer.direction, localPlayer.spriteNum));
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

    public void updateOnlinePlayers(String stateLine, String myCurrentName) {
        this.myName = myCurrentName;
        String[] items = stateLine.split(";");
        renderPlayers.clear();
        for (int i = 1; i < items.length; i++) {
            String[] pData = items[i].split(",");
            if (pData.length >= 6) {
                renderPlayers.put(pData[0], new RenderPlayer(
                    pData[1], Integer.parseInt(pData[2]), Integer.parseInt(pData[3]), pData[4], Integer.parseInt(pData[5])
                ));
            }
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        int myX = (int) Common.SPAWN_X;
        int myY = (int) Common.SPAWN_Y;

        boolean foundMe = false;
        for (RenderPlayer p : renderPlayers.values()) {
            if (p.name.equals(myName)) {
                myX = p.x;
                myY = p.y;
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

        int playerRenderSize = (int) (16 * Common.SCALE);

        // Fallback vẽ tạm nhân vật nếu state online chưa về kịp để không bị tàng hình
        if (renderPlayers.isEmpty() || !foundMe) {
            Image[] defaultFrames = spriteMap.get("down");
            if (defaultFrames != null && defaultFrames[0] != null) {
                g2.drawImage(defaultFrames[0], (int)Common.SPAWN_X - camX, (int)Common.SPAWN_Y - camY, playerRenderSize, playerRenderSize, null);
            } else {
                g2.setColor(Color.GREEN);
                g2.fillRect((int)Common.SPAWN_X - camX, (int)Common.SPAWN_Y - camY, playerRenderSize, playerRenderSize);
            }
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            g2.drawString(myName, (int)Common.SPAWN_X - camX - 5, (int)Common.SPAWN_Y - camY - 5);
        }

        for (RenderPlayer p : renderPlayers.values()) {
            int screenX = p.x - camX;
            int screenY = p.y - camY;

            Image[] frames = spriteMap.get(p.direction);
            Image currentSprite = null;
            if (frames != null && frames.length > 0) {
                int frameIdx = (p.spriteNum == 2 && frames.length > 1) ? 1 : 0;
                currentSprite = frames[frameIdx];
            }

            if (currentSprite != null) {
                g2.drawImage(currentSprite, screenX, screenY, playerRenderSize, playerRenderSize, null);
            } else {
                g2.setColor(p.name.equals(myName) ? Color.CYAN : Color.GREEN);
                g2.fillRect(screenX, screenY, playerRenderSize, playerRenderSize);
            }

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            g2.drawString(p.name, screenX - 5, screenY - 5);
        }
    }
}