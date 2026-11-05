package source.common;

public class Common {
    public static final int PORT = 8888;

    public static final int ORIGINAL_TILE_SIZE = 16;
    public static final float SCALE = 2.0f; 
    public static final int TILE_SIZE = (int) (ORIGINAL_TILE_SIZE * SCALE); // 32px
    
    public static final int CHUNK_SIZE = 32; // 32x32 tiles
    public static final int MAX_CHUNK_X = 3;
    public static final int MAX_CHUNK_Y = 3;

    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;

    // Đặt Spawn tại ô (col 22, row 18) của Chunk 0_0 -> Rơi đúng giữa khu đất vàng rộng
    public static final float SPAWN_X = 22 * TILE_SIZE; // ~704px
    public static final float SPAWN_Y = 18 * TILE_SIZE; // ~576px

    // Protocol
    public static final String CMD_CREATE_ROOM = "CREATE_ROOM";
    public static final String CMD_JOIN_ROOM   = "JOIN_ROOM";
    public static final String CMD_ROOM_LIST   = "ROOM_LIST";
    public static final String CMD_INPUT       = "INPUT";
    public static final String CMD_STATE       = "STATE";
    public static final String CMD_ERROR       = "ERROR";

    // --- Protocol v2 (phần mạng) ---
    public static final String CMD_LEAVE_ROOM  = "LEAVE_ROOM"; // C->S: rời phòng
    public static final String CMD_PING        = "PING";       // C->S: PING:<nanoTime>
    public static final String CMD_PONG        = "PONG";       // S->C: PONG:<nanoTime> (echo)
    public static final String CMD_WELCOME     = "WELCOME";    // S->C: WELCOME:<id>:<token>
    public static final String CMD_JOINED      = "JOINED";     // S->C: JOINED:<tênPhòng>
    public static final String CMD_LEFT        = "LEFT";       // S->C: đã rời phòng
    public static final String CMD_RESUME      = "RESUME";     // C->S: RESUME:<token> (vào lại sau khi rớt mạng)
    public static final String CMD_RESUMED     = "RESUMED";    // S->C: RESUMED:<tênPhòng>
    public static final String CMD_RESUME_FAIL = "RESUME_FAIL";// S->C: phiên hết hạn / không tồn tại

    // Tick cố định của server (và của client khi dự đoán) — phải giống nhau để reconcile chính xác
    public static final int  TICK_RATE = 60;
    public static final long TICK_NS   = 1_000_000_000L / TICK_RATE;

    public static final int MAX_PLAYERS_PER_ROOM = 4;
}