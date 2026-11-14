# BÀI TẬP LỚN: LẬP TRÌNH MẠNG

## 🎮 Game 2D Co-op – Dungeon Crawler

---

## 🧑‍💻 THÔNG TIN NHÓM

| STT | Họ và Tên | MSSV | Email | Đóng góp |
|-----|-----------|------|-------|----------|
| 1 | Trần Văn Đức | B23DCCN191 | b23dccn191@ptit.edu.vn | *(xem commit history)* |
| 2 | Nguyễn Minh Hiển | B23DCCN289 | b23dccn289@ptit.edu.vn | *(xem commit history)* |
| 3 | Vũ Hải Dương | B23DCCN233 | b23dccn233@ptit.edu.vn | *(xem commit history)* |

**Tên nhóm:** Nhóm 6 – Lập trình mạng  
**Chủ đề đã đăng ký:** Game 2D Co-op

---

## 🧠 MÔ TẢ HỆ THỐNG

Hệ thống là một **game 2D top-down co-op nhiều người chơi** xây dựng hoàn toàn bằng Java thuần.

- **Server** (Dedicated Server): Tiếp nhận kết nối TCP từ nhiều client, quản lý các phòng chơi (Game Room), chạy vòng lặp vật lý game ở tốc độ 60 FPS, broadcast trạng thái game và relay voice qua UDP trong cùng phòng.
- **Client**: Giao diện đồ họa Java Swing, cho phép người chơi tạo/vào phòng, di chuyển nhân vật bằng phím WASD hoặc mũi tên. Client hỗ trợ cả chế độ **Offline** lẫn **Online**, có open-mic voice chat và điều khiển mute.
- **Common**: Module dùng chung giữa server và client — hằng số giao thức, quản lý bản đồ (Tiled TMX chunks), vật lý va chạm.

**Cấu trúc logic tổng quát:**
```
[ClientApp A] ──┐
[ClientApp B] ──┼──TCP:8888──► [ServerApp]
[ClientApp C] ──┘      UDP:8888──► [Voice Relay]
                                     │
                               [ServerGame]
                               (quản lý phòng, physics 60 tick/s)
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
| Giao thức mạng | TCP + UDP Socket (java.net) | Game commands dạng dòng; voice PCM qua UDP relay |
| Giao diện | Java Swing (JFrame, JPanel) | Render tile map + player |
| Bản đồ | Tiled TMX (CSV encoding) | 9 chunk 32×32 tiles |
| Build | `javac` + `.bat` script | Không cần Maven/Gradle |
| Port | `8888` | Mở cả TCP và UDP trên máy chủ |

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
Server sẽ tự động compile và lắng nghe TCP và UDP tại **port 8888**.

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
| `ESC` | Thoát về menu |
| `M` | Tắt/bật micro trong phòng online |

Prediction, reconciliation và nội suy người chơi khác luôn chạy mặc định.

### 5. Chơi Online (nhiều người)
1. Một người chạy `run-server.bat`
2. Tất cả người chơi chạy `run-client.bat` → chọn **Online** → nhập IP của người chạy server
3. Một người chọn **"Tạo phòng"** → những người khác chọn phòng và nhấn **"Vào phòng"**

> **Lưu ý:** Để chơi qua Internet mà không có IP public, dùng **ZeroTier** hoặc **Hamachi** để tạo mạng LAN ảo.

---

## 🔗 GIAO TIẾP (GIAO THỨC SỬ DỤNG)

Giao thức TCP dạng văn bản (UTF-8), **mỗi message là một dòng** (`\n`). Lệnh phân cách bằng `:`, trạng thái dùng `;` và `,`.
Để không phá định dạng, **tên người chơi không được chứa `; , : |`** và **tên phòng không có khoảng trắng** — server
tự lọc các ký tự này (xem `Protocol.sanitizeName / sanitizeRoom`). Client → Server tối đa 512 ký tự/dòng.

![Luồng giao tiếp](./statics/network_flow.png)

### Client → Server

| Lệnh | Định dạng | Mô tả |
|------|-----------|-------|
| Danh sách phòng | `ROOM_LIST` | Yêu cầu danh sách phòng |
| Tạo phòng | `CREATE_ROOM:<phòng>:<tên>` | Tạo phòng và vào ngay. Trả `JOINED` hoặc `ERROR` |
| Vào phòng | `JOIN_ROOM:<phòng>:<tên>` | Vào phòng có sẵn (tối đa `MAX_PLAYERS_PER_ROOM` = 4). Tự rời phòng cũ nếu đang ở phòng khác |
| Rời phòng | `LEAVE_ROOM` | Rời phòng, về sảnh |
| Gửi đầu vào | `INPUT:<left>:<right>:<up>:<down>[:<seq>[:<attack>]]` | Trạng thái di chuyển, sequence và cờ attack; các trường cuối có thể bỏ qua với client cũ |
| Heartbeat | `PING:<t>` | Client gửi định kỳ; server phản hồi `PONG:<t>` để giữ kết nối hoạt động |
| Kết nối lại | `RESUME:<token>` | Gửi trên kết nối mới sau khi rớt mạng để lấy lại chỗ trong phòng |

### Server → Client

| Lệnh | Định dạng | Mô tả |
|------|-----------|-------|
| Chào | `WELCOME:<id>:<token>` | Ngay khi kết nối: id người chơi + token phiên (dùng cho `RESUME`) |
| Danh sách phòng | `ROOM_LIST;<phòng1> (N người);...` | Khi kết nối, khi được hỏi, và **tự đẩy** cho người ở sảnh mỗi khi phòng thay đổi |
| Đã vào phòng | `JOINED:<phòng>` | Client chỉ chuyển sang màn hình game khi nhận được lệnh này |
| Đã rời phòng | `LEFT` | Phản hồi `LEAVE_ROOM` |
| Trạng thái game | `STATE#PLAYERS:<...>#MONSTERS:<...>` | Snapshot người chơi và slime do server phát ở 60 tick/s |
| Heartbeat | `PONG:<t>` | Echo giá trị heartbeat; không dùng để đo hoặc hiển thị RTT |
| Kết nối lại | `RESUMED:<phòng>` / `RESUME_FAIL` | Kết quả của `RESUME` |
| Lỗi | `ERROR:<thôngBáo>` | Sai cú pháp, phòng đầy / đã tồn tại / không tồn tại... |

**Ví dụ luồng:**
```
Server → Client : WELCOME:P1:9f3a1c5e7b2d4a60
Server → Client : ROOM_LIST
Client → Server : CREATE_ROOM:DungeonA:Duc
Server → Client : JOINED:DungeonA
Client → Server : INPUT:false:true:false:false:0:false  (tick 0: đi phải)
Server → Client : STATE#PLAYERS:P1,Duc,...#MONSTERS:M1,SLIME,...
```

Voice dùng UDP trên cùng số cổng `8888` (socket riêng với TCP). Client đăng ký
endpoint bằng token phiên; server chỉ xác nhận client đang ở phòng và relay các
gói âm thanh đến những người chơi khác trong phòng đó. Dữ liệu giọng nói không
đi qua giao thức dòng TCP.

### Phân chia TCP / UDP

| Dữ liệu | Giao thức | Lý do |
|---|---|---|
| Sảnh phòng, WELCOME + token, RESUME, lỗi | TCP | Phải chắc chắn tới và đúng thứ tự |
| INPUT (client → server), 60 gói/giây | **UDP** (`GAM1`) | Gói mới thay gói cũ, mất một gói không sao |
| STATE người chơi, 60 gói/giây | **UDP** (`GAM1`) | Như trên; TCP sẽ chặn các gói sau khi phải gửi lại |
| STATE quái, 20 gói/giây | **UDP** (`GAM1`) | Quái chậm; client nội suy nên vẫn mượt |
| Voice | UDP (`VCH1`) | Âm thanh trễ thì vô dụng |

Gói game UDP (chi tiết trong `common/GameUdp.java`) dùng chung cổng `8888` và chung socket với voice,
phân biệt bằng magic `GAM1`. Client gửi `REGISTER` kèm token, server trả `REGISTERED`, sau đó client gửi `INPUT`
mỗi tick và server gửi `STATE` bằng UDP. Các cơ chế đi kèm:

- **Dự phòng TCP:** UDP bị chặn thì client gửi INPUT qua TCP và server gửi STATE đầy đủ qua TCP (game vẫn chơi được).
  Server tự chuyển về TCP nếu 3 giây không nhận input UDP, và chuyển lại UDP khi client gửi lại.
- **Chống kẹt phím:** server tự nhả phím nếu 300 ms không nhận input UDP (mất gói "nhả phím").
- **Gói sai thứ tự:** STATE có số tick, client bỏ gói cũ; INPUT có seq, server bỏ gói cũ hơn gói đã nhận.
- **Bảo mật:** INPUT chỉ được nhận từ địa chỉ UDP đã đăng ký bằng token phiên.
- **Dự đoán + nội suy khi vẽ:** nhân vật của mình chạy vật lý cục bộ mỗi tick rồi reconcile theo `lastAckSeq`;
  người chơi khác và quái được nội suy giữa hai snapshot, vẽ chậm 100 ms (`ClientMovement`).

---

## 🌐 CƠ CHẾ MẠNG (ĐIỂM NỔI BẬT)

| # | Cơ chế | Giải quyết vấn đề gì | Cài đặt |
|---|--------|----------------------|---------|
| 1 | **Tick cố định 60 Hz + xác nhận input có `seq`** | Server cập nhật vị trí và trạng thái phòng theo tick; `ack` xác nhận sequence input đã nhận | `ServerGame` |
| 2 | **Prediction + server reconciliation** | Điều khiển nhân vật cục bộ phản hồi ngay; khi nhận `STATE`, client xác nhận input đã nhận và hiệu chỉnh theo vị trí server. Luôn bật | `ClientMovement` |
| 3 | **Nội suy người chơi khác** | Làm mượt vị trí người chơi từ các snapshot; luôn bật | `ClientMovement`, `ClientGame` |
| 4 | **Tự kết nối lại (session resume)** | Server giữ chỗ 20 giây; client gửi `RESUME:<token>` trên kết nối mới | `ClientNetwork`, `ServerApp` |
| 5 | **Voice chat theo phòng** | Open mic truyền PCM qua UDP relay; token phiên xác thực endpoint và server chỉ relay trong cùng phòng | `ClientVoiceChat`, `ServerNetwork` |
| 6 | **Heartbeat kết nối** | Client gửi `PING` định kỳ, server phản hồi `PONG`; chỉ duy trì kết nối, không đo RTT | `ClientNetwork`, `ServerNetwork` |
| 7 | **Quản lý phòng** | Giới hạn 4 người; phòng trống được xóa và dừng game loop | `ServerApp`, `ServerGame` |
| 8 | **Kiểm tra dữ liệu** | Lệnh sai/thiếu tham số → `ERROR`; giới hạn độ dài dòng và lọc ký tự cấm | `ServerNetwork`, `GameProtocol` |


---

## 🎙️ VOICE CHAT

- Micro tự bật khi vào phòng online; nhấn `M` để tắt/bật micro. Voice dừng khi rời phòng hoặc thoát game.
- Âm thanh mono PCM 16 kHz được gửi đến server qua UDP và relay cho người khác trong cùng phòng; client không gửi audio trực tiếp cho nhau.
- Máy chủ cần mở cả **TCP 8888** cho game và **UDP 8888** cho voice. Cần thiết bị micro và loa/tai nghe để thu/phát âm thanh.
- Nếu không có thiết bị âm thanh, trạng thái voice sẽ báo thiết bị nào không khả dụng; phần chơi game vẫn tiếp tục.

---

## 🧩 CẤU TRÚC DỰ ÁN
```
Game2DCo-op/
├── README.md                    ← Tài liệu dự án
├── INSTRUCTION.md               ← Hướng dẫn GV (không chỉnh sửa)
├── run-server.bat               ← Compile + chạy server
├── run-client.bat               ← Compile + chạy client
├── statics/
│   ├── diagram.png              ← Sơ đồ hệ thống
│   ├── network_flow.png         ← Sơ đồ luồng giao tiếp
│   ├── demo_*.png               ← Ảnh chạy thử
│   ├── logo.png
│   └── dataset_sample.csv       ← Mẫu log game state (định dạng giống logs/game_state_*.csv)
└── source/
    ├── common/                  ← Dùng chung server & client
    │   ├── Common.java          ← Hằng số: PORT, TICK_RATE, tên lệnh giao thức...
    │   ├── Protocol.java        ← Giới hạn, timeout, lọc tên, đọc dòng có giới hạn
    │   ├── Player.java · AnimationController.java
    │   ├── PhysicsEngine.java   ← Vật lý top-down tất định (server & client dự đoán dùng chung)
    │   └── MapManager.java      ← Nạp bản đồ TMX, va chạm tile
    ├── server/
    │   ├── ServerApp.java       ← Entry point, quản lý phòng và phiên
    │   ├── ServerNetwork.java   ← TCP game protocol và UDP voice relay
    │   ├── ServerSession.java   ← ID, token và trạng thái phiên
    │   └── ServerGame.java      ← Phòng, game loop 60 Hz, slime và chiến đấu
    ├── client/
    │   ├── ClientApp.java       ← UI Swing, lobby và điều khiển
    │   ├── ClientNetwork.java   ← TCP và tự kết nối lại
    │   ├── ClientMovement.java  ← Prediction, reconciliation và nội suy
    │   ├── ClientGame.java      ← Render gameplay và trạng thái voice
    │   └── ClientVoiceChat.java ← Thu/phát âm thanh và voice UDP
    └── content/
        ├── map/                 ← 9 chunk TMX (3×3) + tileset
        └── player/              ← Sprite nhân vật
```

---

## 🧩 HƯỚNG PHÁT TRIỂN THÊM

- [ ] Thêm lệnh `CHAT` để người chơi nhắn tin trong phòng
- [x] Giới hạn số người tối đa mỗi phòng (`MAX_PLAYERS_PER_ROOM`)
- [ ] Thêm trạng thái `GAME_START` / `GAME_OVER`
- [ ] Thêm mob/enemy do server điều khiển
- [ ] Hỗ trợ kết nối qua Internet với ZeroTier (cần cấu hình TCP và UDP; nhóm chưa kiểm thử thực tế)
- [x] Tự kết nối lại khi rớt mạng (giữ chỗ 20 s)
- [ ] (Tùy chọn) Chuyển phần `STATE` sang UDP để tránh head-of-line blocking
- [x] Lưu log game state ra file CSV (`logs/`)

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
