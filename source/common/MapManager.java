package source.common;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

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

    public MapManager() {}

    /**
     * Khởi tạo mảng tiles với số lượng tile xác định.
     * Bắt buộc phải gọi trước khi loadTilesetProperties hoặc loadTilesetImage.
     */
    public void initTileset(int totalTiles) {
        if (totalTiles <= 0) {
            throw new IllegalArgumentException("[FATAL ERROR] Tong so tile phai lon hon 0!");
        }
        tiles = new Tile[totalTiles];
        for (int i = 0; i < totalTiles; i++) {
            tiles[i] = new Tile();
        }
        System.out.println("[MapManager] Da khoi tao bo nho cho " + totalTiles + " tiles.");
    }

    public void loadTilesetImage(String pngPath, int originalTileSize) {
        try {
            InputStream is = null;
            File file = new File(pngPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + pngPath);

            if (is == null) {
                throw new FileNotFoundException("[FATAL ERROR] Khong tim thấy file anh Tileset PNG tai: " + pngPath);
            }

            BufferedImage tileset = ImageIO.read(is);
            is.close();

            int cols = tileset.getWidth() / originalTileSize;
            int rows = tileset.getHeight() / originalTileSize;
            int totalTiles = cols * rows;

            // Neu mảng tiles chưa được khởi tạo, tự động khởi tạo theo kích thước ảnh
            if (tiles == null || tiles.length < totalTiles) {
                initTileset(totalTiles);
            }

            int idx = 0;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    tiles[idx].image = tileset.getSubimage(x * originalTileSize, y * originalTileSize, originalTileSize, originalTileSize);
                    idx++;
                }
            }
            System.out.println("[MapManager] Nap anh Tileset PNG thanh cong!");
        } catch (Exception e) {
            throw new RuntimeException("[FATAL ERROR] Loi khi nap file anh PNG: " + pngPath, e);
        }
    }

    public void loadTilesetProperties(String tsxPath) {
        // Kiểm tra nguyên tắc Fail Fast: Nếu chưa cấp phát tiles -> CRASH NGAY
        if (tiles == null) {
            throw new IllegalStateException("[FATAL ERROR] Mang tiles chua duoc khoi tao! Hay goi initTileset() hoac loadTilesetImage() truoc!");
        }

        try {
            InputStream is = null;
            File file = new File(tsxPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + tsxPath);

            if (is == null) {
                throw new FileNotFoundException("[FATAL ERROR] Khong tim thấy file thuoc tinh TSX tai: " + tsxPath);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder xmlBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                xmlBuilder.append(line.trim()).append(" ");
            }
            br.close();

            String xml = xmlBuilder.toString();
            String[] tileBlocks = xml.split("<tile ");
            int collisionCount = 0;

            for (int i = 1; i < tileBlocks.length; i++) {
                String block = tileBlocks[i];
                if (block.startsWith("id=\"")) {
                    int startId = 4;
                    int endId = block.indexOf("\"", startId);
                    int tileId = Integer.parseInt(block.substring(startId, endId));

                    if (block.contains("name=\"collision\"") && (block.contains("value=\"true\"") || block.contains("value=\"1\""))) {
                        if (tileId >= 0 && tileId < tiles.length) {
                            tiles[tileId].collision = true;
                            collisionCount++;
                        } else {
                            throw new IndexOutOfBoundsException("[FATAL ERROR] Tile ID " + tileId + " trong TSX vuot qua kich thuoc mang tiles (" + tiles.length + ")");
                        }
                    }
                }
            }
            System.out.println("[MapManager] Parse thuoc tinh TSX hoan tat! Tong so tile co va cham: " + collisionCount);
        } catch (Exception e) {
            throw new RuntimeException("[FATAL ERROR] Loi khi parse file TSX: " + tsxPath, e);
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

            if (is == null) {
                throw new FileNotFoundException("[FATAL ERROR] Khong tim thấy file Chunk TMX: " + fileName + " trong thư mục: " + folderPath);
            }

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
            throw new RuntimeException("[FATAL ERROR] Loi khi nap file Chunk chunk" + chunkX + "_" + chunkY + ".tmx", e);
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

        int tileCol = Math.floorDiv(worldX, Common.TILE_SIZE);
        int tileRow = Math.floorDiv(worldY, Common.TILE_SIZE);  
        
        int chunkX = tileCol / chunkSize;
        int chunkY = tileRow / chunkSize;
        int inChunkCol = tileCol % chunkSize;
        int inChunkRow = tileRow % chunkSize;

        String key = chunkX + "_" + chunkY;
        Chunk c = loadedChunks.get(key);
        if (c == null) return false;

        int tileNum = c.mapTileNum[inChunkRow][inChunkCol];
        if (tiles != null && tileNum >= 0 && tileNum < tiles.length) {
            return tiles[tileNum].collision;
        }
        return false;
    }

    public void draw(Graphics2D g2, int focusWorldX, int focusWorldY, int screenWidth, int screenHeight) {
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, screenWidth, screenHeight);

        int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

        int camX = focusWorldX - screenWidth / 2;
        int camY = focusWorldY - screenHeight / 2;

        if (totalMapWidth > screenWidth) {
            camX = Math.max(0, Math.min(camX, totalMapWidth - screenWidth));
        }
        if (totalMapHeight > screenHeight) {
            camY = Math.max(0, Math.min(camY, totalMapHeight - screenHeight));
        }

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
                            if (tiles[tileNum].image != null) {
                                g2.drawImage(tiles[tileNum].image, screenX, screenY, Common.TILE_SIZE, Common.TILE_SIZE, null);
                            }
                        }
                    }
                }
            }
        }
    }
}