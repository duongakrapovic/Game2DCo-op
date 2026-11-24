# Module Server

Dedicated server authoritative nhiều phòng, tối đa bốn player/phòng. Cập nhật 19/11/2026 theo code 7fd270b.

## Chạy

Từ gốc chạy `run-server.bat` hoặc `.\run-server.bat` trong PowerShell; cần JDK 17+. `--compile-only` chỉ build. Class riêng .runtime/server-..., không xóa/ghi đè output JVM khác. Gõ exit để dừng server có xử lý vòng đời.

Mở cả TCP 8888 (phiên/phòng) và UDP 8888 (gameplay GAM1, voice VCH1). Client tạo phòng không tự mở server hoặc trở thành host mô phỏng.

| Class | Trách nhiệm |
|---|---|
| ServerApp | Start/stop, map phòng/phiên theo token và ID, RESUME và expiry |
| ServerNetwork | TCP accept/ClientHandler; UDP dispatcher; bốn gameplay worker, hai voice worker |
| ServerSession | playerId/token RAM/roomId/handler/thời điểm hoạt động |
| ServerGame | Loop phòng, input buffer, gameplay, full/delta và autosave |
| RoomSaveManager | Ánh xạ player-slot, join/attach/detach và save online |

RoomAdmissionRegressionChecks và LauncherRegressionChecks là test, loại khỏi build sản xuất.

Mục tiêu 60 tick/s. Tick lấy input mới nhất, không mô phỏng mỗi datagram thành một bước. Cập nhật player/AI/chiến đấu/item rồi gửi binary snapshot UDP v2. Thử tạo 15 slime; thiếu spawn hợp lệ có thể ít hơn. Full khi cần và mỗi 60 state sequence, delta theo full baseline.

Rời chủ động loại player; phòng rỗng bị dừng/xóa ở RAM, file save còn. Mất TCP có grace 20 giây sau khi phát hiện disconnect; người được giữ chỗ có thể vẫn chiếm slot. Save ở saves/online_<tênPhòng>.json, autosave 900 tick và theo vòng đời. Bốn slot lịch sử nhận lại theo tên không phân biệt hoa thường, nên 1/4 online có thể hết slot save. Token không là account bền; chưa có campaign catalog/lời mời/matchmaking.

Giới hạn: queue worker chưa bounded, I/O trong khóa phòng, INPUT thiếu room epoch, thiếu timeout input khi UDP im lặng, JSON write chưa nguyên tử. Token/endpoint mapping không thay TLS/HMAC/tài khoản. Xem README gốc cho protocol và phạm vi kiểm chứng.
