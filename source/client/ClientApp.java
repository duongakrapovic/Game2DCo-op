package source.client;

import source.common.Common;
import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;
import source.common.Protocol;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientApp extends JFrame {

    // Lớp chứa dữ liệu đồng bộ để hiển thị Player lên màn hình
    public static class RenderPlayer {
        public String id;   // id do server cấp (null ở chế độ Offline)
        public String name;
        public int x, y;
        public String direction = "down"; // "up", "down", "left", "right"
        public int spriteNum = 1;        // 1 hoặc 2

        public RenderPlayer(String name, int x, int y, String direction, int spriteNum) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
        }
    }

    // Quản lý sprite động bằng Map<Direction, Image[]>
    private final Map<String, Image[]> spriteMap = new HashMap<>();

    // Danh sách người chơi hiển thị (Thread-safe)
    private final Map<String, RenderPlayer> renderPlayers = new ConcurrentHashMap<>();
    private String myName = "Player_" + (int) (Math.random() * 100);

    // Network (xem NetClient / ClientPrediction / SnapshotInterpolator)
    private volatile NetClient net;
    private volatile String myId = "";
    private final ClientPrediction prediction;
    private final SnapshotInterpolator interp = new SnapshotInterpolator();
    private final Map<String, String> playerNames = new ConcurrentHashMap<>();
    private Thread onlineTickThread;
    private volatile boolean onlineTickRunning = false;
    private volatile String netBanner = null;          // thông báo khi mất kết nối
    // Công tắc để demo/đo: F3 HUD, F6 dự đoán, F7 nội suy, F8 độ trễ nội suy
    private volatile boolean showNetHud = true;
    private volatile boolean usePrediction = true;
    private volatile boolean useInterpolation = true;
    private volatile int interpDelayMs = 100;

    // Trạng thái phím điều khiển (volatile: ghi từ luồng giao diện, đọc từ luồng game/mạng)
    private volatile boolean left = false, right = false, up = false, down = false;

    // Map Manager
    private final MapManager mapManager;

    // Chế độ Offline Test (Dedicated Thread + DeltaTime)
    private boolean isOfflineMode = false;
    private Player localPlayer;
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
        prediction = new ClientPrediction(mapManager);

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
        setupDebugKeys();

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
                myName = Protocol.sanitizeName(nameInput, myName);
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

        btnRefresh.addActionListener(e -> netSend(Common.CMD_ROOM_LIST));

        btnCreate.addActionListener(e -> {
            String rName = JOptionPane.showInputDialog(this, "Nhập tên phòng:");
            if (rName != null && !Protocol.sanitizeRoom(rName).isEmpty()) {
                // Chỉ chuyển sang màn game khi server trả JOINED (xem handleServerMessage)
                netSend(Common.CMD_CREATE_ROOM + ":" + Protocol.sanitizeRoom(rName) + ":" + myName);
            }
        });

        btnJoin.addActionListener(e -> {
            String selected = roomJList.getSelectedValue();
            if (selected != null) {
                String rName = selected.split(" ")[0];
                netSend(Common.CMD_JOIN_ROOM + ":" + rName + ":" + myName);
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
        renderPlayers.clear();
        renderPlayers.put("LOCAL_1", new RenderPlayer(
            myName, 
            (int) localPlayer.x, 
            (int) localPlayer.y, 
            localPlayer.direction, 
            localPlayer.spriteNum
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

                // 2. Tính toán vật lý và animation bước chân qua PhysicsEngine trong common
                PhysicsEngine.updatePlayer(localPlayer, mapManager, dt);

                // 3. Cập nhật trạng thái hiển thị
                renderPlayers.put("LOCAL_1", new RenderPlayer(
                    myName, 
                    (int) localPlayer.x, 
                    (int) localPlayer.y, 
                    localPlayer.direction, 
                    localPlayer.spriteNum
                ));

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
     * Kết nối mạng tới Server Online (chạy ở luồng riêng để không treo giao diện khi IP sai).
     */
    private void connectToServer(String host) {
        stopLocalGame();
        disconnectNetwork();

        isOfflineMode = false;
        setTitle("Dungeon Co-op Game [ONLINE - " + host + "]");

        final NetClient nc = new NetClient(host, Common.PORT, new NetClient.Listener() {
            @Override public void onMessage(String line) { handleServerMessage(line); }

            @Override public void onStateChanged(NetClient.State s, String info) {
                netBanner = (s == NetClient.State.RECONNECTING)
                        ? "Mất kết nối - đang kết nối lại (" + info + ")..." : null;
                gamePanel.repaint();
            }

            @Override public void onSessionLost(String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (net == null) return; // người dùng đã tự thoát trước đó
                    disconnectNetwork();
                    JOptionPane.showMessageDialog(ClientApp.this, reason);
                    setTitle("Dungeon Co-op Game");
                    cardLayout.show(mainContainer, "MENU");
                });
            }
        });
        net = nc;

        new Thread(() -> {
            try {
                nc.connect();
                SwingUtilities.invokeLater(() -> {
                    if (net == nc) cardLayout.show(mainContainer, "LOBBY");
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (net == nc) net = null;
                    JOptionPane.showMessageDialog(ClientApp.this, "Không thể kết nối tới Server: " + ex.getMessage());
                    cardLayout.show(mainContainer, "MENU");
                });
            }
        }, "Net-Connect").start();
    }

    private void netSend(String msg) {
        NetClient nc = net;
        if (nc != null) nc.send(msg);
    }

    private void disconnectNetwork() {
        stopOnlineTick();
        NetClient nc = net;
        net = null;
        if (nc != null) nc.close();
        renderPlayers.clear();
        playerNames.clear();
        interp.clear();
        netBanner = null;
    }

    /** Xử lý một dòng từ server (chạy trên luồng mạng). */
    private void handleServerMessage(String line) {
        if (line.startsWith(Common.CMD_ROOM_LIST)) {
            String[] items = line.split(";");
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                for (int i = 1; i < items.length; i++) roomListModel.addElement(items[i]);
            });
        } else if (line.startsWith(Common.CMD_STATE)) {
            handleState(line);
        } else if (line.startsWith(Common.CMD_WELCOME + ":")) {
            String[] p = line.split(":");
            if (p.length >= 2) myId = p[1];
        } else if (line.startsWith(Common.CMD_JOINED + ":")) {
            // Reset trạng thái đồng bộ rồi mới vào màn hình game
            prediction.resync();
            interp.clear();
            playerNames.clear();
            renderPlayers.clear();
            startOnlineTick();
            SwingUtilities.invokeLater(() -> {
                cardLayout.show(mainContainer, "GAME");
                gamePanel.requestFocusInWindow();
            });
        } else if (line.startsWith(Common.CMD_RESUMED + ":")) {
            prediction.resync(); // kết nối lại xong: lấy lại vị trí theo server
        } else if (line.startsWith(Common.CMD_ERROR)) {
            int i = line.indexOf(':');
            String errorMsg = i >= 0 ? line.substring(i + 1) : "Lỗi";
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, errorMsg));
        }
    }

    /** STATE;id,tên,x,y,hướng,frame,ack;... */
    private void handleState(String line) {
        long now = System.nanoTime();
        String[] items = line.split(";");
        Set<String> present = new HashSet<>();
        for (int i = 1; i < items.length; i++) {
            String[] f = items[i].split(",");
            if (f.length < 6) continue;
            try {
                String id = f[0];
                float x = Float.parseFloat(f[2]);
                float y = Float.parseFloat(f[3]);
                int sprite = Integer.parseInt(f[5]);
                present.add(id);
                playerNames.put(id, f[1]);
                interp.add(id, now, x, y, f[4], sprite);
                if (id.equals(myId) && f.length >= 7) {
                    prediction.onServerState(x, y, Integer.parseInt(f[6]));
                }
            } catch (NumberFormatException ignored) {}
        }
        interp.retainOnly(present);
        playerNames.keySet().retainAll(present);
    }

    // ------------------------------------------------------------------ vòng lặp online (60 Hz)

    /**
     * Mỗi tick: (1) chạy dự đoán + gửi INPUT kèm seq, (2) dựng danh sách người chơi để vẽ
     * (mình = vị trí dự đoán, người khác = nội suy), (3) vẽ lại.
     */
    private synchronized void startOnlineTick() {
        stopOnlineTick();
        onlineTickRunning = true;
        onlineTickThread = new Thread(() -> {
            long next = System.nanoTime();
            while (onlineTickRunning) {
                int n = 0;
                while (System.nanoTime() >= next && n < 5) {
                    onlineStep();
                    next += Common.TICK_NS;
                    n++;
                }
                if (n == 5) next = System.nanoTime();
                renderOnline();
                gamePanel.repaint();
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "Online-Tick-Thread");
        onlineTickThread.setDaemon(true);
        onlineTickThread.start();
    }

    private synchronized void stopOnlineTick() {
        onlineTickRunning = false;
        if (onlineTickThread != null) {
            onlineTickThread.interrupt();
            onlineTickThread = null;
        }
    }

    private void onlineStep() {
        NetClient nc = net;
        if (nc == null || nc.getState() != NetClient.State.CONNECTED) return; // đang rớt mạng: tạm dừng
        ClientPrediction.Input in = prediction.step(left, right, up, down);
        nc.send(Common.CMD_INPUT + ":" + in.left + ":" + in.right + ":" + in.up + ":" + in.down + ":" + in.seq);
    }

    private void renderOnline() {
        long renderTime = System.nanoTime() - interpDelayMs * 1_000_000L;
        for (Map.Entry<String, String> e : playerNames.entrySet()) {
            String id = e.getKey();
            float x, y;
            String dir;
            int sprite;
            if (id.equals(myId) && usePrediction) {
                x = prediction.x(); y = prediction.y();
                dir = prediction.direction(); sprite = prediction.spriteNum();
            } else {
                SnapshotInterpolator.Snap s = useInterpolation ? interp.sample(id, renderTime) : interp.latest(id);
                if (s == null) continue;
                x = s.x; y = s.y; dir = s.dir; sprite = s.sprite;
            }
            RenderPlayer rp = new RenderPlayer(e.getValue(), Math.round(x), Math.round(y), dir, sprite);
            rp.id = id;
            renderPlayers.put(id, rp);
        }
        renderPlayers.keySet().retainAll(playerNames.keySet());
    }

    private boolean isSelf(RenderPlayer p) {
        return p.id != null ? p.id.equals(myId) : p.name.equals(myName);
    }

    // ------------------------------------------------------------------ phím debug mạng

    private void setupDebugKeys() {
        bindDebugKey(KeyEvent.VK_F3, () -> showNetHud = !showNetHud);
        bindDebugKey(KeyEvent.VK_F4, () -> { // vòng lặp độ trễ giả lập mỗi chiều
            NetClient nc = net;
            if (nc == null) return;
            int[] steps = {0, 50, 100, 200, 400};
            int idx = 0;
            for (int i = 0; i < steps.length; i++) if (steps[i] == nc.getLagMs()) idx = i;
            nc.setLagMs(steps[(idx + 1) % steps.length]);
        });
        bindDebugKey(KeyEvent.VK_F6, () -> usePrediction = !usePrediction);
        bindDebugKey(KeyEvent.VK_F7, () -> useInterpolation = !useInterpolation);
        bindDebugKey(KeyEvent.VK_F8, () -> {
            int[] steps = {50, 100, 150, 250};
            int idx = 0;
            for (int i = 0; i < steps.length; i++) if (steps[i] == interpDelayMs) idx = i;
            interpDelayMs = steps[(idx + 1) % steps.length];
        });
        bindDebugKey(KeyEvent.VK_F9, () -> { // giả lập rớt mạng để demo tự kết nối lại
            NetClient nc = net;
            if (nc != null) nc.simulateConnectionDrop();
        });
    }

    private void bindDebugKey(int keyCode, Runnable action) {
        String name = "dbg_" + keyCode;
        gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0, false), name);
        gamePanel.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                action.run();
                gamePanel.repaint();
            }
        });
    }

    /** HUD mạng (F3) + biểu ngữ khi mất kết nối. Chỉ vẽ ở chế độ Online. */
    private void drawNetHud(Graphics2D g2, int w, int h) {
        NetClient nc = net;
        if (isOfflineMode || nc == null) return;

        if (showNetHud) {
            double rtt = nc.getRttMs();
            String[] lines = {
                String.format("PING %s ms (jitter %.1f)   STATE %.0f/s   down %.1f KB/s   up %.1f KB/s",
                        rtt < 0 ? "--" : String.format("%.0f", rtt), nc.getJitterMs(),
                        nc.getStatePerSec(), nc.getKbpsIn(), nc.getKbpsOut()),
                "Dự đoán [F6]: " + (usePrediction ? "BẬT" : "TẮT")
                        + "   Nội suy [F7]: " + (useInterpolation ? "BẬT" : "TẮT") + " (" + interpDelayMs + " ms, F8)"
                        + "   Lag giả lập [F4]: " + nc.getLagMs() + " ms/chiều",
                String.format("Sai số reconcile: %.2f px (max %.2f)   pending: %d   hiệu chỉnh: %d   kết nối lại: %d   [F9] rớt mạng   [F3] ẩn HUD",
                        prediction.lastError(), prediction.maxError(), prediction.pendingCount(),
                        prediction.correctionCount(), nc.getReconnectCount())
            };
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            int bw = 0;
            for (String l : lines) bw = Math.max(bw, g2.getFontMetrics().stringWidth(l));
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRect(6, 6, bw + 14, lines.length * 16 + 8);
            g2.setColor(new Color(120, 255, 140));
            for (int i = 0; i < lines.length; i++) g2.drawString(lines[i], 13, 22 + i * 16);
        }

        String banner = netBanner;
        if (banner != null) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRect(0, 0, w, h);
            g2.setFont(new Font("Arial", Font.BOLD, 26));
            g2.setColor(new Color(255, 200, 80));
            int tw = g2.getFontMetrics().stringWidth(banner);
            g2.drawString(banner, (w - tw) / 2, h / 2);
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
            {KeyEvent.VK_ESCAPE, -1}
        };
        String[] names = {"up", "down", "left", "right", "esc"};
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
        }
        // INPUT được gửi theo tick cố định (onlineStep), không gửi theo sự kiện phím nữa.
    }

    private void updateKey(int keyCode, boolean isPressed) {
        if (keyCode == KeyEvent.VK_A || keyCode == KeyEvent.VK_LEFT)  left  = isPressed;
        if (keyCode == KeyEvent.VK_D || keyCode == KeyEvent.VK_RIGHT) right = isPressed;
        if (keyCode == KeyEvent.VK_W || keyCode == KeyEvent.VK_UP)    up    = isPressed;
        if (keyCode == KeyEvent.VK_S || keyCode == KeyEvent.VK_DOWN)  down  = isPressed;
    }

    private class GamePanel extends JPanel {
        public GamePanel() {
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            int myX = (int) Common.SPAWN_X;
            int myY = (int) Common.SPAWN_Y;

            for (RenderPlayer p : renderPlayers.values()) {
                if (isSelf(p)) {
                    myX = p.x;
                    myY = p.y;
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

            for (RenderPlayer p : renderPlayers.values()) {
                int screenX = p.x - camX;
                int screenY = p.y - camY;

                // Lấy ảnh sprite từ Map<Direction, Image[]>
                Image[] frames = spriteMap.get(p.direction);
                Image currentSprite = null;
                if (frames != null && frames.length > 0) {
                    int frameIdx = (p.spriteNum == 2 && frames.length > 1) ? 1 : 0;
                    currentSprite = frames[frameIdx];
                }

                if (currentSprite != null) {
                    g2.drawImage(currentSprite, screenX, screenY, playerRenderSize, playerRenderSize, null);
                } else {
                    g2.setColor(isSelf(p) ? Color.CYAN : Color.GREEN);
                    g2.fillRect(screenX, screenY, playerRenderSize, playerRenderSize);
                }

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.PLAIN, 12));
                g2.drawString(p.name, screenX - 5, screenY - 5);
            }

            drawNetHud(g2, getWidth(), getHeight());
        }
    }

    public static void main(String[] args) {
        // Tham số dev: --lag=<ms> giả lập độ trễ mỗi chiều (để demo prediction/interpolation)
        if (args != null) {
            for (String a : args) {
                if (a.startsWith("--lag=")) System.setProperty("net.lag", a.substring(6));
            }
        }
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