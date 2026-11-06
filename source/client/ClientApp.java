package source.client;

import source.common.Common;
import source.common.MapManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;

public class ClientApp extends JFrame implements ClientNetwork.NetworkListener {

    private String myName = "Player_" + (int)(Math.random() * 100);
    private final MapManager mapManager;
    private final ClientNetwork network;
    private final GamePanel gamePanel;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(cardLayout);
    
    private final DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private final JList<String> roomJList = new JList<>(roomListModel);
    private JTextField txtCreateRoomName;
    private JTextField txtPlayerNameLobby;
    private JTextField txtServerIp;

    public ClientApp() {
        setTitle("Dungeon Co-op Game");
        setSize(Common.SCREEN_WIDTH, Common.SCREEN_HEIGHT);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 1. Nạp Map
        String mapPath = "source/content/map";
        mapManager = new MapManager();
        mapManager.loadTilesetImage(mapPath + "/tileset.png", Common.ORIGINAL_TILE_SIZE);
        mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        // 2. Khởi tạo Module
        network = new ClientNetwork(this);
        gamePanel = new GamePanel(mapManager);

        // 3. UI Scenes
        JPanel menuPanel = createMainMenuUI();
        JPanel lobbyPanel = createLobbyUI();

        mainContainer.add(menuPanel, "MENU");
        mainContainer.add(lobbyPanel, "LOBBY");
        mainContainer.add(gamePanel, "GAME");
        add(mainContainer);

        cardLayout.show(mainContainer, "MENU");
        setupKeyBindings();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gamePanel.stopOfflineMode();
                network.disconnect();
            }
        });
    }

    /**
     * HÀM TỰ ĐỘNG KHỞI CHẠY FILE RUN-SERVER.BAT NGẦM TRÊN HỆ THỐNG
     */
    private void launchServerBatch() {
        try {
            File batFile = new File("run-server.bat");
            if (batFile.exists()) {
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", "Dungeon Server", "run-server.bat");
                pb.directory(batFile.getParentFile());
                pb.start();
                System.out.println("[ClientApp] Đã tự động kích hoạt file run-server.bat thành công!");
            } else {
                System.err.println("[ClientApp] Không tìm thấy file run-server.bat ở thư mục gốc!");
            }
        } catch (IOException e) {
            System.err.println("[ClientApp] Lỗi khi chạy run-server.bat: " + e.getMessage());
        }
    }

    private JPanel createMainMenuUI() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(25, 30, 42));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 20, 15, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JLabel title = new JLabel("DUNGEON CO-OP", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 42));
        title.setForeground(new Color(245, 190, 75));
        gbc.gridy = 0; panel.add(title, gbc);

        JButton btnOffline = createStyledButton("CHOI OFFLINE (KHONG CAN SERVER)");
        gbc.gridy = 1; panel.add(btnOffline, gbc);

        JButton btnOnline = createStyledButton("CHOI ONLINE (SANH PHONG)");
        gbc.gridy = 2; panel.add(btnOnline, gbc);

        JButton btnExit = createStyledButton("THOAT GAME");
        gbc.gridy = 3; panel.add(btnExit, gbc);

        btnOffline.addActionListener(e -> {
            gamePanel.stopOfflineMode();
            network.disconnect();
            setTitle("Dungeon Co-op [OFFLINE]");
            gamePanel.startOfflineMode(myName);
            cardLayout.show(mainContainer, "GAME");
            gamePanel.requestFocusInWindow();
        });

        btnOnline.addActionListener(e -> {
            cardLayout.show(mainContainer, "LOBBY");
        });

        btnExit.addActionListener(e -> System.exit(0));
        return panel;
    }

    private JButton createStyledButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Arial", Font.BOLD, 16));
        btn.setPreferredSize(new Dimension(350, 45));
        btn.setBackground(new Color(45, 52, 70));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    private JPanel createLobbyUI() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.setBackground(new Color(24, 28, 38));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        topBar.setOpaque(false);
        JButton btnBack = new JButton("Quay lại Menu");
        btnBack.addActionListener(e -> {
            network.disconnect();
            cardLayout.show(mainContainer, "MENU");
        });
        topBar.add(btnBack);

        topBar.add(new JLabel("<html><font color='white'>IP Server:</font></html>"));
        txtServerIp = new JTextField("localhost", 12);
        topBar.add(txtServerIp);

        JButton btnConnect = new JButton("Kết nối Server");
        btnConnect.addActionListener(e -> {
            String ip = txtServerIp.getText().trim();
            if (network.connect(ip)) {
                network.sendTcp(Common.CMD_ROOM_LIST);
                JOptionPane.showMessageDialog(this, "Kết nối Server thành công!");
            } else {
                JOptionPane.showMessageDialog(this, "Không thể kết nối Server! (Bạn có thể bấm Tạo phòng để tự chạy Server)", "Thông báo", JOptionPane.WARNING_MESSAGE);
            }
        });
        topBar.add(btnConnect);
        panel.add(topBar, BorderLayout.NORTH);

        JPanel centerSplit = new JPanel(new GridLayout(1, 2, 20, 0));
        centerSplit.setOpaque(false);

        // CỘT TRÁI: DANH SÁCH PHÒNG
        JPanel leftCol = new JPanel(new BorderLayout(5, 5));
        leftCol.setOpaque(false);
        JLabel lblLeftTitle = new JLabel("<html><font color='white'><b>DANH SÁCH PHÒNG ĐANG HOẠT ĐỘNG</b></font></html>");
        leftCol.add(lblLeftTitle, BorderLayout.NORTH);

        roomJList.setBackground(new Color(35, 40, 55));
        roomJList.setForeground(Color.WHITE);
        roomJList.setFont(new Font("Arial", Font.PLAIN, 14));
        leftCol.add(new JScrollPane(roomJList), BorderLayout.CENTER);

        JButton btnJoin = new JButton("Vào phòng đã chọn");
        btnJoin.setFont(new Font("Arial", Font.BOLD, 14));
        btnJoin.addActionListener(e -> joinSelectedRoom());
        leftCol.add(btnJoin, BorderLayout.SOUTH);

        centerSplit.add(leftCol);

        // CỘT PHẢI: TẠO PHÒNG
        JPanel rightCol = new JPanel(new GridBagLayout());
        rightCol.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JLabel lblRightTitle = new JLabel("<html><font color='white'><b>TẠO PHÒNG MỚI (TỰ KHỞI CHẠY SERVER)</b></font></html>");
        gbc.gridy = 0; rightCol.add(lblRightTitle, gbc);

        JLabel lbl1 = new JLabel("<html><font color='white'>Tên người chơi:</font></html>");
        gbc.gridy = 1; rightCol.add(lbl1, gbc);
        txtPlayerNameLobby = new JTextField(myName);
        gbc.gridy = 2; rightCol.add(txtPlayerNameLobby, gbc);

        JLabel lbl2 = new JLabel("<html><font color='white'>Tên phòng muốn tạo:</font></html>");
        gbc.gridy = 3; rightCol.add(lbl2, gbc);
        txtCreateRoomName = new JTextField("Room_1");
        gbc.gridy = 4; rightCol.add(txtCreateRoomName, gbc);

        JButton btnCreate = new JButton("Tạo phòng & Chạy Server (Enter)");
        btnCreate.setFont(new Font("Arial", Font.BOLD, 14));
        btnCreate.setBackground(new Color(60, 140, 60));
        btnCreate.setForeground(Color.WHITE);
        gbc.gridy = 5; rightCol.add(btnCreate, gbc);

        ActionListener createAction = e -> createNewRoom();
        btnCreate.addActionListener(createAction);
        txtCreateRoomName.addActionListener(createAction);
        txtPlayerNameLobby.addActionListener(createAction);

        centerSplit.add(rightCol);
        panel.add(centerSplit, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottomBar.setOpaque(false);
        JButton btnRefresh = new JButton("Làm mới danh sách phòng");
        btnRefresh.addActionListener(e -> {
            if (network.isConnected()) {
                network.sendTcp(Common.CMD_ROOM_LIST);
            } else {
                JOptionPane.showMessageDialog(this, "Chưa kết nối Server!");
            }
        });
        bottomBar.add(btnRefresh);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void createNewRoom() {
        String rName = txtCreateRoomName.getText().trim();
        String pName = txtPlayerNameLobby.getText().trim();
        if(!pName.isEmpty()) myName = pName;

        if (rName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên phòng!");
            return;
        }

        if (!network.isConnected()) {
            launchServerBatch();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}

            String ip = txtServerIp.getText().trim();
            if (!network.connect(ip)) {
                JOptionPane.showMessageDialog(this, "Không thể tự khởi động hoặc kết nối tới Server!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        network.sendTcp(Common.CMD_CREATE_ROOM + ":" + rName + ":" + myName);
        cardLayout.show(mainContainer, "GAME");
        gamePanel.requestFocusInWindow();
    }

    private void joinSelectedRoom() {
        String selected = roomJList.getSelectedValue();
        String pName = txtPlayerNameLobby.getText().trim();
        if(!pName.isEmpty()) myName = pName;

        if (selected == null || selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn 1 phòng trong danh sách!");
            return;
        }
        if (!network.isConnected()) {
            JOptionPane.showMessageDialog(this, "Bạn chưa kết nối tới Server! Hãy bấm Kết nối Server trước.");
            return;
        }

        String rName = selected.split(" ")[0];
        network.sendTcp(Common.CMD_JOIN_ROOM + ":" + rName + ":" + myName);
        cardLayout.show(mainContainer, "GAME");
        gamePanel.requestFocusInWindow();
    }

    private void setupKeyBindings() {
        int[][] keys = {
            {KeyEvent.VK_W, KeyEvent.VK_UP}, 
            {KeyEvent.VK_S, KeyEvent.VK_DOWN}, 
            {KeyEvent.VK_A, KeyEvent.VK_LEFT}, 
            {KeyEvent.VK_D, KeyEvent.VK_RIGHT},
            {KeyEvent.VK_ESCAPE, -1}
        };
        String[] names = {"up", "down", "left", "right", "esc"};
        int cond = JComponent.WHEN_IN_FOCUSED_WINDOW;

        for (int i = 0; i < names.length; i++) {
            final String n = names[i];
            int p = keys[i][0], a = keys[i][1];
            
            if ("esc".equals(n)) {
                gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p, 0, false), n + "_p");
                gamePanel.getActionMap().put(n + "_p", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        handleEscapeKey();
                    }
                });
            } else {
                gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p, 0, false), n + "_p");
                if (a != -1) gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(a, 0, false), n + "_p");
                gamePanel.getActionMap().put(n + "_p", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { updateKey(n, true); }
                });

                gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p, 0, true), n + "_r");
                if (a != -1) gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(a, 0, true), n + "_r");
                gamePanel.getActionMap().put(n + "_r", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { updateKey(n, false); }
                });
            }
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
            gamePanel.stopOfflineMode();
            network.disconnect();
            setTitle("Dungeon Co-op Game");
            cardLayout.show(mainContainer, "MENU");
        }
    }

    private void updateKey(String dir, boolean pressed) {
        switch (dir) {
            case "left": gamePanel.left = pressed; break;
            case "right": gamePanel.right = pressed; break;
            case "up": gamePanel.up = pressed; break;
            case "down": gamePanel.down = pressed; break;
        }
        // Gửi trạng thái phím qua TCP thuần ngay khi có thay đổi
        if (network.isConnected()) {
            network.sendTcp(Common.CMD_INPUT + ":" + gamePanel.left + ":" + gamePanel.right + ":" + gamePanel.up + ":" + gamePanel.down);
        }
    }

    // --- NETWORK LISTENER CALLBACKS ---
    @Override public void onRoomListReceived(String line) {
        String[] items = line.split(";");
        SwingUtilities.invokeLater(() -> {
            roomListModel.clear();
            for (int i = 1; i < items.length; i++) roomListModel.addElement(items[i]);
        });
    }

    @Override public void onStateReceived(String line) {
        gamePanel.updateOnlinePlayers(line, myName);
    }

    @Override public void onErrorReceived(String err) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, err));
    }

    @Override 
    public void onDisconnected() {
        gamePanel.stopOfflineMode();
        SwingUtilities.invokeLater(() -> cardLayout.show(mainContainer, "MENU"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}