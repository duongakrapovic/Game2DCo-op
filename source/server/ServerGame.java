package source.server;

import source.common.Common;
import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;
import source.common.GameProtocol;
import source.common.CombatSystem;
import source.common.MonsterAI;
import source.common.ItemSystem;
import source.common.SpawnSystem;
import java.util.concurrent.atomic.AtomicReference;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Một thế giới online đang sống, chạy loop riêng và giữ quyền quyết định gameplay. Tất cả player/quái/item
 * của một phòng được cập nhật tại đây; ServerNetwork chỉ vận chuyển input và snapshot.
 * players/monsters/handlers, lastAcks, inputBuffers AtomicReference và cờ attack giữ dữ liệu runtime.
 * RoomSaveManager lo slot; ItemSystem lo bình; MonsterHistory lo rewind. streamId định danh một đời phòng;
 * baseline/stateSequence phục vụ delta. Nhiều thao tác synchronized dùng cùng monitor phòng.
 */
public class ServerGame implements Runnable {

    /*
     * MONSTER_COUNT: Số quái mục tiêu khi khởi tạo phòng.
     */
    private static final int MONSTER_COUNT = 15;
    /*
     * RESPAWN_MIN_TICKS: Giới hạn dưới số tick quái chờ hồi sinh.
     */
    private static final int RESPAWN_MIN_TICKS = 180;
    /*
     * RESPAWN_MAX_TICKS: Giới hạn trên số tick quái chờ hồi sinh.
     */
    private static final int RESPAWN_MAX_TICKS = 300;

    /*
     * roomId: Định danh hoặc tên phòng; không phải IP server hay token phiên.
     */
    private final String roomId;
    /*
     * serverNetwork: Tham chiếu thành phần điều phối để chuyển việc tới tầng ứng dụng hoặc mạng.
     */
    private final ServerNetwork serverNetwork;
    /*
     * players: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
     * xuống JSON.
     */
    private final Map<String, GameData.PlayerState> players = new ConcurrentHashMap<>();
    /*
     * monsters: Tập hợp trạng thái entity đang được lớp sở hữu quản lý; không phải tất cả đều được lưu
     * xuống JSON.
     */
    private final Map<String, GameData.MonsterState> monsters = new ConcurrentHashMap<>();
    /*
     * clientHandlers: Handler TCP hiện gắn với phiên hoặc ánh xạ player tới handler.
     */
    private final Map<String, ServerNetwork.ClientHandler> clientHandlers = new ConcurrentHashMap<>();
    /*
     * lastAcks: Sequence input cao nhất server đã áp nhận cho player; phục vụ loại pending ở client.
     */
    private final Map<String, Integer> lastAcks = new ConcurrentHashMap<>();
    /*
     * attackInputs: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn nhanh bị bỏ
     * qua.
     */
    private final Map<String, Boolean> attackInputs = new ConcurrentHashMap<>();
    /*
     * previousAttackInputs: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn
     * nhanh bị bỏ qua.
     */
    private final Map<String, Boolean> previousAttackInputs = new ConcurrentHashMap<>();

    /*
     * mapManager: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    private final GameMap mapManager;
    /*
     * random: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private final Random random = new Random();
    /*
     * itemSystem: Nguồn ngẫu nhiên, bộ đếm ID item, lượng hồi máu, cờ đã nhặt hoặc hệ thống vật phẩm.
     */
    private final ItemSystem itemSystem;
    /*
     * pendingAttackTriggers: Trạng thái giữ hoặc cạnh nhấn đánh đang chờ; tránh một số trường hợp nhấn
     * nhanh bị bỏ qua.
     */
    private final Map<String, Boolean> pendingAttackTriggers = new ConcurrentHashMap<>();
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private volatile boolean running = true;
    /*
     * loopThread: Thread thực hiện accept, dispatch, nhận UDP, heartbeat hoặc vòng mô phỏng tùy lớp.
     */
    private final Thread loopThread;
    /*
     * saves: RoomSaveManager quản lý slot tiến trình của phòng.
     */
    private final RoomSaveManager saves;
    /*
     * inputBuffers: Mỗi player có AtomicReference giữ input mới nhất; không là FIFO chứa mọi packet.
     */
    private final Map<String, AtomicReference<GameData.PlayerInput>> inputBuffers = new ConcurrentHashMap<>();
    /*
     * history: Lịch sử tick và tọa độ quái dùng query hitbox lùi thời gian.
     */
    private final CombatSystem.MonsterHistory history = new CombatSystem.MonsterHistory();
    /*
     * tick: Tick mô phỏng hoặc bộ đếm tới đợt autosave.
     */
    private long tick;
    /*
     * forceFullState: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
     */
    private boolean forceFullState = true;
    /*
     * baseline: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
     */
    private GameData.WorldSnapshot baseline;
    /*
     * baselineSequence: Bản full làm mốc delta, sequence của bản mốc và cờ ép phát full mới.
     */
    private long baselineSequence;
    /*
     * streamId: ID nguồn snapshot của phòng và cờ decoder đã khóa stream.
     */
    private final long streamId = java.util.UUID.randomUUID().getMostSignificantBits();
    /*
     * stateSequence: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
     */
    private long stateSequence = 0;
    /*
     * saveTickCounter: Tick mô phỏng hoặc bộ đếm tới đợt autosave.
     */
    private int saveTickCounter = 0;

    /*
     * Khởi tạo ServerGame
     * Constructor chỉ có roomId gọi bản đầy đủ với network=null. Vẫn khởi tạo và chạy mô phỏng; khi không
     * có network thì broadcastUdpState không thực hiện gửi.
     */
    public ServerGame(String roomId) {
        this(roomId, null);
    }

    /*
     * Khởi tạo ServerGame
     * Lưu tên/network, nạp collision và kiểm spawn, tạo RoomSaveManager/ItemSystem, thử sinh 15 quái và
     * tám bình rồi start RoomLoop. Nếu thiếu điểm hợp lệ có thể ít entity hơn. Constructor có tác động mở
     * thread và đọc/tạo save qua các lớp phụ trách.
     */
    public ServerGame(String roomId, ServerNetwork serverNetwork) {
        this.roomId = roomId;
        this.serverNetwork = serverNetwork;
        this.mapManager = new GameMap();
        mapManager.ensureCollisionMapLoaded();
        mapManager.validatedPlayerSpawn(Common.SPAWN_X, Common.SPAWN_Y);
        this.saves = new RoomSaveManager(roomId, mapManager);
        this.itemSystem = new ItemSystem(mapManager, random);

        for (int i = 1; i <= MONSTER_COUNT; i++) {
            Point spawn = findRandomMapPoint();
            if (spawn != null) monsters.put("M" + i, new GameData.MonsterState("M" + i, spawn.x, spawn.y));
        }

        itemSystem.initialize(this::findRandomMapPoint);
        loopThread = new Thread(this, "RoomLoop-" + roomId);
        loopThread.start();
    }

    /*
     * getRoomId()
     * Trả tên phòng dùng trong lobby và tra rooms, không phải streamId.
     */
    public String getRoomId() { return roomId; }
    /*
     * getStreamId()
     * Trả long ngẫu nhiên cố định cho instance ServerGame; client dùng loại snapshot của phòng/lần tạo
     * trước.
     */
    public long getStreamId() { return streamId; }
    /*
     * requestFullState()
     * Đặt forceFullState=true dưới khóa phòng để lần broadcast tới phát full; không gửi ngay tại thời điểm
     * gọi.
     */
    public synchronized void requestFullState() { forceFullState = true; }
    /*
     * getPlayers()
     * Trả map player runtime đang giữ, không phải bản sao sâu hoặc danh sách slot JSON.
     */
    public Map<String, GameData.PlayerState> getPlayers() { return players; }
    /*
     * getPlayerCount()
     * Trả players.size(), bao gồm player có thể đang được giữ chỗ chờ RESUME.
     */
    public int getPlayerCount() { return players.size(); }
    /*
     * isFull()
     * So số player runtime với MAX_PLAYERS_PER_ROOM=4; chưa kiểm bốn slot lịch sử trong RoomSaveManager.
     */
    public boolean isFull() { return players.size() >= Common.MAX_PLAYERS_PER_ROOM; }
    /*
     * isEmpty()
     * Trả map player có rỗng không để app quyết định dừng phòng.
     */
    public boolean isEmpty() { return players.isEmpty(); }

    /*
     * addPlayer()
     * Kiểm phòng còn chạy, ID chưa có và số người chưa đầy; gọi saves.join để nạp/nhận slot rồi thêm
     * player, buffer, ACK=-1 và cờ attack. Có handler thì gắn vào bảng. Yêu cầu full snapshot; lỗi save
     * admission có thể ném exception thay vì trả false.
     * Chặn phòng dừng, ID đã có và sức chứa runtime. Gọi saves.join trước khi đưa player vào maps runtime
     * để lỗi admission không để lại player nửa chừng. Thành công mới tạo input buffer, ACK=-1 và các cờ
     * đánh, rồi force full state.
     */
    public synchronized boolean addPlayer(GameData.PlayerState player, ServerNetwork.ClientHandler handler) {
        if (!running) throw new IllegalStateException("Phong da dung. Hay lam moi danh sach phong.");
        if (players.containsKey(player.id)) throw new IllegalStateException("Ban da o trong phong nay.");
        if (isFull()) return false;
        saves.join(player);
        players.put(player.id, player);
        inputBuffers.put(player.id, new AtomicReference<>());
        lastAcks.put(player.id, -1);
        attackInputs.put(player.id, false);
        previousAttackInputs.put(player.id, false);
        if (handler != null) clientHandlers.put(player.id, handler);
        forceFullState = true;
        return true;
    }

    /*
     * updatePlayerHandler()
     * Khi RESUME, kiểm player còn, chuẩn hóa vị trí, thay handler, đánh dấu slot attach và yêu cầu full.
     * Giữ player/tiến trình runtime, không tạo player thứ hai.
     */
    public synchronized void updatePlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        if (handler != null && players.containsKey(playerId)) {
            GameData.PlayerState player = players.get(playerId);
            java.awt.geom.Point2D.Float spawn = mapManager.validatedPlayerSpawn(player.x, player.y);
            player.x = spawn.x; player.y = spawn.y;
            clientHandlers.put(playerId, handler);
            saves.attach(playerId);
            forceFullState = true;
        }
    }

    /*
     * detachPlayerHandler()
     * Chỉ tháo khi handler truyền vào trùng owner hiện tại. Xóa input/cạnh attack, nhả phím và đánh dấu
     * slot DISCONNECTED nhưng giữ quyền ID trong grace. Không loại player khỏi map ngay.
     */
    public synchronized void detachPlayerHandler(String playerId, ServerNetwork.ClientHandler handler) {
        if (!clientHandlers.remove(playerId, handler)) return;
        GameData.PlayerState player = players.get(playerId);
        if (player == null) return;
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(playerId);
        if (buffer != null) buffer.set(null);
        player.left = player.right = player.up = player.down = false;
        attackInputs.put(playerId, false);
        previousAttackInputs.put(playerId, false);
        pendingAttackTriggers.remove(playerId);
        saves.detach(player, false);
    }

    /*
     * removePlayer()
     * Loại player và mọi buffer/ACK/attack/handler tương ứng; detach save với remove=true để giải phóng
     * liên kết ID runtime nhưng giữ tên/tiến trình slot. Đặt forceFullState để client thấy player biến
     * mất.
     */
    public synchronized void removePlayer(String playerId) {
        GameData.PlayerState player = players.remove(playerId);
        clientHandlers.remove(playerId);
        inputBuffers.remove(playerId);
        lastAcks.remove(playerId);
        attackInputs.remove(playerId);
        previousAttackInputs.remove(playerId);
        pendingAttackTriggers.remove(playerId);
        if (player != null) saves.detach(player, true);
        forceFullState = true;
    }

    /*
     * bufferPlayerInput()
     * Chỉ nhận nếu có buffer và handler đang hoạt động. Bỏ sequence không mới hơn ACK hoặc pending, giữ
     * cạnh attack vào pendingAttackTriggers rồi thay input mới nhất. Không chạy physics trên worker UDP.
     * Tra buffer và handler của player; bỏ input có seq không mới hơn ACK hoặc buffer hiện tại. Giữ lại
     * cạnh đánh trước khi thay input mới nhất. Network worker không chạy thêm physics cho mỗi packet;
     * RoomLoop áp input trong nhịp mô phỏng riêng.
     */
    public synchronized void bufferPlayerInput(String playerId, GameData.PlayerInput input) {
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(playerId);
        if (buffer == null || !clientHandlers.containsKey(playerId)) return;
        GameData.PlayerInput previous = buffer.get();
        /*
         * Bỏ input đã ACK hoặc tới muộn hơn input mới nhất đang đợi áp.
         */
        if (input.seq <= lastAcks.getOrDefault(playerId, -1)
                || (previous != null && input.seq <= previous.seq)) return;
        if (input.attack && (previous == null || !previous.attack)) pendingAttackTriggers.put(playerId, true);
        buffer.set(input);
    }

    /*
     * applyBufferedInput()
     * Lấy input đang giữ, chép phím và ping vào player, cập nhật ACK và trạng thái attack. Không xóa
     * reference sau mỗi tick nên ý định được giữ tới input mới hoặc disconnect.
     */
    private void applyBufferedInput(GameData.PlayerState player) {
        AtomicReference<GameData.PlayerInput> buffer = inputBuffers.get(player.id);
        GameData.PlayerInput input = buffer == null ? null : buffer.get();
        if (input == null) return;
        player.left = input.left; player.right = input.right;
        player.up = input.up; player.down = input.down;
        player.pingMs = input.pingMs;
        updateAck(player.id, input.seq);
        updateAttackInput(player.id, input.attack);
    }

    /*
     * updateAck()
     * Tăng lastAcks theo sequence không lùi khi player còn tồn tại. ACK là mốc input của mô hình buffer,
     * không chứng minh mỗi input đã được mô phỏng một tick riêng.
     */
    public void updateAck(String playerId, int sequence) {
        if (players.containsKey(playerId)) {
            /*
             * Callback/lambda: Hàm remap của computeIfPresent chỉ tăng ACK hoặc giữ giá trị cũ; không cho
             * ACK lùi.
             */
            lastAcks.computeIfPresent(playerId, (ignored, previous) ->
                    sequence >= previous ? sequence : previous);
        }
    }

    /*
     * updateAttackInput()
     * Đặt boolean attack cho player còn trong phòng; updatePlayerAttack xử lý cạnh và animation sau đó.
     */
    public void updateAttackInput(String playerId, boolean pressed) {
        if (players.containsKey(playerId)) {
            attackInputs.put(playerId, pressed);
        }
    }

    /*
     * stop()
     * Đặt running=false, interrupt loop, chờ tối đa hai giây nếu caller không phải loop rồi sync/save.
     * Không xóa file JSON.
     */
    public void stop() {
        synchronized (this) { running = false; }
        loopThread.interrupt();
        if (Thread.currentThread() != loopThread) {
            try { loopThread.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        syncAndSaveOnline();
    }

    /*
     * run()
     * Loop mục tiêu 60 tick/s. Dưới khóa phòng: ghi lịch sử quái → áp input/đánh/physics/hồi sinh player →
     * cập nhật quái → item → broadcast → tăng tick → save mỗi 900 tick. Sleep tới mốc kế tiếp; khi trễ đặt
     * lại mốc, không chạy vô hạn để bù mọi tick. I/O trong vùng khóa có thể ảnh hưởng nhịp.
     * Một tick giữ khóa phòng: ghi history → áp input mới nhất → xử lý cạnh đánh → physics/hồi sinh player
     * → AI/hồi sinh quái → item pickup/respawn → broadcast snapshot → tăng tick và autosave mỗi 900 tick.
     * Sau khi nhả khóa, ngủ theo TICK_NS nếu còn ngân sách; trễ thì đặt lại mốc, không bảo đảm luôn đạt 60
     * Hz.
     */
    @Override
    public void run() {
        long nextTick = System.nanoTime();
        while (running) {
            synchronized (this) {
                if (!running) break;
                /*
                 * Ghi vị trí quái trước mô phỏng để đòn đánh có thể query lịch sử khi bù trễ.
                 */
                history.record(monsters.values(), tick);
                for (GameData.PlayerState player : players.values()) {
                    /*
                     * Lấy trạng thái input mới nhất; không chạy một bước physics riêng cho mỗi packet UDP.
                     */
                    applyBufferedInput(player);
                    /*
                     * Xử lý cạnh nhấn đánh đang chờ trước khi tăng hoạt ảnh/di chuyển.
                     */
                    updatePlayerAttack(player);
                    GamePhysics.updatePlayer(player, mapManager);
                    if (player.hp <= 0) player.respawn();
                }
                /*
                 * Sau player mới cập nhật AI, hồi sinh quái và luật nhặt/hồi sinh bình.
                 */
                updateMonsters();
                itemSystem.update(players.values(), this::findRandomMapPoint);
                /*
                 * Chụp kết quả tick có thẩm quyền để gửi các client, rồi tăng số tick và xét autosave.
                 */
                broadcastUdpState();
                tick++;
                if (++saveTickCounter >= 900) {
                    saveTickCounter = 0;
                    syncAndSaveOnline();
                }
            }

            /*
             * Ngủ phần thời gian còn lại; nếu xử lý quá chậm thì đặt lại mốc, không bảo đảm luôn đạt 60
             * Hz.
             */
            nextTick += Common.TICK_NS;
            long sleepNs = nextTick - System.nanoTime();
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    if (!running) {
                        break;
                    }
                }
            } else {
                nextTick = System.nanoTime();
            }
        }
    }

    /*
     * updatePlayerAttack()
     * Ghi cạnh nhấn mới, và khi có pending trigger mà player không đang đánh thì gọi CombatSystem với lịch
     * sử/quy tắc respawn/callback rơi item. Giữ trạng thái nút trước để giữ nút không tự sinh đòn mỗi
     * tick.
     */
    private void updatePlayerAttack(GameData.PlayerState player) {
        boolean pressed = attackInputs.getOrDefault(player.id, false);
        if (pressed && !previousAttackInputs.getOrDefault(player.id, false)) pendingAttackTriggers.put(player.id, true);
        if (pendingAttackTriggers.getOrDefault(player.id, false) && !player.isAttacking) {
            pendingAttackTriggers.remove(player.id);
            CombatSystem.resolveAttack(player, monsters.values(), mapManager,
                    this::randomRespawnTicks, history, tick, itemSystem::onMonsterKilled);
        }
        previousAttackInputs.put(player.id, pressed);
    }

    /*
     * updateMonsters()
     * Quái chết đếm respawn, tìm điểm mới; null thì đợi thêm 60 tick. Hồi sinh reset
     * HP/path/flash/history. Quái sống tìm player gần nhất rồi giao MonsterAI; không tự để client quyết
     * định quái.
     */
    private void updateMonsters() {
        for (GameData.MonsterState monster : monsters.values()) {
            if (monster.isDead) {
                if (--monster.respawnTimer <= 0) {
                    Point spawn = findRandomMapPoint();
                    if (spawn == null) { monster.respawnTimer = 60; continue; }
                    history.clear(monster.id);
                    monster.x = spawn.x;
                    monster.y = spawn.y;
                    monster.hp = monster.maxHp;
                    monster.isDead = false;
                    monster.flashUntil = 0;
                    monster.nextPathAt = 0;
                    monster.currentPath = new int[0][2];
                    monster.pathIndex = 0;
                }
                continue;
            }

            GameData.PlayerState target = findNearestPlayer(monster);
            if (target != null) {
                MonsterAI.updateMonster(monster, target, mapManager);
            }
        }
    }

    /*
     * findNearestPlayer()
     * Duyệt player trong phòng, tính khoảng cách Euclid tới quái và trả người gần nhất hoặc null nếu không
     * có. Là bước chọn mục tiêu, khác thuật toán tìm đường A*.
     */
    private GameData.PlayerState findNearestPlayer(GameData.MonsterState monster) {
        GameData.PlayerState nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (GameData.PlayerState player : players.values()) {
            double distance = Math.hypot(player.x - monster.x, player.y - monster.y);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /*
     * findRandomMapPoint()
     * Gọi SpawnSystem.find với map, Random, quái và player hiện tại; trả điểm hoặc null.
     */
    private Point findRandomMapPoint() {
        return SpawnSystem.find(mapManager, random, monsters.values(), players.values());
    }

    /*
     * randomRespawnTicks()
     * Trả số nguyên ngẫu nhiên từ 180 tới 300, gồm cả hai đầu; danh nghĩa ba đến năm giây ở 60 tick/s.
     */
    private int randomRespawnTicks() {
        return RESPAWN_MIN_TICKS
                + random.nextInt(RESPAWN_MAX_TICKS - RESPAWN_MIN_TICKS + 1);
    }

    /*
     * broadcastUdpState()
     * Nếu có người và network, gom snapshot, gắn ACK cho từng player, tăng stateSequence. Chọn full khi
     * force/baseline chưa có hoặc seq chia hết 60; full chụp baseline sâu. Encode danh sách packet binary
     * và gửi từng mảnh tới endpoint của mọi player. Không gửi STATE dạng dòng TCP.
     * Gom player có lastAckSeq, quái và item; tăng stateSequence. Full khi bị force, chưa có baseline hoặc
     * sequence chia hết cho 60; full được copy sâu làm baseline. Mọi client trong phòng nhận các mảnh
     * state qua endpoint đã đăng ký, không gửi STATE thường kỳ qua TCP.
     */
    private void broadcastUdpState() {
        if (players.isEmpty() || serverNetwork == null) return;
        GameData.WorldSnapshot snapshot = new GameData.WorldSnapshot();
        for (GameData.PlayerState player : players.values()) {
            player.lastAckSeq = lastAcks.getOrDefault(player.id, -1);
            snapshot.players.add(player);
        }
        snapshot.monsters.addAll(monsters.values());
        snapshot.items.addAll(itemSystem.items());
        stateSequence++;
        /*
         * Ép full sau thay đổi phòng và định kỳ mỗi 60 state; delta luôn dựa trên full baseline.
         */
        boolean full = forceFullState || baseline == null || stateSequence % 60 == 0;
        if (full) baselineSequence = stateSequence;
        java.util.List<byte[]> packets = GameProtocol.serializeSnapshotBinary(
                stateSequence, streamId, baselineSequence, snapshot, full ? null : baseline);
        if (full) {
            /*
             * Copy sâu bản mốc, tránh entity tiếp tục đổi làm hỏng delta về sau.
             */
            baseline = GameProtocol.copySnapshot(snapshot);
            forceFullState = false;
        }
        for (String playerId : players.keySet()) {
            for (byte[] packet : packets) serverNetwork.sendGameUdpPacket(playerId, packet);
        }
    }

    /*
     * syncAndSaveOnline()
     * Wrapper synchronized gọi RoomSaveManager.syncAndSaveOnline từ các player runtime. Autosave hoặc stop
     * gọi hàm này; việc lưu trường nào do DTO quyết định.
     */
    private synchronized void syncAndSaveOnline() {
        saves.syncAndSaveOnline(players.values());
    }
}
