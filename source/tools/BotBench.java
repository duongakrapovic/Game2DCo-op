package source.tools;

import source.common.Common;
import source.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Công cụ đo tải: tạo N "bot" kết nối vào server, mỗi bot gửi INPUT 60 lần/giây như một client thật
 * và đo RTT (PING/PONG), tần suất STATE nhận được, băng thông vào/ra.
 *
 *   java -cp . source.tools.BotBench [host] [bots=8] [seconds=10] [port]
 *
 * Bot được xếp vào các phòng, mỗi phòng tối đa MAX_PLAYERS_PER_ROOM người.
 * Kết quả in ra màn hình và nối thêm vào logs/bench_results.csv (dùng làm số liệu cho báo cáo).
 */
public class BotBench {

    static class Bot implements Runnable {
        final int index;
        final String host, room;
        final int port, seconds;
        final boolean creator;
        final CountDownLatch ready, start;

        final List<Double> rtts = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong statesIn = new AtomicLong(), bytesIn = new AtomicLong(), bytesOut = new AtomicLong();
        volatile boolean joined = false, dropped = false, finished = false;
        volatile long firstStateAt = 0, lastStateAt = 0;

        Bot(int index, String host, int port, String room, boolean creator, int seconds, CountDownLatch ready, CountDownLatch start) {
            this.index = index; this.host = host; this.port = port; this.room = room;
            this.creator = creator; this.seconds = seconds; this.ready = ready; this.start = start;
        }

        @Override
        public void run() {
            Random rnd = new Random(index * 7919L);
            try (Socket s = new Socket(host, port)) {
                s.setTcpNoDelay(true);
                s.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                Thread reader = new Thread(() -> {
                    try {
                        String l;
                        while ((l = Protocol.readLineLimited(in, Protocol.MAX_LINE_LENGTH_CLIENT)) != null) {
                            bytesIn.addAndGet(l.length() + 1L);
                            if (l.startsWith("STATE")) {
                                long now = System.nanoTime();
                                if (firstStateAt == 0) firstStateAt = now;
                                lastStateAt = now;
                                statesIn.incrementAndGet();
                            } else if (l.startsWith("PONG:")) {
                                try {
                                    rtts.add((System.nanoTime() - Long.parseLong(l.substring(5))) / 1e6);
                                } catch (NumberFormatException ignored) {}
                            } else if (l.startsWith("JOINED")) {
                                joined = true;
                            }
                        }
                    } catch (IOException e) {
                        if (!finished) dropped = true; // chỉ tính là rớt nếu xảy ra TRƯỚC khi bot kết thúc
                    }
                }, "bot-reader-" + index);
                reader.setDaemon(true);
                reader.start();

                // người tạo phòng vào trước, những người còn lại vào sau
                if (!creator) { ready.await(); Thread.sleep(150); }
                send(out, (creator ? "CREATE_ROOM:" : "JOIN_ROOM:") + room + ":Bot" + index);
                for (int i = 0; i < 100 && !joined; i++) Thread.sleep(20);
                if (creator) ready.countDown();
                start.countDown();
                start.await();

                long tick = Common.TICK_NS;
                long next = System.nanoTime();
                long end = next + seconds * 1_000_000_000L;
                int seq = 0, keep = 0;
                boolean[] k = new boolean[4];
                long nextPing = next;
                while (System.nanoTime() < end && !dropped) {
                    if (keep-- <= 0) { // đổi hướng ngẫu nhiên mỗi ~0.5–1.5 giây
                        Arrays.fill(k, false);
                        int d = rnd.nextInt(5);
                        if (d < 4) k[d] = true;
                        keep = 30 + rnd.nextInt(60);
                    }
                    send(out, "INPUT:" + k[0] + ":" + k[1] + ":" + k[2] + ":" + k[3] + ":" + (seq++));
                    if (System.nanoTime() >= nextPing) {
                        send(out, "PING:" + System.nanoTime());
                        nextPing += 500_000_000L;
                    }
                    next += tick;
                    long sleep = next - System.nanoTime();
                    if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                }
                Thread.sleep(300);
                finished = true;
            } catch (Exception e) {
                dropped = true;
                ready.countDown();
                start.countDown();
            }
        }

        void send(PrintWriter out, String m) {
            out.print(m + "\n");
            out.flush();
            bytesOut.addAndGet(m.length() + 1L);
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int bots = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        int port = args.length > 3 ? Integer.parseInt(args[3]) : Common.PORT;
        int perRoom = Common.MAX_PLAYERS_PER_ROOM;
        int rooms = (bots + perRoom - 1) / perRoom;
        String runId = Long.toString(System.currentTimeMillis() % 100000);

        System.out.println("BotBench: " + bots + " bot, " + rooms + " phòng, " + seconds + "s -> " + host + ":" + port);

        CountDownLatch start = new CountDownLatch(bots);
        List<Bot> list = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        CountDownLatch[] ready = new CountDownLatch[rooms];
        for (int r = 0; r < rooms; r++) ready[r] = new CountDownLatch(1);
        for (int i = 0; i < bots; i++) {
            int r = i / perRoom;
            Bot b = new Bot(i, host, port, "bench" + runId + "_" + r, i % perRoom == 0, seconds, ready[r], start);
            list.add(b);
            Thread t = new Thread(b, "bot-" + i);
            threads.add(t);
            t.start();
            Thread.sleep(20);
        }
        for (Thread t : threads) t.join();

        // ---- tổng hợp ----
        List<Double> all = new ArrayList<>();
        double stateHz = 0, kbDown = 0, kbUp = 0;
        int joined = 0, dropped = 0;
        for (Bot b : list) {
            all.addAll(b.rtts);
            if (b.joined) joined++;
            if (b.dropped) dropped++;
            double span = (b.lastStateAt - b.firstStateAt) / 1e9;
            if (span > 0) stateHz += b.statesIn.get() / span;
            kbDown += b.bytesIn.get() / 1024.0 / Math.max(1, seconds);
            kbUp += b.bytesOut.get() / 1024.0 / Math.max(1, seconds);
        }
        Collections.sort(all);
        double avg = all.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
        double p95 = all.isEmpty() ? -1 : all.get((int) Math.min(all.size() - 1, Math.floor(all.size() * 0.95)));
        double max = all.isEmpty() ? -1 : all.get(all.size() - 1);
        int n = Math.max(1, bots);

        String summary = String.format(Locale.ROOT,
                "bots=%d rooms=%d joined=%d dropped=%d | RTT avg=%.2f ms p95=%.2f ms max=%.2f ms | STATE/bot=%.1f Hz | down/bot=%.1f KB/s up/bot=%.1f KB/s",
                bots, rooms, joined, dropped, avg, p95, max, stateHz / n, kbDown / n, kbUp / n);
        System.out.println(summary);

        Path csv = Paths.get("logs", "bench_results.csv");
        Files.createDirectories(csv.getParent());
        if (!Files.exists(csv)) {
            Files.write(csv, ("timestamp,bots,rooms,seconds,joined,dropped,rtt_avg_ms,rtt_p95_ms,rtt_max_ms,"
                    + "state_hz_per_bot,kbps_down_per_bot,kbps_up_per_bot\n").getBytes(StandardCharsets.UTF_8));
        }
        String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.1f,%.2f,%.2f\n",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), bots, rooms, seconds, joined, dropped,
                avg, p95, max, stateHz / n, kbDown / n, kbUp / n);
        Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("Đã ghi thêm vào " + csv);
        System.exit(0);
    }
}
