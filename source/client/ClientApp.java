package source.client;

import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;
import source.common.Enemy;
import source.common.EnemyManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientApp extends JFrame {

    // Lớp chứa dữ liệu đồng bộ để hiển thị Player lên màn hình
    public static class RenderPlayer {
        public String name;
        public volatile int x, y;
        public volatile String direction = "down"; // "up", "down", "left", "right"
        public volatile int spriteNum = 1;        // 1 hoặc 2
        public final boolean enemy;
        public volatile int health = 100;
        public volatile int maxHealth = 100;
        public volatile long flashUntil;

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum) {
            this(name, x, y, direction, spriteNum, false);
        }

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum, boolean enemy) {
            this(name, x, y, direction, spriteNum, enemy, 100);
        }

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum, boolean enemy, int health) {
            this(name, x, y, direction, spriteNum, enemy, health, 100, 0);
        }

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum,
                            boolean enemy, int health, int maxHealth, long flashUntil) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
            this.enemy = enemy;
            this.maxHealth = Math.max(1, maxHealth);
            this.health = Math.max(0, Math.min(this.maxHealth, health));
            this.flashUntil = flashUntil;
        }
    }

    // Quản lý sprite động bằng Map<Direction, Image[]>
    private final Map<String, Image[]> spriteMap = new HashMap<>();

    // Danh sách người chơi hiển thị (Thread-safe)
    private volatile Map<String, RenderPlayer> renderPlayers = new ConcurrentHashMap<>();
    private final Map<String, Long> hitFlashUntil = new ConcurrentHashMap<>();
    private String myName = "Player_" + (int) (Math.random() * 100);

    // Network I/O
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile BufferedReader in;
    private volatile Thread netListenThread;
    private final Object networkLock = new Object();
    private volatile boolean waitingForRoomState;

    // Trạng thái phím điều khiển
    private volatile boolean left = false, right = false, up = false, down = false, attack = false;

    // Map Manager
    private final MapManager mapManager;

    // Chế độ Offline Test (Dedicated Thread + DeltaTime)
    private boolean isOfflineMode = false;
    private Player localPlayer;
    private EnemyManager localEnemyManager = new EnemyManager();
    private Thread localGameThread;
    private volatile boolean isLocalRunning = false;

    // Giao diện và chuyển cảnh (CardLayout)
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(cardLayout);
    private final DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private final JList<String> roomJList = new JList<>(roomListModel);
    private final GamePanel gamePanel;

    public ClientApp() {
        setTitle("Dungeon Co-op Game");
        setSize(Common.SCREEN_WIDTH, Common.SCREEN_HEIGHT);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 1. Nạp bản đồ từ source/content/map
        String mapPath = "source/content/map";
        mapManager = new MapManager();
        mapManager.loadTilesetImage(mapPath + "/tileset.png", Common.ORIGINAL_TILE_SIZE);
        mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        // 2. Nạp assets (Sprite nhân vật với Map<Direction, Image[]>)
        loadAssets();

        // 3. Khởi tạo các Scene
        JPanel menuPanel = createMainMenuUI();
        JPanel lobbyPanel = createLobbyUI();
        gamePanel = new GamePanel();

        mainContainer.add(menuPanel, "MENU");
        mainContainer.add(lobbyPanel, "LOBBY");
        mainContainer.add(gamePanel, "GAME");
        add(mainContainer);

        // Mặc định hiển thị Main Menu
        cardLayout.show(mainContainer, "MENU");

        // 4. Lắng nghe phím bấm — Key Bindings chuẩn Swing để tránh mất focus
        //    Dùng WHEN_IN_FOCUSED_WINDOW: nhận phím khi bất kỳ component nào trong cửa sổ có focus
        setupKeyBindings();

        // Xử lý đóng cửa sổ an toàn
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopLocalGame();
                disconnectNetwork();
            }
        });
    }

    /**
     * Nạp sprites nhân vật vào Map<Direction, Image[]>
     * Rút gọn code từ nhiều biến rời rạc thành vòng lặp tiện lợi.
     */
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
            System.out.println("[ClientApp] Nạp thành công bộ sprite nhân vật vào spriteMap!");
        } catch (Exception e) {
            System.err.println("[ClientApp] Không tìm thấy đủ sprite trong " + path + ": " + e.getMessage());
        }
    }

    /**
     * Tạo màn hình Menu chính (Main Scene) với 3 tùy chọn:
     * - Chơi Offline (Test)
     * - Chơi Online (Sảnh phòng)
     * - Thoát Game
     */
    private JPanel createMainMenuUI() {
        JPanel menuPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                // Nền gradient tối
                GradientPaint gp = new GradientPaint(0, 0, new Color(25, 30, 42), 
                                                     0, getHeight(), new Color(12, 14, 20));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        menuPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 20, 15, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        // Tiêu đề Game
        JLabel lblTitle = new JLabel("DUNGEON CO-OP", SwingConstants.CENTER);
        lblTitle.setFont(new Font("Arial", Font.BOLD, 42));
        lblTitle.setForeground(new Color(245, 190, 75));
        gbc.gridy = 0;
        menuPanel.add(lblTitle, gbc);

        JLabel lblSub = new JLabel("2D Top-Down Multiplayer Exploration", SwingConstants.CENTER);
        lblSub.setFont(new Font("Arial", Font.ITALIC, 16));
        lblSub.setForeground(new Color(180, 185, 200));
        gbc.gridy = 1;
        menuPanel.add(lblSub, gbc);

        // Khoảng cách
        gbc.insets = new Insets(10, 50, 10, 50);

        // 1. Nút Chơi Offline (Test)
        JButton btnOffline = createStyledButton("🎮  CHƠI OFFLINE (LUYỆN TẬP / TEST)");
        gbc.gridy = 2;
        menuPanel.add(btnOffline, gbc);

        // 2. Nút Chơi Online
        JButton btnOnline = createStyledButton("🌐  CHƠI ONLINE (SẢNH PHÒNG)");
        gbc.gridy = 3;
        menuPanel.add(btnOnline, gbc);

        // 3. Nút Thoát Game
        JButton btnExit = createStyledButton("🚪  THOÁT GAME");
        gbc.gridy = 4;
        menuPanel.add(btnExit, gbc);

        // Sự kiện các nút
        btnOffline.addActionListener(e -> startLocalGame());

        btnOnline.addActionListener(e -> {
            String host = JOptionPane.showInputDialog(this, "Nhập IP Server:", "localhost");
            if (host == null || host.trim().isEmpty()) return;

            String nameInput = JOptionPane.showInputDialog(this, "Nhập Tên người chơi:", myName);
            if (nameInput != null && !nameInput.trim().isEmpty()) {
                String candidateName = nameInput.trim();
                if (!isValidProtocolField(candidateName, "Tên người chơi")) return;
                myName = candidateName;
            }

            connectToServer(host.trim());
        });

        btnExit.addActionListener(e -> {
            stopLocalGame();
            disconnectNetwork();
            System.exit(0);
        });

        return menuPanel;
    }

    /**
     * Tạo nút bấm có phong cách hiện đại
     */
    private JButton createStyledButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Arial", Font.BOLD, 17));
        btn.setPreferredSize(new Dimension(380, 50));
        btn.setFocusPainted(false);
        btn.setBackground(new Color(45, 52, 70));
        btn.setForeground(Color.WHITE);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 95, 125), 2),
            BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(new Color(65, 78, 105));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBackground(new Color(45, 52, 70));
            }
        });
        return btn;
    }

    private boolean isValidProtocolField(String value, String fieldName) {
        if (value.indexOf(':') >= 0 || value.indexOf(';') >= 0 || value.indexOf(',') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            JOptionPane.showMessageDialog(this,
                    fieldName + " không được chứa ký tự hai chấm (:), chấm phẩy (;), dấu phẩy (,) hoặc xuống dòng.");
            return false;
        }
        return true;
    }

    private String roomNameFromDisplay(String displayValue) {
        int countStart = displayValue.lastIndexOf(" (");
        return countStart >= 0 ? displayValue.substring(0, countStart) : displayValue;
    }

    /**
     * Giao diện Sảnh chờ (Lobby UI)
     */
    private JPanel createLobbyUI() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.setBackground(new Color(24, 28, 38));

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);

        JButton btnBack = new JButton("⬅ Quay lại Menu");
        btnBack.setFocusPainted(false);
        btnBack.addActionListener(e -> {
            disconnectNetwork();
            setTitle("Dungeon Co-op Game");
            cardLayout.show(mainContainer, "MENU");
        });
        topBar.add(btnBack, BorderLayout.WEST);

        JLabel title = new JLabel("SẢNH CHỜ PHÒNG CHƠI", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        title.setForeground(Color.WHITE);
        topBar.add(title, BorderLayout.CENTER);
        panel.add(topBar, BorderLayout.NORTH);

        roomJList.setBackground(new Color(35, 40, 55));
        roomJList.setForeground(Color.WHITE);
        roomJList.setFont(new Font("Arial", Font.PLAIN, 16));
        panel.add(new JScrollPane(roomJList), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        btnPanel.setOpaque(false);
        JButton btnRefresh = new JButton("Làm mới danh sách");
        JButton btnCreate  = new JButton("Tạo phòng mới");
        JButton btnJoin    = new JButton("Vào phòng đã chọn");

        btnPanel.add(btnRefresh);
        btnPanel.add(btnCreate);
        btnPanel.add(btnJoin);
        panel.add(btnPanel, BorderLayout.SOUTH);

        btnRefresh.addActionListener(e -> sendCommand(Common.CMD_ROOM_LIST));

        btnCreate.addActionListener(e -> {
            String rName = JOptionPane.showInputDialog(this, "Nhập tên phòng:");
            if (rName != null && !rName.trim().isEmpty()) {
                String roomName = rName.trim();
                if (!isValidProtocolField(roomName, "Tên phòng")
                        || !isValidProtocolField(myName, "Tên người chơi")) return;
                waitingForRoomState = true;
                sendCommand(Common.CMD_CREATE_ROOM + ":" + roomName + ":" + myName);
            }
        });

        btnJoin.addActionListener(e -> {
            String selected = roomJList.getSelectedValue();
            if (selected != null) {
                String roomName = roomNameFromDisplay(selected);
                if (!isValidProtocolField(roomName, "Tên phòng")
                        || !isValidProtocolField(myName, "Tên người chơi")) return;
                waitingForRoomState = true;
                sendCommand(Common.CMD_JOIN_ROOM + ":" + roomName + ":" + myName);
            } else {
                JOptionPane.showMessageDialog(this, "Vui lòng chọn 1 phòng trong danh sách!");
            }
        });

        return panel;
    }

    /**
     * Bắt đầu chế độ Local Offline bằng Dedicated Thread và DeltaTime (Fixed Timestep)
     */
    public synchronized void startLocalGame() {
        stopLocalGame();
        disconnectNetwork();

        isOfflineMode = true;
        setTitle("Dungeon Co-op Game [OFFLINE TEST]");

        localPlayer = new Player("LOCAL_1", myName);
        localEnemyManager = new EnemyManager();
        renderPlayers.clear();
        renderPlayers.put("LOCAL_1", new RenderPlayer(
            myName, 
            (int) localPlayer.x, 
            (int) localPlayer.y, 
            localPlayer.direction, 
            localPlayer.spriteNum,
            false,
            localPlayer.health
        ));

        isLocalRunning = true;
        localGameThread = new Thread(() -> {
            final double TARGET_FPS = 60.0;
            final double OPTIMAL_NS = 1_000_000_000.0 / TARGET_FPS;
            long lastTime = System.nanoTime();

            while (isLocalRunning) {
                long now = System.nanoTime();
                long updateLength = now - lastTime;
                lastTime = now;
                float dt = (float) (updateLength / 1_000_000_000.0);

                // Giới hạn max dt tránh lỗi vật lý khi bị lag khung hình
                if (dt > 0.1f) dt = 0.1f;

                // 1. Cập nhật phím bấm vào Player
                localPlayer.left  = left;
                localPlayer.right = right;
                localPlayer.up    = up;
                localPlayer.down  = down;
                localPlayer.attack = attack;

                // 2. Tính toán vật lý và animation bước chân qua PhysicsEngine trong common
                localPlayer.updateLife(System.currentTimeMillis());
                if (localPlayer.isAlive()) PhysicsEngine.updatePlayer(localPlayer, mapManager, dt);
                localEnemyManager.update(java.util.Collections.singletonList(localPlayer), mapManager, dt);
                localEnemyManager.drainDamageEvents();

                // 3. Cập nhật trạng thái hiển thị
                renderPlayers.put("LOCAL_1", new RenderPlayer(
                    myName, 
                    (int) localPlayer.x, 
                    (int) localPlayer.y, 
                    localPlayer.direction, 
                    localPlayer.spriteNum,
                    false,
                    localPlayer.health,
                    Player.MAX_HEALTH,
                    localPlayer.flashUntil
                ));
                for (Enemy enemy : localEnemyManager.getEnemies()) {
                    renderPlayers.put(enemy.id, new RenderPlayer(enemy.name, (int) enemy.x, (int) enemy.y,
                            enemy.direction, 1, true, enemy.health, Enemy.MAX_HEALTH, enemy.flashUntil));
                }

                // 4. Yêu cầu vẽ lại trên Event Dispatch Thread
                gamePanel.repaint();

                // 5. Đồng bộ FPS mục tiêu (~60 FPS)
                long sleepTimeMs = (long) ((lastTime - System.nanoTime() + OPTIMAL_NS) / 1_000_000.0);
                if (sleepTimeMs > 0) {
                    try {
                        Thread.sleep(sleepTimeMs);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                } else {
                    Thread.yield();
                }
            }
        }, "Local-GameLoop-Thread");

        localGameThread.start();
        cardLayout.show(mainContainer, "GAME");
        gamePanel.requestFocusInWindow();
    }

    /**
     * Dừng chế độ Local game loop
     */
    public synchronized void stopLocalGame() {
        isLocalRunning = false;
        if (localGameThread != null) {
            localGameThread.interrupt();
            try {
                localGameThread.join(250);
            } catch (InterruptedException ignored) {}
            localGameThread = null;
        }
    }

    /**
     * Kết nối mạng tới Server Online
     */
    private void connectToServer(String host) {
        stopLocalGame();
        disconnectNetwork();

        isOfflineMode = false;
        setTitle("Dungeon Co-op Game [ONLINE - " + host + "]");
        renderPlayers = new ConcurrentHashMap<>();

        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, Common.PORT), 5000);
            PrintWriter newOut = new PrintWriter(newSocket.getOutputStream(), true);
            BufferedReader newIn = new BufferedReader(new InputStreamReader(newSocket.getInputStream()));

            synchronized (networkLock) {
                socket = newSocket;
                out = newOut;
                in = newIn;
            }

            Thread listener = new Thread(() -> listenForServerMessages(newSocket, newIn),
                    "Client-Net-Listen-Thread");
            synchronized (networkLock) {
                netListenThread = listener;
            }
            listener.start();
            sendCommand(Common.CMD_ROOM_LIST);
            cardLayout.show(mainContainer, "LOBBY");

        } catch (IOException ex) {
            closeNetworkResources(newSocket, null, null);
            JOptionPane.showMessageDialog(this, "Không thể kết nối tới Server: " + ex.getMessage());
            cardLayout.show(mainContainer, "MENU");
        }
    }

    private void listenForServerMessages(Socket sessionSocket, BufferedReader sessionIn) {
        try {
            String line;
            while ((line = sessionIn.readLine()) != null) {
                handleServerMessage(line, sessionSocket);
            }
        } catch (IOException ex) {
            if (!sessionSocket.isClosed()) {
                System.err.println("[Client] Kết nối server bị ngắt: " + ex.getMessage());
            }
        } finally {
            boolean connectionWasCurrent;
            synchronized (networkLock) {
                connectionWasCurrent = socket == sessionSocket;
            }
            disconnectNetwork(sessionSocket);
            if (connectionWasCurrent) {
                SwingUtilities.invokeLater(() -> {
                    if (!isDisplayable()) return;
                    cardLayout.show(mainContainer, "MENU");
                    setTitle("Dungeon Co-op Game");
                    JOptionPane.showMessageDialog(this, "Mất kết nối tới server.");
                });
            }
        }
    }

    private void handleServerMessage(String line, Socket sessionSocket) {
        if (line.equals(Common.CMD_ROOM_LIST) || line.startsWith(Common.CMD_ROOM_LIST + ";")) {
            String[] items = line.split(";", -1);
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                for (int i = 1; i < items.length; i++) {
                    if (!items[i].isEmpty()) roomListModel.addElement(items[i]);
                }
            });
            return;
        }

        if (line.equals(Common.CMD_STATE) || line.startsWith(Common.CMD_STATE + ";")) {
            Map<String, RenderPlayer> updatedPlayers = new ConcurrentHashMap<>();
            String[] items = line.split(";", -1);
            for (int i = 1; i < items.length; i++) {
                String[] playerData = items[i].split(",", -1);
                if (playerData.length < 6) continue;
                try {
                    int health = playerData.length >= 7 ? Integer.parseInt(playerData[6]) : 100;
                    int maxHealth = playerData.length >= 8 ? Integer.parseInt(playerData[7]) : 100;
                    updatedPlayers.put(playerData[0], new RenderPlayer(
                            playerData[1], Integer.parseInt(playerData[2]),
                            Integer.parseInt(playerData[3]), playerData[4],
                            Integer.parseInt(playerData[5]), playerData[0].startsWith("E_"),
                            health, maxHealth,
                            hitFlashUntil.getOrDefault(playerData[0], 0L)));
                } catch (NumberFormatException ignored) {
                    System.err.println("[Client] Bỏ qua state không hợp lệ: " + items[i]);
                }
            }
            renderPlayers = updatedPlayers;
            if (waitingForRoomState) {
                waitingForRoomState = false;
                SwingUtilities.invokeLater(() -> {
                    if (socket == sessionSocket) {
                        cardLayout.show(mainContainer, "GAME");
                        gamePanel.requestFocusInWindow();
                    }
                });
            }
            gamePanel.repaint();
            return;
        }

        if (line.startsWith(Common.CMD_PLAYER_DAMAGE + ":")
                || line.startsWith(Common.CMD_ENEMY_DAMAGE + ":")) {
            String[] damage = line.split(":", 4);
            if (damage.length >= 4) {
                try {
                    String targetId = damage[1];
                    int currentHealth = Integer.parseInt(damage[3]);
                    long flashUntil = System.currentTimeMillis() + 150;
                    hitFlashUntil.put(targetId, flashUntil);
                    RenderPlayer target = renderPlayers.get(targetId);
                    if (target != null) {
                        target.health = Math.max(0, Math.min(target.maxHealth, currentHealth));
                        target.flashUntil = flashUntil;
                    }
                    gamePanel.repaint();
                } catch (NumberFormatException ignored) {
                    System.err.println("[Client] Bỏ qua damage event không hợp lệ: " + line);
                }
            }
            return;
        }

        if (line.startsWith(Common.CMD_ERROR + ":")) {
            String[] error = line.split(":", 2);
            String errorMessage = error.length > 1 ? error[1] : "Lỗi từ server.";
            boolean wasWaitingForRoom = waitingForRoomState;
            waitingForRoomState = false;
            SwingUtilities.invokeLater(() -> {
                if (wasWaitingForRoom && socket == sessionSocket) {
                    cardLayout.show(mainContainer, "LOBBY");
                }
                JOptionPane.showMessageDialog(this, errorMessage);
            });
        }
    }

    private void sendCommand(String command) {
        PrintWriter writer = out;
        if (writer != null) writer.println(command);
    }

    private void disconnectNetwork() {
        disconnectNetwork(null);
    }

    private void disconnectNetwork(Socket expectedSocket) {
        Socket socketToClose;
        PrintWriter writerToClose = null;
        BufferedReader readerToClose = null;
        synchronized (networkLock) {
            if (expectedSocket != null && socket != expectedSocket) {
                socketToClose = expectedSocket;
            } else {
                socketToClose = socket;
                writerToClose = out;
                readerToClose = in;
                socket = null;
                out = null;
                in = null;
                netListenThread = null;
                waitingForRoomState = false;
                left = right = up = down = attack = false;
            }
        }
        closeNetworkResources(socketToClose, writerToClose, readerToClose);
    }

    private void closeNetworkResources(Socket socketToClose, PrintWriter writerToClose,
                                       BufferedReader readerToClose) {
        if (socketToClose != null) {
            try {
                socketToClose.close();
            } catch (IOException ignored) { }
        }
        if (writerToClose != null) writerToClose.close();
        if (readerToClose != null) {
            try {
                readerToClose.close();
            } catch (IOException ignored) { }
        }
    }

    private void handleEscapeKey() {
        int opt = JOptionPane.showConfirmDialog(
            this, 
            "Bạn có muốn thoát về Menu chính không?", 
            "Xác nhận thoát", 
            JOptionPane.YES_NO_OPTION
        );
        if (opt == JOptionPane.YES_OPTION) {
            stopLocalGame();
            disconnectNetwork();
            setTitle("Dungeon Co-op Game");
            cardLayout.show(mainContainer, "MENU");
        }
    }

    /**
     * Đăng ký Key Bindings dùng WHEN_IN_FOCUSED_WINDOW trên gamePanel.
     * Khác với KeyListener, Key Bindings hoạt động đúng bất kể focus nằm ở component nào trong cửa sổ.
     */
    private void setupKeyBindings() {
        int[][] keys = {
            {KeyEvent.VK_W,     KeyEvent.VK_UP},
            {KeyEvent.VK_S,     KeyEvent.VK_DOWN},
            {KeyEvent.VK_A,     KeyEvent.VK_LEFT},
            {KeyEvent.VK_D,     KeyEvent.VK_RIGHT},
            {KeyEvent.VK_ESCAPE, -1},
            {KeyEvent.VK_J, -1}
        };
        String[] names = {"up", "down", "left", "right", "esc", "attack"};
        int condition = JComponent.WHEN_IN_FOCUSED_WINDOW;

        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            int primaryKey = keys[i][0];
            int altKey     = keys[i][1];

            // --- PRESSED ---
            gamePanel.getInputMap(condition).put(KeyStroke.getKeyStroke(primaryKey, 0, false), name + "_pressed");
            if (altKey != -1)
                gamePanel.getInputMap(condition).put(KeyStroke.getKeyStroke(altKey, 0, false), name + "_pressed");

            gamePanel.getActionMap().put(name + "_pressed", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if ("esc".equals(name)) {
                        handleEscapeKey();
                    } else {
                        updateKey(name, true);
                    }
                }
            });

            // --- RELEASED ---
            if (!"esc".equals(name)) {
                gamePanel.getInputMap(condition).put(KeyStroke.getKeyStroke(primaryKey, 0, true), name + "_released");
                if (altKey != -1)
                    gamePanel.getInputMap(condition).put(KeyStroke.getKeyStroke(altKey, 0, true), name + "_released");

                gamePanel.getActionMap().put(name + "_released", new javax.swing.AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        updateKey(name, false);
                    }
                });
            }
        }
    }

    /** Phiên bản updateKey nhận tên hướng thay vì keyCode (dùng cho Key Bindings) */
    private void updateKey(String dir, boolean isPressed) {
        switch (dir) {
            case "left":  left  = isPressed; break;
            case "right": right = isPressed; break;
            case "up":    up    = isPressed; break;
            case "down":  down  = isPressed; break;
            case "attack": attack = isPressed; break;
        }
        if (!isOfflineMode) sendInputState();
    }

    private void updateKey(int keyCode, boolean isPressed) {
        if (keyCode == KeyEvent.VK_A || keyCode == KeyEvent.VK_LEFT)  left  = isPressed;
        if (keyCode == KeyEvent.VK_D || keyCode == KeyEvent.VK_RIGHT) right = isPressed;
        if (keyCode == KeyEvent.VK_W || keyCode == KeyEvent.VK_UP)    up    = isPressed;
        if (keyCode == KeyEvent.VK_S || keyCode == KeyEvent.VK_DOWN)  down  = isPressed;
        if (keyCode == KeyEvent.VK_J) attack = isPressed;

        if (!isOfflineMode && out != null) {
            // Gửi dữ liệu điều khiển lên Server khi ở chế độ Online
            sendInputState();
        }
    }

    private void sendInputState() {
        sendCommand(Common.CMD_INPUT + ":" + left + ":" + right + ":" + up + ":" + down + ":" + attack);
    }

    private class GamePanel extends JPanel {
        public GamePanel() {
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            Map<String, RenderPlayer> snapshot = renderPlayers;

            int myX = (int) Common.SPAWN_X;
            int myY = (int) Common.SPAWN_Y;
            int myHealth = Player.MAX_HEALTH;
            int myMaxHealth = Player.MAX_HEALTH;

            for (RenderPlayer p : snapshot.values()) {
                if (p.name.equals(myName)) {
                    myX = p.x;
                    myY = p.y;
                    myHealth = p.health;
                    myMaxHealth = p.maxHealth;
                    break;
                }
            }

            // 1. Vẽ Map và tự động khóa biên Camera
            mapManager.draw(g2, myX, myY, getWidth(), getHeight());

            // 2. Tính vị trí Camera thực tế để vẽ Player
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

            int playerRenderSize = (int) (16 * Common.SCALE); // Kích thước hiển thị nhân vật

            for (RenderPlayer p : snapshot.values()) {
                int screenX = p.x - camX;
                int screenY = p.y - camY;

                // Lấy ảnh sprite từ Map<Direction, Image[]>
                Image[] frames = spriteMap.get(p.direction);
                Image currentSprite = null;
                if (!p.enemy && frames != null && frames.length > 0) {
                    int frameIdx = (p.spriteNum == 2 && frames.length > 1) ? 1 : 0;
                    currentSprite = frames[frameIdx];
                }

                if (currentSprite != null) {
                    g2.drawImage(currentSprite, screenX, screenY, playerRenderSize, playerRenderSize, null);
                } else if (p.enemy) {
                    EnemyRenderer.draw(g2, p.name, screenX, screenY, playerRenderSize,
                            p.health, p.maxHealth, p.flashUntil);
                } else {
                    g2.setColor(p.name.equals(myName) ? Color.CYAN : Color.GREEN);
                    g2.fillRect(screenX, screenY, playerRenderSize, playerRenderSize);
                }

                if (!p.enemy && System.currentTimeMillis() < p.flashUntil) {
                    g2.setColor(new Color(255, 25, 25, 145));
                    g2.fillRect(screenX, screenY, playerRenderSize, playerRenderSize);
                }

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.PLAIN, 12));
                g2.drawString(p.name, screenX - 5, screenY - 5);
            }

            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(16, 16, 236, 58, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString("HP: " + myHealth + " / " + myMaxHealth, 26, 34);
            g2.setColor(Color.DARK_GRAY);
            g2.fillRoundRect(26, 42, 216, 12, 8, 8);
            g2.setColor(new Color(225, 65, 55));
            g2.fillRoundRect(26, 42, Math.max(0, 216 * myHealth / myMaxHealth), 12, 8, 8);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ClientApp app = new ClientApp();
            app.setVisible(true);

            // Hỗ trợ tham số dòng lệnh cho developer test nhanh: --offline
            if (args != null && args.length > 0 && args[0].equalsIgnoreCase("--offline")) {
                app.startLocalGame();
            }
        });
    }
}
