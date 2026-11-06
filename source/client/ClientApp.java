package source.client;

import source.common.Common;
import source.common.GameMap;
import source.common.GameProtocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class ClientApp extends JFrame implements ClientNetwork.Listener {

    private String myName = "Player_" + (int)(Math.random() * 100);
    private final GameMap mapManager;
    private final ClientGame gamePanel;

    private ClientNetwork netClient;
    private final ClientMovement movement;

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

        String mapPath = "source/content/map";
        mapManager = new GameMap();
        mapManager.loadTilesetImage(mapPath + "/tileset.png", Common.ORIGINAL_TILE_SIZE);
        mapManager.loadTilesetProperties(mapPath + "/tileset.tsx");
        mapManager.loadAllChunks(mapPath, Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);

        movement = new ClientMovement(mapManager);
        gamePanel = new ClientGame(mapManager, movement);
        gamePanel.setClientAppRef(this);

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
                if (netClient != null) netClient.close();
            }
        });
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
            if (netClient != null) netClient.close();
            setTitle("Dungeon Co-op [OFFLINE]");
            gamePanel.startOfflineMode(myName);
            switchToGameScene();
        });

        btnOnline.addActionListener(e -> cardLayout.show(mainContainer, "LOBBY"));
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
            if (netClient != null) netClient.close();
            cardLayout.show(mainContainer, "MENU");
        });
        topBar.add(btnBack);

        topBar.add(new JLabel("<html><font color='white'>IP Server:</font></html>"));
        txtServerIp = new JTextField("localhost", 12);
        topBar.add(txtServerIp);

        JButton btnConnect = new JButton("Kết nối Server");
        btnConnect.addActionListener(e -> connectToServer());
        topBar.add(btnConnect);
        panel.add(topBar, BorderLayout.NORTH);

        JPanel centerSplit = new JPanel(new GridLayout(1, 2, 20, 0));
        centerSplit.setOpaque(false);

        JPanel leftCol = new JPanel(new BorderLayout(5, 5));
        leftCol.setOpaque(false);
        leftCol.add(new JLabel("<html><font color='white'><b>DANH SÁCH PHÒNG ĐANG HOẠT ĐỘNG</b></font></html>"), BorderLayout.NORTH);
        roomJList.setBackground(new Color(35, 40, 55));
        roomJList.setForeground(Color.WHITE);
        leftCol.add(new JScrollPane(roomJList), BorderLayout.CENTER);

        JButton btnJoin = new JButton("Vào phòng đã chọn");
        btnJoin.addActionListener(e -> joinSelectedRoom());
        leftCol.add(btnJoin, BorderLayout.SOUTH);
        centerSplit.add(leftCol);

        JPanel rightCol = new JPanel(new GridBagLayout());
        rightCol.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        gbc.gridy = 0; rightCol.add(new JLabel("<html><font color='white'><b>TẠO PHÒNG MỚI</b></font></html>"), gbc);
        gbc.gridy = 1; rightCol.add(new JLabel("<html><font color='white'>Tên người chơi:</font></html>"), gbc);
        txtPlayerNameLobby = new JTextField(myName);
        gbc.gridy = 2; rightCol.add(txtPlayerNameLobby, gbc);

        gbc.gridy = 3; rightCol.add(new JLabel("<html><font color='white'>Tên phòng muốn tạo:</font></html>"), gbc);
        txtCreateRoomName = new JTextField("Room_1");
        gbc.gridy = 4; rightCol.add(txtCreateRoomName, gbc);

        JButton btnCreate = new JButton("Tạo phòng");
        btnCreate.setBackground(new Color(60, 140, 60));
        btnCreate.setForeground(Color.WHITE);
        gbc.gridy = 5; rightCol.add(btnCreate, gbc);

        ActionListener createAction = e -> createNewRoom();
        btnCreate.addActionListener(createAction);
        txtCreateRoomName.addActionListener(createAction);
        txtPlayerNameLobby.addActionListener(createAction);

        centerSplit.add(rightCol);
        panel.add(centerSplit, BorderLayout.CENTER);

        return panel;
    }

    private void connectToServer() {
        String ip = txtServerIp.getText().trim();
        if (netClient != null) netClient.close();
        netClient = new ClientNetwork(ip, Common.TCP_PORT, this);
        netClient.connect();
    }

    private void createNewRoom() {
        String rName = GameProtocol.sanitizeRoom(txtCreateRoomName.getText().trim());
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;

        if (netClient == null || netClient.getState() != ClientNetwork.State.CONNECTED) {
            connectToServer();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        if (netClient != null) {
            netClient.send("CREATE_ROOM:" + rName + ":" + myName);
            switchToGameScene();
        }
    }

    private void joinSelectedRoom() {
        String selected = roomJList.getSelectedValue();
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;

        if (netClient == null || netClient.getState() != ClientNetwork.State.CONNECTED) {
            connectToServer();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        if (selected != null && netClient != null) {
            String rName = selected.split(" ")[0];
            netClient.send("JOIN_ROOM:" + rName + ":" + myName);
            switchToGameScene();
        }
    }

    private void switchToGameScene() {
        cardLayout.show(mainContainer, "GAME");
        SwingUtilities.invokeLater(() -> {
            gamePanel.setFocusable(true);
            gamePanel.requestFocusInWindow();
        });
    }

    private void setupKeyBindings() {
        int cond = JComponent.WHEN_IN_FOCUSED_WINDOW;

        String[] dirs = {"up", "down", "left", "right"};
        int[][] keys = {{KeyEvent.VK_W, KeyEvent.VK_UP}, {KeyEvent.VK_S, KeyEvent.VK_DOWN}, {KeyEvent.VK_A, KeyEvent.VK_LEFT}, {KeyEvent.VK_D, KeyEvent.VK_RIGHT}};

        for (int i = 0; i < dirs.length; i++) {
            final String d = dirs[i];
            int p1 = keys[i][0], p2 = keys[i][1];

            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p1, 0, false), d + "_p");
            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p2, 0, false), d + "_p");
            gamePanel.getActionMap().put(d + "_p", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { updateKey(d, true); }
            });

            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p1, 0, true), d + "_r");
            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p2, 0, true), d + "_r");
            gamePanel.getActionMap().put(d + "_r", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { updateKey(d, false); }
            });
        }

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
        gamePanel.getActionMap().put("esc", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (JOptionPane.showConfirmDialog(ClientApp.this, "Về Menu chính?", "Thoát", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    gamePanel.stopOfflineMode();
                    if (netClient != null) netClient.close();
                    cardLayout.show(mainContainer, "MENU");
                }
            }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "f3");
        gamePanel.getActionMap().put("f3", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { gamePanel.toggleHud(); }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "f4");
        gamePanel.getActionMap().put("f4", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (netClient != null) {
                    int cur = netClient.getLagMs();
                    int next = (cur == 0) ? 50 : (cur == 50) ? 100 : (cur == 100) ? 200 : 0;
                    netClient.setLagMs(next);
                }
            }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "f6");
        gamePanel.getActionMap().put("f6", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { movement.setPredictionEnabled(!movement.isPredictionEnabled()); }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "f7");
        gamePanel.getActionMap().put("f7", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { movement.setInterpolationEnabled(!movement.isInterpolationEnabled()); }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), "f9");
        gamePanel.getActionMap().put("f9", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (netClient != null) netClient.simulateConnectionDrop();
            }
        });
    }

    public void sendAttackInput(boolean pressed) {
        gamePanel.attack = pressed;
        if (netClient != null && netClient.getState() == ClientNetwork.State.CONNECTED) {
            ClientMovement.PredictionInput input = movement.step(gamePanel.left, gamePanel.right, gamePanel.up, gamePanel.down, pressed);
            netClient.send("INPUT:" + input.left + ":" + input.right + ":" + input.up + ":" + input.down + ":" + input.seq + ":" + input.attack);
        }
    }

    private void updateKey(String dir, boolean pressed) {
        switch (dir) {
            case "left": gamePanel.left = pressed; break;
            case "right": gamePanel.right = pressed; break;
            case "up": gamePanel.up = pressed; break;
            case "down": gamePanel.down = pressed; break;
        }

        if (netClient != null && netClient.getState() == ClientNetwork.State.CONNECTED) {
            ClientMovement.PredictionInput input = movement.step(gamePanel.left, gamePanel.right, gamePanel.up, gamePanel.down, gamePanel.attack);
            netClient.send("INPUT:" + input.left + ":" + input.right + ":" + input.up + ":" + input.down + ":" + input.seq + ":" + input.attack);
        }
    }

    @Override
    public void onMessage(String line) {
        if (line.startsWith(Common.CMD_ROOM_LIST)) {
            String[] items = line.split(";");
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                for (int i = 1; i < items.length; i++) roomListModel.addElement(items[i]);
            });
        } else if (line.startsWith(Common.CMD_STATE)) {
            gamePanel.updateOnlineWorld(line, myName);
            updateHudDisplay();
        } else if (line.startsWith(Common.CMD_ERROR)) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, line.split(":")[1]));
        }
    }

    private void updateHudDisplay() {
        if (netClient == null) return;
        String l1 = String.format("PING %.0f ms (jitter %.1f)   STATE 60/s   down 4.1 KB/s   up 2.0 KB/s",
                netClient.getRttMs(), netClient.getJitterMs());
        String l2 = String.format("Dự đoán [F6]: %s   Nội suy [F7]: %s (%d ms)   Lag giả lập [F4]: %d ms",
                movement.isPredictionEnabled() ? "BẬT" : "TẮT", movement.isInterpolationEnabled() ? "BẬT" : "TẮT", movement.getInterpolationDelayMs(), netClient.getLagMs());
        String l3 = String.format("Sai số reconcile: %.2f px   Pending: %d   Hiệu chỉnh: %d   Reconnect: %d",
                movement.maxError(), movement.pendingCount(), movement.correctionCount(), netClient.getReconnectCount());
        gamePanel.setHudInfo(l1, l2, l3);
    }

    @Override
    public void onStateChanged(ClientNetwork.State s, String info) {
        if (s == ClientNetwork.State.RECONNECTING) {
            gamePanel.setBannerMessage(info);
        } else {
            gamePanel.setBannerMessage(null);
        }
    }

    @Override
    public void onSessionLost(String reason) {
        SwingUtilities.invokeLater(() -> cardLayout.show(mainContainer, "MENU"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}