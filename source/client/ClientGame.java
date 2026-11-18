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

public class ClientGame extends JPanel {
    private final ClientRenderer renderer = new ClientRenderer();
    private final OfflineGameLoop offline = new OfflineGameLoop(this);
    private final javax.swing.Timer renderTimer = new javax.swing.Timer(1000 / Common.TICK_RATE, event -> {
        if (isShowing() && !offline.isRunning() && this.clientAppRef != null) this.clientAppRef.sendFrameInput();
        repaint();
    });

    public boolean isOfflineRunning() { return offline.isRunning(); }
    public void startOfflineMode(String name) { this.myName = name; myId = "LOCAL_1"; resetInputState(); movement.reset(); offline.startOfflineMode(name); }
    public void stopOfflineMode() { offline.stopOfflineMode(); }
    public void saveOfflineProgress() { offline.saveOfflineProgress(); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D copy = (Graphics2D) g.create();
        try { renderer.render(copy, this); } finally { copy.dispose(); }
    }
    @Override public void addNotify() { super.addNotify(); renderTimer.start(); }
    @Override public void removeNotify() { renderTimer.stop(); super.removeNotify(); }


    volatile Map<String, GameData.PlayerState> renderPlayers = new ConcurrentHashMap<>();
    volatile Map<String, GameData.MonsterState> renderMonsters = new ConcurrentHashMap<>();
    volatile Map<String, GameData.ItemState> renderItems = new HashMap<>();

    final GameMap mapManager;
    final ClientMovement movement;

    String myName = "Player";
    private String myId = "";
    private final java.util.concurrent.atomic.AtomicBoolean attackPending = new java.util.concurrent.atomic.AtomicBoolean();
    public void setMyId(String id) { myId = id == null ? "" : id; }
    public boolean isMe(GameData.PlayerState player) {
        return !myId.isEmpty() ? myId.equals(player.id) : java.util.Objects.equals(player.name, myName);
    }
    public void setAttackPressed(boolean pressed) {
        if (pressed && !attack) attackPending.set(true);
        attack = pressed;
    }
    public boolean consumeAttackPress() { return attackPending.getAndSet(false); }
    public void resetInputState() {
        left = right = up = down = attack = false;
        attackPending.set(false);
    }

    public volatile boolean left = false, right = false, up = false, down = false, attack = false;
    private ClientApp clientAppRef = null;

    volatile boolean showScoreboard = false;
    String currentRoomName = "";
    String bannerMessage = null;
    String voiceStatus = "";

    public ClientGame(GameMap mapManager, ClientMovement movement) {
        this.mapManager = mapManager;
        this.movement = movement;
        setFocusable(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(true);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    triggerAttack(false);
                }
            }
        });
    }

    public void setClientAppRef(ClientApp app) {
        this.clientAppRef = app;
    }

    private void triggerAttack(boolean isPressed) {
        setAttackPressed(isPressed);
        if (clientAppRef != null) {
            clientAppRef.sendAttackInput(isPressed);
        }
    }







    public void updateOnlineWorld(String stateLine, String myCurrentName) {
        GameData.WorldSnapshot snapshot = GameProtocol.parseSnapshot(stateLine);
        SwingUtilities.invokeLater(() -> applySnapshot(snapshot, myCurrentName));
    }

    public void applySnapshot(GameData.WorldSnapshot snapshot, String myCurrentName) {
        if (!SwingUtilities.isEventDispatchThread()) {
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
        renderPlayers = updatedPlayers;
        renderMonsters = updatedMonsters;
        Map<String, GameData.ItemState> updatedItems = new HashMap<>();
        for (GameData.ItemState item : snapshot.items) updatedItems.put(item.id, item);
        renderItems = updatedItems;
        repaint();
    }

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

    public void setVoiceStatus(String status) {
        this.voiceStatus = status == null ? "" : status;
        repaint();
    }

    public void setShowScoreboard(boolean show) {
        this.showScoreboard = show;
        repaint();
    }

    public void setCurrentRoomName(String name) {
        this.currentRoomName = name == null ? "" : name;
    }

    public void setBannerMessage(String msg) {
        this.bannerMessage = msg;
    }


}
