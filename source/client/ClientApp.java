package source.client;

import source.common.Common;
import source.common.GameMap;
import source.common.GameProtocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/*
 * Entry point và bộ điều phối giao diện Swing. Cửa sổ chứa Menu/Lobby/Game, nhận phím và nối ClientNetwork
 * với ClientGame. Các callback mạng phải qua EDT và đúng connectionGeneration để kết nối cũ không cập nhật
 * giao diện mới.
 * netClient/voiceChat/movement/gamePanel kết nối các thành phần. pendingRoomCommand, awaitingLeave,
 * roomCommandInFlight và onlineInRoom ngăn gửi/vào chồng thao tác phòng. roomListModel giữ chuỗi phòng
 * hiển thị; myName chỉ là tên, không là account bền.
 */
public class ClientApp extends JFrame implements ClientNetwork.Listener {

    /*
     * initialMapError: Lỗi nạp map ban đầu được giữ để hiển thị trong lobby.
     */
    private String initialMapError;
    /*
     * lobbyStatus: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final JLabel lobbyStatus = new JLabel("Chua ket noi Server");
    /*
     * connectionGeneration: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt
     * mới.
     */
    private long connectionGeneration;
    /*
     * myName: Tên hiển thị; save online hiện dùng tên để tìm lại slot, chưa là tài khoản xác thực.
     */
    private String myName = "Player_" + (int)(Math.random() * 100);
    /*
     * mapManager: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    private final GameMap mapManager;
    /*
     * gamePanel: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final ClientGame gamePanel;

    /*
     * netClient: Tham chiếu thành phần điều phối để chuyển việc tới tầng ứng dụng hoặc mạng.
     */
    private ClientNetwork netClient;
    /*
     * voiceChat: Đối tượng voice riêng của client, được tạo trong phòng và đóng khi rời.
     */
    private ClientVoiceChat voiceChat;
    /*
     * movement: Thành phần dựng hình, dự đoán, mô phỏng local và timer Swing của panel.
     */
    private final ClientMovement movement;

    /*
     * cardLayout: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final CardLayout cardLayout = new CardLayout();
    /*
     * mainContainer: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final JPanel mainContainer = new JPanel(cardLayout);

    /*
     * roomListModel: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final DefaultListModel<String> roomListModel = new DefaultListModel<>();
    /*
     * roomJList: Cấu trúc Swing chuyển MENU/LOBBY/GAME, danh sách phòng và nhãn trạng thái.
     */
    private final JList<String> roomJList = new JList<>(roomListModel);
    /*
     * txtCreateRoomName: Ô nhập IP, tên người chơi hoặc tên phòng trong lobby.
     */
    private JTextField txtCreateRoomName;
    /*
     * txtPlayerNameLobby: Ô nhập IP, tên người chơi hoặc tên phòng trong lobby.
     */
    private JTextField txtPlayerNameLobby;
    /*
     * txtServerIp: Ô nhập IP, tên người chơi hoặc tên phòng trong lobby.
     */
    private JTextField txtServerIp;
    /*
     * pendingRoomCommand: Chặn yêu cầu vào phòng chồng nhau, giữ một lệnh chờ và chờ LEFT sau LEAVE_ROOM.
     */
    private volatile String pendingRoomCommand;
    /*
     * awaitingLeave: Chặn yêu cầu vào phòng chồng nhau, giữ một lệnh chờ và chờ LEFT sau LEAVE_ROOM.
     */
    private boolean awaitingLeave;
    /*
     * roomCommandInFlight: Chặn yêu cầu vào phòng chồng nhau, giữ một lệnh chờ và chờ LEFT sau LEAVE_ROOM.
     */
    private boolean roomCommandInFlight;
    /*
     * onlineInRoom: Cờ đã vào phòng ở tầng mạng hoặc tầng giao diện; cần phân biệt với UDP ready.
     */
    private boolean onlineInRoom;

    /*
     * Khởi tạo ClientApp
     * Tạo JFrame, nạp map/collision/ảnh và lưu lỗi khởi tạo nếu có; dựng movement/gamePanel, ba scene,
     * phím và listener cửa sổ. Menu hiện trước, chưa mở TCP cho tới thao tác connect. Tắt focus traversal
     * để TAB dùng cho bảng player.
     */
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

        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        addWindowFocusListener(new WindowAdapter() {
            /*
             * windowLostFocus()
             * Callback của WindowAdapter trong constructor: xóa phím/attack/TAB và gửi frame trung tính
             * nếu online sẵn sàng. Tránh giữ chạy vì cửa sổ không nhận sự kiện nhả phím.
             */
            @Override public void windowLostFocus(WindowEvent event) {
                gamePanel.resetInputState();
                gamePanel.setShowScoreboard(false);
                sendFrameInput();
            }
        });
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        addWindowListener(new WindowAdapter() {
            /*
             * windowClosing()
             * Callback đóng cửa sổ: dừng thành phần local cần thiết, đóng voice và ClientNetwork nếu có.
             * Disconnect phía server đi theo cơ chế vòng đời TCP.
             */
            @Override
            public void windowClosing(WindowEvent e) {
                gamePanel.stopOfflineMode();
                stopVoiceChat();
                if (netClient != null) netClient.close();
            }
        });
    }

    /*
     * createMainMenuUI()
     * Tạo panel với nút offline/online/thoát và gắn action lambda. Online chuyển Lobby và hỏi ROOM_LIST
     * nếu TCP đã sẵn; offline đóng mạng và khởi tạo chế độ local; thoát gọi System.exit. Đây là khởi tạo
     * UI, không tự tạo phòng server.
     */
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

        /*
         * Callback/lambda: Action nút offline: đóng mạng/voice, khởi động mô phỏng local rồi chuyển scene;
         * lỗi khởi tạo được báo trên UI.
         */
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

        /*
         * Callback/lambda: Action nút online: hiện lobby và xin ROOM_LIST nếu TCP ready.
         */
        btnOnline.addActionListener(e -> {
            cardLayout.show(mainContainer, "LOBBY");
            roomListModel.clear();
            if (netClient != null && netClient.isReady()) netClient.send(Common.CMD_ROOM_LIST);
            else lobbyStatus.setText("Chua ket noi Server");
        });
        /*
         * Callback/lambda: Action thoát ứng dụng: System.exit(0).
         */
        btnExit.addActionListener(e -> System.exit(0));
        return panel;
    }

    /*
     * createStyledButton()
     * Tạo JButton với font, kích thước và màu thống nhất từ text đầu vào; trả nút cho builder UI.
     */
    private JButton createStyledButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Arial", Font.BOLD, 16));
        btn.setPreferredSize(new Dimension(350, 45));
        btn.setBackground(new Color(45, 52, 70));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    /*
     * createLobbyUI()
     * Dựng thanh IP/kết nối/quay lại, danh sách phòng, tên player/phòng và nút create/join. Gắn Enter ở ô
     * tên/phòng với createNewRoom. Quay Menu từ lobby đóng network/voice; không phải chỉ ẩn panel.
     */
    private JPanel createLobbyUI() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.setBackground(new Color(24, 28, 38));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        topBar.setOpaque(false);
        JButton btnBack = new JButton("Quay lại Menu");
        /*
         * Callback/lambda: Action quay về menu từ lobby: đóng voice và mạng trước khi chuyển card.
         */
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
        /*
         * Callback/lambda: Action nút kết nối gọi connectToServer.
         */
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
        /*
         * Callback/lambda: Action nút vào phòng gọi joinSelectedRoom.
         */
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

        /*
         * Callback/lambda: Một ActionListener dùng chung cho nút tạo phòng và Enter ở hai ô tên.
         */
        ActionListener createAction = e -> createNewRoom();
        btnCreate.addActionListener(createAction);
        txtCreateRoomName.addActionListener(createAction);
        txtPlayerNameLobby.addActionListener(createAction);

        centerSplit.add(rightCol);
        panel.add(centerSplit, BorderLayout.CENTER);

        return panel;
    }

    /*
     * connectToServer()
     * Đọc/kiểm IP, dừng voice, reset cờ phòng, tăng connectionGeneration và đóng client mạng cũ. Tạo
     * Listener lọc thế hệ rồi ClientNetwork.connect; DNS/connect chạy trên thread mạng, không block EDT.
     */
    private void connectToServer() {
        String ip = txtServerIp.getText().trim();
        if (ip.isEmpty()) { lobbyStatus.setText("Hay nhap IP Server"); return; }
        stopVoiceChat();
        awaitingLeave = false;
        roomCommandInFlight = false;
        onlineInRoom = false;
        /*
         * Mỗi đối tượng kết nối có một thế hệ; callback từ kết nối cũ sẽ bị bỏ khi lên EDT.
         */
        final long generation = ++connectionGeneration;
        if (netClient != null) netClient.close();
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        ClientNetwork.Listener callbacks = new ClientNetwork.Listener() {
            /*
             * onEdt()
             * Helper private trong Listener ẩn: invokeLater một action và chỉ chạy nếu generation còn bằng
             * generation hiện tại của app. Chặn callback trễ của kết nối đã bị thay.
             */
            private void onEdt(Runnable action) {
                /*
                 * Callback/lambda: Runnable lên EDT: chỉ thực thi action nếu generation của đối tượng mạng
                 * vẫn hiện hành.
                 */
                SwingUtilities.invokeLater(() -> { if (generation == connectionGeneration) action.run(); });
            }
            /*
             * onMessage()
             * Callback Listener ẩn chuyển thông điệp TCP sang ClientApp.onMessage qua onEdt có kiểm
             * generation.
             * Callback/lambda: Chuyển thông điệp TCP về ClientApp.onMessage qua wrapper EDT.
             */
            public void onMessage(String message) { onEdt(() -> ClientApp.this.onMessage(message)); }
            /*
             * onStateChanged()
             * Callback Listener ẩn chuyển trạng thái mạng và info sang handler UI trên EDT đúng đời kết
             * nối.
             */
            public void onStateChanged(ClientNetwork.State state, String info) {
                /*
                 * Callback/lambda: Chuyển state mạng về handler UI qua wrapper EDT.
                 */
                onEdt(() -> ClientApp.this.onStateChanged(state, info));
            }
            /*
             * onSessionLost()
             * Chuyển thông báo phiên hết hiệu lực sang ClientApp.onSessionLost qua bộ lọc generation.
             * Callback/lambda: Chuyển thông báo mất session về UI.
             */
            public void onSessionLost(String reason) { onEdt(() -> ClientApp.this.onSessionLost(reason)); }
            /*
             * onSnapshot()
             * Chuyển typed snapshot sang ClientApp.onSnapshot trên EDT theo đúng kết nối; không parse lại
             * binary tại UI.
             */
            public void onSnapshot(source.common.GameData.WorldSnapshot snapshot) {
                /*
                 * Callback/lambda: Chuyển WorldSnapshot về UI mà không parse byte lần nữa.
                 */
                onEdt(() -> ClientApp.this.onSnapshot(snapshot));
            }
        };
        netClient = new ClientNetwork(ip, Common.TCP_PORT, callbacks);
        lobbyStatus.setText("Dang ket noi...");
        netClient.connect();
    }

    /*
     * createNewRoom()
     * Lấy tên phòng/player từ ô nhập, sanitize và cập nhật myName rồi tạo CREATE_ROOM:R:N chuyển cho
     * sendRoomCommand. Không tự mở socket server.
     */
    private void createNewRoom() {
        String rName = GameProtocol.sanitizeRoom(txtCreateRoomName.getText().trim());
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;
        sendRoomCommand("CREATE_ROOM:" + rName + ":" + myName);
    }

    /*
     * joinSelectedRoom()
     * Yêu cầu có phòng đang chọn, lấy tên player và tách nhãn n/4 khỏi tên phòng rồi gửi JOIN_ROOM. Không
     * lấy nguyên chuỗi hiển thị làm tên save.
     */
    private void joinSelectedRoom() {
        String selected = roomJList.getSelectedValue();
        if (selected == null) { lobbyStatus.setText("Hay chon phong de vao"); return; }
        String pName = GameProtocol.sanitizeName(txtPlayerNameLobby.getText().trim());
        if (!pName.isEmpty()) myName = pName;
        String roomName = selectedRoomName(selected);
        sendRoomCommand("JOIN_ROOM:" + GameProtocol.sanitizeRoom(roomName) + ":" + myName);
    }

    /*
     * selectedRoomName()
     * Tìm dấu ' (' cuối nhãn danh sách, bỏ phần đếm người và trim. Nếu không có phần đếm, trả tên đã trim.
     */
    private String selectedRoomName(String displayValue) {
        int countStart = displayValue.lastIndexOf(" (");
        return countStart >= 0 ? displayValue.substring(0, countStart).trim() : displayValue.trim();
    }

    /*
     * sendRoomCommand()
     * Nếu đang đợi LEFT thì giữ command chờ; nếu có request đang xử lý thì báo đang bận. TCP sẵn thì đánh
     * dấu in-flight và gửi; chưa sẵn thì lưu pending, connect nếu cần. Tránh mất thao tác create khi
     * handshake chưa xong.
     * Đợi LEFT thì giữ một pending String; đang in flight thì từ chối gửi chồng. TCP ready thì đánh dấu in
     * flight và gửi; chưa ready thì giữ lệnh và có thể mở kết nối. Không gửi CREATE/JOIN chỉ vì
     * Socket.connect đã trả về; phải chờ handshake ứng dụng.
     */
    private void sendRoomCommand(String command) {
        /*
         * Chờ LEFT rồi mới gửi lệnh phòng tiếp theo, tránh race giữa rời và vào phòng.
         */
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

    /*
     * sendPendingRoomCommand()
     * Chỉ gửi pending khi không chờ rời, không có lệnh in-flight và client.isReady. Xóa pending trước gửi
     * và đánh dấu đang xử lý; WELCOME/RESUMED/LEFT kích hoạt đúng thời điểm.
     */
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

    /*
     * switchToGameScene()
     * Chuyển CardLayout sang GAME và hẹn requestFocusInWindow trên EDT để nhận điều khiển.
     */
    private void switchToGameScene() {
        cardLayout.show(mainContainer, "GAME");
        /*
         * Callback/lambda: Sau đổi card GAME, lên lịch yêu cầu focus để nhận input.
         */
        SwingUtilities.invokeLater(() -> {
            gamePanel.setFocusable(true);
            gamePanel.requestFocusInWindow();
        });
    }

    /*
     * setupKeyBindings()
     * Đăng ký phím nhấn/nhả cho WASD/mũi tên, J đánh, TAB bảng, M mute và ESC rời có xác nhận. Action chỉ
     * đổi ý định/scene; việc gửi gameplay thực hiện theo timer và sendFrameInput.
     */
    private void setupKeyBindings() {
        int cond = JComponent.WHEN_IN_FOCUSED_WINDOW;

        String[] dirs = {"up", "down", "left", "right"};
        int[][] keys = {{KeyEvent.VK_W, KeyEvent.VK_UP}, {KeyEvent.VK_S, KeyEvent.VK_DOWN}, {KeyEvent.VK_A, KeyEvent.VK_LEFT}, {KeyEvent.VK_D, KeyEvent.VK_RIGHT}};

        for (int i = 0; i < dirs.length; i++) {
            final String d = dirs[i];
            int p1 = keys[i][0], p2 = keys[i][1];

            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p1, 0, false), d + "_p");
            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p2, 0, false), d + "_p");
            /*
             * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các
             * hàm override bên dưới.
             */
            gamePanel.getActionMap().put(d + "_p", new AbstractAction() {
                /*
                 * actionPerformed()
                 * Callback nhấn phím di chuyển trong vòng bốn hướng: gọi updateKey(d,true). Một khai báo
                 * này tạo action cho từng hướng và cả phím chữ/mũi tên.
                 */
                @Override public void actionPerformed(ActionEvent e) { updateKey(d, true); }
            });

            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p1, 0, true), d + "_r");
            gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(p2, 0, true), d + "_r");
            /*
             * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các
             * hàm override bên dưới.
             */
            gamePanel.getActionMap().put(d + "_r", new AbstractAction() {
                /*
                 * actionPerformed()
                 * Callback nhả phím di chuyển: updateKey(d,false), không trực tiếp sửa tọa độ
                 * authoritative.
                 */
                @Override public void actionPerformed(ActionEvent e) { updateKey(d, false); }
            });
        }

        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_J, 0, false), "attack_press");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("attack_press", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback nhấn J: sendAttackInput(true) để ghi cạnh nhấn và trạng thái giữ đánh.
             */
            @Override public void actionPerformed(ActionEvent e) { sendAttackInput(true); }
        });
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_J, 0, true), "attack_release");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("attack_release", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback nhả J: sendAttackInput(false). Cạnh nhấn đã ghi vẫn có thể được timer tiêu thụ một
             * lần.
             */
            @Override public void actionPerformed(ActionEvent e) { sendAttackInput(false); }
        });

        // Bắt sự kiện giữ phím TAB hiển thị Bảng điểm (Scoreboard) kiểu Valorant
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0, false), "tab_press");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("tab_press", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback nhấn TAB: bật showScoreboard của gamePanel và repaint.
             */
            @Override public void actionPerformed(ActionEvent e) { gamePanel.setShowScoreboard(true); }
        });
        gamePanel.getInputMap(cond).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0, true), "tab_release");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("tab_release", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback nhả TAB: tắt bảng, không đóng phiên hoặc rời phòng.
             */
            @Override public void actionPerformed(ActionEvent e) { gamePanel.setShowScoreboard(false); }
        });

        gamePanel.getInputMap(cond).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("esc", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback ESC: hỏi xác nhận; nếu đồng ý dừng voice, xóa pending/input/cờ game và gửi
             * LEAVE_ROOM khi TCP sẵn. Đặt awaitingLeave để thao tác mới đợi LEFT, rồi về Menu; không coi
             * ẩn game là đã được server xác nhận rời.
             */
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
        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        gamePanel.getActionMap().put("voice-mute", new AbstractAction() {
            /*
             * actionPerformed()
             * Callback M: gọi toggleVoiceMute, chỉ có tác dụng khi đang có voiceChat.
             */
            @Override public void actionPerformed(ActionEvent e) { toggleVoiceMute(); }
        });
    }

    /*
     * sendAttackInput()
     * Gọi gamePanel.setAttackPressed để giữ boolean và cạnh nhấn. Tên hàm có 'send' nhưng body không trực
     * tiếp gửi datagram.
     */
    public void sendAttackInput(boolean pressed) {
        gamePanel.setAttackPressed(pressed);
    }

    /*
     * updateKey()
     * Từ tên hướng đặt boolean left/right/up/down của gamePanel. Timer lấy bốn cờ để tạo một input có
     * sequence.
     */
    private void updateKey(String dir, boolean pressed) {
        switch (dir) {
            case "left": gamePanel.left = pressed; break;
            case "right": gamePanel.right = pressed; break;
            case "up": gamePanel.up = pressed; break;
            case "down": gamePanel.down = pressed; break;
        }

    }

    /*
     * sendFrameInput()
     * Chỉ làm khi onlineInRoom và UDP gameplay active. Lấy phím/attack pending, gọi movement.step để
     * prediction và tạo seq; ghép payload phím rồi giao sendGameInputUdp, nơi ping được thêm sau.
     */
    public void sendFrameInput() {
        ClientNetwork client = netClient;
        /*
         * TCP ready chưa đủ: chỉ gửi gameplay sau khi đã vào phòng và nhận ACK UDP hợp lệ.
         */
        if (!onlineInRoom || client == null || !client.isGameplayActive()) return;
        ClientMovement.PredictionInput input = movement.step(gamePanel.left, gamePanel.right,
                gamePanel.up, gamePanel.down, gamePanel.consumeAttackPress() | gamePanel.attack);
        client.sendGameInputUdp(input.seq + ":" + input.left + ":" + input.right + ":"
                + input.up + ":" + input.down + ":" + input.attack);
    }

    /*
     * onMessage()
     * Đảm bảo chạy trên EDT rồi xử lý WELCOME/ROOM_LIST/JOINED/RESUMED/LEFT/ERROR. WELCOME mới thả
     * pending, JOINED reset movement rồi enterOnlineGame, LEFT giải phóng awaitingLeave; ERROR đưa về
     * lobby. Có nhánh STATE văn bản tương thích nhưng luồng sản xuất nhận binary qua onSnapshot.
     */
    @Override
    public void onMessage(String line) {
        if (!SwingUtilities.isEventDispatchThread()) {
            /*
             * Callback/lambda: Nếu onMessage được gọi ngoài EDT, xếp lại cùng thông điệp vào EDT rồi
             * return khỏi lượt ngoài.
             */
            SwingUtilities.invokeLater(() -> onMessage(line));
            return;
        }
        /*
         * WELCOME đánh dấu handshake ứng dụng hoàn tất; lúc này mới gửi yêu cầu lobby đang chờ.
         */
        if (line.startsWith(Common.CMD_WELCOME + ":")) {
            if (netClient != null && netClient.isReady()) {
                netClient.send(Common.CMD_ROOM_LIST);
                sendPendingRoomCommand();
                lobbyStatus.setText("Da ket noi Server");
            }
        } else if (line.startsWith(Common.CMD_ROOM_LIST)) {
            String[] items = line.split(";");
            /*
             * Callback/lambda: Thay danh sách room trên EDT bằng các nhãn ROOM_LIST đã tách.
             */
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                for (int i = 1; i < items.length; i++) roomListModel.addElement(items[i]);
            });
        } else if (line.startsWith(Common.CMD_JOINED + ":")) {
            roomCommandInFlight = false;
            if (awaitingLeave) return;
            String rName = line.substring((Common.CMD_JOINED + ":").length()).trim();
            /*
             * Callback/lambda: JOINED: tránh bước tiếp khi đang rời, dừng local, reset movement, chọn
             * phòng và enterOnlineGame.
             */
            SwingUtilities.invokeLater(() -> {
                if (awaitingLeave) return;
                gamePanel.stopOfflineMode();
                movement.reset();
                gamePanel.setCurrentRoomName(rName);
                enterOnlineGame();
            });
        } else if (line.startsWith("RESUMED:")) {
            String rName = line.substring("RESUMED:".length()).trim();
            /*
             * Callback/lambda: RESUMED: đặt lại tên phòng, vào game và thử gửi pending.
             */
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
            /*
             * Callback/lambda: Nhánh STATE chuỗi cũ: áp bản đã parse lên panel.
             */
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

    /*
     * onSnapshot()
     * Giữ tham chiếu client và gameplayGeneration lúc nhận, hẹn EDT; chỉ áp nếu đúng client còn active và
     * generation chưa đổi. Cập nhật myId rồi gamePanel.applySnapshot, tránh snapshot phòng/kết nối cũ tác
     * động UI mới.
     * Callback nhận snapshot không trực tiếp sửa GUI từ receiver. Ghi nhớ client và gameplayGeneration,
     * enqueue lên EDT rồi kiểm chúng còn đúng, gameplayActive còn true mới setMyId và applySnapshot. Vì
     * invokeLater là bất đồng bộ, callback đã enqueue chưa có nghĩa state đã được vẽ ngay.
     */
    @Override
    public void onSnapshot(source.common.GameData.WorldSnapshot snapshot) {
        final String name = myName;
        ClientNetwork client = netClient;
        if (client == null) return;
        final long generation = client.getGameplayGeneration();
        /*
         * Callback/lambda: Áp snapshot chỉ nếu client còn hiện hành, gameplay active và generation không
         * đổi trong lúc đợi EDT.
         */
        SwingUtilities.invokeLater(() -> {
            /*
             * Kiểm lại thế hệ sau khi đợi EDT để không áp snapshot của lượt chơi cũ.
             */
            if (netClient == client && client.isGameplayActive()
                    && client.getGameplayGeneration() == generation) {
                gamePanel.setMyId(client.getMyId());
                gamePanel.applySnapshot(snapshot, name);
            }
        });
    }

    /*
     * startVoiceChat()
     * Cần token; nếu voice đang có thì đăng ký lại, nếu chưa thì tạo ClientVoiceChat từ IP/port/token.
     * Callback status được đưa lên EDT; lỗi khởi tạo chỉ báo voice, không hủy gameplay.
     */
    private void startVoiceChat() {
        if (netClient == null || netClient.getSessionToken() == null) return;
        if (voiceChat != null) {
            voiceChat.registerNow();
            return;
        }

        try {
            voiceChat = new ClientVoiceChat(txtServerIp.getText().trim(), Common.TCP_PORT,
                    netClient.getSessionToken(),
                    /*
                     * Callback/lambda: Callback trạng thái voice được đưa qua EDT để setVoiceStatus, không
                     * sửa component từ thread âm thanh.
                     * Callback/lambda: Callback trạng thái voice được đưa qua EDT để setVoiceStatus, không
                     * sửa component từ thread âm thanh.
                     */
                    status -> SwingUtilities.invokeLater(() -> gamePanel.setVoiceStatus(status)));
        } catch (java.io.IOException e) {
            gamePanel.setVoiceStatus("VOICE: Could not start (" + e.getMessage() + ")");
        }
    }

    /*
     * toggleVoiceMute()
     * Đảo mute trên voiceChat nếu có; không thay đầu vào di chuyển hoặc server room.
     */
    private void toggleVoiceMute() {
        if (voiceChat != null) {
            voiceChat.toggleMuted();
        }
    }

    /*
     * stopVoiceChat()
     * Tách tham chiếu voice hiện tại, đặt null rồi close và xóa status. Dọn cả socket và thiết bị thông
     * qua lớp voice.
     */
    private void stopVoiceChat() {
        ClientVoiceChat current = voiceChat;
        voiceChat = null;
        if (current != null) current.close();
        gamePanel.setVoiceStatus("");
    }

    /*
     * enterOnlineGame()
     * Đảm bảo map, gán myId, đặt cờ online, chuyển scene, yêu cầu đăng ký UDP và khởi động voice. Nếu map
     * lỗi, gửi LEAVE_ROOM và trở lại lobby để không giữ người trong phòng vô hình.
     */
    private void enterOnlineGame() {
        try {
            mapManager.ensureCollisionMapLoaded();
            gamePanel.setMyId(netClient == null ? "" : netClient.getMyId());
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

    /*
     * onStateChanged()
     * Đưa về EDT nếu cần, cập nhật trạng thái lobby và banner reconnect. Không tự gửi pending chỉ vì TCP
     * connect; chờ WELCOME/RESUMED.
     */
    @Override
    public void onStateChanged(ClientNetwork.State state, String info) {
        if (!SwingUtilities.isEventDispatchThread()) {
            /*
             * Callback/lambda: Đưa stateChanged về EDT nếu đang được gọi ngoài EDT.
             */
            SwingUtilities.invokeLater(() -> onStateChanged(state, info)); return;
        }
        lobbyStatus.setText(info);
        gamePanel.setBannerMessage(state == ClientNetwork.State.RECONNECTING ? info : null);
        // Pending lobby commands are released by WELCOME or RESUMED, never TCP connect alone.
    }

    /*
     * onSessionLost()
     * Đảm bảo EDT, dừng voice và reset các cờ phòng, báo lý do rồi về lobby. Nếu còn lệnh pending có thể
     * connect lại cho yêu cầu mới.
     */
    @Override
    public void onSessionLost(String reason) {
        if (!SwingUtilities.isEventDispatchThread()) {
            /*
             * Callback/lambda: Đưa sessionLost về EDT nếu đang được gọi ngoài EDT.
             */
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

    /*
     * main()
     * Hẹn tạo và hiển thị ClientApp bằng SwingUtilities.invokeLater; giao diện được khởi tạo trên EDT.
     */
    public static void main(String[] args) {
        /*
         * Callback/lambda: Tạo và hiện ClientApp trên EDT khi bắt đầu chương trình.
         */
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}
