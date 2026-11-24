# Tư liệu minh họa

Ảnh, sơ đồ và dataset phục vụ báo cáo; có tư liệu thuộc bản cũ. Đối chiếu README gốc và code hiện tại trước khi dùng.

## dataset_sample.csv dùng làm gì

Đây là dữ liệu mẫu lịch sử, không được code hiện tại đọc hoặc tự sinh. Game không cần file này để chạy. Giữ file phù hợp mục dataset mẫu trong cấu trúc bài tập ở INSTRUCTION.md; không coi nó là phép đo hiệu năng hay log demo hiện tại.

| Cột | Ý nghĩa minh họa |
|---|---|
| timestamp | Thời gian mili giây |
| player_id, player_name | ID phiên và tên hiển thị |
| pos_x, pos_y | Tọa độ pixel của mẫu lịch sử |
| input_left/right/up/down | Trạng thái phím boolean |
| room_name | Tên phòng |

Timestamp, tọa độ và bước di chuyển trong mẫu thuộc bản cũ, không dùng suy ra spawn/tốc độ/tần suất hiện tại. Thu dữ liệu thật cần triển khai logger, ghi tick/sequence, đơn vị và điều kiện đo. Lần sửa tài liệu này không thêm logger Java.

CSV bên trong `<data encoding="csv">` của source/content/map/*.tmx là các ô bản đồ mà GameMap thực sự đọc. Đây là dữ liệu cần cho map, khác dataset mẫu; không xóa nhầm.

## Sơ đồ và ảnh

- diagram.png và network_flow.png: sơ đồ trước refactor, có tên class và transport cũ; sơ đồ hiện tại ở README gốc dưới dạng Mermaid để review bằng văn bản.
- demo_online_2clients.png, demo_reconnect_banner.png: ảnh lịch sử; chỉ số trên ảnh không phải benchmark hiện tại, phím debug có thể đã bỏ.
- logo.png: hình nhận diện.
- dev.txt: ghi chú lịch sử, không phải đặc tả hiện hành hay yêu cầu phải thực hiện.

Ảnh chứng minh build mới cần chụp demo thật của đúng commit và ghi môi trường/kịch bản; không biến ảnh cũ thành bằng chứng cho code mới.
