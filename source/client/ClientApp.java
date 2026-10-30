package source.client;

import source.common.Common;
import source.common.MapManager;
import source.common.Player;
import source.server.PhysicsEngine;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientApp extends JFrame {
    private static class RenderPlayer {
        String name;
        int x, y;
        RenderPlayer(String name, int x, int y) { 
            this.name = name; 
            this.x = x; 
            this.y = y; 
        }
    }

    private static final Map<String, RenderPlayer> renderPlayers = new ConcurrentHashMap<>();
    private static String myName = "Player_Test";
    private static PrintWriter out;
    private static BufferedReader in;

    // Biến trạng thái phím bấm 4 hướng Top-Down
    private static boolean left = false, right = false, up = false, down = false;
    private static Image[] playerSprites = new Image[4];
    private static int animTick = 0;

    private MapManager mapManager;

    private static boolean isOfflineMode = false;
    private static Player localPlayer;

    private CardLayout cardLayout = new CardLayout();
    private JPanel mainContainer = new JPanel(cardLayout);
    private DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private JList<String> roomJList = new JList<>(roomListModel);

    public ClientApp() {
        setTitle("Dungeon Co-op Game " + (isOfflineMode ? "[OFFLINE TEST]" : "[ONLINE]"));
        setSize(Common.SCREEN_WIDTH, Common.SCREEN_HEIGHT);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Nạp bản đồ từ source/content/map
        String mapPath = "source/content/map";
        mapManager = new MapManager();
        mapManager.loadTilesetImage(mapPath + "/tileset.png", Common.ORIGINAL_TILE_SIZE);
        mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        loadAssets();

        JPanel lobbyPanel = createLobbyUI();
        GamePanel gamePanel = new GamePanel();

        mainContainer.add(lobbyPanel, "LOBBY");
        mainContainer.add(gamePanel, "GAME");
        add(mainContainer);

        // Đăng ký lắng nghe sự kiện bàn phím
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) { updateKey(e.getKeyCode(), true); }
            @Override
            public void keyReleased(KeyEvent e) { updateKey(e.getKeyCode(), false); }
        });

        // Xử lý riêng cho chế độ Test Offline
        if (isOfflineMode) {
            localPlayer = new Player("LOCAL_1", myName);
            renderPlayers.put("LOCAL_1", new RenderPlayer(myName, (int) localPlayer.x, (int) localPlayer.y));
            cardLayout.show(mainContainer, "GAME");

            // Game Loop 60 FPS chạy trực tiếp tại Client
            new Timer(16, e -> {
                localPlayer.left  = left;
                localPlayer.right = right;
                localPlayer.up    = up;
                localPlayer.down  = down;

                // Tính toán va chạm Top-Down qua PhysicsEngine
                PhysicsEngine.updatePlayer(localPlayer, mapManager);

                renderPlayers.put("LOCAL_1", new RenderPlayer(myName, (int) localPlayer.x, (int) localPlayer.y));
                repaint();
            }).start();
        }
    }

    private void loadAssets() {
        try {
            for (int i = 0; i < 4; i++) {
                File pFile = new File("source/content/map/player_" + (i + 1) + ".png");
                if (pFile.exists()) {
                    playerSprites[i] = ImageIO.read(pFile);
                }
            }
        } catch (Exception e) {}
    }

    private JPanel createLobbyUI() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("SẢNH CHỜ GAME MULTIPLAYER", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));
        panel.add(title, BorderLayout.NORTH);

        panel.add(new JScrollPane(roomJList), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        JButton btnRefresh = new JButton("Làm mới");
        JButton btnCreate  = new JButton("Tạo phòng");
        JButton btnJoin    = new JButton("Vào phòng");

        btnPanel.add(btnRefresh);
        btnPanel.add(btnCreate);
        btnPanel.add(btnJoin);
        panel.add(btnPanel, BorderLayout.SOUTH);

        btnRefresh.addActionListener(e -> { if (out != null) out.println(Common.CMD_ROOM_LIST); });

        btnCreate.addActionListener(e -> {
            String rName = JOptionPane.showInputDialog(this, "Nhập tên phòng:");
            if (rName != null && !rName.trim().isEmpty()) {
                out.println(Common.CMD_CREATE_ROOM + ":" + rName.trim() + ":" + myName);
                cardLayout.show(mainContainer, "GAME");
                requestFocusInWindow();
            }
        });

        btnJoin.addActionListener(e -> {
            String selected = roomJList.getSelectedValue();
            if (selected != null) {
                String rName = selected.split(" ")[0];
                out.println(Common.CMD_JOIN_ROOM + ":" + rName + ":" + myName);
                cardLayout.show(mainContainer, "GAME");
                requestFocusInWindow();
            }
        });

        return panel;
    }

    private void updateKey(int keyCode, boolean isPressed) {
        if (keyCode == KeyEvent.VK_A || keyCode == KeyEvent.VK_LEFT)  left  = isPressed;
        if (keyCode == KeyEvent.VK_D || keyCode == KeyEvent.VK_RIGHT) right = isPressed;
        if (keyCode == KeyEvent.VK_W || keyCode == KeyEvent.VK_UP)    up    = isPressed;
        if (keyCode == KeyEvent.VK_S || keyCode == KeyEvent.VK_DOWN)  down  = isPressed;

        if (isOfflineMode && localPlayer != null) {
            localPlayer.left  = left;
            localPlayer.right = right;
            localPlayer.up    = up;
            localPlayer.down  = down;
        } else if (!isOfflineMode && out != null) {
            // Gửi dữ liệu điều khiển 4 hướng lên Server
            out.println(Common.CMD_INPUT + ":" + left + ":" + right + ":" + up + ":" + down);
        }
    }

    private class GamePanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            animTick++;

            int myX = (int) Common.SPAWN_X;
            int myY = (int) Common.SPAWN_Y;

            for (RenderPlayer p : renderPlayers.values()) {
                if (p.name.equals(myName)) {
                    myX = p.x;
                    myY = p.y;
                    break;
                }
            }

            // 1. Vẽ Map và tự động khóa biên Camera
            mapManager.draw(g2, myX, myY, getWidth(), getHeight());

            // 2. Tải vị trí Camera thực tế để vẽ Player
            int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
            int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

            int camX = myX - getWidth() / 2;
            int camY = myY - getHeight() / 2;

            if (totalMapWidth > getWidth()) {
                camX = Math.max(0, Math.min(camX, totalMapWidth - getWidth()));
            }
            if (totalMapHeight > getHeight()) {
                camY = Math.max(0, Math.min(camY, totalMapHeight - getHeight()));
            }

            int spriteIndex = (animTick / 8) % 4;
            Image currentSprite = playerSprites[spriteIndex];

            int playerRenderSize = (int) (24 * Common.SCALE);

            for (RenderPlayer p : renderPlayers.values()) {
                int screenX = p.x - camX;
                int screenY = p.y - camY;

                if (currentSprite != null) {
                    g2.drawImage(currentSprite, screenX, screenY, playerRenderSize, playerRenderSize, null);
                } else {
                    g2.setColor(p.name.equals(myName) ? Color.CYAN : Color.GREEN);
                    g2.fillRect(screenX, screenY, playerRenderSize, playerRenderSize);
                }
                g2.setColor(Color.WHITE);
                g2.drawString(p.name, screenX - 5, screenY - 5);
            }
        }
    }

    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        int choice = JOptionPane.showOptionDialog(
            null, 
            "Chọn chế độ khởi chạy:", 
            "Test Game", 
            JOptionPane.YES_NO_OPTION, 
            JOptionPane.QUESTION_MESSAGE, 
            null, 
            new String[]{"Test Offline (Không cần Server)", "Online (Kết nối Server)"}, 
            "Test Offline (Không cần Server)"
        );

        if (choice == 0) {
            isOfflineMode = true;
            ClientApp app = new ClientApp();
            app.setVisible(true);
        } else if (choice == 1) {
            isOfflineMode = false;
            String host = JOptionPane.showInputDialog(null, "Nhập IP Server:", "localhost");
            if (host == null || host.isEmpty()) return;

            myName = JOptionPane.showInputDialog(null, "Nhập Tên người chơi:", "Player_" + (int)(Math.random() * 100));
            if (myName == null || myName.isEmpty()) myName = "Player";

            ClientApp app = new ClientApp();
            app.setVisible(true);

            Socket socket = new Socket(host, Common.PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith(Common.CMD_ROOM_LIST)) {
                            String[] items = line.split(";");
                            app.roomListModel.clear();
                            for (int i = 1; i < items.length; i++) {
                                app.roomListModel.addElement(items[i]);
                            }
                        } else if (line.startsWith(Common.CMD_STATE)) {
                            String[] items = line.split(";");
                            renderPlayers.clear();
                            for (int i = 1; i < items.length; i++) {
                                String[] pData = items[i].split(",");
                                if (pData.length >= 4) {
                                    renderPlayers.put(pData[0], new RenderPlayer(pData[1], Integer.parseInt(pData[2]), Integer.parseInt(pData[3])));
                                }
                            }
                            app.repaint();
                        } else if (line.startsWith(Common.CMD_ERROR)) {
                            JOptionPane.showMessageDialog(app, line.split(":")[1]);
                        }
                    }
                } catch (Exception e) {}
            }).start();
        }
    }
}