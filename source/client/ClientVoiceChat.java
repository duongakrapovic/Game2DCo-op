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

public class ClientVoiceChat implements AutoCloseable {
    static final byte[] MAGIC = {'V', 'C', 'H', '1'};
    static final byte TYPE_REGISTER = 1;
    static final byte TYPE_AUDIO = 2;
    static final byte TYPE_ACK = 3;

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16_000, 16, 1, true, false);
    private static final int FRAME_BYTES = 640;
    private static final int HEADER_BYTES = MAGIC.length + 1;
    private static final int MAX_PACKET_BYTES = 1200;

    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final byte[] registrationPacket;
    private final Consumer<String> statusListener;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ArrayBlockingQueue<byte[]> playbackQueue = new ArrayBlockingQueue<>(20);
    private final long startedAtNanos = System.nanoTime();
    private volatile boolean muted;
    private volatile boolean registered;
    private volatile boolean microphoneAvailable;
    private volatile boolean microphoneChecked;
    private volatile boolean speakersAvailable;
    private volatile boolean speakersChecked;
    private volatile String microphoneError;
    private volatile String speakersError;
    private volatile boolean relayUnavailable;
    private volatile long lastAckAtNanos;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;

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

    private static InetAddress resolveHost(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    private void startThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isMuted() {
        return muted;
    }

    public void toggleMuted() {
        muted = !muted;
        updateStatus();
    }

    public void registerNow() {
        registered = false;
        sendRegistration();
    }

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

    private void sendRegistration() {
        if (!running.get()) return;
        try {
            sendPacket(registrationPacket, registrationPacket.length, serverAddress, serverPort);
        } catch (IOException e) {
            if (running.get()) reportStatus("VOICE: Server unavailable (" + e.getMessage() + ")");
        }
    }

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

    private void reportStatus(String status) {
        statusListener.accept(status);
    }

    private void sendPacket(byte[] data, int length, InetAddress address, int port) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, length, address, port);
        socket.send(packet);
    }

    private static byte[] createPacket(byte type, byte[] payload) {
        byte[] packet = new byte[HEADER_BYTES + payload.length];
        System.arraycopy(MAGIC, 0, packet, 0, MAGIC.length);
        packet[MAGIC.length] = type;
        System.arraycopy(payload, 0, packet, HEADER_BYTES, payload.length);
        return packet;
    }

    private static boolean hasMagic(byte[] data) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }

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
