# Module Server

Thư mục server gồm 4 file Java:

| Class | Trách nhiệm |
|---|---|
| `ServerApp` | Entry point, quản lý phòng/phiên, khởi động và tắt server |
| `ServerNetwork` | TCP accept, nhận lệnh client, quản lý thành viên phòng và gửi dữ liệu |
| `ServerSession` | ID, token, phòng và thời điểm hoạt động của phiên |
| `ServerGame` | Vòng lặp authoritative 60 Hz cho mỗi phòng, di chuyển, slime, chiến đấu và snapshot |

Biên dịch và chạy từ thư mục gốc:

```bat
javac -encoding UTF-8 source\common\*.java source\server\*.java
java -cp . source.server.ServerApp
```

Client kết nối qua cổng `Common.TCP_PORT`. Mỗi phòng khởi tạo 15 slime; server
xử lý input người chơi, AI và sát thương của quái, hồi sinh slime, rồi gửi
snapshot qua định dạng chung của `GameProtocol`. Phiên ngắt kết nối được giữ
trong 20 giây để client có thể dùng token `RESUME`.
