# MODULE CLIENT – Game Client (Java Swing)

---

## 🎯 MỤC TIÊU

Client chịu trách nhiệm:
- Hiển thị giao diện (menu, sảnh phòng, bản đồ tile, nhân vật) bằng Java Swing
- Nhận phím (WASD / mũi tên) và gửi `INPUT` lên server **theo tick cố định 60 Hz**
- **Dự đoán** chuyển động của nhân vật mình và **hòa giải** với server; **nội suy** người chơi khác
- Duy trì kết nối: heartbeat, đo ping, **tự kết nối lại** khi rớt mạng
- Hỗ trợ **chế độ Offline** (chơi đơn, không cần server)

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ |
|------------|-----------|
| Ngôn ngữ | Java 17+ |
| Giao diện | Java Swing (`JFrame`, `JPanel`, `CardLayout`) |
| Mạng | `java.net.Socket` (TCP, `TCP_NODELAY`, UTF-8) |
| Đa luồng | Luồng đọc mạng, luồng heartbeat, luồng tick online 60 Hz, luồng game loop offline |
| Bản đồ | `common/MapManager` |

---

## 🚀 HƯỚNG DẪN CHẠY

### Cách 1: Dùng file bat (khuyên dùng)
```bash
# Chạy từ thư mục gốc của project
run-client.bat
```

### Cách 2: Thủ công
```bash
javac -encoding UTF-8 source/common/*.java source/client/*.java
java -cp . source.client.ClientApp
java -cp . source.client.ClientApp --offline     # vào thẳng chế độ offline
java -cp . source.client.ClientApp --lag=150     # giả lập độ trễ 150 ms mỗi chiều
```

---

## 📦 CẤU TRÚC MODULE

| Class | Trách nhiệm |
|-------|------------|
| `ClientApp` | UI (Menu / Lobby / Game), vẽ, HUD, phím. Nối các thành phần mạng lại với nhau |
| `NetClient` | Tầng mạng không phụ thuộc Swing: kết nối, luồng đọc, `PING/PONG` (RTT, jitter), tự kết nối lại bằng `RESUME`, giả lập lag, thống kê |
| `ClientPrediction` | Dự đoán chuyển động bằng đúng `PhysicsEngine` của server; lưu input chưa được `ack`; hòa giải khi nhận `STATE` |
| `SnapshotInterpolator` | Lưu snapshot của người chơi khác và nội suy ở thời điểm "hiện tại − 100 ms" |

### Luồng hoạt động (Online)
```
1. NetClient.connect() -> server gửi WELCOME (id + token) và ROOM_LIST -> hiện sảnh chờ
2. Tạo / vào phòng -> chờ JOINED rồi mới chuyển sang màn hình game (lỗi thì ở lại sảnh)
3. Mỗi tick 1/60 s (Online-Tick-Thread):
     a. ClientPrediction.step(phím) -> vị trí dự đoán ngay lập tức
     b. gửi INPUT:l:r:u:d:seq
     c. dựng danh sách vẽ: mình = vị trí dự đoán, người khác = nội suy
4. Nhận STATE -> lưu snapshot cho nội suy; với nhân vật mình: ClientPrediction.onServerState(x, y, ack)
5. Mất kết nối -> hiện biểu ngữ, NetClient tự thử lại và gửi RESUME:<token>; thành công thì đồng bộ lại vị trí
```

---

## 🎮 ĐIỀU KHIỂN

| Phím | Hành động |
|------|-----------|
| `W` `A` `S` `D` / mũi tên | Di chuyển (đi chéo tự chuẩn hóa tốc độ) |
| `ESC` | Thoát về menu |
| `F3` | Bật/tắt HUD mạng |
| `F4` | Đổi độ trễ giả lập mỗi chiều (0 / 50 / 100 / 200 / 400 ms) |
| `F6` | Bật/tắt dự đoán (Prediction) |
| `F7` | Bật/tắt nội suy (Interpolation) |
| `F8` | Đổi độ trễ nội suy (50 / 100 / 150 / 250 ms) |
| `F9` | Giả lập rớt mạng |

**Gợi ý demo:** bấm `F4` đến 200 ms rồi `F6` để tắt Prediction ⇒ nhân vật phản hồi trễ rõ rệt; bật lại ⇒ phản hồi tức thì
và HUD vẫn báo sai số reconcile ≈ 0 px.

---

## 📝 GHI CHÚ

- Chạy client từ **thư mục gốc project** để đường dẫn tài nguyên (`source/content/...`) hoạt động đúng.
- Muốn thêm thông tin vẽ cho gameplay: `renderPlayers` (map `RenderPlayer`) là nơi `GamePanel.paintComponent` đọc; `ClientApp.handleServerMessage` là nơi thêm xử lý message mới từ server.
- Chế độ Offline không dùng mạng: physics chạy trực tiếp tại client với `dt` thực.
