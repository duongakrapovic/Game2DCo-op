package ui;

import network.TCPClient;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class GamePanel extends JPanel {
    private int p1X = 100;
    private int p2X = 200;
    private final int GROUND_Y = 300;
    private TCPClient tcpClient;

    public GamePanel(TCPClient client) {
        this.tcpClient = client;
        setBackground(Color.BLACK);
        setFocusable(true);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_A) {
                    tcpClient.sendMove("LEFT");
                }
                if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_D) {
                    tcpClient.sendMove("RIGHT");
                }
            }
        });
    }

    public void updatePositions(int p1X, int p2X) {
        this.p1X = p1X;
        this.p2X = p2X;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(Color.WHITE);
        g2.drawLine(0, GROUND_Y, 800, GROUND_Y);

        g2.setColor(Color.CYAN);
        g2.fillRect(p1X, GROUND_Y - 40, 40, 40);

        g2.setColor(Color.RED);
        g2.fillRect(p2X, GROUND_Y - 40, 40, 40);
    }
}