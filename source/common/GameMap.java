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
        public int[][][] layers;

        public Chunk(int chunkX, int chunkY, int size) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.size = size;
            this.mapTileNum = new int[size][size];
        }
    }

    private boolean propertiesLoaded;
    private volatile boolean collisionMapLoaded;
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

    protected InputStream openResource(String path) throws IOException {
        File file = new File(path);
        return file.exists() ? new FileInputStream(file) : getClass().getResourceAsStream("/" + path);
    }

    public void loadTilesetImage(String pngPath, int originalTileSize) {
        try (InputStream is = openResource(pngPath)) {
            if (is == null) throw new FileNotFoundException("Không tìm thấy file ảnh Tileset: " + pngPath);

            BufferedImage tileset = ImageIO.read(is);


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

    private org.w3c.dom.Document readXml(InputStream input) throws Exception {
        if (input == null) throw new FileNotFoundException("Map resource not found");
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(input);
    }

    public void loadTilesetProperties(String tsxPath) {
        propertiesLoaded = false;
        collisionMapLoaded = false;
        try (InputStream input = openResource(tsxPath)) {
            org.w3c.dom.Element root = readXml(input).getDocumentElement();
            if (!"tileset".equals(root.getTagName())) throw new IOException("Expected tileset XML");
            Set<Integer> blocked = new HashSet<>();
            org.w3c.dom.NodeList tileNodes = root.getElementsByTagName("tile");
            for (int i = 0; i < tileNodes.getLength(); i++) {
                org.w3c.dom.Element tile = (org.w3c.dom.Element) tileNodes.item(i);
                int id = Integer.parseInt(tile.getAttribute("id"));
                org.w3c.dom.NodeList properties = tile.getElementsByTagName("property");
                for (int j = 0; j < properties.getLength(); j++) {
                    org.w3c.dom.Element property = (org.w3c.dom.Element) properties.item(j);
                    String value = property.hasAttribute("value") ? property.getAttribute("value") : property.getTextContent().trim();
                    if ("collision".equals(property.getAttribute("name"))
                            && ("true".equalsIgnoreCase(value) || "1".equals(value))) blocked.add(id);
                }
            }
            if (blocked.isEmpty()) throw new IOException("Tileset has no collision definitions");
            collisionTiles.clear(); collisionTiles.addAll(blocked);
            if (tiles != null) for (int i = 0; i < tiles.length; i++) tiles[i].collision = blocked.contains(i);
            propertiesLoaded = true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load tileset collision: " + tsxPath, e);
        }
    }

    public Chunk loadChunkFromFile(int chunkX, int chunkY, String folderPath) {
        String path = folderPath + "/chunk" + chunkX + "_" + chunkY + ".tmx";
        try (InputStream input = openResource(path)) {
            org.w3c.dom.Element root = readXml(input).getDocumentElement();
            if (!"map".equals(root.getTagName())
                    || Integer.parseInt(root.getAttribute("width")) != chunkSize
                    || Integer.parseInt(root.getAttribute("height")) != chunkSize) throw new IOException("Invalid chunk dimensions");
            int firstGid = 1;
            org.w3c.dom.NodeList tilesets = root.getElementsByTagName("tileset");
            if (tilesets.getLength() > 1) throw new IOException("Multiple tilesets are not supported");
            if (tilesets.getLength() == 1) firstGid = Integer.parseInt(((org.w3c.dom.Element) tilesets.item(0)).getAttribute("firstgid"));
            org.w3c.dom.NodeList layerNodes = root.getElementsByTagName("layer");
            if (layerNodes.getLength() == 0) throw new IOException("Chunk has no tile layers");
            Chunk chunk = new Chunk(chunkX, chunkY, chunkSize);
            chunk.layers = new int[layerNodes.getLength()][chunkSize][chunkSize];
            for (int layer = 0; layer < layerNodes.getLength(); layer++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) layerNodes.item(layer);
                org.w3c.dom.Element data = (org.w3c.dom.Element) element.getElementsByTagName("data").item(0);
                if (data == null || !"csv".equals(data.getAttribute("encoding"))) throw new IOException("Expected CSV tile layer");
                String[] numbers = data.getTextContent().trim().split(",");
                if (numbers.length != chunkSize * chunkSize) throw new IOException("Incomplete tile layer");
                for (int index = 0; index < numbers.length; index++) {
                    long gid = Long.parseLong(numbers[index].trim()) & 0x0fffffffL;
                    int tile = gid == 0 ? -1 : Math.toIntExact(gid - firstGid);
                    if (tile < -1 || (tiles != null && tile >= tiles.length)) throw new IOException("Tile ID outside tileset");
                    chunk.layers[layer][index / chunkSize][index % chunkSize] = tile;
                }
            }
            chunk.mapTileNum = chunk.layers[0];
            return chunk;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load map chunk: " + path, e);
        }
    }

    public void loadAllChunks(String folderPath, int maxChunkX, int maxChunkY) {
        collisionMapLoaded = false;
        loadedChunks.clear();
        Map<String, Chunk> chunks = new HashMap<>();
        for (int x = 0; x < maxChunkX; x++) for (int y = 0; y < maxChunkY; y++) {
            chunks.put(x + "_" + y, loadChunkFromFile(x, y, folderPath));
        }
        loadedChunks.putAll(chunks);
        collisionMapLoaded = propertiesLoaded && maxChunkX == Common.MAX_CHUNK_X && maxChunkY == Common.MAX_CHUNK_Y;
    }

    public synchronized void ensureCollisionMapLoaded() {
        if (collisionMapLoaded) return;
        initTileset(256);
        loadTilesetProperties("source/content/map/tileset.tsx");
        loadAllChunks("source/content/map", Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);
        if (!collisionMapLoaded) throw new IllegalStateException("Map is not ready");
    }

    public boolean isCollisionAtWorld(int worldX, int worldY) {
        int maxWidth = Common.MAX_CHUNK_X * chunkSize * Common.TILE_SIZE;
        int maxHeight = Common.MAX_CHUNK_Y * chunkSize * Common.TILE_SIZE;
        if (!propertiesLoaded || worldX < 0 || worldY < 0 || worldX >= maxWidth || worldY >= maxHeight) return true;
        int col = worldX / Common.TILE_SIZE, row = worldY / Common.TILE_SIZE;
        Chunk chunk = loadedChunks.get((col / chunkSize) + "_" + (row / chunkSize));
        if (chunk == null) return true;
        int localCol = col % chunkSize, localRow = row % chunkSize;
        boolean hasGround = false;
        for (int[][] layer : chunk.layers) {
            int tile = layer[localRow][localCol];
            if (tile >= 0) hasGround = true;
            if (collisionTiles.contains(tile)) return true;
        }
        return !hasGround;
    }

    public boolean isWalkable(float x, float y) {
        int size = GamePhysics.ENTITY_SIZE;
        int maxWidth = Common.MAX_CHUNK_X * chunkSize * Common.TILE_SIZE;
        int maxHeight = Common.MAX_CHUNK_Y * chunkSize * Common.TILE_SIZE;
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0
                || x + size > maxWidth || y + size > maxHeight) return false;
        int left = (int) Math.floor(x), top = (int) Math.floor(y);
        int right = (int) Math.floor(x + size - 0.001f), bottom = (int) Math.floor(y + size - 0.001f);
        return !isCollisionAtWorld(left, top) && !isCollisionAtWorld(right, top)
                && !isCollisionAtWorld(left, bottom) && !isCollisionAtWorld(right, bottom);
    }

    public java.awt.geom.Point2D.Float validatedPlayerSpawn(float x, float y) {
        boolean valid = !(x == 0 && y == 0) && isWalkable(x, y);
        if (!valid) { x = Common.SPAWN_X; y = Common.SPAWN_Y; }
        if (!isWalkable(x, y)) throw new IllegalStateException("Default player spawn is blocked or map is incomplete");
        return new java.awt.geom.Point2D.Float(x, y);
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

            int startCol = Math.max(0, Math.floorDiv(camX - chunkWorldX, Common.TILE_SIZE));
            int endCol = Math.min(c.size, Math.floorDiv(camX + screenWidth - 1 - chunkWorldX, Common.TILE_SIZE) + 1);
            int startRow = Math.max(0, Math.floorDiv(camY - chunkWorldY, Common.TILE_SIZE));
            int endRow = Math.min(c.size, Math.floorDiv(camY + screenHeight - 1 - chunkWorldY, Common.TILE_SIZE) + 1);
            for (int r = startRow; r < endRow; r++) {
                for (int col = startCol; col < endCol; col++) {
                    for (int[][] layer : c.layers) {
                    int tileNum = layer[r][col];
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
}
