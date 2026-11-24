package source.common;

/**
 * COMMON.JAVA - Hằng số và cấu hình dùng chung toàn hệ thống
 */
/*
 * Kho hằng số chung để client và server hiểu cùng cấu hình. File không có hàm khai báo trong source; các
 * thành phần đọc trực tiếp trường static final. Các tên CMD_INPUT/CMD_STATE còn tồn tại không đồng nghĩa
 * chúng đang được dùng làm lệnh TCP gameplay.
 * TCP_PORT/UDP_PORT đều 8888; GAM1 và opcode 1–6 phân loại UDP gameplay. TICK_RATE=60, TICK_NS≈16,67 triệu
 * nano giây. MAX_PLAYERS_PER_ROOM=4. Tile gốc 16 × SCALE 3 = 48 pixel; chunk 32 tile, map 3×3 chunk, spawn
 * (1056,864). Comment 32px cạnh TILE_SIZE là mô tả cũ.
 */
public class Common {

    // CẤU HÌNH MẠNG & HỆ THỐNG
    /*
     * TCP_PORT: Cổng TCP của server.
     */
    public static final int TCP_PORT = 8888;
    /*
     * UDP_PORT: Cổng UDP của server.
     */
    public static final int UDP_PORT = 8888;
    /*
     * PORT: Bí danh cổng dùng chung trong code.
     */
    public static final int PORT = 8888;

    // UDP CẤU HÌNH & OPCODES
    /*
     * UDP_MAGIC_GAME: Chuỗi nhận diện gameplay UDP.
     */
    public static final String UDP_MAGIC_GAME = "GAM1";
    /*
     * UDP_MAGIC_GAME_BYTES: Dạng byte của magic gameplay.
     */
    public static final byte[] UDP_MAGIC_GAME_BYTES = {'G', 'A', 'M', '1'};

    /*
     * UDP_GAME_REGISTER: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_REGISTER = 1;
    /*
     * UDP_GAME_ACK: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_ACK      = 2;
    /*
     * UDP_GAME_INPUT: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_INPUT    = 3;
    /*
     * UDP_GAME_STATE: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_STATE    = 4;
    /*
     * UDP_GAME_PING: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_PING     = 5;
    /*
     * UDP_GAME_PONG: Opcode trong GAM1 phân biệt REGISTER ACK INPUT STATE PING PONG.
     */
    public static final byte UDP_GAME_PONG     = 6;

    /*
     * ORIGINAL_TILE_SIZE: Cạnh tile ảnh gốc, pixel.
     */
    public static final int ORIGINAL_TILE_SIZE = 16;
    /*
     * SCALE: Hệ số phóng ảnh tile.
     */
    public static final float SCALE = 3.0f;
    /*
     * TILE_SIZE: Cạnh tile world sau scale, thực tế 48 pixel.
     */
    public static final int TILE_SIZE = (int) (ORIGINAL_TILE_SIZE * SCALE); // 32px

    /*
     * CHUNK_SIZE: Số tile một cạnh chunk.
     */
    public static final int CHUNK_SIZE = 32; // 32x32 tiles / chunk
    /*
     * MAX_CHUNK_X: Số chunk theo chiều ngang.
     */
    public static final int MAX_CHUNK_X = 3;
    /*
     * MAX_CHUNK_Y: Số chunk theo chiều dọc.
     */
    public static final int MAX_CHUNK_Y = 3;

    /*
     * SCREEN_WIDTH: Chiều rộng cửa sổ mặc định.
     */
    public static final int SCREEN_WIDTH = 1280;
    /*
     * SCREEN_HEIGHT: Chiều cao cửa sổ mặc định.
     */
    public static final int SCREEN_HEIGHT = 720;

    // Tọa độ Spawn mặc định
    /*
     * SPAWN_X: Tọa độ world X spawn mặc định.
     */
    public static final float SPAWN_X = 22 * TILE_SIZE;
    /*
     * SPAWN_Y: Tọa độ world Y spawn mặc định.
     */
    public static final float SPAWN_Y = 18 * TILE_SIZE;

    // Tick Rate (60 Hz)
    /*
     * TICK_RATE: Tần số mô phỏng mục tiêu.
     */
    public static final int TICK_RATE = 60;
    /*
     * TICK_NS: Khoảng cách tick bằng nano giây.
     */
    public static final long TICK_NS = 1_000_000_000L / TICK_RATE;

    /*
     * MAX_PLAYERS_PER_ROOM: Giới hạn player runtime mỗi phòng.
     */
    public static final int MAX_PLAYERS_PER_ROOM = 4;

    // GIAO THỨC MẠNG (COMMAND OPCODES)
    /*
     * CMD_CREATE_ROOM: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_CREATE_ROOM = "CREATE_ROOM";
    /*
     * CMD_JOIN_ROOM: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_JOIN_ROOM   = "JOIN_ROOM";
    /*
     * CMD_ROOM_LIST: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_ROOM_LIST   = "ROOM_LIST";
    /*
     * CMD_LEAVE_ROOM: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_LEAVE_ROOM  = "LEAVE_ROOM";
    /*
     * CMD_WELCOME: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_WELCOME     = "WELCOME";
    /*
     * CMD_JOINED: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_JOINED      = "JOINED";
    /*
     * CMD_ERROR: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_ERROR       = "ERROR";
    /*
     * CMD_INPUT: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_INPUT       = "INPUT";
    /*
     * CMD_STATE: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_STATE       = "STATE";
    /*
     * CMD_ATTACK: Tên lệnh TCP; khai báo chuỗi không có nghĩa mọi tên lệnh còn được server xử lý.
     */
    public static final String CMD_ATTACK      = "ATTACK";
}