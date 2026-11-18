package source.client;

import source.common.Common;
import source.common.GameMap;
import source.common.GameProtocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class ClientApp extends JFrame implements ClientNetwork.Listener {

    private String initialMapError;
    private final JLabel lobbyStatus = new JLabel("Chua ket noi Server");
    private long connectionGeneration;
    private String myName = "Player_" + (int)(Math.random() * 100);
    private final GameMap mapManager;
    private final ClientGame gamePanel;

    private ClientNetwork netClient;
    private ClientVoiceChat voiceChat;
    private final ClientMovement movement;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(cardLayout);

    private final DefaultListModel<String> roomListModel = new DefaultListModel<>();
    private final JList<String> roomJList = new JList<>(roomListModel);
    private JTextField txtCreateRoomName;
    private JTextField txtPlayerNameLobby;
    private JTextField txtServerIp;
    private volatile String pendingRoomCommand;
    private boolean awaitingLeave;
    private boolean roomCommandInFlight;
    private boolean onlineInRoom;

    public ClientApp() {
        setTitle("Dungeon Co-op Game");
        setSize(Common.SCREEN_WIDTH, Common.SCREEN_HEIGHT);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        String mapPath = "source/content/map";
        mapManager = new GameMap();
        try {
            mapManager.ensureCollisionMapLoaded();
            mapManager.loadTilesetImage(mapPath + "/tileset.png", Common.ORIGINAL_TILE_SIZE);
        } catch (IllegalStateException error) {
            initialMapError = "Map error: " + error.getMessage();
        }

        movement = new ClientMovement(mapManager);
        gamePanel = new ClientGame(mapManager, movement);
        gamePanel.setClientAppRef(this);

        JPanel menuPanel = createMainMenuUI();
        JPanel lobbyPanel = createLobbyUI();

        mainContainer.add(menuPanel, "MENU");
        mainContainer.add(lobbyPanel, "LOBBY");
        mainContainer.add(gamePanel, "GAME");
        add(mainContainer);

        setFocusTraversalKeysEnabled(false);
        mainContainer.setFocusTraversalKeysEnabled(false);
        gamePanel.setFocusTraversalKeysEnabled(false);

        cardLayout.show(mainContainer, "MENU");
        setupKeyBindings();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gamePanel.stopOfflineMode();
                stopVoiceChat();
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
            stopVoiceChat();
            if (netClient != null) netClient.close();
            setTitle("Dungeon Co-op [OFFLINE]");
            gamePanel.setCurrentRoomName("OFFLINE");
            try {
                gamePanel.startOfflineMode(myName);
                switchToGameScene();
            } catch (IllegalStateException error) {
                lobbyStatus.setText(error.getMessage());
                JOptionPane.showMessageDialog(this, error.getMessage());
            }
        });

        btnOnline.addActionListener(e -> {
            cardLayout.show(mainContainer, "LOBBY");
            roomListModel.clear();
            if (netClient != null && netClient.isReady()) netClient.send(Common.CMD_ROOM_LIST);
            else lobbyStatus.setText("Chua ket noi Server");
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
            stopVoiceChat();
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
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        header.add(topBar, BorderLayout.NORTH);
        lobbyStatus.setForeground(Color.WHITE);
        if (initialMapError != null) lobbyStatus.setText(initialMapError);
        header.add(lobbyStatus, BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);

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
        if (ip.isEmpty()) { lobbyStatus.setText("Hay nhap IP Server"); return; }
        stopVoiceChat();
        awaitingLeave = false;
        roomCommandInFlight = false;
        onlineInRoom = false;
        final long generation = ++connectionGeneration;
        if (netClient != null) netClient.close();
        ClientNetwork.Listener callbacks = new ClientNetwork.Listener() {
            private void onEdt(Runnable action) {
                SwingUtilities.invokeLater(() -> { if (generation == connectionGeneration) action.run(); });
            }
            public void onMessage(String message) { onEdt(() -> ClientApp.this.onMessage(message)); }
            public void onStateChanged(ClientNetwork.State state, String info) {
                onEdt(() -> ClientApp.this.onStateChanged(state, info));
            }
            public void onSessionLost(String reason) { onEdt(() -> ClientApp.this.onSessionLost(reason)); }
            public void onSnapshot(source.common.GameData.WorldSnapshot snapshot) {
                onEdt(() -> ClientApp.this.onSnapshot(snapshot));
            }
        };
        netClient = new ClientNetwork(ip, Common.TCP_PORT, callbacks);
        lobbyStatus.setText("Dang ket noi...");
        netClient.connect();
    }

    private void createNewRoom() {
        String rName = GameProtocol.sanitizeRoom(txtCreateRoomName.getText().trim());
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;
        sendRoomCommand("CREATE_ROOM:" + rName + ":" + myName);
    }

    private void joinSelectedRoom() {
        String selected = roomJList.getSelectedValue();
        if (selected == null) { lobbyStatus.setText("Hay chon phong de vao"); return; }
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;
        String roomName = selectedRoomName(selected);
        sendRoomCommand("JOIN_ROOM:" + GameProtocol.sanitizeRoom(roomName) + ":" + myName);
    }

    private String selectedRoomName(String displayValue) {
        int countStart = displayValue.lastIndexOf(" (");
        return countStart >= 0 ? displayValue.substring(0, countStart).trim() : displayValue.trim();
    }

    private void sendRoomCommand(String command) {
        if (awaitingLeave) { pendingRoomCommand = command; return; }
        if (roomCommandInFlight) { lobbyStatus.setText("Dang xu ly yeu cau vao phong..."); return; }
        ClientNetwork client = netClient;
        if (client != null && client.isReady()) {
            pendingRoomCommand = null;
            roomCommandInFlight = true;
            lobbyStatus.setText("Dang xu ly yeu cau vao phong...");
            client.send(command);
        } else {
            pendingRoomCommand = command;
            if (client == null || client.getState() == ClientNetwork.State.CLOSED) {
                connectToServer();
            }
        }
    }

    private void sendPendingRoomCommand() {
        String command = pendingRoomCommand;
        ClientNetwork client = netClient;
        if (!awaitingLeave && !roomCommandInFlight && command != null && client != null && client.isReady()) {
            pendingRoomCommand = null;
            roomCommandInFlight = true;
            lobbyStatus.setText("Dang xu ly yeu cau vao phong...");
            client.send(command);
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

        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_J, 0, false), "attack_press");
        gamePanel.getActionMap().put("attack_press", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendAttackInput(true); }
        });
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_J, 0, true), "attack_release");
        gamePanel.getActionMap().put("attack_release", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendAttackInput(false); }
        });

        // Bắt sự kiện giữ phím TAB hiển thị Bảng điểm (Scoreboard) kiểu Valorant
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0, false), "tab_press");
        gamePanel.getActionMap().put("tab_press", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { gamePanel.setShowScoreboard(true); }
        });
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0, true), "tab_release");
        gamePanel.getActionMap().put("tab_release", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { gamePanel.setShowScoreboard(false); }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
        gamePanel.getActionMap().put("esc", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (JOptionPane.showConfirmDialog(ClientApp.this, "Về Menu chính?", "Thoát", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    gamePanel.stopOfflineMode();
                    stopVoiceChat();
                    pendingRoomCommand = null;
                    roomCommandInFlight = false;
                    onlineInRoom = false;
                    gamePanel.left = gamePanel.right = gamePanel.up = gamePanel.down = gamePanel.attack = false;
                    roomListModel.clear();
                    if (netClient != null && netClient.isReady()) {
                        awaitingLeave = true;
                        netClient.send(Common.CMD_LEAVE_ROOM);
                    } else {
                        awaitingLeave = false;
                        if (netClient != null) netClient.close();
                    }
                    cardLayout.show(mainContainer, "MENU");
                }
            }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "voice-mute");
        gamePanel.getActionMap().put("voice-mute", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggleVoiceMute(); }
        });
    }

    public void sendAttackInput(boolean pressed) {
        gamePanel.attack = pressed;
    }

    private void updateKey(String dir, boolean pressed) {
        switch (dir) {
            case "left": gamePanel.left = pressed; break;
            case "right": gamePanel.right = pressed; break;
            case "up": gamePanel.up = pressed; break;
            case "down": gamePanel.down = pressed; break;
        }

    }

    public void sendFrameInput() {
        ClientNetwork client = netClient;
        if (!onlineInRoom || client == null || !client.isGameplayActive()) return;
        ClientMovement.PredictionInput input = movement.step(gamePanel.left, gamePanel.right,
                gamePanel.up, gamePanel.down, gamePanel.attack);
        client.sendGameInputUdp(input.seq + ":" + input.left + ":" + input.right + ":"
                + input.up + ":" + input.down + ":" + input.attack);
    }

    @Override
    public void onMessage(String line) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onMessage(line));
            return;
        }
        if (line.startsWith(Common.CMD_WELCOME + ":")) {
            if (netClient != null && netClient.isReady()) {
                netClient.send(Common.CMD_ROOM_LIST);
                sendPendingRoomCommand();
                lobbyStatus.setText("Da ket noi Server");
            }
        } else if (line.startsWith(Common.CMD_ROOM_LIST)) {
            String[] items = line.split(";");
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                for (int i = 1; i < items.length; i++) roomListModel.addElement(items[i]);
            });
        } else if (line.startsWith(Common.CMD_JOINED + ":")) {
            roomCommandInFlight = false;
            if (awaitingLeave) return;
            String rName = line.substring((Common.CMD_JOINED + ":").length()).trim();
            SwingUtilities.invokeLater(() -> {
                if (awaitingLeave) return;
                gamePanel.stopOfflineMode();
                movement.reset();
                gamePanel.setCurrentRoomName(rName);
                enterOnlineGame();
            });
        } else if (line.startsWith("RESUMED:")) {
            String rName = line.substring("RESUMED:".length()).trim();
            SwingUtilities.invokeLater(() -> {
                gamePanel.setCurrentRoomName(rName); enterOnlineGame(); sendPendingRoomCommand();
            });
        } else if ("LEFT".equals(line)) {
            awaitingLeave = false;
            onlineInRoom = false;
            gamePanel.setCurrentRoomName(""); stopVoiceChat();
            lobbyStatus.setText("Da roi phong");
            sendPendingRoomCommand();
        } else if (line.startsWith(Common.CMD_STATE)) {
            final source.common.GameData.WorldSnapshot snapshot = source.common.GameProtocol.parseSnapshot(line);
            SwingUtilities.invokeLater(() -> gamePanel.applySnapshot(snapshot, myName));
        } else if (line.startsWith(Common.CMD_ERROR)) {
            roomCommandInFlight = false;
            pendingRoomCommand = null;
            String[] parts = line.split(":", 2);
            String message = parts.length > 1 ? parts[1] : "Lỗi từ server.";
            lobbyStatus.setText(message);
            cardLayout.show(mainContainer, "LOBBY");
        }
    }

    @Override
    public void onSnapshot(source.common.GameData.WorldSnapshot snapshot) {
        final String name = myName;
        ClientNetwork client = netClient;
        if (client == null) return;
        final long generation = client.getGameplayGeneration();
        SwingUtilities.invokeLater(() -> {
            if (netClient == client && client.isGameplayActive()
                    && client.getGameplayGeneration() == generation) gamePanel.applySnapshot(snapshot, name);
        });
    }

    private void startVoiceChat() {
        if (netClient == null || netClient.getSessionToken() == null) return;
        if (voiceChat != null) {
            voiceChat.registerNow();
            return;
        }

        try {
            voiceChat = new ClientVoiceChat(txtServerIp.getText().trim(), Common.TCP_PORT,
                    netClient.getSessionToken(),
                    status -> SwingUtilities.invokeLater(() -> gamePanel.setVoiceStatus(status)));
        } catch (java.io.IOException e) {
            gamePanel.setVoiceStatus("VOICE: Could not start (" + e.getMessage() + ")");
        }
    }

    private void toggleVoiceMute() {
        if (voiceChat != null) {
            voiceChat.toggleMuted();
        }
    }

    private void stopVoiceChat() {
        ClientVoiceChat current = voiceChat;
        voiceChat = null;
        if (current != null) current.close();
        gamePanel.setVoiceStatus("");
    }

    private void enterOnlineGame() {
        try {
            mapManager.ensureCollisionMapLoaded();
            onlineInRoom = true;
            roomCommandInFlight = false;
            lobbyStatus.setText("Da vao phong: " + gamePanel.currentRoomName);
            switchToGameScene();
            if (netClient != null) netClient.requestGameUdpRegistration();
            startVoiceChat();
        } catch (IllegalStateException error) {
            lobbyStatus.setText("Map error: " + error.getMessage());
            if (netClient != null) netClient.send(Common.CMD_LEAVE_ROOM);
            cardLayout.show(mainContainer, "LOBBY");
        }
    }

    @Override
    public void onStateChanged(ClientNetwork.State state, String info) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onStateChanged(state, info)); return;
        }
        lobbyStatus.setText(info);
        gamePanel.setBannerMessage(state == ClientNetwork.State.RECONNECTING ? info : null);
        // Pending lobby commands are released by WELCOME or RESUMED, never TCP connect alone.
    }

    @Override
    public void onSessionLost(String reason) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onSessionLost(reason)); return;
        }
        stopVoiceChat();
        awaitingLeave = false;
        roomCommandInFlight = false;
        onlineInRoom = false;
        lobbyStatus.setText(reason);
        cardLayout.show(mainContainer, "LOBBY");
        if (pendingRoomCommand != null) connectToServer();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}
