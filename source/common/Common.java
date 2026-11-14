package source.common;

/**
 * COMMON.JAVA - Hằng số và cấu hình dùng chung toàn hệ thống
 */
public class Common {

    // CẤU HÌNH MẠNG & HỆ THỐNG
    public static final int TCP_PORT = 8888;
    public static final int UDP_PORT = 8888;
    public static final int PORT = 8888;

    // UDP CẤU HÌNH & OPCODES
    public static final String UDP_MAGIC_GAME = "GAM1";
    public static final byte[] UDP_MAGIC_GAME_BYTES = {'G', 'A', 'M', '1'};

    public static final byte UDP_GAME_REGISTER = 1;
    public static final byte UDP_GAME_ACK      = 2;
    public static final byte UDP_GAME_INPUT    = 3;
    public static final byte UDP_GAME_STATE    = 4;
    public static final byte UDP_GAME_PING     = 5;
    public static final byte UDP_GAME_PONG     = 6;

    public static final int ORIGINAL_TILE_SIZE = 16;
    public static final float SCALE = 3.0f;
    public static final int TILE_SIZE = (int) (ORIGINAL_TILE_SIZE * SCALE); // 32px

    public static final int CHUNK_SIZE = 32; // 32x32 tiles / chunk
    public static final int MAX_CHUNK_X = 3;
    public static final int MAX_CHUNK_Y = 3;

    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;

    // Tọa độ Spawn mặc định
    public static final float SPAWN_X = 22 * TILE_SIZE;
    public static final float SPAWN_Y = 18 * TILE_SIZE;

    // Tick Rate (60 Hz)
    public static final int TICK_RATE = 60;
    public static final long TICK_NS = 1_000_000_000L / TICK_RATE;

    public static final int MAX_PLAYERS_PER_ROOM = 4;

    // GIAO THỨC MẠNG (COMMAND OPCODES)
    public static final String CMD_CREATE_ROOM = "CREATE_ROOM";
    public static final String CMD_JOIN_ROOM   = "JOIN_ROOM";
    public static final String CMD_ROOM_LIST   = "ROOM_LIST";
    public static final String CMD_LEAVE_ROOM  = "LEAVE_ROOM";
    public static final String CMD_WELCOME     = "WELCOME";
    public static final String CMD_JOINED      = "JOINED";
    public static final String CMD_ERROR       = "ERROR";
    public static final String CMD_INPUT       = "INPUT";
    public static final String CMD_STATE       = "STATE";
    public static final String CMD_ATTACK      = "ATTACK";
}