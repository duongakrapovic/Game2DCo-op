package source.client;

import source.common.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.File;

/*
 * Vẽ lớp phủ bảng người chơi khi renderer được yêu cầu hiển thị TAB. Bảng là hình ảnh từ snapshot hiện
 * tại, không phải giao diện chỉnh bốn slot JSON.
 * Không có trường trạng thái riêng; render nhận Graphics2D và ClientGame, đọc tên phòng, myId, danh sách
 * player và ping.
 */
public final class ScoreboardOverlay {
    /*
     * render()
     * Làm tối nền, vẽ panel/bốn hàng và các cột slot/tên/trạng thái/HP/ping; tô local player bằng isMe.
     * Online lấy player đang hiển thị, không liệt kê mọi chủ save đã offline; thứ tự danh sách không tự
     * gắn với slotIndex lưu trên đĩa. Offline có nhánh riêng trong source nhưng không thuộc luồng online.
     */
    public void render(Graphics2D g2, ClientGame panel) {
        int width = panel.getWidth(), height = panel.getHeight();
        String myName = panel.myName, currentRoomName = panel.currentRoomName;
        boolean isLocalRunning = panel.isOfflineRunning();
        Map<String, GameData.PlayerState> renderPlayers = panel.renderPlayers;
        GameData.PlayerState localPlayer = renderPlayers.get("LOCAL_1");
        int panelW = 680;
        int panelH = 340;
        int panelX = (width - panelW) / 2;
        int panelY = (height - panelH) / 2;

        // Lớp phủ mờ nền toàn màn hình
        g2.setColor(new Color(0, 0, 0, 140));
        g2.fillRect(0, 0, width, height);

        // Bảng nền bán trong suốt bo góc
        g2.setColor(new Color(15, 20, 30, 230));
        g2.fillRoundRect(panelX, panelY, panelW, panelH, 16, 16);
        g2.setColor(new Color(70, 85, 115));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(panelX, panelY, panelW, panelH, 16, 16);

        // Tiêu đề phòng / chế độ chơi
        String title = isLocalRunning ? "BẢNG ĐIỂM - CHẾ ĐỘ OFFLINE TEST"
                : (currentRoomName.isEmpty() ? "BẢNG ĐIỂM - PHÒNG CHƠI ONLINE"
                : "BẢNG ĐIỂM - PHÒNG: " + currentRoomName);
        g2.setColor(new Color(245, 190, 75));
        g2.setFont(new Font("Arial", Font.BOLD, 18));
        g2.drawString(title, panelX + 24, panelY + 36);

        g2.setColor(new Color(170, 180, 200));
        g2.setFont(new Font("Arial", Font.ITALIC, 12));
        g2.drawString("[TAB] ĐANG GIỮ ĐỂ XEM BẢNG ĐIỂM", panelX + panelW - 240, panelY + 36);

        // Kẻ ngang phân cách
        g2.setColor(new Color(50, 60, 80));
        g2.drawLine(panelX + 16, panelY + 50, panelX + panelW - 16, panelY + 50);

        // Tiêu đề các cột
        int startY = panelY + 75;
        g2.setFont(new Font("Arial", Font.BOLD, 13));
        g2.setColor(new Color(140, 160, 190));
        g2.drawString("SLOT", panelX + 30, startY);
        g2.drawString("NGƯỜI CHƠI", panelX + 110, startY);
        g2.drawString("TRẠNG THÁI", panelX + 310, startY);
        g2.drawString("MÁU (HP)", panelX + 440, startY);
        g2.drawString("PING", panelX + 560, startY);

        g2.setColor(new Color(40, 50, 70));
        g2.drawLine(panelX + 16, startY + 10, panelX + panelW - 16, startY + 10);

        // Danh sách người chơi
        List<GameData.PlayerState> playerList = new ArrayList<>();
        if (isLocalRunning) {
            if (localPlayer != null) {
                playerList.add(localPlayer);
            }
        } else {
            playerList.addAll(renderPlayers.values());
        }

        int rowY = startY + 22;
        int rowH = 46;

        for (int slot = 0; slot < 4; slot++) {
            int curY = rowY + slot * rowH;
            boolean hasPlayer = slot < playerList.size();
            GameData.PlayerState p = hasPlayer ? playerList.get(slot) : null;
            boolean isMe = p != null && panel.isMe(p);

            // Nền hàng
            if (isMe) {
                g2.setColor(new Color(45, 75, 115, 170));
                g2.fillRoundRect(panelX + 20, curY, panelW - 40, 38, 8, 8);
                g2.setColor(new Color(85, 140, 205));
                g2.drawRoundRect(panelX + 20, curY, panelW - 40, 38, 8, 8);
            } else if (hasPlayer) {
                g2.setColor(new Color(25, 32, 45, 130));
                g2.fillRoundRect(panelX + 20, curY, panelW - 40, 38, 8, 8);
            } else {
                g2.setColor(new Color(20, 25, 35, 80));
                g2.fillRoundRect(panelX + 20, curY, panelW - 40, 38, 8, 8);
            }

            int textY = curY + 24;

            if (hasPlayer && p != null) {
                // Slot
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 13));
                g2.drawString("Slot " + (slot + 1), panelX + 30, textY);

                // Tên
                if (isMe) {
                    g2.setColor(new Color(255, 215, 80));
                    g2.drawString(p.name + " (Bạn)", panelX + 110, textY);
                } else {
                    g2.setColor(Color.WHITE);
                    g2.drawString(p.name, panelX + 110, textY);
                }

                // Trạng thái
                if (p.hp > 0) {
                    g2.setColor(new Color(65, 220, 95));
                    g2.drawString("SỐNG", panelX + 310, textY);
                } else {
                    g2.setColor(new Color(240, 70, 70));
                    g2.drawString("HỒI SINH", panelX + 310, textY);
                }

                // Máu
                g2.setColor(Color.WHITE);
                g2.drawString(p.hp + " / " + p.maxHp, panelX + 440, textY);

                // Ping
                int ping = isLocalRunning ? 0 : p.pingMs;
                Color pingColor = ping < 60 ? new Color(65, 220, 95)
                        : (ping <= 120 ? new Color(245, 215, 60) : new Color(240, 70, 70));
                g2.setColor(pingColor);
                g2.drawString(ping + " ms", panelX + 560, textY);
            } else {
                // Slot trống
                g2.setColor(new Color(130, 140, 160, 140));
                g2.setFont(new Font("Arial", Font.PLAIN, 13));
                g2.drawString("Slot " + (slot + 1), panelX + 30, textY);
                g2.drawString("Slot " + (slot + 1) + " - [Trống]", panelX + 110, textY);
                g2.drawString("-", panelX + 310, textY);
                g2.drawString("-", panelX + 440, textY);
                g2.drawString("-", panelX + 560, textY);
            }
        }
    }
}
