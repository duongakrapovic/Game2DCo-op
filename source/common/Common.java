package source.common;

/**
 * ============================================================================
 * ENGINE CORE GATEWAY - FILE MẸ TRUNG GIAN (COMMON.JAVA)
 * ============================================================================
 * Facade Gateway chứa Hằng số, Cấu hình Port TCP và trỏ đến các Module Engine.
 * ============================================================================
 */
public class Common {

    // ========================================================================
    // 1. CẤU HÌNH HỆ THỐNG & PORT TCP THUẦN
    // ========================================================================
    public static final int TCP_PORT = 8888;
    public static final int PORT     = 8888; // Alias tương thích với NetSelfTest & BotBench

    public static final int ORIGINAL_TILE_SIZE = 16;
    public static final float SCALE = 2.0f; 
    public static final int TILE_SIZE = (int) (ORIGINAL_TILE_SIZE * SCALE); // 32px
    
    public static final int CHUNK_SIZE = 32; // 32x32 tiles
    public static final int MAX_CHUNK_X = 3;
    public static final int MAX_CHUNK_Y = 3;

    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;

    // Tọa độ Spawn mặc định
    public static final float SPAWN_X = 22 * TILE_SIZE; // ~704px
    public static final float SPAWN_Y = 18 * TILE_SIZE; // ~576px

    // Tick Rate tiêu chuẩn cho Physics & Network (60 Hz)
    public static final int  TICK_RATE = 60;
    public static final long TICK_NS   = 1_000_000_000L / TICK_RATE;

    public static final int MAX_PLAYERS_PER_ROOM = 4;

    // ========================================================================
    // 2. GIAO THỨC MẠNG (TCP COMMANDS)
    // ========================================================================
    public static final String CMD_CREATE_ROOM = "CREATE_ROOM";
    public static final String CMD_JOIN_ROOM   = "JOIN_ROOM";
    public static final String CMD_ROOM_LIST   = "ROOM_LIST";
    public static final String CMD_LEAVE_ROOM  = "LEAVE_ROOM"; 
    public static final String CMD_WELCOME     = "WELCOME";    
    public static final String CMD_JOINED      = "JOINED";     
    public static final String CMD_ERROR       = "ERROR";
    public static final String CMD_INPUT       = "INPUT";
    public static final String CMD_STATE       = "STATE";

    // ========================================================================
    // 3. ỦY QUYỀN VÀ TRUY XUẤT MODULE CON QUA FACADE
    // ========================================================================
    public static class Player extends source.common.Player {
        public Player(String id, String name) { super(id, name); }
    }

    public static class PhysicsEngine extends source.common.PhysicsEngine {}

    public static class MapManager extends source.common.MapManager {}

    public static class Protocol extends source.common.Protocol {}
}