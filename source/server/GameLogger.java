package source.server;

import source.common.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ghi log trạng thái game của một phòng ra CSV (logs/game_state_<phòng>_<thời gian>.csv).
 * Định dạng cột trùng với statics/dataset_sample.csv.
 *
 * Ghi bất đồng bộ bằng luồng riêng để việc ghi đĩa không làm chậm game loop 60 FPS.
 * Bật/tắt: tham số server --no-log (hoặc -Dgame.log=false). Tần suất: -Dlog.every=<số tick> (mặc định 6 = 10 Hz).
 */
public class GameLogger implements Runnable {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("game.log", "true"));
    public static final int EVERY_N_TICKS = Integer.getInteger("log.every", 6);
    private static final String HEADER =
            "timestamp,player_id,player_name,pos_x,pos_y,input_left,input_right,input_up,input_down,room_name";
    private static final String POISON = "\0EOF";

    private final String roomName;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(20_000);
    private final Path file;

    public GameLogger(String roomName) {
        this.roomName = roomName;
        String safe = roomName.replaceAll("[^A-Za-z0-9_-]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        this.file = Paths.get("logs", "game_state_" + safe + "_" + stamp + ".csv");
        if (ENABLED) {
            Thread t = new Thread(this, "GameLogger-" + safe);
            t.setDaemon(true);
            t.start();
        }
    }

    /** Gọi từ game loop — không bao giờ chặn (đầy hàng đợi thì bỏ qua dòng log). */
    public void log(Player p) {
        if (!ENABLED) return;
        queue.offer(System.currentTimeMillis() + "," + p.id + "," + p.name + ","
                + (int) p.x + "," + (int) p.y + ","
                + p.left + "," + p.right + "," + p.up + "," + p.down + "," + roomName);
    }

    public void close() {
        if (ENABLED) queue.offer(POISON);
    }

    @Override
    public void run() {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(HEADER);
                w.newLine();
                while (true) {
                    String line = queue.poll(1, TimeUnit.SECONDS);
                    if (line == null) { w.flush(); continue; }
                    if (line.equals(POISON)) break;
                    w.write(line);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("[GameLogger] Không ghi được " + file + ": " + e.getMessage());
        } catch (InterruptedException ignored) {}
    }
}
