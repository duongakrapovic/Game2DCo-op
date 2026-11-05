# MODULE SERVER – Dedicated Game Server

---

## 🎯 MỤC TIÊU

Server chịu trách nhiệm:
- Lắng nghe kết nối TCP từ nhiều client đồng thời (mỗi client một luồng riêng)
- Quản lý danh sách **Game Room** (tạo phòng, vào phòng, xóa phòng khi trống)
- Chạy **vòng lặp vật lý game 60 FPS** trong mỗi phòng (server-authoritative)
- Broadcast trạng thái tất cả người chơi (`STATE`) cho toàn bộ client trong phòng

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ |
|------------|-----------|
| Ngôn ngữ | Java 17+ |
| Mạng | `java.net.ServerSocket` / `Socket` (TCP) |
| Đa luồng | `Thread` per client + `ConcurrentHashMap` |
| Vật lý | `PhysicsEngine` (top-down, va chạm tile) |
| Bản đồ | `MapManager` (đọc file TMX chunk) |

---

## 🚀 HƯỚNG DẪN CHẠY

### Cách 1: Dùng file bat (khuyên dùng)
```bash
# Chạy từ thư mục gốc của project
run-server.bat
```

### Cách 2: Thủ công
```bash
# Đứng ở thư mục gốc project
javac -encoding UTF-8 source/common/*.java source/server/*.java
java -cp . source.server.ServerApp
```

Server khởi động và lắng nghe tại: **TCP port `8888`**

---

## 📦 CẤU TRÚC MODULE

```
source/server/
├── README.md
├── ServerApp.java       ← Entry point: mở ServerSocket, tạo thread mỗi client
├── GameRoom.java        ← Quản lý 1 phòng chơi, chạy game loop 60FPS
└── PhysicsEngine.java   ← Tính vật lý top-down, kiểm tra va chạm tile
```

### Vai trò từng class

| Class | Trách nhiệm |
|-------|------------|
| `ServerApp` | Mở `ServerSocket(8888)`, vòng lặp `accept()`, spawn `ClientHandler` thread |
| `ClientHandler` | Đọc lệnh từ 1 client, xử lý `CREATE_ROOM` / `JOIN_ROOM` / `INPUT` |
| `GameRoom` | Lưu danh sách player + writer, chạy vòng lặp 60FPS, gọi physics, broadcast `STATE` |
| `PhysicsEngine` | Cập nhật vị trí player theo input, xử lý va chạm với tile map |

---

## 🔗 GIAO THỨC NHẬN / GỬI

### Nhận từ Client
| Lệnh | Định dạng |
|------|-----------|
| Lấy phòng | `ROOM_LIST` |
| Tạo phòng | `CREATE_ROOM:<tênPhòng>:<tênNgườiChơi>` |
| Vào phòng | `JOIN_ROOM:<tênPhòng>:<tênNgườiChơi>` |
| Input phím | `INPUT:<left>:<right>:<up>:<down>:<attack>` |

### Gửi về Client
| Lệnh | Định dạng |
|------|-----------|
| Danh sách phòng | `ROOM_LIST;<tên> (N người);...` |
| Trạng thái game | `STATE;<id>,<name>,<x>,<y>,<direction>,<frame>,<hp>,<maxHp>;...` |
| Lỗi | `ERROR:<thôngBáo>` |
| Người chơi nhận sát thương | `PLAYER_DAMAGE:<playerId>:<damage>:<currentHp>` |
| Quái nhận sát thương | `ENEMY_DAMAGE:<enemyId>:<damage>:<currentHp>` |

---

## 🧪 TEST

Kiểm tra server đang chạy bằng Telnet:
```bash
telnet localhost 8888
```
Gõ `ROOM_LIST` rồi Enter → server phải trả về `ROOM_LIST` (danh sách trống khi chưa có phòng).

---

## 📝 GHI CHÚ

- Port mặc định: **8888** — có thể đổi tại `source/common/Common.java`
- Server chạy **server-authoritative**: mọi tính toán vật lý đều ở server
- Mỗi `GameRoom` chạy trên 1 thread riêng với game loop `Thread.sleep(16)` (~60 FPS)
