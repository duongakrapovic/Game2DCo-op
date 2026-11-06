package source.client;

import source.common.Common;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientNetwork {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread netListenThread;
    private NetworkListener listener;

    public interface NetworkListener {
        void onRoomListReceived(String roomListLine);
        void onStateReceived(String stateLine);
        void onErrorReceived(String errorMsg);
        void onDisconnected();
    }

    public ClientNetwork(NetworkListener listener) {
        this.listener = listener;
    }

    public boolean connect(String host) {
        disconnect();
        try {
            socket = new Socket(host, Common.TCP_PORT);
            socket.setTcpNoDelay(true);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            netListenThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith(Common.CMD_ROOM_LIST)) {
                            if (listener != null) listener.onRoomListReceived(line);
                        } else if (line.startsWith(Common.CMD_STATE)) {
                            if (listener != null) listener.onStateReceived(line);
                        } else if (line.startsWith(Common.CMD_ERROR)) {
                            String err = line.substring(line.indexOf(":") + 1);
                            if (listener != null) listener.onErrorReceived(err);
                        }
                    }
                } catch (Exception e) {
                    // Mất kết nối
                } finally {
                    disconnect();
                    if (listener != null) listener.onDisconnected();
                }
            }, "Client-TCP-Listen");

            netListenThread.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void sendTcp(String msg) {
        if (out != null) out.println(msg);
    }

    public void disconnect() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
            if (out != null) out.close();
            if (in != null) in.close();
        } catch (IOException ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}