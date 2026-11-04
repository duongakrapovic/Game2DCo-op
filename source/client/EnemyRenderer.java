package source.client;

import java.awt.Color;
import java.awt.Graphics2D;

/** Draws the visual representation of each enemy type. */
public final class EnemyRenderer {
    private EnemyRenderer() {}

    public static void draw(Graphics2D g, String name, int x, int y, int size) {
        Color body = "Linh".equals(name) ? new Color(190, 65, 55) : new Color(145, 65, 190);
        g.setColor(body);
        g.fillOval(x + 2, y + 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.drawOval(x + 2, y + 2, size - 4, size - 4);
    }
}
