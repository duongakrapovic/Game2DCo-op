package source.client;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GameProtocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Panel giữ dữ liệu cần vẽ và input, nối renderer/movement/app với các snapshot. Timer Swing 1000/60 theo
 * chia nguyên = 16 ms: khi panel đang hiện và không chạy local thì gọi app.sendFrameInput, sau đó repaint.
 * Nó không là loop authoritative online.
 * renderPlayers/renderMonsters/renderItems là các map tách khỏi mô phỏng. attackPending AtomicBoolean giữ
 * lần click quá ngắn giữa hai frame; myId ưu tiên nhận diện local player. renderer, movement và các chuỗi
 * HUD phục vụ vẽ; thành phần offline là phần source được giải thích riêng, không đưa vào sơ đồ online.
 */
public class ClientGame extends JPanel {
    /*
     * renderer: Thành phần dựng hình, dự đoán, mô phỏng local và timer Swing của panel.
     */
    private final ClientRenderer renderer = new ClientRenderer();
    /*
     * offline: Thành phần dựng hình, dự đoán, mô phỏng local và timer Swing của panel.
     */
    private final OfflineGameLoop offline = new OfflineGameLoop(this);
    /*
     * renderTimer: Thành phần dựng hình, dự đoán, mô phỏng local và timer Swing của panel.
     * Callback/lambda: Callback timer Swing: nếu panel đang hiện và không offline, lấy input online rồi
     * repaint. Timer đặt 1000/60 ra 16 ms; không bảo đảm khóa đồng nhịp server.
     */
    private final javax.swing.Timer renderTimer = new javax.swing.Timer(1000 / Common.TICK_RATE, event -> {
        if (isShowing() && !offline.isRunning() && this.clientAppRef != null) this.clientAppRef.sendFrameInput();
        repaint();
    });

    /*
     * isOfflineRunning()
     * Trả offline.isRunning để chọn đường timer/vẽ. Không mở hoặc đóng chế độ nào.
     */
    public boolean isOfflineRunning() { return offline.isRunning(); }
    /*
     * startOfflineMode()
     * Đặt tên và ID LOCAL_1, xóa input, reset prediction rồi giao OfflineGameLoop.startOfflineMode.
     */
    public void startOfflineMode(String name) { this.myName = name; myId = "LOCAL_1"; resetInputState(); movement.reset(); offline.startOfflineMode(name); }
    /*
     * stopOfflineMode()
     * Ủy quyền cho OfflineGameLoop dừng và lưu phù hợp; ClientApp gọi khi chuyển sang online hoặc rời
     * game.
     */
    public void stopOfflineMode() { offline.stopOfflineMode(); }
    /*
     * saveOfflineProgress()
     * Wrapper gọi hàm lưu của loop local; không ghi online_Room_1.json.
     */
    public void saveOfflineProgress() { offline.saveOfflineProgress(); }

    /*
     * paintComponent()
     * Gọi super để xóa nền, tạo bản Graphics2D, giao renderer.render rồi dispose bản copy trong finally.
     * Không chạy network I/O trong hàm vẽ.
     */
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D copy = (Graphics2D) g.create();
        try { renderer.render(copy, this); } finally { copy.dispose(); }
    }
    /*
     * addNotify()
     * Khi panel được gắn vào hệ thống hiển thị, gọi super rồi start renderTimer.
     */
    @Override public void addNotify() { super.addNotify(); renderTimer.start(); }
    /*
     * removeNotify()
     * Dừng renderTimer trước super khi panel bị tháo, tránh timer tiếp tục chạy trên component không còn
     * được dùng.
     */
    @Override public void removeNotify() { renderTimer.stop(); super.removeNotify(); }


    /*
     * renderPlayers: Bản dữ liệu entity dành cho vẽ; online được thay khi áp snapshot.
     */
    volatile Map<String, GameData.PlayerState> renderPlayers = new ConcurrentHashMap<>();
    /*
     * renderMonsters: Bản dữ liệu entity dành cho vẽ; online được thay khi áp snapshot.
     */
    volatile Map<String, GameData.MonsterState> renderMonsters = new ConcurrentHashMap<>();
    /*
     * renderItems: Bản dữ liệu entity dành cho vẽ; online được thay khi áp snapshot.
     */
    volatile Map<String, GameData.ItemState> renderItems = new HashMap<>();

    /*
     * mapManager: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    final GameMap mapManager;
    /*
     * movement: Thành phần dựng hình, dự đoán, mô phỏng local và timer Swing của panel.
     */
    final ClientMovement movement;

    /*
     * myName: Tên hiển thị; save online hiện dùng tên để tìm lại slot, chưa là tài khoản xác thực.
     */
    String myName = "Player";
    /*
     * myId: ID người chơi hoặc entity trong runtime; khác tên hiển thị và token.
     */
    private String myId = "";
    /*
     * attackPending: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn nhanh bị bỏ
     * qua.
     */
    private final java.util.concurrent.atomic.AtomicBoolean attackPending = new java.util.concurrent.atomic.AtomicBoolean();
    /*
     * setMyId()
     * Lưu ID local, đổi null thành chuỗi rỗng; dùng cho isMe và chọn nhân vật dự đoán.
     */
    public void setMyId(String id) { myId = id == null ? "" : id; }
    /*
     * isMe()
     * Nếu myId có thì so ID; chỉ fallback so tên khi chưa có ID. Tránh hai người trùng tên bị coi là cùng
     * một local player khi đã có định danh phiên.
     */
    public boolean isMe(GameData.PlayerState player) {
        return !myId.isEmpty() ? myId.equals(player.id) : java.util.Objects.equals(player.name, myName);
    }
    /*
     * setAttackPressed()
     * Khi chuyển false→true thì đặt attackPending, sau đó lưu trạng thái giữ attack. Nhả nhanh không xóa
     * mất cạnh nhấn chưa được timer lấy.
     */
    public void setAttackPressed(boolean pressed) {
        if (pressed && !attack) attackPending.set(true);
        attack = pressed;
    }
    /*
     * consumeAttackPress()
     * getAndSet(false) trả và tiêu thụ cạnh nhấn đúng một lần bằng thao tác nguyên tử.
     */
    public boolean consumeAttackPress() { return attackPending.getAndSet(false); }
    /*
     * resetInputState()
     * Đặt bốn hướng/attack=false và xóa attackPending. Dùng khi mất focus hoặc thay chế độ/phòng.
     */
    public void resetInputState() {
        left = right = up = down = attack = false;
        attackPending.set(false);
    }

    /*
     * left, right, up, down, attack: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
     */
    public volatile boolean left = false, right = false, up = false, down = false, attack = false;
    /*
     * clientAppRef: Tham chiếu thành phần điều phối để chuyển việc tới tầng ứng dụng hoặc mạng.
     */
    private ClientApp clientAppRef = null;

    /*
     * showScoreboard: Cờ/lớp phủ TAB hoặc nội dung HUD thông báo reconnect và voice.
     */
    volatile boolean showScoreboard = false;
    /*
     * currentRoomName: Định danh hoặc tên phòng; không phải IP server hay token phiên.
     */
    String currentRoomName = "";
    /*
     * bannerMessage: Cờ/lớp phủ TAB hoặc nội dung HUD thông báo reconnect và voice.
     */
    String bannerMessage = null;
    /*
     * voiceStatus: Cờ/lớp phủ TAB hoặc nội dung HUD thông báo reconnect và voice.
     */
    String voiceStatus = "";

    /*
     * Khởi tạo ClientGame
     * Lưu map/movement, bật focus và cài MouseAdapter cho chuột trái; renderer/loop/timer đã được field
     * initializer tạo.
     */
    public ClientGame(GameMap mapManager, ClientMovement movement) {
        this.mapManager = mapManager;
        this.movement = movement;
        setFocusable(true);

        /*
         * Lớp ẩn cung cấp callback hoặc dữ liệu giả cho ngữ cảnh bao quanh; caller/hệ thống gọi các hàm
         * override bên dưới.
         */
        addMouseListener(new MouseAdapter() {
            /*
             * mousePressed()
             * Xin focus cho panel và nếu là chuột trái thì triggerAttack(true). Chuột khác không tạo đòn.
             */
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(true);
                }
            }

            /*
             * mouseReleased()
             * Nếu nhả chuột trái thì triggerAttack(false). Không tự trừ HP quái ở client.
             */
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(false);
                }
            }
        });
    }

    /*
     * setClientAppRef()
     * Lưu tham chiếu app để timer và input chuột gọi bộ điều phối; không tạo app mới.
     */
    public void setClientAppRef(ClientApp app) {
        this.clientAppRef = app;
    }

    /*
     * triggerAttack()
     * Ghi trạng thái đánh vào panel rồi chuyển ý định tới app.sendAttackInput nếu app đã gắn. Không gọi
     * CombatSystem ở luồng online client.
     */
    private void triggerAttack(boolean isPressed) {
        setAttackPressed(isPressed);
        if (clientAppRef != null) {
            clientAppRef.sendAttackInput(isPressed);
        }
    }







    /*
     * updateOnlineWorld()
     * Đường tương thích nhận STATE chuỗi, parseSnapshot rồi hẹn applySnapshot trên EDT. Luồng online
     * binary hiện dùng typed snapshot trực tiếp.
     */
    public void updateOnlineWorld(String stateLine, String myCurrentName) {
        GameData.WorldSnapshot snapshot = GameProtocol.parseSnapshot(stateLine);
        /*
         * Callback/lambda: Runnable áp snapshot trên EDT; dữ liệu đã được parse trước đó, không chạy
         * network I/O trong callback.
         */
        SwingUtilities.invokeLater(() -> applySnapshot(snapshot, myCurrentName));
    }

    /*
     * applySnapshot()
     * Bảo đảm EDT và bỏ khi local loop đang chạy. Dựng map entity mới, suy hiệu ứng flash/animation từ mẫu
     * trước; local gọi reconciliation theo ACK, người khác thêm mẫu nội suy. Loại lịch sử người đã rời,
     * thay ba map hiển thị và repaint. Không tự quyết sát thương.
     * Chỉ áp online khi không chạy offline. Dựng maps mới theo ID, giữ hiệu ứng nháy khi HP giảm, điều hòa
     * hoạt ảnh. Player của mình đi qua onServerState để reconcile; player khác addSnap để nội suy. Dọn mẫu
     * ID đã rời, thay render maps và repaint; không tự tính damage hay nhặt item.
     */
    public void applySnapshot(GameData.WorldSnapshot snapshot, String myCurrentName) {
        if (!SwingUtilities.isEventDispatchThread()) {
            /*
             * Callback/lambda: Runnable áp snapshot trên EDT; dữ liệu đã được parse trước đó, không chạy
             * network I/O trong callback.
             */
            SwingUtilities.invokeLater(() -> applySnapshot(snapshot, myCurrentName));
            return;
        }
        if (offline.isRunning()) return;
        myName = myCurrentName;
        long now = System.currentTimeMillis();
        Map<String, GameData.PlayerState> updatedPlayers = new HashMap<>();
        for (GameData.PlayerState p : snapshot.players) {
            GameData.PlayerState previous = renderPlayers.get(p.id);
            if (previous != null) {
                p.flashUntil = p.hp < previous.hp ? now + 150 : previous.flashUntil;
                p.attackTicks = p.isAttacking && previous.isAttacking ? previous.attackTicks + 1 : 0;
            }
            updatedPlayers.put(p.id, p);
            /*
             * Mình dùng reconciliation; người chơi khác lưu mẫu nội suy theo ID.
             */
            if (isMe(p)) movement.onServerState(p.x, p.y, p.lastAckSeq);
            else movement.addSnap(p.id, p.x, p.y, p.direction, p.spriteNum);
        }
        movement.retainPlayers(updatedPlayers.keySet());
        Map<String, GameData.MonsterState> updatedMonsters = new HashMap<>();
        for (GameData.MonsterState m : snapshot.monsters) {
            GameData.MonsterState previous = renderMonsters.get(m.id);
            if (previous != null) m.flashUntil = m.hp < previous.hp ? now + 150 : previous.flashUntil;
            updatedMonsters.put(m.id, m);
        }
        /*
         * Thay tập dữ liệu render sau khi dựng xong; renderer đọc state, không tự quyết damage.
         */
        renderPlayers = updatedPlayers;
        renderMonsters = updatedMonsters;
        Map<String, GameData.ItemState> updatedItems = new HashMap<>();
        for (GameData.ItemState item : snapshot.items) updatedItems.put(item.id, item);
        renderItems = updatedItems;
        repaint();
    }

    /*
     * applyOfflineSnapshot()
     * Chuyển snapshot local đã tách thành các map để vẽ rồi repaint; không có handshake/network hoặc
     * reconciliation online ở hàm này.
     */
    void applyOfflineSnapshot(GameData.WorldSnapshot snapshot) {
        Map<String, GameData.PlayerState> players = new HashMap<>();
        Map<String, GameData.MonsterState> monsters = new HashMap<>();
        for (GameData.PlayerState p : snapshot.players) players.put(p.id, p);
        for (GameData.MonsterState m : snapshot.monsters) monsters.put(m.id, m);
        renderPlayers = players; renderMonsters = monsters;
        Map<String, GameData.ItemState> items = new HashMap<>();
        for (GameData.ItemState item : snapshot.items) items.put(item.id, item);
        renderItems = items;
        repaint();
    }

    /*
     * setVoiceStatus()
     * Lưu status voice, null thành chuỗi rỗng, yêu cầu repaint.
     */
    public void setVoiceStatus(String status) {
        this.voiceStatus = status == null ? "" : status;
        repaint();
    }

    /*
     * setShowScoreboard()
     * Bật/tắt bảng TAB rồi repaint.
     */
    public void setShowScoreboard(boolean show) {
        this.showScoreboard = show;
        repaint();
    }

    /*
     * setCurrentRoomName()
     * Lưu nhãn tên phòng, null thành rỗng. Không tạo/chọn save bằng thao tác setter này.
     */
    public void setCurrentRoomName(String name) {
        this.currentRoomName = name == null ? "" : name;
    }

    /*
     * setBannerMessage()
     * Lưu message banner, thường dùng reconnect; renderer lấy để vẽ.
     */
    public void setBannerMessage(String msg) {
        this.bannerMessage = msg;
    }


}
