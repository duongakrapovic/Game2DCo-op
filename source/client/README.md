# MODULE CLIENT – Game Client (Java Swing)

---

## 🎯 MỤC TIÊU

Client chịu trách nhiệm:
- Hiển thị giao diện đồ họa game (tile map + nhân vật) bằng Java Swing
- Nhận đầu vào bàn phím (WASD / mũi tên) và gửi lên server
- Kết nối TCP tới server, nhận trạng thái game (`STATE`) và render
- Hỗ trợ **chế độ Offline** (chơi đơn, không cần server)

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ |
|------------|-----------|
| Ngôn ngữ | Java 17+ |
| Giao diện | Java Swing (`JFrame`, `JPanel`, `CardLayout`) |
| Mạng | `java.net.Socket` (TCP client) |
| Đa luồng | Thread lắng nghe server, Timer 60FPS render |
| Bản đồ | `MapManager` (load + vẽ tilemap TMX) |

---

## 🚀 HƯỚNG DẪN CHẠY

### Cách 1: Dùng file bat (khuyên dùng)
```bash
# Chạy từ thư mục gốc của project
run-client.bat
```

### Cách 2: Thủ công
```bash
# Đứng ở thư mục gốc project
javac -encoding UTF-8 source/common/*.java source/server/PhysicsEngine.java source/client/*.java
java -cp . source.client.ClientApp
```

### Chế độ khởi chạy
Khi chạy, một hộp thoại sẽ hiện ra:

| Tùy chọn | Mô tả |
|----------|-------|
| **Test Offline** | Chơi đơn ngay lập tức, không cần server, physics chạy tại client |
| **Online** | Nhập IP server → Nhập tên → Vào sảnh chờ, tạo/vào phòng |

---

## 📦 CẤU TRÚC MODULE

```
source/client/
├── README.md
└── ClientApp.java    ← Toàn bộ logic client: UI, network, render, input
```

### Các thành phần trong `ClientApp.java`

| Thành phần | Mô tả |
|------------|-------|
| `main()` | Hộp thoại chọn chế độ, kết nối TCP nếu Online |
| `createLobbyUI()` | Sảnh chờ: danh sách phòng, nút Tạo/Vào/Làm mới |
| `GamePanel` | Vẽ tile map và nhân vật lên màn hình |
| `updateKey()` | Xử lý phím bấm, gửi `INPUT` lên server |
| Listener thread | Đọc gói tin `STATE` / `ROOM_LIST` / `ERROR` từ server |

---

## 🎮 ĐIỀU KHIỂN

| Phím | Hành động |
|------|-----------|
| `W` / `↑` | Di chuyển lên |
| `S` / `↓` | Di chuyển xuống |
| `A` / `←` | Di chuyển trái |
| `D` / `→` | Di chuyển phải |

Di chuyển chéo (VD: `W + D`) tự động chuẩn hóa tốc độ (nhân `√2/2 ≈ 0.707`).

---

## 💡 LUỒNG HOẠT ĐỘNG (ONLINE)

```
1. Kết nối TCP tới server:8888
2. Server gửi ROOM_LIST → hiện sảnh chờ
3. Người chơi tạo/vào phòng
4. Mỗi khi bấm phím → gửi INPUT lên server
5. Server tính physics → gửi STATE về
6. Client nhận STATE → cập nhật renderPlayers → repaint()
```

---

## 📝 GHI CHÚ

- Client kết nối mặc định tới `localhost:8888` — nhập IP khác khi chạy
- Tài nguyên bản đồ (`tileset.png`, `chunk*.tmx`) đọc từ `source/content/map/`
- Chạy client từ **thư mục gốc project** để đường dẫn tài nguyên hoạt động đúng
