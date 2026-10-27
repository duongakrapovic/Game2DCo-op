import network.TCPClient;
import ui.GamePanel;
import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Limbo Co-op 2D - Client");
            TCPClient client = new TCPClient();
            GamePanel gamePanel = new GamePanel(client);

            frame.add(gamePanel);
            frame.setSize(800, 400);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            String ip = JOptionPane.showInputDialog(frame, "Nhập IP Server (hoặc 'localhost'):", "localhost");
            if (ip != null && !ip.trim().isEmpty()) {
                boolean success = client.connect(ip.trim(), 8888, gamePanel);
                if (!success) {
                    JOptionPane.showMessageDialog(frame, "Không thể kết nối tới Server!");
                }
            }
        });
    }
}