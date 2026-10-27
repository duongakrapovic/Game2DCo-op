package game;

import core.ClientHandler;
import protocol.Command;
import java.util.ArrayList;
import java.util.List;

public class Room {
    private List<ClientHandler> clients = new ArrayList<>();
    private int p1X = 100;
    private int p2X = 200;

    public synchronized boolean addClient(ClientHandler client) {
        if (clients.size() < 2) {
            clients.add(client);
            broadcastState();
            return true;
        }
        return false;
    }

    public synchronized void handleMove(ClientHandler client, String dir) {
        int playerIndex = clients.indexOf(client);
        int step = 15;

        if (playerIndex == 0) {
            if (dir.equals("LEFT")) p1X -= step;
            if (dir.equals("RIGHT")) p1X += step;
        } else if (playerIndex == 1) {
            if (dir.equals("LEFT")) p2X -= step;
            if (dir.equals("RIGHT")) p2X += step;
        }

        broadcastState();
    }

    public void broadcastState() {
        String msg = Command.STATE + ":" + p1X + ":" + p2X;
        for (ClientHandler c : clients) {
            c.sendMessage(msg);
        }
    }
}