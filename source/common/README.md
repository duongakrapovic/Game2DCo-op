# Module Common

Model, giao thức và luật game chung cho server/client/offline. Cập nhật 19/11/2026 theo code 7fd270b.

| File | Trách nhiệm |
|---|---|
| Common.java | Port/opcode, tick, bốn người, kích thước và spawn |
| GameData.java | PlayerState, MonsterState, ItemState, PlayerInput bất biến, WorldSnapshot |
| GameProtocol.java | TCP framing/lọc tên, GAM1, snapshot nhị phân v2 full/delta/ghép mảnh/copy; còn hàm văn bản không phải transport gameplay hiện tại |
| GameMap.java | DOM XML TMX/TSX, collision, CSV tile layer, vùng spawn liên thông, vẽ map |
| GamePhysics.java | Di chuyển/đi chéo/va chạm, knockback, hitbox, animation |
| MonsterAI.java | A* bốn hướng có ngân sách, workspace tái dùng, đuổi/đánh/LOS |
| CombatSystem.java | Đòn đánh player, quái chết và lịch sử rewind giới hạn |
| SpawnSystem.java | Điểm đi được/liên thông/không chồng; fallback grid và null khi hết chỗ |
| ItemSystem.java | Bình máu map/drop, nhặt, respawn, TTL, giới hạn item |
| SaveManager.java | DTO/JSON offline/online, kiểm vị trí khi load |

PortedGameplayRegressionChecks là chương trình test.

Tile 16 px × scale 3 = **48 px**; comment 32px trong Common đã cũ. Map 3×3 chunk, 32×32 tile/chunk, tổng 4608×4608 px, spawn (1056,864). Map đang dùng source/content/map, đường dẫn tương đối từ gốc. Server tải collision độc lập ảnh.

CSV trong TMX được đọc thật; statics/dataset_sample.csv không dùng cho logic game. Vật lý chung hỗ trợ prediction nhưng khác nhịp và input vẫn gây lệch; không gọi toàn bộ gameplay là tất định tuyệt đối. Snapshot tối đa 499 byte ứng dụng/mảnh, delta dựa trên full baseline. Save chưa lưu toàn thế giới, JSON parser thủ công và write chưa nguyên tử.

Build qua launcher gốc, không ghi class vào source. Đổi wire/save phải xét version, decoder, caller và test hai phía.
