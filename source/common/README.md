# MODULE COMMON – Mã dùng chung server & client

---

## 📦 CẤU TRÚC

| File | Vai trò |
|------|---------|
| `Common.java` | Hằng số: `PORT`, kích thước tile/chunk, vị trí spawn, **tên lệnh giao thức**, `TICK_RATE`/`TICK_NS`, `MAX_PLAYERS_PER_ROOM` |
| `Protocol.java` | Giới hạn & timeout của giao thức, lọc tên (`sanitizeName`, `sanitizeRoom`), `fmt2` (định dạng tọa độ), `readLineLimited` (đọc dòng có giới hạn độ dài) |
| `Player.java` | Model người chơi: vị trí, vận tốc, phím, hướng nhìn, frame animation |
| `AnimationController.java` | Nhịp đổi frame bước chân, độc lập với vật lý |
| `PhysicsEngine.java` | Vật lý top-down 4 hướng + va chạm tile. **Tất định**: cùng input ⇒ cùng kết quả |
| `MapManager.java` | Nạp bản đồ TMX (3×3 chunk), thuộc tính collision từ `tileset.tsx`, vẽ bản đồ |

## ⚠️ QUY ƯỚC QUAN TRỌNG

- **`PhysicsEngine` và `MapManager` được server dùng để quyết định vị trí và được client dùng để dự đoán.**
  Mọi thay đổi trong hai file này phải giữ tính tất định (không dùng `Math.random`, thời gian thực, trạng thái ngoài `Player`),
  nếu không client sẽ dự đoán sai và nhân vật bị giật khi hòa giải.
- Đường dẫn bản đồ là `source/content/map` (tương đối từ thư mục gốc project).
- Server chỉ cần dữ liệu va chạm (`loadTilesetProperties`), không cần nạp ảnh tileset — thông tin collision độc lập với việc có nạp ảnh hay không.
- Mọi tên đi qua mạng phải qua `Protocol.sanitizeName/sanitizeRoom` để không chứa ký tự phân cách `; , : |`.
