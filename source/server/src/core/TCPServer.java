package core;

import game.Room;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer implements Runnable {
    private int port;
    private Room room;

    public TCPServer(int port) {
        this.port = port;
        this.room = new Room();
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server TCP is running on Port: " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected: " + socket.getInetAddress());
                ClientHandler handler = new ClientHandler(socket, room);
                if (room.addClient(handler)) {
                    new Thread(handler).start();
                } else {
                    System.out.println("Room is full!");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}