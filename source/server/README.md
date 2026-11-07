# Module Server

Thư mục server gồm 4 file Java:

| Class | Trách nhiệm |
|---|---|
| `ServerApp` | Entry point, quản lý phòng/phiên, khởi động và tắt server |
| `ServerNetwork` | TCP game commands, UDP voice relay, quản lý kết nối và thành viên phòng |
| `ServerSession` | ID, token, phòng và thời điểm hoạt động của phiên |
| `ServerGame` | Vòng lặp authoritative 60 Hz cho mỗi phòng, di chuyển, slime, chiến đấu và snapshot |

Biên dịch và chạy từ thư mục gốc:

```bat
javac -encoding UTF-8 source\common\*.java source\server\*.java
java -cp . source.server.ServerApp
```

Client dùng TCP và UDP cùng cổng `Common.TCP_PORT` (mỗi giao thức có socket riêng);
hãy mở cả TCP lẫn UDP trên firewall/router để dùng voice chat. Voice endpoint chỉ
được đăng ký bằng session token hợp lệ và voice chỉ được relay tới người cùng phòng.
Mỗi phòng khởi tạo 15 slime; server
xử lý input người chơi, AI và sát thương của quái, hồi sinh slime, rồi gửi
snapshot qua định dạng chung của `GameProtocol`. Phiên ngắt kết nối được giữ
trong 20 giây để client có thể dùng token `RESUME`.
