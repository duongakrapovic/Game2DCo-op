package source.client;

import source.common.*;
import java.awt.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class ClientRenderer {
    private final Map<String, Image[]> spriteMap = new HashMap<>();
    private final Map<String, Image[]> axeSpriteMap = new HashMap<>();
    private final Image[] slimeSprites = new Image[2];
    private Image potionImage;
    private final ScoreboardOverlay scoreboard = new ScoreboardOverlay();
    public ClientRenderer() { loadAssets(); }
    private void loadAssets() {
        String pPath = "source/content/player/";
        String mPath = "source/content/monster/";
        String[] directions = {"down", "up", "left", "right"};

            for (String dir : directions) {
                Image[] frames = new Image[2];
                frames[0] = loadRequiredImage(pPath + "boy_" + dir + "_1.png");
                frames[1] = loadRequiredImage(pPath + "boy_" + dir + "_2.png");
                spriteMap.put(dir, frames);
            }

            for (String dir : directions) {
                Image[] axeFrames = new Image[2];
                axeFrames[0] = loadRequiredImage(pPath + "boy_axe_" + dir + "_1.png");
                axeFrames[1] = loadRequiredImage(pPath + "boy_axe_" + dir + "_2.png");
                axeSpriteMap.put(dir, axeFrames);
            }

            slimeSprites[0] = loadRequiredImage(mPath + "greenslime_down_1.png");
            slimeSprites[1] = loadRequiredImage(mPath + "greenslime_down_2.png");
            potionImage = loadRequiredImage("source/content/object/healthposion.png");
    }

    private Image loadRequiredImage(String path) {
        try (java.io.InputStream input = new java.io.FileInputStream(path)) {
            Image image = ImageIO.read(input);
            if (image == null) throw new java.io.IOException("Unsupported image format");
            return image;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Khong nap duoc asset: " + path, error);
        }
    }
    public void render(Graphics2D g2, ClientGame panel) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        String myName = panel.myName;
        ClientMovement movement = panel.movement;
        GameMap mapManager = panel.mapManager;
        Map<String, GameData.PlayerState> renderPlayers = panel.renderPlayers;
        Map<String, GameData.MonsterState> renderMonsters = panel.renderMonsters;
        String voiceStatus = panel.voiceStatus, bannerMessage = panel.bannerMessage;
        boolean showScoreboard = panel.showScoreboard;
        int width = panel.getWidth(), height = panel.getHeight();
        Map<String, GameData.PlayerState> playersSnapshot = renderPlayers;
        Map<String, GameData.MonsterState> monstersSnapshot = renderMonsters;

        int myX = (int) Common.SPAWN_X;
        int myY = (int) Common.SPAWN_Y;
        int myHp = 100;
        int myMaxHp = 100;

        for (GameData.PlayerState p : playersSnapshot.values()) {
            if (panel.isMe(p)) {
                myX = (int) (panel.isOfflineRunning() ? p.x : movement.x());
                myY = (int) (panel.isOfflineRunning() ? p.y : movement.y());
                myHp = p.hp;
                myMaxHp = p.maxHp;
                break;
            }
        }

        mapManager.draw(g2, myX, myY, width, height);

        int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

        int camX = myX - width / 2;
        int camY = myY - height / 2;
        if (totalMapWidth > width) camX = Math.max(0, Math.min(camX, totalMapWidth - width));
        if (totalMapHeight > height) camY = Math.max(0, Math.min(camY, totalMapHeight - height));

        int entitySize = GamePhysics.ENTITY_SIZE;

        // Use the original potion sprite from tranvanducb23dccn191.
        for (GameData.ItemState item : panel.renderItems.values()) {
            if (item.collected) continue;
            int x = (int) item.x - camX, y = (int) item.y - camY;
            if (x + entitySize < 0 || y + entitySize < 0 || x > width || y > height) continue;
            g2.drawImage(potionImage, x, y, entitySize, entitySize, null);
        }

        // 1. Vẽ Quái Slime
        for (GameData.MonsterState m : monstersSnapshot.values()) {
            if (m.isDead) continue;
            int screenX = (int) m.x - camX;
            int screenY = (int) m.y - camY;

            Image currentSlime = slimeSprites[(m.spriteNum == 2) ? 1 : 0];
            g2.drawImage(currentSlime, screenX, screenY, entitySize, entitySize, null);

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
            float drawXPos = p.x, drawYPos = p.y;
            String direction = p.direction;
            int spriteNum = p.spriteNum;
            if (!panel.isOfflineRunning()) {
                if (panel.isMe(p)) { drawXPos = movement.x(); drawYPos = movement.y(); }
                else {
                    ClientMovement.Snap interpolated = movement.getInterpolatedState(p.id);
                    if (interpolated != null) {
                        drawXPos = interpolated.x; drawYPos = interpolated.y;
                        direction = interpolated.direction; spriteNum = interpolated.spriteNum;
                    }
                }
            }
            int screenX = (int) drawXPos - camX;
            int screenY = (int) drawYPos - camY;

            Image currentSprite = null;

            if (p.isAttacking) {
                Image[] axeFrames = axeSpriteMap.get(direction);
                if (axeFrames != null && axeFrames.length > 0) {
                    int axeIdx = (p.attackTicks <= 8) ? 0 : 1;
                    currentSprite = axeFrames[axeIdx];
                }
            } else {
                Image[] walkFrames = spriteMap.get(direction);
                if (walkFrames != null && walkFrames.length > 0) {
                    int frameIdx = (spriteNum == 2 && walkFrames.length > 1) ? 1 : 0;
                    currentSprite = walkFrames[frameIdx];
                }
            }

            if (currentSprite != null) {
                if (p.isAttacking) {
                    int renderW = entitySize;
                    int renderH = entitySize;
                    int drawX = screenX;
                    int drawY = screenY;

                    switch (direction) {
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
            g2.fillRect(screenX, screenY - 5, Math.max(0, Math.min(entitySize, (int) (entitySize * ((float) Math.max(0, p.hp) / Math.max(1, p.maxHp))))), 4);
        }

        // 3. Hiển thị HUD cá nhân & Máu người chơi
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

        // 4. Hiển thị trạng thái Voice Chat
        if (!voiceStatus.isEmpty()) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(16, 86, 320, 28, 8, 8);
            Color statusColor = voiceStatus.contains("unavailable") ? Color.ORANGE
                    : voiceStatus.contains("Muted") ? Color.YELLOW : Color.GREEN;
            g2.setColor(statusColor);
            g2.setFont(new Font("Arial", Font.BOLD, 12));
            g2.drawString(voiceStatus, 24, 104);
        }

        if (bannerMessage != null) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, height / 2 - 40, width, 80);
            g2.setColor(Color.ORANGE);
            g2.setFont(new Font("Arial", Font.BOLD, 22));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (width - fm.stringWidth(bannerMessage)) / 2;
            g2.drawString(bannerMessage, tx, height / 2 + 8);
        }

        // 5. Hiển thị Bảng điểm Giữ phím TAB (Kiểu Valorant)
        if (showScoreboard) {
            scoreboard.render(g2, panel);
        }
        }
}
