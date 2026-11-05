package source.client;

import java.awt.Color;
import java.awt.Graphics2D;

/** Draws the visual representation of each enemy type. */
public final class EnemyRenderer {
    private EnemyRenderer() {}

    public static void draw(Graphics2D g, String name, int x, int y, int size,
                            int health, int maxHealth, long flashUntil) {
        boolean flashing = System.currentTimeMillis() < flashUntil;
        Color body = flashing ? new Color(255, 55, 55)
                : "Linh".equals(name) ? new Color(190, 65, 55) : new Color(145, 65, 190);
        g.setColor(body);
        g.fillOval(x + 2, y + 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.drawOval(x + 2, y + 2, size - 4, size - 4);

        if (health < maxHealth) {
            int barWidth = 32;
            int barX = x + (size - barWidth) / 2;
            int barY = y - 7;
            g.setColor(Color.DARK_GRAY);
            g.fillRect(barX, barY, barWidth, 4);
            g.setColor(health > maxHealth / 2 ? new Color(65, 200, 90) : new Color(225, 65, 55));
            g.fillRect(barX, barY, barWidth * Math.max(0, health) / maxHealth, 4);
        }
    }
}
