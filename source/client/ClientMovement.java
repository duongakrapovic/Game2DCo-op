package source.client;

import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Xử lý chuyển động hiển thị local trước server, sửa prediction bằng ACK và làm mượt người chơi khác.
 * Không sở hữu socket hoặc quyền quyết định thế giới.
 * localPlayer giữ vị trí dự đoán; pendingInputs tối đa 600; playerSnaps tối đa 30 mẫu mỗi ID; nextSeq tăng
 * mỗi step. interpolationDelayMs=100; maxErrorObserved/correctionCount phục vụ chẩn đoán.
 */
public class ClientMovement {

    /*
     * Input không đổi để đối chiếu ACK và replay khi sửa dự đoán.
     */
    public static class PredictionInput {
        /*
         * seq: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
         */
        public final int seq;
        /*
         * left, right, up, down, attack: Trạng thái thao tác đang giữ để bước mô phỏng lấy mẫu.
         */
        public final boolean left, right, up, down, attack;
        /*
         * Khởi tạo PredictionInput
         * Gán seq và các trạng thái phím/attack vào các trường final để một input prediction không bị sửa
         * khi đang chờ ACK.
         */
        public PredictionInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
            this.seq = seq; this.left = left; this.right = right; this.up = up; this.down = down; this.attack = attack;
        }
    }

    /*
     * Mẫu vị trí remote cùng thời gian nhận, dùng nội suy giữa hai mẫu.
     */
    public static class Snap {
        /*
         * timestamp: Thời gian snapshot hoặc mẫu nội suy được tạo; không thay thế sequence.
         */
        public final long timestamp;
        /*
         * x, y: Tọa độ của đối tượng; trong entity là pixel world, còn node A* là tọa độ ô.
         */
        public final float x, y;
        /*
         * direction: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public final String direction;
        /*
         * spriteNum: Hướng, chỉ số sprite và bộ đếm đổi frame hoạt ảnh.
         */
        public final int spriteNum;

        /*
         * Khởi tạo Snap
         * Ghi timestamp nhận, x/y, direction và spriteNum cho một mẫu nội suy bất biến.
         */
        public Snap(long timestamp, float x, float y, String direction, int spriteNum) {
            this.timestamp = timestamp;
            this.x = x; this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
        }
    }

    /*
     * localPlayer: Bản PlayerState client dùng dự đoán vị trí; không là trạng thái server có thẩm quyền.
     */
    private final GameData.PlayerState localPlayer;
    /*
     * mapManager: GameMap cung cấp collision/spawn/draw; server dùng dữ liệu bản đồ không cần dựng GUI.
     */
    private final GameMap mapManager;
    /*
     * pendingInputs: Input dự đoán chưa bị ACK loại; dùng replay, không tự truyền lại UDP bị mất.
     */
    private final Queue<PredictionInput> pendingInputs = new LinkedList<>();
    /*
     * playerSnaps: Lịch sử mẫu vị trí theo ID để nội suy người chơi khác.
     */
    private final Map<String, List<Snap>> playerSnaps = new ConcurrentHashMap<>();

    /*
     * nextSeq: Số thứ tự cho input hoặc frame tùy lớp; phân biệt dữ liệu cũ mới.
     */
    private int nextSeq = 0;
    /*
     * maxErrorObserved: Chỉ số theo dõi độ lệch vị trí và số lần vượt ngưỡng sửa dự đoán.
     */
    private float maxErrorObserved = 0;
    /*
     * correctionCount: Chỉ số theo dõi độ lệch vị trí và số lần vượt ngưỡng sửa dự đoán.
     */
    private int correctionCount = 0;

    /*
     * predictionEnabled: Cấu hình dự đoán local, nội suy remote và khoảng trễ vẽ remote.
     */
    private boolean predictionEnabled = true;
    /*
     * interpolationEnabled: Cấu hình dự đoán local, nội suy remote và khoảng trễ vẽ remote.
     */
    private boolean interpolationEnabled = true;
    /*
     * interpolationDelayMs: Cấu hình dự đoán local, nội suy remote và khoảng trễ vẽ remote.
     */
    private long interpolationDelayMs = 100;

    /*
     * Khởi tạo ClientMovement
     * Lưu GameMap và tạo localPlayer dùng cho prediction tại spawn mặc định.
     */
    public ClientMovement(GameMap mapManager) {
        this.mapManager = mapManager;
        this.localPlayer = new GameData.PlayerState("local", "Me");
    }

    /*
     * step()
     * Sinh input với nextSeq++, nếu prediction bật thì chặn hàng đợi ở 600, thêm pending, chép ý định vào
     * localPlayer rồi chạy GamePhysics một bước chuẩn. Trả input để mạng gửi cùng seq. Không đợi server
     * trước khi local hình di chuyển.
     */
    public synchronized PredictionInput step(boolean l, boolean r, boolean u, boolean d, boolean atk) {
        PredictionInput input = new PredictionInput(nextSeq++, l, r, u, d, atk);
        if (predictionEnabled) {
            if (pendingInputs.size() >= 600) pendingInputs.poll();
            /*
             * Lưu lịch sử dự đoán để replay khi sửa vị trí; đây không phải hàng đợi retransmit UDP.
             */
            pendingInputs.add(input);
            localPlayer.left = l;
            localPlayer.right = r;
            localPlayer.up = u;
            localPlayer.down = d;
            localPlayer.isAttacking = atk;
            GamePhysics.updatePlayer(localPlayer, mapManager);
        }
        return input;
    }

    /*
     * onServerState()
     * Bỏ pending có seq ≤ lastAckSeq, đo khoảng cách với vị trí server. Khi sai số >1,5 pixel thì đặt
     * local về server rồi replay từng pending còn lại và tăng correctionCount. maxError lưu đỉnh quan sát;
     * vì input/tick chưa khớp chặt, replay chưa bảo đảm trùng mọi trạng thái authoritative.
     * Ví dụ pending 10,11,12 và ACK=11: bỏ 10,11; nếu sai số lớn hơn 1.5 pixel thì đặt x/y theo server và
     * replay 12. Code so vị trí dự đoán hiện tại với state server đã về trễ, nên correctionCount tăng
     * không tự chứng minh lỗi physics. Server dùng input mới nhất theo tick, client step theo timer nên
     * chưa bảo đảm đồng nhất tuyệt đối.
     */
    public synchronized void onServerState(float serverX, float serverY, int lastAckSeq) {
        /*
         * ACK cho biết input nào đã được server áp nhận; loại chúng khỏi lịch sử local.
         */
        while (!pendingInputs.isEmpty() && pendingInputs.peek().seq <= lastAckSeq) {
            pendingInputs.poll();
        }

        float err = (float) Math.hypot(localPlayer.x - serverX, localPlayer.y - serverY);
        if (err > maxErrorObserved) maxErrorObserved = err;

        if (err > 1.5f) {
            correctionCount++;
            localPlayer.x = serverX;
            localPlayer.y = serverY;

            /*
             * Sau khi đặt lại vị trí server, replay input chưa ACK để giữ phản hồi điều khiển.
             */
            for (PredictionInput in : pendingInputs) {
                localPlayer.left = in.left;
                localPlayer.right = in.right;
                localPlayer.up = in.up;
                localPlayer.down = in.down;
                localPlayer.isAttacking = in.attack;
                GamePhysics.updatePlayer(localPlayer, mapManager);
            }
        }
    }

    /*
     * addSnap()
     * Thêm mẫu người khác với thời gian nhận System.currentTimeMillis; quá 30 thì bỏ mẫu đầu.
     */
    public synchronized void addSnap(String id, float x, float y, String dir, int sprite) {
        long now = System.currentTimeMillis();
        /*
         * Callback/lambda: Factory computeIfAbsent tạo ArrayList lịch sử khi lần đầu nhận mẫu cho ID.
         */
        List<Snap> list = playerSnaps.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(new Snap(now, x, y, dir, sprite));
        if (list.size() > 30) list.remove(0);
    }

    /*
     * getInterpolatedState()
     * Nếu tắt nội suy trả mẫu mới nhất. Nếu bật, dùng thời điểm hiện tại trừ 100 ms để tìm hai mẫu bao
     * quanh, nội suy x/y theo tỷ lệ thời gian và chọn hướng/frame từ mẫu liên quan. Ngoài vùng mẫu trả
     * đầu/cuối phù hợp; không extrapolate vô hạn.
     */
    public synchronized Snap getInterpolatedState(String id) {
        List<Snap> list = playerSnaps.get(id);
        if (list == null || list.isEmpty()) return null;

        if (!interpolationEnabled) {
            return list.get(list.size() - 1);
        }

        /*
         * Vẽ remote trễ 100 ms để thường có hai mẫu bao quanh; không dự đoán vô hạn khi mất mẫu.
         */
        long renderTime = System.currentTimeMillis() - interpolationDelayMs;

        for (int i = list.size() - 1; i >= 0; i--) {
            Snap s2 = list.get(i);
            if (s2.timestamp <= renderTime && i + 1 < list.size()) {
                Snap s1 = list.get(i + 1);
                long diff = s1.timestamp - s2.timestamp;
                if (diff <= 0) return s2;

                float factor = (float) (renderTime - s2.timestamp) / diff;
                factor = Math.max(0f, Math.min(1f, factor));

                float rx = s2.x + (s1.x - s2.x) * factor;
                float ry = s2.y + (s1.y - s2.y) * factor;
                return new Snap(renderTime, rx, ry, s1.direction, s1.spriteNum);
            }
        }
        return renderTime < list.get(0).timestamp ? list.get(0) : list.get(list.size() - 1);
    }

    /*
     * reset()
     * Xóa pending và lịch sử người khác, respawn localPlayer và đặt nextSeq=0. Không reset mọi chỉ số chẩn
     * đoán được khai báo trong lớp.
     */
    public synchronized void reset() {
        pendingInputs.clear(); playerSnaps.clear();
        localPlayer.respawn(); nextSeq = 0;
    }
    /*
     * retainPlayers()
     * Giữ lịch sử nội suy chỉ cho các ID còn trong snapshot, bỏ người đã rời.
     */
    public synchronized void retainPlayers(Set<String> ids) { playerSnaps.keySet().retainAll(ids); }

    /*
     * x()
     * Trả tọa độ X dự đoán của localPlayer, dùng khi vẽ local/camera.
     */
    public synchronized float x() { return localPlayer.x; }
    /*
     * y()
     * Trả tọa độ Y dự đoán, không phải truy vấn vị trí mới trực tiếp từ server.
     */
    public synchronized float y() { return localPlayer.y; }
    /*
     * pendingCount()
     * Trả số input chưa bị ACK loại khỏi hàng đợi; không đồng nghĩa số gói thật sự còn đang trên mạng.
     */
    public int pendingCount() { return pendingInputs.size(); }
    /*
     * maxError()
     * Trả độ lệch vị trí lớn nhất từng quan sát trong reconciliation, đơn vị pixel.
     */
    public float maxError() { return maxErrorObserved; }
    /*
     * correctionCount()
     * Trả số lần sai số vượt ngưỡng khiến đặt về server và replay.
     */
    public int correctionCount() { return correctionCount; }
    /*
     * isPredictionEnabled()
     * Trả cờ bật prediction; không kiểm UDP đang sẵn hay chưa.
     */
    public boolean isPredictionEnabled() { return predictionEnabled; }
    /*
     * setPredictionEnabled()
     * Đổi cờ prediction. Không tự xóa/rebuild pending, caller cần hiểu tác động khi chuyển giữa phiên.
     */
    public void setPredictionEnabled(boolean enabled) { this.predictionEnabled = enabled; }
    /*
     * isInterpolationEnabled()
     * Trả cờ nội suy người chơi khác.
     */
    public boolean isInterpolationEnabled() { return interpolationEnabled; }
    /*
     * setInterpolationEnabled()
     * Bật/tắt nội suy; khi tắt, getInterpolatedState lấy mẫu mới nhất.
     */
    public void setInterpolationEnabled(boolean enabled) { this.interpolationEnabled = enabled; }
    /*
     * getInterpolationDelayMs()
     * Trả độ lùi thời gian vẽ 100 ms đang cấu hình, khác RTT thật.
     */
    public long getInterpolationDelayMs() { return interpolationDelayMs; }
}