# Module Client

Client là ứng dụng Java Swing, xử lý giao diện menu/sảnh/game, gửi input và
hiển thị trạng thái do server authoritative cung cấp. Prediction, reconciliation
và nội suy người chơi khác luôn được áp dụng tự động.

## Chạy

Chạy từ thư mục gốc của dự án để client tìm được tài nguyên:

```bat
run-client.bat
```

Hoặc biên dịch và chạy thủ công:

```bat
javac -encoding UTF-8 source\common\*.java source\client\*.java
java -cp . source.client.ClientApp
```

## Cấu trúc

| Class | Trách nhiệm |
|---|---|
| `ClientApp` | Menu, sảnh phòng, điều khiển, kết nối các thành phần client |
| `ClientNetwork` | Kết nối TCP, heartbeat `PING/PONG` không đo RTT và tự kết nối lại bằng `RESUME` |
| `ClientMovement` | Prediction/reconciliation và nội suy snapshot người chơi khác |
| `ClientGame` | Render bản đồ, người chơi, quái và trạng thái voice |
| `ClientVoiceChat` | Thu/phát PCM qua Java Sound và gửi/nhận voice qua UDP relay |

Voice chat tự bật micro khi vào phòng online. Nhấn `M` để mute/unmute; voice
dừng khi rời phòng hoặc thoát game. Cần cho phép cả TCP và UDP qua cổng `8888`
trên máy chủ. Nếu không có thiết bị micro/loa, game vẫn chạy nhưng voice tương
ứng sẽ không hoạt động.

## Điều khiển

| Phím | Hành động |
|---|---|
| `W` `A` `S` `D` / phím mũi tên | Di chuyển |
| `M` | Tắt/bật micro trong phòng online |
| `ESC` | Thoát về menu |
