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
}