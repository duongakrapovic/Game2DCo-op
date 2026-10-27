import core.TCPServer;

public class Main {
    public static void main(String[] args) {
        TCPServer server = new TCPServer(8888);
        new Thread(server).start();
    }
}