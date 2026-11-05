# BÀI TẬP LỚN: LẬP TRÌNH MẠNG

## 🎮 Game 2D Co-op – Dungeon Crawler

---

## 🧑‍💻 THÔNG TIN NHÓM

| STT | Họ và Tên | MSSV | Email | Đóng góp |
|-----|-----------|------|-------|----------|
| 1 | Trần Văn Đức | B23DCCN191 | b23dccn191@ptit.edu.vn | *(xem commit history)* |
| 2 | Nguyễn Minh Hiền | B23DCCN289 | b23dccn289@ptit.edu.vn | *(xem commit history)* |
| 3 | Vũ Hải Dương | B23DCCN233 | b23dccn233@ptit.edu.vn | *(xem commit history)* |

**Tên nhóm:** Nhóm 6 – Lập trình mạng  
**Chủ đề đã đăng ký:** Game 2D Co-op

---

## 🧠 MÔ TẢ HỆ THỐNG

Hệ thống là một **game 2D top-down co-op nhiều người chơi** xây dựng hoàn toàn bằng Java thuần.

- **Server** (Dedicated Server): Tiếp nhận kết nối TCP từ nhiều client, quản lý các phòng chơi (Game Room), chạy vòng lặp vật lý game ở tốc độ 60 FPS và broadcast trạng thái game cho toàn bộ người chơi trong phòng.
- **Client**: Giao diện đồ họa Java Swing, cho phép người chơi tạo/vào phòng, di chuyển nhân vật bằng phím WASD hoặc mũi tên. Client hỗ trợ cả chế độ **Offline Test** (không cần server) lẫn **Online** (kết nối server qua TCP).
- **Common**: Module dùng chung giữa server và client — hằng số giao thức, quản lý bản đồ (Tiled TMX chunks), vật lý va chạm.

**Cấu trúc logic tổng quát:**
```
[ClientApp A] ──┐
[ClientApp B] ──┼──TCP:8888──► [ServerApp]
[ClientApp C] ──┘                   │
                               [GameRoom]
                               (quản lý phòng, physics 60FPS)
                                    │
                              [MapManager]
                              (9 chunks TMX 3×3)
```

**Sơ đồ hệ thống:**

![System Diagram](./statics/diagram.png)

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ | Ghi chú |
|------------|-----------|---------|
| Ngôn ngữ | Java 17+ | Không cần framework ngoài |
| Giao thức mạng | TCP Socket (java.net) | Plain-text, line-delimited |
| Giao diện | Java Swing (JFrame, JPanel) | Render tile map + player |
| Bản đồ | Tiled TMX (CSV encoding) | 9 chunk 32×32 tiles |
| Build | `javac` + `.bat` script | Không cần Maven/Gradle |
| Port | `8888` | Có thể đổi trong `Common.java` |

---

## 🚀 HƯỚNG DẪN CHẠY DỰ ÁN

### Yêu cầu
- **Java JDK 17+** đã được cài và thêm vào PATH

### 1. Clone repository
```bash
git clone https://github.com/duongakrapovic/Game2DCo-op.git
cd Game2DCo-op
```

### 2. Chạy Server (nếu chơi Online)
Double-click file `run-server.bat` hoặc chạy lệnh:
```bash
run-server.bat
```
Server sẽ tự động compile và lắng nghe tại **port 8888**.

### 3. Chạy Client
Double-click file `run-client.bat` hoặc chạy lệnh:
```bash
run-client.bat
```
Khi khởi động, client hiển thị hộp thoại chọn chế độ:
- **"Test Offline (Không cần Server)"** — Chơi đơn, không cần chạy server
- **"Online (Kết nối Server)"** — Nhập IP server → Nhập tên người chơi → Vào sảnh chờ

### 4. Điều khiển
| Phím | Hành động |
|------|-----------|
| `W` / `↑` | Di chuyển lên |
| `S` / `↓` | Di chuyển xuống |
| `A` / `←` | Di chuyển trái |
| `D` / `→` | Di chuyển phải |
| `J` | Tấn công quái |

### 5. Chơi Online (nhiều người)
1. Một người chạy `run-server.bat`
2. Tất cả người chơi chạy `run-client.bat` → chọn **Online** → nhập IP của người chạy server
3. Một người chọn **"Tạo phòng"** → những người khác chọn phòng và nhấn **"Vào phòng"**

> **Lưu ý:** Để chơi qua Internet mà không có IP public, dùng **ZeroTier** hoặc **Hamachi** để tạo mạng LAN ảo.

---

## 🔗 GIAO TIẾP (GIAO THỨC SỬ DỤNG)

Giao thức TCP plain-text, mỗi lệnh là một dòng (`\n`), các trường phân cách bằng `:` hoặc `;`.

### Client → Server

| Lệnh | Định dạng | Mô tả |
|------|-----------|-------|
| Lấy danh sách phòng | `ROOM_LIST` | Yêu cầu server gửi danh sách phòng hiện tại |
| Tạo phòng | `CREATE_ROOM:<tênPhòng>:<tênNgườiChơi>` | Tạo phòng mới và vào ngay |
| Vào phòng | `JOIN_ROOM:<tênPhòng>:<tênNgườiChơi>` | Vào phòng đã có |
| Gửi đầu vào | `INPUT:<left>:<right>:<up>:<down>:<attack>` | Gửi trạng thái phím bấm (true/false) |

### Server → Client

| Lệnh | Định dạng | Mô tả |
|------|-----------|-------|
| Danh sách phòng | `ROOM_LIST;<phòng1> (N người);<phòng2>...` | Broadcast khi có thay đổi |
| Trạng thái game | `STATE;<id>,<name>,<x>,<y>,<direction>,<frame>,<hp>,<maxHp>;...` | Broadcast 60 lần/giây |
| Lỗi | `ERROR:<thôngBáo>` | Phòng đã tồn tại / không tồn tại |
| Người chơi nhận sát thương | `PLAYER_DAMAGE:<playerId>:<damage>:<currentHp>` | Phát khi người chơi bị đánh |
| Quái nhận sát thương | `ENEMY_DAMAGE:<enemyId>:<damage>:<currentHp>` | Phát khi quái bị đánh |

**Ví dụ luồng kết nối:**
```
Server → Client : ROOM_LIST                          (gửi ngay khi client kết nối)
Client → Server : CREATE_ROOM:DungeonA:Duc           (tạo phòng)
Server → Client : STATE;P1,Duc,704,576,down,1,100,100 (loop 60FPS)
Client → Server : INPUT:false:true:false:false:false (bấm phím D - đi phải)
```

---

## 📊 KẾT QUẢ THỰC NGHIỆM

> Xem ảnh chụp kết quả chạy thử tại thư mục `statics/`.

![Demo Result](./statics/result.png)

---

## 🧩 CẤU TRÚC DỰ ÁN
```
Game2DCo-op/
├── README.md                    ← Tài liệu dự án
├── INSTRUCTION.md               ← Hướng dẫn GV (không chỉnh sửa)
├── run-server.bat               ← Script compile + chạy server
├── run-client.bat               ← Script compile + chạy client
├── statics/
│   ├── diagram.png              ← Sơ đồ hệ thống
│   ├── logo.png
│   └── dataset_sample.csv      ← Mẫu dữ liệu game state log
└── source/
    ├── .gitignore
    ├── common/                  ← Dùng chung server & client
    │   ├── Common.java          ← Hằng số: PORT, TILE_SIZE, lệnh giao thức
    │   ├── Player.java          ← Model người chơi (vị trí, vận tốc, input)
    │   └── MapManager.java      ← Load & render bản đồ TMX chunk
    ├── server/
    │   ├── README.md
    │   ├── ServerApp.java       ← Entry point server, vòng lặp accept TCP
    │   ├── GameRoom.java        ← Quản lý phòng chơi, game loop 60FPS
    │   └── PhysicsEngine.java   ← Tính vật lý top-down, xử lý va chạm tile
    ├── client/
    │   ├── README.md
    │   └── ClientApp.java       ← Entry point client, UI Swing, kết nối TCP
    └── content/
        └── map/                 ← 9 file chunk TMX (3×3) + tileset
            ├── tileset.png
            ├── tileset.tsx
            ├── chunk0_0.tmx ... chunk2_2.tmx
```

---

## 🧩 HƯỚNG PHÁT TRIỂN THÊM

- [ ] Thêm lệnh `CHAT` để người chơi nhắn tin trong phòng
- [ ] Giới hạn số người tối đa mỗi phòng (`MAX_PLAYERS_PER_ROOM`)
- [ ] Thêm trạng thái `GAME_START` / `GAME_OVER`
- [ ] Thêm mob/enemy do server điều khiển
- [ ] Hỗ trợ kết nối qua Internet với ZeroTier
- [ ] Lưu log game state ra file CSV

---

## 📝 GHI CHÚ

- Repo tuân thủ đúng cấu trúc đã hướng dẫn trong `INSTRUCTION.md`.
- File `INSTRUCTION.md` **không được chỉnh sửa** theo quy định.
- Toàn bộ logic vật lý (va chạm tile) chạy **phía server** — client chỉ render.

---

## 📚 TÀI LIỆU THAM KHẢO

- [Java TCP Socket – Oracle Docs](https://docs.oracle.com/javase/tutorial/networking/sockets/)
- [Tiled Map Editor](https://www.mapeditor.org/) – Công cụ thiết kế bản đồ TMX
- [Java Swing Tutorial](https://docs.oracle.com/javase/tutorial/uiswing/)
- [ZeroTier – LAN ảo qua Internet](https://www.zerotier.com/)
