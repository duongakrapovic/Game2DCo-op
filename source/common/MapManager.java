package source.common;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

public class MapManager {
    public static class Tile {
        public BufferedImage image;
        public boolean collision = false;
    }

    public static class Chunk {
        public int chunkX, chunkY;
        public int size;
        public int[][] mapTileNum;

        public Chunk(int chunkX, int chunkY, int size) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.size = size;
            this.mapTileNum = new int[size][size];
        }
    }

    private Tile[] tiles;
    private final int chunkSize = Common.CHUNK_SIZE;
    private final Map<String, Chunk> loadedChunks = new HashMap<>();
    private final Set<Integer> collisionTileIds = new HashSet<>();

    public MapManager() {}

    public void loadTilesetImage(String pngPath, int originalTileSize) {
        try {
            InputStream is = null;
            File file = new File(pngPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + pngPath);

            if (is == null) return;

            BufferedImage tileset = ImageIO.read(is);
            is.close();

            int cols = tileset.getWidth() / originalTileSize;
            int rows = tileset.getHeight() / originalTileSize;
            tiles = new Tile[cols * rows];

            int idx = 0;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    tiles[idx] = new Tile();
                    tiles[idx].image = tileset.getSubimage(x * originalTileSize, y * originalTileSize, originalTileSize, originalTileSize);
                    tiles[idx].collision = collisionTileIds.contains(idx);
                    idx++;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadTilesetProperties(String tsxPath) {
        try {
            InputStream is = null;
            File file = new File(tsxPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + tsxPath);

            if (is == null) {
                System.out.println("[MapManager] Khong tim thấy file TSX!");
                return;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder xmlBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                xmlBuilder.append(line.trim()).append(" ");
            }
            br.close();

            String xml = xmlBuilder.toString();
            // Tách các thẻ <tile id="...">
            String[] tileBlocks = xml.split("<tile ");
            for (int i = 1; i < tileBlocks.length; i++) {
                String block = tileBlocks[i];
                if (block.startsWith("id=\"")) {
                    int startId = 4;
                    int endId = block.indexOf("\"", startId);
                    int tileId = Integer.parseInt(block.substring(startId, endId));

                    // Kiểm tra xem tile này có thuộc tính collision true hay không
                    if (block.contains("name=\"collision\"") && (block.contains("value=\"true\"") || block.contains("value=\"1\""))) {
                        collisionTileIds.add(tileId);
                        if (tiles != null && tileId >= 0 && tileId < tiles.length) {
                            tiles[tileId].collision = true;
                            System.out.println("[MapManager] Set Collision TRUE cho Tile ID: " + tileId);
                        }
                    }
                }
            }
            System.out.println("[MapManager] Parse thuoc tinh TSX hoan tat!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Chunk loadChunkFromFile(int chunkX, int chunkY, String folderPath) {
        Chunk c = new Chunk(chunkX, chunkY, chunkSize);
        try {
            String fileName = "chunk" + chunkX + "_" + chunkY + ".tmx";
            File f = new File(folderPath, fileName);
            InputStream is = null;

            if (f.exists()) is = new FileInputStream(f);
            else is = getClass().getResourceAsStream("/" + folderPath + "/" + fileName);

            if (is == null) return null;

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder xmlBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) xmlBuilder.append(line.trim()).append(" ");
            br.close();

            String content = xmlBuilder.toString();
            int start = content.indexOf("<data encoding=\"csv\">") + "<data encoding=\"csv\">".length();
            int end = content.indexOf("</data>");
            String csv = content.substring(start, end).trim();
            String[] numbers = csv.split(",");

            int idx = 0;
            for (int r = 0; r < chunkSize; r++) {
                for (int col = 0; col < chunkSize; col++) {
                    if (idx < numbers.length) {
                        int num = Integer.parseInt(numbers[idx].trim());
                        c.mapTileNum[r][col] = (num == 0) ? -1 : num - 1;
                        idx++;
                    }
                }
            }
            return c;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void loadAllChunks(String folderPath, int maxChunkX, int maxChunkY) {
        for (int cx = 0; cx < maxChunkX; cx++) {
            for (int cy = 0; cy < maxChunkY; cy++) {
                Chunk c = loadChunkFromFile(cx, cy, folderPath);
                if (c != null) {
                    addChunk(c);
                }
            }
        }
    }

    public void addChunk(Chunk chunk) {
        String key = chunk.chunkX + "_" + chunk.chunkY;
        loadedChunks.put(key, chunk);
    }

    public boolean isCollisionAtWorld(int worldX, int worldY) {
        if (worldX < 0 || worldY < 0) return true;

        int tileCol = worldX / Common.TILE_SIZE;
        int tileRow = worldY / Common.TILE_SIZE;

        int chunkX = tileCol / chunkSize;
        int chunkY = tileRow / chunkSize;
        int inChunkCol = tileCol % chunkSize;
        int inChunkRow = tileRow % chunkSize;

        String key = chunkX + "_" + chunkY;
        Chunk c = loadedChunks.get(key);
        if (c == null) return true;

        int tileNum = c.mapTileNum[inChunkRow][inChunkCol];
        if (collisionTileIds.contains(tileNum)) return true;
        if (tiles != null && tileNum >= 0 && tileNum < tiles.length) {
            return tiles[tileNum].collision;
        }
        return false;
    }

    // Tô viền ngoài màu đen
    public void draw(Graphics2D g2, int focusWorldX, int focusWorldY, int screenWidth, int screenHeight) {
        // Tô nền đen cho màn hình
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, screenWidth, screenHeight);

        // Tính tổng kích thước World Map (3x3 Chunks)
        int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

        // Tính tọa độ Camera mong muốn
        int camX = focusWorldX - screenWidth / 2;
        int camY = focusWorldY - screenHeight / 2;

        // Khóa biên Camera (Clamp) không cho trượt ra ngoài rìa map
        if (totalMapWidth > screenWidth) {
            camX = Math.max(0, Math.min(camX, totalMapWidth - screenWidth));
        }
        if (totalMapHeight > screenHeight) {
            camY = Math.max(0, Math.min(camY, totalMapHeight - screenHeight));
        }

        // Vẽ toàn bộ Tilemap
        for (Chunk c : loadedChunks.values()) {
            int chunkWorldX = c.chunkX * c.size * Common.TILE_SIZE;
            int chunkWorldY = c.chunkY * c.size * Common.TILE_SIZE;

            for (int r = 0; r < c.size; r++) {
                for (int col = 0; col < c.size; col++) {
                    int tileNum = c.mapTileNum[r][col];
                    if (tiles != null && tileNum >= 0 && tileNum < tiles.length) {
                        int tileWorldX = chunkWorldX + col * Common.TILE_SIZE;
                        int tileWorldY = chunkWorldY + r * Common.TILE_SIZE;

                        int screenX = tileWorldX - camX;
                        int screenY = tileWorldY - camY;

                        if (screenX + Common.TILE_SIZE >= 0 && screenX <= screenWidth &&
                            screenY + Common.TILE_SIZE >= 0 && screenY <= screenHeight) {
                            g2.drawImage(tiles[tileNum].image, screenX, screenY, Common.TILE_SIZE, Common.TILE_SIZE, null);
                        }
                    }
                }
            }
        }
    }
}
