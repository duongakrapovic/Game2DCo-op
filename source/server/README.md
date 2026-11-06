# MODULE SERVER – Dedicated Game Server

---

## 🎯 MỤC TIÊU

Server chịu trách nhiệm:
- Lắng nghe kết nối TCP từ nhiều client đồng thời (mỗi client một luồng đọc + một luồng gửi)
- Quản lý **Game Room**: tạo, vào, rời, xóa phòng trống; giới hạn 4 người/phòng
- Chạy **vòng lặp game cố định 60 tick/giây** trong mỗi phòng (server-authoritative)
- Broadcast trạng thái (`STATE`) cho cả phòng, kèm `ack` để client dự đoán/hòa giải
- Giữ chỗ người chơi bị rớt mạng 20 giây để họ kết nối lại (`RESUME`)
- Kiểm tra mọi dữ liệu từ client, ghi log CSV và thống kê lưu lượng

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ |
|------------|-----------|
| Ngôn ngữ | Java 17+ |
| Mạng | `ServerSocket` / `Socket` (TCP, `TCP_NODELAY`, `SO_KEEPALIVE`, UTF-8) |
| Đa luồng | Luồng đọc/client (`ClientHandler`), luồng gửi/client (`OutChannel`), luồng game loop/phòng, `ScheduledExecutorService` cho thời gian ân hạn |
| Vật lý | `common/PhysicsEngine` (cùng mã với client để dự đoán khớp) |
| Bản đồ | `common/MapManager` — nạp 1 lần, dùng chung cho mọi phòng |

---

## 🚀 HƯỚNG DẪN CHẠY

### Cách 1: Dùng file bat (khuyên dùng)
```bash
# Chạy từ thư mục gốc của project
run-server.bat
```

### Cách 2: Thủ công
```bash
javac -encoding UTF-8 source/common/*.java source/server/*.java
java -cp . source.server.ServerApp            # port mặc định 8888
java -cp . source.server.ServerApp 9000       # đổi port
java -cp . source.server.ServerApp --no-log   # không ghi log CSV
```

Tuỳ chọn JVM: `-Dlog.every=<số tick>` (mặc định 6 = log 10 Hz), `-Dgame.log=false`.

---

## 📦 CẤU TRÚC MODULE

| Class | Trách nhiệm |
|-------|------------|
| `ServerApp` | `main`, vòng `accept()`, quản lý phòng + phiên (tạo/vào/rời/xóa, thời gian ân hạn, đẩy `ROOM_LIST`). Mọi thao tác phòng đi qua một `LOCK` |
| `ClientHandler` | Đọc từng dòng lệnh của 1 client, kiểm tra hợp lệ, gọi `ServerApp`/`GameRoom`. Chống flood (300 msg/s), dòng quá dài, lệnh sai |
| `Session` | Phiên người chơi (id, token, phòng, kênh gửi hiện tại) — tồn tại độc lập với socket |
| `GameRoom` | 1 phòng: tick 60 Hz bằng bộ tích lũy thời gian, hàng đợi input theo `seq`, vật lý, broadcast `STATE`, log |
| `OutChannel` | Luồng gửi riêng cho từng client. `sendReliable()` cho lệnh điều khiển; `sendState()` gộp snapshot cũ ⇒ client chậm không làm đứng phòng |
| `GameLogger` | Ghi `logs/game_state_<phòng>_<thời gian>.csv` bất đồng bộ |
| `ServerStats` | Đếm message/byte, in và ghi `logs/net_stats.csv` mỗi 5 giây |

### Đồng bộ input (quan trọng khi mở rộng)
- Client gửi `INPUT` **mỗi tick** kèm `seq`. Mỗi tick server lấy tối đa 2 input trong hàng đợi của người chơi, mỗi input = **đúng 1 bước** `PhysicsEngine.updatePlayer`; `ack` = `seq` cuối cùng đã xử lý.
- Nếu hàng đợi rỗng, server **không tự bịa thêm bước đi** (giữ cho mô phỏng tất định).
- ⚠️ Nếu gameplay thay đổi vị trí người chơi ngoài `PhysicsEngine` (đẩy lùi, dịch chuyển...), client sẽ tự sửa lại vị trí ở lần `STATE` kế tiếp (giật nhẹ). Nên đưa các thay đổi đó vào `PhysicsEngine` hoặc chấp nhận hiệu chỉnh.

### Điểm mở rộng cho gameplay (không cần sửa code mạng)
- `GameRoom.onCommand(pId, cmd, parts)`: lệnh không thuộc giao thức cơ bản (ví dụ `ATTACK`, `CHAT`) được chuyển vào đây; trả `true` nếu đã xử lý.
- `GameRoom.broadcast(msg)`: gửi 1 message đáng tin cậy cho cả phòng (ví dụ `CHAT:...`, `GAME_OVER`).
- `GameRoom.tick()`: nơi thêm logic quái vật, va chạm... mỗi tick.
- Thêm trường vào cuối mỗi bản ghi người chơi trong `STATE` là an toàn: client chỉ đọc 7 trường đầu và bỏ qua phần còn lại.

---

## 🔗 GIAO THỨC

Xem bảng đầy đủ trong `README.md` ở thư mục gốc. Tóm tắt:

| Nhận từ client | Gửi về client |
|----------------|---------------|
| `ROOM_LIST`, `CREATE_ROOM:<phòng>:<tên>`, `JOIN_ROOM:<phòng>:<tên>`, `LEAVE_ROOM`, `INPUT:l:r:u:d[:seq]`, `PING:<t>`, `RESUME:<token>` | `WELCOME`, `ROOM_LIST;...`, `JOINED`, `LEFT`, `STATE;...`, `PONG`, `RESUMED`, `RESUME_FAIL`, `ERROR` |

---

## 🧪 TEST

Kiểm thử tự động (cần server đang chạy): `run-selftest.bat`. Đo tải: `run-bench.bat localhost 40 10`.

Thử thủ công bằng `telnet localhost 8888`: server gửi `WELCOME` và `ROOM_LIST` ngay. Lưu ý server **ngắt kết nối sau 10 giây
không nhận được gì** — gõ `PING:1` định kỳ để giữ kết nối.

---

## 📝 GHI CHÚ

- Port mặc định **8888** (`Common.PORT`), đổi bằng tham số dòng lệnh.
- Server-authoritative: vị trí và va chạm chỉ do server quyết định; client chỉ dự đoán để phản hồi tức thì.
- Khi một người rớt mạng, nhân vật đứng yên trong phòng tối đa 20 giây (`Protocol.RECONNECT_GRACE_MS`); hết hạn thì bị xóa.
