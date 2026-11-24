# Module Client

Swing Menu/Lobby/Game, online kết nối dedicated server, offline mô phỏng local bằng common. Cập nhật 19/11/2026 theo code 7fd270b.

## Chạy

Từ gốc chạy `run-client.bat` hoặc `.\run-client.bat` trong PowerShell. Cần JDK 17+. `--compile-only` chỉ build. Output riêng `.runtime/client-...`, không ghi class vào source; loại RegressionChecks khỏi build sản xuất.

Offline không cần server. Online nhập IP máy đã bật server; localhost là chính máy client. Cần đường truy cập server và TCP/UDP 8888, không bắt buộc LAN ảo nếu mạng đã thông.

| Class | Trách nhiệm |
|---|---|
| ClientApp | JFrame/CardLayout, menu/lobby, phím và vòng đời phòng |
| ClientGame | Input, timer client, áp snapshot, phối hợp hiển thị |
| ClientNetwork | TCP phiên/phòng/RESUME, UDP GAM1 đăng ký/input/state và RTT/jitter |
| ClientMovement | Prediction/pending/reconciliation theo ACK, nội suy người khác trễ khoảng 100 ms |
| ClientRenderer | Tải ảnh thật, vẽ map/entity/bình máu/HUD/voice |
| ClientVoiceChat | PCM mono 16 kHz/16 bit qua UDP VCH1, thu/phát/mute |
| OfflineGameLoop | Thread offline dùng common, copy snapshot sang EDT và save |
| ScoreboardOverlay | Bảng người chơi khi giữ TAB |

Network I/O và mô phỏng offline ngoài EDT. Timer client 16 ms danh nghĩa không đồng nhịp tuyệt đối với tick server. Prediction không trao quyền quyết định vị trí cho client. Quái chưa có cùng pipeline nội suy như người khác.

WASD/mũi tên di chuyển; J/chuột trái đánh; giữ TAB xem bảng; M mute; ESC xác nhận rời về menu. Mất focus xóa phím. Voice tự thu trong phòng; thiếu micro/loa báo riêng; chưa có codec/mixer hoàn chỉnh.

Token chỉ ở RAM, RESUME không phải đăng nhập hôm sau. Save offline ở saves/offline_save.json; online do server lưu. Test trong module gồm AssetRegressionChecks, AuditRegressionChecks, LobbySpawnRegressionChecks; một số tham chiếu server nên không gom vào build client sản xuất. Xem README gốc cho giao thức và giới hạn.
