package network;

import ui.GamePanel;
import java.io.*;
import java.net.Socket;

public class TCPClient {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private GamePanel gamePanel;

    public boolean connect(String ip, int port, GamePanel panel) {
        this.gamePanel = panel;
        try {
            socket = new Socket(ip, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("STATE:")) {
                            String[] parts = line.split(":");
                            int p1X = Integer.parseInt(parts[1]);
                            int p2X = Integer.parseInt(parts[2]);
                            gamePanel.updatePositions(p1X, p2X);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Connection lost.");
                }
            }).start();

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void sendMove(String dir) {
        if (out != null) {
            out.println("MOVE:" + dir);
        }
    }
}