package source.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/*
 * Thu micro, gửi/nhận và phát voice UDP qua relay theo phòng. Âm thanh không đi trực tiếp giữa các client
 * và không đi qua socket gameplay của client.
 * VCH1 + một byte type; PCM signed 16 kHz, 16 bit, mono, little-endian; frame mục tiêu 640 byte/20 ms.
 * Queue phát 20 frame. running AtomicBoolean cùng cờ volatile bảo vệ vòng đời; có thread
 * receive/capture/playback/register.
 */
public class ClientVoiceChat implements AutoCloseable {
    /*
     * MAGIC: Magic VCH1 của voice client.
     */
    static final byte[] MAGIC = {'V', 'C', 'H', '1'};
    /*
     * TYPE_REGISTER: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    static final byte TYPE_REGISTER = 1;
    /*
     * TYPE_AUDIO: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    static final byte TYPE_AUDIO = 2;
    /*
     * TYPE_ACK: Loại gói VCH1 phân biệt đăng ký, audio và ACK.
     */
    static final byte TYPE_ACK = 3;

    /*
     * AUDIO_FORMAT: PCM mono signed 16 kHz 16 bit little endian.
     */
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16_000, 16, 1, true, false);
    /*
     * FRAME_BYTES: Kích thước mục tiêu một frame voice 20 ms.
     */
    private static final int FRAME_BYTES = 640;
    /*
     * HEADER_BYTES: Header VCH1 gồm magic và type.
     */
    private static final int HEADER_BYTES = MAGIC.length + 1;
    /*
     * MAX_PACKET_BYTES: Dung lượng buffer nhận voice.
     */
    private static final int MAX_PACKET_BYTES = 1200;

    /*
     * socket: Socket TCP hoặc UDP theo kiểu khai báo; serverSocket nhận kết nối TCP mới.
     */
    private final DatagramSocket socket;
    /*
     * serverAddress: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private final InetAddress serverAddress;
    /*
     * serverPort: Địa chỉ và cổng đích; host có thể cần DNS, số cổng không phải ID phòng.
     */
    private final int serverPort;
    /*
     * registrationPacket: Gói token voice đã dựng, cờ ACK voice và trạng thái tắt gửi micro.
     */
    private final byte[] registrationPacket;
    /*
     * statusListener: Callback nhận kết quả mạng hoặc trạng thái; caller quyết định chuyển sang EDT.
     */
    private final Consumer<String> statusListener;
    /*
     * running: Cờ vòng đời hoặc thế hệ tác vụ; generation ngăn callback/gói cũ áp vào lượt mới.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);
    /*
     * playbackQueue: Hàng đợi tối đa 20 frame voice; đầy thì bỏ cũ nhất để giới hạn tích lũy trễ.
     */
    private final ArrayBlockingQueue<byte[]> playbackQueue = new ArrayBlockingQueue<>(20);
    /*
     * startedAtNanos: Mốc nanoTime để xét ACK voice quá hạn và cờ relay không phản hồi.
     */
    private final long startedAtNanos = System.nanoTime();
    /*
     * muted: Gói token voice đã dựng, cờ ACK voice và trạng thái tắt gửi micro.
     */
    private volatile boolean muted;
    /*
     * registered: Gói token voice đã dựng, cờ ACK voice và trạng thái tắt gửi micro.
     */
    private volatile boolean registered;
    /*
     * microphoneAvailable: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile boolean microphoneAvailable;
    /*
     * microphoneChecked: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile boolean microphoneChecked;
    /*
     * speakersAvailable: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile boolean speakersAvailable;
    /*
     * speakersChecked: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile boolean speakersChecked;
    /*
     * microphoneError: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile String microphoneError;
    /*
     * speakersError: Kết quả dò và lỗi thiết bị âm thanh; dùng tạo status cụ thể.
     */
    private volatile String speakersError;
    /*
     * relayUnavailable: Mốc nanoTime để xét ACK voice quá hạn và cờ relay không phản hồi.
     */
    private volatile boolean relayUnavailable;
    /*
     * lastAckAtNanos: Mốc nanoTime để xét ACK voice quá hạn và cờ relay không phản hồi.
     */
    private volatile long lastAckAtNanos;
    /*
     * microphone: Thiết bị thu TargetDataLine và phát SourceDataLine của Java Sound.
     */
    private volatile TargetDataLine microphone;
    /*
     * speakers: Thiết bị thu TargetDataLine và phát SourceDataLine của Java Sound.
     */
    private volatile SourceDataLine speakers;

    /*
     * Khởi tạo ClientVoiceChat
     * Resolve host, kiểm token không rỗng/tối đa 128 ký tự, tạo packet đăng ký/socket UDP timeout một
     * giây, báo Connecting và start bốn daemon thread. Không yêu cầu thiết bị âm thanh phải mở thành công
     * trước khi game tiếp tục.
     */
    public ClientVoiceChat(String host, int port, String token, Consumer<String> statusListener)
            throws IOException {
        this.serverAddress = resolveHost(host);
        this.serverPort = port;
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        Objects.requireNonNull(token, "token");
        if (token.isEmpty() || token.length() > 128) {
            throw new IllegalArgumentException("Invalid voice session token");
        }
        this.registrationPacket = createPacket(TYPE_REGISTER, token.getBytes(StandardCharsets.UTF_8));
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(1000);

        reportStatus("VOICE: Connecting...");
        startThread(this::receiveLoop, "Voice-Receive");
        startThread(this::captureLoop, "Voice-Capture");
        startThread(this::playbackLoop, "Voice-Playback");
        startThread(this::registrationLoop, "Voice-Register");
    }

    /*
     * resolveHost()
     * Tra InetAddress từ host; DNS không hợp lệ ném UnknownHostException cho caller.
     */
    private static InetAddress resolveHost(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    /*
     * startThread()
     * Tạo daemon thread với task/tên rồi start; dùng cho bốn công việc voice riêng.
     */
    private void startThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /*
     * isMuted()
     * Trả trạng thái mute gửi micro, không có nghĩa loa đang tắt.
     */
    public boolean isMuted() {
        return muted;
    }

    /*
     * toggleMuted()
     * Đảo muted và cập nhật status. Capture vẫn có thể đọc micro nhưng không gửi audio khi muted.
     */
    public void toggleMuted() {
        muted = !muted;
        updateStatus();
    }

    /*
     * registerNow()
     * Hạ registered=false và gửi đăng ký ngay; dùng khi vừa vào/resume phòng, không tạo socket mới.
     */
    public void registerNow() {
        registered = false;
        sendRegistration();
    }

    /*
     * registrationLoop()
     * Gửi REGISTER mỗi khoảng hai giây. Không có ACK quá sáu giây tính từ ACK cuối hoặc lúc bắt đầu thì
     * báo relayUnavailable, bỏ registered. Interrupt làm vòng kết thúc.
     */
    private void registrationLoop() {
        while (running.get()) {
            sendRegistration();
            long lastAck = lastAckAtNanos;
            long reference = lastAck == 0 ? startedAtNanos : lastAck;
            if (System.nanoTime() - reference > 6_000_000_000L) {
                relayUnavailable = true;
                registered = false;
                updateStatus();
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

        }
    }

    /*
     * sendRegistration()
     * Gửi registrationPacket đã tạo nếu còn running; lỗi mạng báo status, không tự đóng gameplay.
     */
    private void sendRegistration() {
        if (!running.get()) return;
        try {
            sendPacket(registrationPacket, registrationPacket.length, serverAddress, serverPort);
        } catch (IOException e) {
            if (running.get()) reportStatus("VOICE: Server unavailable (" + e.getMessage() + ")");
        }
    }

    /*
     * receiveLoop()
     * Chỉ nhận gói từ IP/port server có magic và header đúng. ACK đánh dấu registered và thời điểm ACK;
     * AUDIO được copy vào playbackQueue. Queue đầy bỏ frame cũ rồi thêm mới để chặn tích trễ. Socket đóng
     * dừng vòng, timeout không coi là lỗi terminal.
     * Chỉ nhận gói đúng địa chỉ/cổng server và magic VCH1. ACK cập nhật trạng thái relay; AUDIO copy
     * payload ra khỏi buffer nhận rồi offer vào queue 20 phần tử. Queue đầy thì poll cũ nhất, không chờ nó
     * phát hết và tăng trễ không giới hạn.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (!packet.getAddress().equals(serverAddress) || packet.getPort() != serverPort
                        || packet.getLength() < HEADER_BYTES || !hasMagic(packet.getData())) {
                    continue;
                }

                byte type = packet.getData()[MAGIC.length];
                if (type == TYPE_ACK) {
                    registered = true;
                    lastAckAtNanos = System.nanoTime();
                    relayUnavailable = false;
                    updateStatus();
                } else if (type == TYPE_AUDIO && packet.getLength() > HEADER_BYTES) {
                    byte[] audio = Arrays.copyOfRange(
                            packet.getData(), HEADER_BYTES, packet.getLength());
                    /*
                     * Queue đầy thì bỏ frame cũ nhất để không tích lũy trễ vô hạn; chưa phải jitter buffer
                     * hoàn chỉnh.
                     */
                    if (!playbackQueue.offer(audio)) {
                        playbackQueue.poll();
                        playbackQueue.offer(audio);
                    }
                }
            } catch (SocketException e) {
                if (running.get()) reportStatus("VOICE: Network error: " + e.getMessage());
                return;
            } catch (IOException e) {
                if (running.get() && !(e instanceof java.net.SocketTimeoutException)) {
                    reportStatus("VOICE: Network error: " + e.getMessage());
                }
            }
        }
    }

    /*
     * captureLoop()
     * Mở TargetDataLine theo AudioFormat, đọc frame 640 byte. Chỉ gửi VCH1 AUDIO khi có dữ liệu,
     * registered và không mute. Báo lỗi thiết bị/quyền/I/O; finally dừng/đóng micro. Không nén PCM.
     * Mở TargetDataLine với PCM mono 16 kHz 16 bit little endian; đọc frame mục tiêu 640 byte. Chỉ gửi khi
     * registered và không muted. Mute vẫn đọc micro và vẫn nghe người khác; vòng gửi không mã hóa hoặc nén
     * PCM.
     */
    private void captureLoop() {
        TargetDataLine line = null;
        try {
            line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT));
            line.open(AUDIO_FORMAT, FRAME_BYTES * 4);
            line.start();
            microphone = line;
            microphoneAvailable = true;
            microphoneChecked = true;
            updateStatus();

            byte[] frame = new byte[FRAME_BYTES];
            while (running.get()) {
                int length = line.read(frame, 0, frame.length);
                /*
                 * Mute chỉ ngăn gửi mic; capture vẫn đọc thiết bị và playback vẫn nghe người khác.
                 */
                if (length > 0 && registered && !muted) {
                    byte[] packet = createPacket(TYPE_AUDIO, Arrays.copyOf(frame, length));
                    sendPacket(packet, packet.length, serverAddress, serverPort);
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            microphoneChecked = true;
            microphoneError = e.getMessage();
            if (running.get()) updateStatus();
        } catch (SecurityException e) {
            microphoneChecked = true;
            microphoneError = e.getMessage();
            if (running.get()) updateStatus();
        } catch (IOException e) {
            microphoneError = e.getMessage();
            if (running.get()) updateStatus();
        } finally {
            microphoneAvailable = false;
            microphone = null;
            if (running.get()) updateStatus();
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /*
     * playbackLoop()
     * Mở SourceDataLine và lấy frame từ queue để ghi ra loa. Chờ take khi chưa có dữ liệu, xử lý thiếu
     * thiết bị/interruption và đóng line khi dừng. Không có mixer/timestamp riêng cho từng người nói.
     */
    private void playbackLoop() {
        SourceDataLine line = null;
        try {
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT));
            line.open(AUDIO_FORMAT, FRAME_BYTES * 4);
            line.start();
            speakers = line;
            speakersAvailable = true;
            speakersChecked = true;
            updateStatus();

            while (running.get()) {
                byte[] audio = playbackQueue.take();
                line.write(audio, 0, audio.length);
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            speakersChecked = true;
            speakersError = e.getMessage();
            if (running.get()) updateStatus();
        } catch (SecurityException e) {
            speakersChecked = true;
            speakersError = e.getMessage();
            if (running.get()) updateStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            speakers = null;
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /*
     * updateStatus()
     * Chọn chuỗi status theo relay, đăng ký, lỗi micro/loa, mute hoặc micro sẵn; ưu tiên phản ánh nguyên
     * nhân hiện tại cho UI.
     */
    private void updateStatus() {
        if (relayUnavailable) {
            reportStatus("VOICE: UDP relay unavailable");
        } else if (!registered) {
            reportStatus("VOICE: Connecting...");
        } else if (microphoneChecked && !microphoneAvailable) {
            reportStatus("VOICE: Microphone unavailable"
                    + (microphoneError == null ? "" : " (" + microphoneError + ")"));
        } else if (speakersChecked && !speakersAvailable) {
            reportStatus("VOICE: Speakers unavailable"
                    + (speakersError == null ? "" : " (" + speakersError + ")"));
        } else if (muted) {
            reportStatus("VOICE: Muted (M to unmute)");
        } else if (!microphoneAvailable) {
            reportStatus("VOICE: Connected; checking audio devices");
        } else {
            reportStatus("VOICE: Microphone on (M to mute)");
        }
    }

    /*
     * reportStatus()
     * Gọi Consumer<String> được truyền vào constructor; ClientApp tự chuyển status sang EDT.
     */
    private void reportStatus(String status) {
        statusListener.accept(status);
    }

    /*
     * sendPacket()
     * Tạo DatagramPacket từ data/length/address/port rồi socket.send, truyền IOException cho caller xử lý.
     */
    private void sendPacket(byte[] data, int length, InetAddress address, int port) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, length, address, port);
        socket.send(packet);
    }

    /*
     * createPacket()
     * Cấp mảng header VCH1 + type + payload và chép dữ liệu; frame audio đủ tạo 645 byte ứng dụng.
     */
    private static byte[] createPacket(byte type, byte[] payload) {
        byte[] packet = new byte[HEADER_BYTES + payload.length];
        System.arraycopy(MAGIC, 0, packet, 0, MAGIC.length);
        packet[MAGIC.length] = type;
        System.arraycopy(payload, 0, packet, HEADER_BYTES, payload.length);
        return packet;
    }

    /*
     * hasMagic()
     * So bốn byte VCH1. Caller kiểm chiều dài trước; magic không là chữ ký bảo mật.
     */
    private static boolean hasMagic(byte[] data) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }

    /*
     * close()
     * compareAndSet đảm bảo dọn một lần, đóng socket và thiết bị, xóa queue rồi thêm frame rỗng để đánh
     * thức luồng playback đang take. Không gọi shutdown server room.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        socket.close();
        TargetDataLine mic = microphone;
        if (mic != null) mic.close();
        SourceDataLine output = speakers;
        if (output != null) output.close();
        playbackQueue.clear();
        playbackQueue.offer(new byte[0]);
    }
}
