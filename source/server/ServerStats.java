package source.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Bộ đếm lưu lượng mạng của server + báo cáo định kỳ (console và logs/net_stats.csv).
 * Dùng để lấy số liệu cho báo cáo: số client, msg/s, KB/s, số snapshot bị gộp...
 */
public final class ServerStats {
    private ServerStats() {}

    public static final AtomicLong bytesIn = new AtomicLong();
    public static final AtomicLong bytesOut = new AtomicLong();
    public static final AtomicLong msgsIn = new AtomicLong();
    public static final AtomicLong msgsOut = new AtomicLong();
    public static final AtomicLong statesCoalesced = new AtomicLong();
    public static final AtomicInteger activeClients = new AtomicInteger();

    private static final int REPORT_INTERVAL_SEC = 5;

    public static void start(IntSupplier roomCount, boolean writeCsv) {
        Thread t = new Thread(() -> run(roomCount, writeCsv), "Server-Stats");
        t.setDaemon(true);
        t.start();
    }

    private static void run(IntSupplier roomCount, boolean writeCsv) {
        Path csv = Paths.get("logs", "net_stats.csv");
        if (writeCsv) {
            try {
                Files.createDirectories(csv.getParent());
                if (!Files.exists(csv)) {
                    Files.write(csv, "timestamp,clients,rooms,msgs_in_per_s,msgs_out_per_s,kbps_in,kbps_out,states_coalesced\n"
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                System.err.println("[Stats] Không ghi được " + csv + ": " + e.getMessage());
                writeCsv = false;
            }
        }
        long pBi = 0, pBo = 0, pMi = 0, pMo = 0, pCo = 0;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        while (true) {
            try { Thread.sleep(REPORT_INTERVAL_SEC * 1000L); } catch (InterruptedException e) { return; }
            long bi = bytesIn.get(), bo = bytesOut.get(), mi = msgsIn.get(), mo = msgsOut.get(), co = statesCoalesced.get();
            int clients = activeClients.get();
            double s = REPORT_INTERVAL_SEC;
            if (clients > 0 || bi != pBi) {
                String line = String.format(java.util.Locale.ROOT,
                        "%s,%d,%d,%.1f,%.1f,%.2f,%.2f,%d",
                        fmt.format(new Date()), clients, roomCount.getAsInt(),
                        (mi - pMi) / s, (mo - pMo) / s, (bi - pBi) / s / 1024.0, (bo - pBo) / s / 1024.0, co - pCo);
                System.out.println("[STATS] clients=" + clients + " rooms=" + roomCount.getAsInt()
                        + String.format(java.util.Locale.ROOT, " in=%.0f msg/s %.1f KB/s | out=%.0f msg/s %.1f KB/s | coalesced=%d",
                        (mi - pMi) / s, (bi - pBi) / s / 1024.0, (mo - pMo) / s, (bo - pBo) / s / 1024.0, co - pCo));
                if (writeCsv) {
                    try {
                        Files.write(csv, (line + "\n").getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ignored) {}
                }
            }
            pBi = bi; pBo = bo; pMi = mi; pMo = mo; pCo = co;
        }
    }
}
