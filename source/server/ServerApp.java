package source.server;

import source.common.Common;
import source.common.Player;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerApp {
    private static final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    
    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        ServerSocket serverSocket = new ServerSocket(Common.PORT);
        System.out.println("Dedicated Server Online on Port: " + Common.PORT);

        int pCounter = 1;
        while (true) {
            Socket socket = serverSocket.accept();
            String pId = "P" + (pCounter++);
            new Thread(new ClientHandler(socket, pId)).start();
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket socket;
        private String pId;
        private GameRoom currentRoom = null;

        public ClientHandler(Socket socket, String pId) {
            this.socket = socket;
            this.pId = pId;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                sendRoomList(out);

                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.split(":");
                    String cmd = parts[0];

                    if (cmd.equals(Common.CMD_CREATE_ROOM)) {
                        String roomName = parts[1];
                        String playerName = parts[2];

                        if (rooms.containsKey(roomName)) {
                            out.println(Common.CMD_ERROR + ":Phòng đã tồn tại!");
                        } else {
                            GameRoom newRoom = new GameRoom(roomName);
                            rooms.put(roomName, newRoom);
                            Player p = new Player(pId, playerName);
                            newRoom.addPlayer(pId, p, out);
                            currentRoom = newRoom;
                        }
                    } else if (cmd.equals(Common.CMD_JOIN_ROOM)) {
                        String roomName = parts[1];
                        String playerName = parts[2];

                        if (!rooms.containsKey(roomName)) {
                            out.println(Common.CMD_ERROR + ":Phòng không tồn tại!");
                        } else {
                            GameRoom room = rooms.get(roomName);
                            Player p = new Player(pId, playerName);
                            room.addPlayer(pId, p, out);
                            currentRoom = room;
                        }
                    } else if (cmd.equals(Common.CMD_INPUT) && currentRoom != null) {
                        Player p = currentRoom.players.get(pId);
                        if (p != null && parts.length >= 5) {
                            p.left  = Boolean.parseBoolean(parts[1]);
                            p.right = Boolean.parseBoolean(parts[2]);
                            p.up    = Boolean.parseBoolean(parts[3]);
                            p.down  = Boolean.parseBoolean(parts[4]);
                        }
                    } else if (cmd.equals(Common.CMD_ROOM_LIST)) {
                        sendRoomList(out);
                    }
                }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + pId);
            } finally {
                if (currentRoom != null) {
                    currentRoom.removePlayer(pId);
                    if (currentRoom.players.isEmpty()) {
                        currentRoom.stopRoom(); // <--- Bổ sung dòng này để dừng luồng phòng
                        rooms.remove(currentRoom.roomName);
                        System.out.println("[Server] Da giai phong phong: " + currentRoom.roomName);
                    }
                }
            }
        }

        private void sendRoomList(PrintWriter out) {
            StringBuilder sb = new StringBuilder(Common.CMD_ROOM_LIST);
            for (String rName : rooms.keySet()) {
                sb.append(";").append(rName).append(" (").append(rooms.get(rName).players.size()).append(" người)");
            }
            out.println(sb.toString());
        }
    }
}