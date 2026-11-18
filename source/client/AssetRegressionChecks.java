package source.client;

import source.common.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;
import javax.swing.SwingUtilities;

/** Reads actual synchronized assets; never creates a save or starts a game loop. */
public final class AssetRegressionChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("source/content");
        int images = 0, audio = 0, xml = 0;
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".png")) {
                    try (InputStream input = Files.newInputStream(path)) {
                        BufferedImage image = ImageIO.read(input);
                        check(image != null && image.getWidth() > 0 && image.getHeight() > 0, "Unreadable image: " + path);
                    }
                    images++;
                } else if (name.endsWith(".wav")) {
                    check(AudioSystem.getAudioFileFormat(path.toFile()).getFrameLength() > 0, "Unreadable sound: " + path);
                    audio++;
                } else if (name.endsWith(".tmx") || name.endsWith(".tsx")) {
                    try (InputStream input = Files.newInputStream(path)) {
                        factory.newDocumentBuilder().parse(input);
                    }
                    xml++;
                }
            }
        }
        GameMap map = new GameMap();
        map.ensureCollisionMapLoaded();
        check(map.isWalkable(Common.SPAWN_X, Common.SPAWN_Y), "Actual map default spawn blocked");
        map.loadTilesetImage("source/content/map/tileset.png", Common.ORIGINAL_TILE_SIZE);
        check(map.isSpawnReachableAtWorld((int) Common.SPAWN_X, (int) Common.SPAWN_Y), "Actual spawn region unavailable");

        Path preview = Path.of(".runtime", "asset-review", "potion-render.png");
        Files.createDirectories(preview.getParent());
        AtomicInteger matchingPixels = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            try {
                GameMap blackMap = new GameMap() {
                    @Override public void draw(Graphics2D g, int x, int y, int width, int height) {
                        g.setColor(Color.BLACK); g.fillRect(0, 0, width, height);
                    }
                };
                ClientGame panel = new ClientGame(blackMap, new ClientMovement(blackMap));
                panel.setSize(640, 480); panel.setMyId("P1");
                GameData.PlayerState player = new GameData.PlayerState("P1", "Player");
                panel.renderPlayers = Map.of(player.id, player);
                int camX = (int) Common.SPAWN_X - 320, camY = (int) Common.SPAWN_Y - 240;
                GameData.ItemState potion = new GameData.ItemState("POTION_1", "HEAL", camX + 450, camY + 300);
                panel.renderItems = Map.of(potion.id, potion);
                BufferedImage actual = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = actual.createGraphics();
                panel.paint(graphics); graphics.dispose();
                ImageIO.write(actual, "png", preview.toFile());
                BufferedImage expected = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
                graphics = expected.createGraphics();
                graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, 48, 48);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(ImageIO.read(Path.of("source/content/object/healthposion.png").toFile()), 0, 0, 48, 48, null);
                graphics.dispose();
                for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
                    check(actual.getRGB(450 + x, 300 + y) == expected.getRGB(x, y), "Potion render differs from the original sprite");
                    matchingPixels.incrementAndGet();
                }
                potion.collected = true;
                graphics = actual.createGraphics(); panel.paint(graphics); graphics.dispose();
                for (int y = 300; y < 348; y++) for (int x = 450; x < 498; x++)
                    check(actual.getRGB(x, y) == Color.BLACK.getRGB(), "Collected potion still visible");
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        System.out.println("PASS: " + images + " PNG, " + audio + " WAV, " + xml + " map XML files decoded");
        System.out.println("PASS: actual map spawn and " + matchingPixels.get() + " original potion sprite pixels verified");
        System.out.println("Preview: " + preview);
    }
}
