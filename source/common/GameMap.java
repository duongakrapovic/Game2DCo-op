package source.common;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * GAMEMAP.JAVA - Nạp TMX/TSX, quản lý 9 Chunk dữ liệu bản đồ & Render Tilemap
 */
public class GameMap {

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
    private final Set<Integer> collisionTiles = new HashSet<>();
    private final int chunkSize = Common.CHUNK_SIZE;
    private final Map<String, Chunk> loadedChunks = new HashMap<>();

    public GameMap() {}

    public void initTileset(int totalTiles) {
        tiles = new Tile[totalTiles];
        for (int i = 0; i < totalTiles; i++) {
            tiles[i] = new Tile();
        }
    }

    public void loadTilesetImage(String pngPath, int originalTileSize) {
        try {
            InputStream is = null;
            File file = new File(pngPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + pngPath);

            if (is == null) throw new FileNotFoundException("Không tìm thấy file ảnh Tileset: " + pngPath);

            BufferedImage tileset = ImageIO.read(is);
            is.close();

            int cols = tileset.getWidth() / originalTileSize;
            int rows = tileset.getHeight() / originalTileSize;
            int totalTiles = cols * rows;

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
        } catch (Exception e) {
            System.err.println("[GameMap] Lỗi nạp ảnh PNG: " + e.getMessage());
        }
    }

    public void loadTilesetProperties(String tsxPath) {
        try {
            InputStream is = null;
            File file = new File(tsxPath);
            if (file.exists()) is = new FileInputStream(file);
            else is = getClass().getResourceAsStream("/" + tsxPath);

            if (is == null) throw new FileNotFoundException("Không tìm thấy file TSX: " + tsxPath);

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder xmlBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) xmlBuilder.append(line.trim()).append(" ");
            br.close();

            String xml = xmlBuilder.toString();
            String[] tileBlocks = xml.split("<tile ");

            for (int i = 1; i < tileBlocks.length; i++) {
                String block = tileBlocks[i];
                if (block.startsWith("id=\"")) {
                    int startId = 4;
                    int endId = block.indexOf("\"", startId);
                    int tileId = Integer.parseInt(block.substring(startId, endId));

                    if (block.contains("name=\"collision\"") && (block.contains("value=\"true\"") || block.contains("value=\"1\""))) {
                        collisionTiles.add(tileId);
                        if (tiles != null && tileId >= 0 && tileId < tiles.length) {
                            tiles[tileId].collision = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GameMap] Lỗi parse file TSX: " + e.getMessage());
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

            if (is == null) throw new FileNotFoundException("Không tìm thấy file Chunk: " + fileName);

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
            System.err.println("[GameMap] Lỗi nạp Chunk: " + e.getMessage());
            return c;
        }
    }

    public void loadAllChunks(String folderPath, int maxChunkX, int maxChunkY) {
        for (int cx = 0; cx < maxChunkX; cx++) {
            for (int cy = 0; cy < maxChunkY; cy++) {
                Chunk c = loadChunkFromFile(cx, cy, folderPath);
                if (c != null) {
                    loadedChunks.put(cx + "_" + cy, c);
                }
            }
        }
    }

    public boolean isCollisionAtWorld(int worldX, int worldY) {
        if (worldX < 0 || worldY < 0) return true;

        int tileCol = Math.floorDiv(worldX, Common.TILE_SIZE);
        int tileRow = Math.floorDiv(worldY, Common.TILE_SIZE);

        int chunkX = tileCol / chunkSize;
        int chunkY = tileRow / chunkSize;
        int inChunkCol = tileCol % chunkSize;
        int inChunkRow = tileRow % chunkSize;

        if (chunkX >= Common.MAX_CHUNK_X || chunkY >= Common.MAX_CHUNK_Y) return true;

        Chunk c = loadedChunks.get(chunkX + "_" + chunkY);
        if (c == null) return false;

        int tileNum = c.mapTileNum[inChunkRow][inChunkCol];
        return tileNum >= 0 && collisionTiles.contains(tileNum);
    }

    private int[][] regionMap;
    private int spawnRegion = -2;

    private int mapWidthTiles() {
        return Common.MAX_CHUNK_X * Common.CHUNK_SIZE;
    }

    private int mapHeightTiles() {
        return Common.MAX_CHUNK_Y * Common.CHUNK_SIZE;
    }

    private boolean isTileStandable(int tileX, int tileY) {
        if (tileX < 0 || tileY < 0 || tileX >= mapWidthTiles() || tileY >= mapHeightTiles()) return false;
        int centerX = tileX * Common.TILE_SIZE + Common.TILE_SIZE / 2;
        int centerY = tileY * Common.TILE_SIZE + Common.TILE_SIZE / 2;
        return !isCollisionAtWorld(centerX, centerY);
    }

    private synchronized void ensureSpawnRegions() {
        if (regionMap != null) return;
        int w = mapWidthTiles();
        int h = mapHeightTiles();
        regionMap = new int[h][w];
        for (int[] row : regionMap) Arrays.fill(row, -1);

        int[] queueX = new int[w * h];
        int[] queueY = new int[w * h];
        int[] offsetX = {1, -1, 0, 0};
        int[] offsetY = {0, 0, 1, -1};

        int region = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (regionMap[y][x] != -1 || !isTileStandable(x, y)) continue;
                int head = 0, tail = 0;
                queueX[tail] = x;
                queueY[tail] = y;
                tail++;
                regionMap[y][x] = region;
                while (head < tail) {
                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + offsetX[d];
                        int ny = cy + offsetY[d];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        if (regionMap[ny][nx] != -1 || !isTileStandable(nx, ny)) continue;
                        regionMap[ny][nx] = region;
                        queueX[tail] = nx;
                        queueY[tail] = ny;
                        tail++;
                    }
                }
                region++;
            }
        }

        int spawnTileX = Math.floorDiv((int) Common.SPAWN_X, Common.TILE_SIZE);
        int spawnTileY = Math.floorDiv((int) Common.SPAWN_Y, Common.TILE_SIZE);
        spawnRegion = (spawnTileX >= 0 && spawnTileY >= 0 && spawnTileX < w && spawnTileY < h)
                ? regionMap[spawnTileY][spawnTileX] : -1;
    }

    public boolean isSpawnReachableAtWorld(int worldX, int worldY) {
        ensureSpawnRegions();
        if (spawnRegion < 0) return false;
        int tileX = Math.floorDiv(worldX, Common.TILE_SIZE);
        int tileY = Math.floorDiv(worldY, Common.TILE_SIZE);
        if (tileX < 0 || tileY < 0 || tileX >= mapWidthTiles() || tileY >= mapHeightTiles()) return false;
        return regionMap[tileY][tileX] == spawnRegion;
    }

    public void draw(Graphics2D g2, int focusWorldX, int focusWorldY, int screenWidth, int screenHeight) {
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, screenWidth, screenHeight);

        int totalMapWidth = Common.MAX_CHUNK_X * Common.CHUNK_SIZE * Common.TILE_SIZE;
        int totalMapHeight = Common.MAX_CHUNK_Y * Common.CHUNK_SIZE * Common.TILE_SIZE;

        int camX = focusWorldX - screenWidth / 2;
        int camY = focusWorldY - screenHeight / 2;

        if (totalMapWidth > screenWidth) camX = Math.max(0, Math.min(camX, totalMapWidth - screenWidth));
        if (totalMapHeight > screenHeight) camY = Math.max(0, Math.min(camY, totalMapHeight - screenHeight));

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