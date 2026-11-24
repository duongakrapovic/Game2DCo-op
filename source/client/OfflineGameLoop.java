package source.client;

import source.common.*;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.swing.SwingUtilities;

/** Owns the offline simulation; Swing sees detached snapshots only. */
/*
 * File Java dành riêng mô phỏng local. Vẫn được giải thích để danh mục source đầy đủ, nhưng không nằm
 * trong sơ đồ/chuỗi chạy online ở cuối. Không mở TCP/UDP server để chạy một người.
 * Thread/running/generation và stateLock quản lý loop; player/monsters/itemSystem là dữ liệu local.
 * Snapshot copy đưa sang UI. queuedAttack giữ cạnh bấm chưa xử lý.
 */
public final class OfflineGameLoop implements Runnable {
    /*
     * panel: Player local, panel nhận snapshot, tên local hoặc khóa bảo vệ trạng thái mô phỏng offline.
     */
    private final ClientGame panel;
    /*
     * random: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private final Random random = new Random();
    /*
     * stateLock: Player local, panel nhận snapshot, tên local hoặc khóa bảo vệ trạng thái mô phỏng
     * offline.
     */
    private final Object stateLock = new Object();
    /*
     * monsters: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
     * xuống JSON.
     */
    private final List<GameData.MonsterState> monsters = new ArrayList<>();
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile boolean running;
    /*
     * generation: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile long generation;
    /*
     * thread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy lớp.
     */
    private Thread thread;
    /*
     * player: Player local, panel nhận snapshot, tên local hoặc khóa bảo vệ trạng thái mô phỏng offline.
     */
    private GameData.PlayerState player;
    /*
     * name: Tên hiển thị hoặc tên local; đọc theo lớp sở hữu, không phải định danh xác thực.
     */
    private String name;
    /*
     * previousAttack: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn nhanh bị
     * bỏ qua.
     */
    private boolean previousAttack;
    /*
     * queuedAttack: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn nhanh bị bỏ
     * qua.
     */
    private boolean queuedAttack;
    /*
     * itemSystem: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private ItemSystem itemSystem;

    /*
     * Khởi tạo OfflineGameLoop
     * Giữ panel được truyền để đọc input, map và áp ảnh chụp; chưa start mô phỏng.
     */
    public OfflineGameLoop(ClientGame panel) { this.panel = panel; }
    /*
     * isRunning()
     * Trả cờ đang chạy để UI/timer chọn chế độ phù hợp.
     */
    public boolean isRunning() { return running; }

    /*
     * startOfflineMode()
     * Dừng vòng cũ, kiểm map/spawn, tạo LOCAL_1 và nạp save local; dựng lại quái/item, reset trigger, tăng
     * generation và start thread. Hàm thuộc local, không phải CREATE_ROOM online.
     */
    public void startOfflineMode(String playerName) {
        stopOfflineMode();
        panel.mapManager.ensureCollisionMapLoaded();
        panel.mapManager.validatedPlayerSpawn(Common.SPAWN_X, Common.SPAWN_Y);
        synchronized (stateLock) {
            name = playerName;
            player = new GameData.PlayerState("LOCAL_1", playerName);
            SaveManager.OfflineSaveData save = SaveManager.loadOfflineSave(panel.mapManager);
            if (save != null) {
                if (save.x > 0 || save.y > 0) { player.x = save.x; player.y = save.y; }
                if (save.hp > 0) { player.hp = save.hp; player.maxHp = save.maxHp; }
            }
            monsters.clear();
            for (int i = 1; i <= 15; i++) {
                Point point = getRandomMapPoint();
                if (point != null) monsters.add(new GameData.MonsterState("M" + i, point.x, point.y));
            }
            itemSystem = new ItemSystem(panel.mapManager, random);
            itemSystem.initialize(this::getRandomMapPoint);
            previousAttack = false;
            queuedAttack = false;
            running = true;
            generation++;
            thread = new Thread(this, "Offline-Loop");
            thread.start();
        }
    }

    /*
     * stopOfflineMode()
     * Tắt running/tăng generation, interrupt và join loop cũ tối đa hai giây rồi lưu tiến trình local. Bảo
     * vệ callback cũ không áp vào lượt sau.
     */
    public void stopOfflineMode() {
        running = false;
        generation++;
        Thread old = thread;
        if (old != null) {
            old.interrupt();
            if (Thread.currentThread() != old) {
                try { old.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            thread = null;
        }
        saveOfflineProgress();
    }

    /*
     * saveOfflineProgress()
     * Dưới stateLock, nếu có player thì gọi SaveManager.saveOffline với tên/vị trí/HP. Không ghi
     * online_Room_1.json.
     */
    public void saveOfflineProgress() {
        synchronized (stateLock) {
            if (player != null) SaveManager.saveOffline(name, player.x, player.y, player.hp, player.maxHp);
        }
    }

    /*
     * getRandomMapPoint()
     * Gọi SpawnSystem.find bằng entity local để tránh điểm trùng/không đi được.
     */
    private Point getRandomMapPoint() {
        return SpawnSystem.find(panel.mapManager, random, monsters, player == null ? List.of() : List.of(player));
    }

    /*
     * run()
     * Loop local cập nhật input/đánh/vật lý/AI/hồi sinh/item và lưu mỗi 900 tick, chụp bản copy rồi
     * invokeLater áp snapshot nếu generation vẫn đúng. Không gửi STATE ra mạng.
     * Đây là thân hàm local vẫn phải hiểu vì nằm trong Java ứng dụng; không phải đường chạy online. Thread
     * tự mô phỏng combat/physics/AI/item rồi copy snapshot để EDT nhận. Epoch generation chặn snapshot cũ
     * sau stop/start; kết thúc vẫn qua save local riêng.
     */
    @Override public void run() {
        long next = System.nanoTime(), epoch = generation;
        int saveTicks = 0;
        while (running && epoch == generation && !Thread.currentThread().isInterrupted()) {
            GameData.WorldSnapshot view;
            synchronized (stateLock) {
                if (!running || epoch != generation) break;
                player.left = panel.left; player.right = panel.right;
                player.up = panel.up; player.down = panel.down;
                boolean attack = panel.attack;
                queuedAttack |= panel.consumeAttackPress() || (attack && !previousAttack);
                if (queuedAttack && !player.isAttacking) {
                    queuedAttack = false;
                    /*
                     * Callback/lambda: Supplier trả 180 tick hồi sinh cho luật combat local.
                     */
                    CombatSystem.resolveAttack(player, monsters, panel.mapManager, () -> 180, null, 0, itemSystem::onMonsterKilled);
                }
                previousAttack = attack;
                GamePhysics.updatePlayer(player, panel.mapManager);
                for (GameData.MonsterState monster : monsters) {
                    if (monster.isDead) {
                        if (--monster.respawnTimer <= 0) {
                            Point point = getRandomMapPoint();
                            if (point == null) { monster.respawnTimer = 60; continue; }
                            monster.x = point.x; monster.y = point.y;
                            monster.hp = monster.maxHp; monster.isDead = false;
                            monster.flashUntil = 0; monster.nextPathAt = 0;
                            monster.currentPath = new int[0][2]; monster.pathIndex = 0;
                        }
                    } else MonsterAI.updateMonster(monster, player, panel.mapManager);
                }
                if (player.hp <= 0) player.respawn();
                itemSystem.update(List.of(player), this::getRandomMapPoint);
                if (++saveTicks >= 900) { saveTicks = 0; saveOfflineProgress(); }
                GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
                snapshot.players.add(player); snapshot.monsters.addAll(monsters);
                snapshot.items.addAll(itemSystem.items());
                /*
                 * Copy dữ liệu trước khi đưa sang EDT để UI không đọc entity đang được thread local sửa.
                 */
                view = GameProtocol.copySnapshot(snapshot);
            }
            /*
             * Callback/lambda: Runnable đưa snapshot local copy sang EDT; chỉ áp khi running và epoch
             * generation vẫn thuộc vòng hiện hành.
             */
            SwingUtilities.invokeLater(() -> {
                /*
                 * Chặn snapshot còn trong hàng đợi của vòng local đã dừng hoặc được khởi động lại.
                 */
                if (running && epoch == generation) panel.applyOfflineSnapshot(view);
            });
            next += Common.TICK_NS;
            long sleep = next - System.nanoTime();
            if (sleep <= 0) { next = System.nanoTime(); continue; }
            try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
