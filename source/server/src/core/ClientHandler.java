package core;

import game.Room;
import protocol.Command;
import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Room room;

    public ClientHandler(Socket socket, Room room) {
        this.socket = socket;
        this.room = room;
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith(Command.MOVE)) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        room.handleMove(this, parts[1]);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Client disconnected.");
        }
    }

    public void sendMessage(String msg) {
        out.println(msg);
    }
}