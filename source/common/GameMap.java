package source.common;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * GAMEMAP.JAVA - Nạp TMX/TSX, quản lý 9 Chunk dữ liệu bản đồ & Render Tilemap
 */
/*
 * Cầu nối dữ liệu bản đồ với va chạm, spawn và render. Server dùng collision mà không cần ảnh; client dùng
 * cùng hệ tọa độ để dự đoán/vẽ. Nạp sai map phải báo lỗi hoặc coi khu vực chưa tải là chặn, tránh sinh
 * nhân vật trong vùng vô hiệu.
 * tiles giữ ảnh/thuộc tính từng tile; loadedChunks ánh xạ tọa độ tới các layer. collisionTiles và
 * propertiesLoaded dùng cho collision; collisionMapLoaded ngăn nạp lặp; spawnReachable cache flood fill.
 * Tile chủ yếu là dữ liệu và không có constructor viết tường minh trong source.
 */
public class GameMap {

    /*
     * Một tile gồm ảnh hiển thị và cờ collision.
     */
    public static class Tile {
        /*
         * image: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public BufferedImage image;
        /*
         * collision: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public boolean collision = false;
    }

    /*
     * Một phần bản đồ giữ mọi layer tile để vẽ và kiểm vật cản.
     */
    public static class Chunk {
        /*
         * chunkX, chunkY: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public int chunkX, chunkY;
        /*
         * size: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public int size;
        /*
         * mapTileNum: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public int[][] mapTileNum;
        /*
         * layers: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
         */
        public int[][][] layers;

        /*
         * Khởi tạo Chunk
         * Gán chunkX/chunkY/size và cấp mảng mapTileNum[size][size]. Mảng layers được loadChunkFromFile
         * cấp sau khi biết số layer của XML.
         */
        public Chunk(int chunkX, int chunkY, int size) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.size = size;
            this.mapTileNum = new int[size][size];
        }
    }

    /*
     * spawnReachable: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private boolean[] spawnReachable;
    /*
     * propertiesLoaded: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private boolean propertiesLoaded;
    /*
     * collisionMapLoaded: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private volatile boolean collisionMapLoaded;
    /*
     * tiles: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private Tile[] tiles;
    /*
     * collisionTiles: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private final Set<Integer> collisionTiles = new HashSet<>();
    /*
     * chunkSize: Ảnh/cờ tile hoặc cấu trúc chunk chứa chỉ số tile và mọi layer của TMX.
     */
    private final int chunkSize = Common.CHUNK_SIZE;
    /*
     * loadedChunks: Tile ảnh/collision, cờ đã nạp và cache chunk/vùng nối với spawn.
     */
    private final Map<String, Chunk> loadedChunks = new HashMap<>();

    /*
     * Khởi tạo GameMap
     * Constructor có body rỗng. Các field initializer đã tạo loadedChunks/collisionTiles; chưa có bảng
     * Tile hay chunk/collision được nạp. ensureCollisionMapLoaded hoặc loadTilesetImage mới thực hiện bước
     * nạp tương ứng.
     */
    public GameMap() {}

    /*
     * initTileset()
     * Cấp phát mảng Tile với số phần tử yêu cầu và tạo từng Tile rỗng. Body chỉ khởi tạo bảng tile, không
     * tự nạp ảnh, chunk hoặc reset cờ collision/cache liên thông; các bước nạp map do hàm khác quản lý.
     */
    public void initTileset(int totalTiles) {
        tiles = new Tile[totalTiles];
        for (int i = 0; i < totalTiles; i++) {
            tiles[i] = new Tile();
        }
    }

    /*
     * openResource()
     * Nếu file tại đường dẫn tồn tại thì mở FileInputStream, nếu không thử lấy resource cùng đường dẫn từ
     * classpath. Có thể trả null khi classpath không có; caller kiểm để báo thiếu tài nguyên. Được
     * override trong test để cung cấp XML trong RAM.
     */
    protected InputStream openResource(String path) throws IOException {
        File file = new File(path);
        return file.exists() ? new FileInputStream(file) : getClass().getResourceAsStream("/" + path);
    }

    /*
     * loadTilesetImage()
     * Mở và đọc ảnh tileset, tính số ô theo originalTileSize, cấp bảng Tile nếu cần rồi gắn từng subimage
     * kích thước gốc. Việc phóng lên TILE_SIZE diễn ra khi draw. Body bắt Exception và chỉ log stderr,
     * không ném lại như ClientRenderer.loadRequiredImage; caller không nhận kết quả thành công/ thất bại
     * rõ từ void này.
     */
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

    /*
     * readXml()
     * Tạo DOM parser với DTD/external entity bị vô hiệu hóa rồi parse InputStream. Đây là lớp bảo vệ khi
     * đọc XML map, không phải parser JSON hay mạng.
     */
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

    /*
     * loadTilesetProperties()
     * Đọc TSX, tra từng tile ID và thuộc tính collision dạng true/1, hỗ trợ giá trị ở attribute hoặc nội
     * dung. Kiểm dữ liệu cần thiết và báo lỗi nếu metadata không hợp lệ; không coi thiếu collision là tất
     * cả đi được.
     */
    public void loadTilesetProperties(String tsxPath) {
        spawnReachable = null;
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

    /*
     * loadChunkFromFile()
     * Nhận tọa độ chunk và thư mục, đọc TMX, kiểm 32×32 tile, tileset/firstgid và layer encoding CSV.
     * Chuyển GID sang chỉ số tile, mask cờ flip và kiểm đủ dữ liệu/phạm vi. Trả Chunk đã nạp hoặc phát
     * sinh lỗi, không lấp chunk hỏng bằng nền trống an toàn giả.
     * Trình tự: mở chunkX_Y.tmx; kiểm root map và kích thước 32×32; nhận firstgid; chỉ hỗ trợ một tileset.
     * Mỗi layer phải có data encoding csv và đủ 1024 số. GID bị bỏ cờ flip bằng mask rồi trừ firstgid; 0
     * trở thành ô trống -1.
     * Kết quả Chunk giữ mọi layer để collision xét cả lớp nền và vật cản; mapTileNum trỏ layer đầu. Sai
     * cấu trúc, ID hoặc thiếu tài nguyên phát IllegalStateException. CSV ở đây là các số ID tile nằm trong
     * XML TMX, không phải dữ liệu giao thức mạng hay save player.
     */
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
                    /*
                     * Bỏ các cờ flip của Tiled rồi đổi GID sang chỉ số tile local theo firstgid.
                     */
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

    /*
     * loadAllChunks()
     * Duyệt tọa độ chunk theo hai giới hạn, gọi loadChunkFromFile và lưu vào map chunk. Sau thay đổi dữ
     * liệu phải tính lại cache liên thông; thiếu file làm luồng nạp thất bại.
     */
    public void loadAllChunks(String folderPath, int maxChunkX, int maxChunkY) {
        collisionMapLoaded = false;
        spawnReachable = null;
        loadedChunks.clear();
        Map<String, Chunk> chunks = new HashMap<>();
        for (int x = 0; x < maxChunkX; x++) for (int y = 0; y < maxChunkY; y++) {
            chunks.put(x + "_" + y, loadChunkFromFile(x, y, folderPath));
        }
        loadedChunks.putAll(chunks);
        collisionMapLoaded = propertiesLoaded && maxChunkX == Common.MAX_CHUNK_X && maxChunkY == Common.MAX_CHUNK_Y;
    }

    /*
     * ensureCollisionMapLoaded()
     * Nếu đã nạp thì thoát; nếu chưa thì tải properties trước rồi đủ chín chunk của map hiện hành, đánh
     * dấu thành công sau cùng. ClientApp và ServerGame gọi trước khi chạy gameplay.
     * Thứ tự bắt buộc là cấp tile → đọc collision từ tileset.tsx → đọc đủ 3×3 chunk → xác nhận ready. Khi
     * đã ready thì return sớm, tránh reset map mỗi lần vào phòng. Không có ảnh sprite vẫn có thể kiểm
     * collision trên server; dữ liệu collision mới là yêu cầu của mô phỏng.
     */
    public synchronized void ensureCollisionMapLoaded() {
        if (collisionMapLoaded) return;
        initTileset(256);
        loadTilesetProperties("source/content/map/tileset.tsx");
        loadAllChunks("source/content/map", Common.MAX_CHUNK_X, Common.MAX_CHUNK_Y);
        if (!collisionMapLoaded) throw new IllegalStateException("Map is not ready");
    }

    /*
     * isCollisionAtWorld()
     * Đổi pixel thế giới thành chunk/tile nội bộ và kiểm các layer. Trả true khi ngoài biên, chưa có
     * metadata/chunk, tile collision hoặc ô hoàn toàn rỗng theo luật hiện tại. Hàm là nền cho physics, LOS
     * và test.
     */
    public boolean isCollisionAtWorld(int worldX, int worldY) {
        int maxWidth = Common.MAX_CHUNK_X * chunkSize * Common.TILE_SIZE;
        int maxHeight = Common.MAX_CHUNK_Y * chunkSize * Common.TILE_SIZE;
        /*
         * Thiếu map, ra ngoài biên hoặc chưa nạp collision được coi là bị chặn.
         */
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

    /*
     * isWalkable()
     * Trả boolean cho một hitbox entity đầy đủ đặt tại x/y: kiểm float hữu hạn, trong giới hạn và bốn góc
     * không bị chặn. Một điểm trung tâm trống không đủ để trả true.
     */
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

    /*
     * validatedPlayerSpawn()
     * Giữ tọa độ save nếu hợp lệ và không phải điểm rỗng (0,0); nếu không, dùng spawn mặc định đã kiểm.
     * Trả Point2D.Float nên giữ được phần lẻ hợp lệ; nếu cả spawn mặc định bị chặn thì ném lỗi.
     */
    public java.awt.geom.Point2D.Float validatedPlayerSpawn(float x, float y) {
        boolean valid = !(x == 0 && y == 0) && isWalkable(x, y);
        if (!valid) { x = Common.SPAWN_X; y = Common.SPAWN_Y; }
        if (!isWalkable(x, y)) throw new IllegalStateException("Default player spawn is blocked or map is incomplete");
        return new java.awt.geom.Point2D.Float(x, y);
    }

    /** Flood fill on entity-sized standable cells, invalidated whenever collision data reloads. */
    /*
     * isSpawnReachableAtWorld()
     * Lười tạo vùng đi được bằng flood fill bốn hướng từ spawn trên grid, dùng hitbox đi được; sau đó trả
     * xem tọa độ yêu cầu thuộc vùng liên thông hay không. Cache giúp nhiều lần chọn spawn không phải flood
     * fill lại.
     * Lần đầu dùng BFS bốn hướng từ ô spawn, chỉ qua ô có thể đặt vừa entity; lưu mảng reachable cho các
     * lần tra sau. Reload collision làm mất cache này. Hàm này bổ sung điều kiện liên thông; một điểm đứng
     * được nhưng nằm trên đảo cỏ bị tách rời vẫn không được coi là spawn hợp lệ.
     */
    public synchronized boolean isSpawnReachableAtWorld(int x, int y) {
        int width = Common.MAX_CHUNK_X * chunkSize, height = Common.MAX_CHUNK_Y * chunkSize;
        if (x < 0 || y < 0 || x >= width * Common.TILE_SIZE || y >= height * Common.TILE_SIZE) return false;
        if (spawnReachable == null) {
            boolean[] reachable = new boolean[width * height];
            int startX = (int) Common.SPAWN_X / Common.TILE_SIZE;
            int startY = (int) Common.SPAWN_Y / Common.TILE_SIZE;
            if (!isWalkable(Common.SPAWN_X, Common.SPAWN_Y)) return false;
            int[] queue = new int[reachable.length];
            int start = startY * width + startX, head = 0, tail = 0;
            queue[tail++] = start; reachable[start] = true;
            /*
             * Flood fill bốn hướng từ spawn, chỉ đi qua ô đặt vừa entity; tránh sinh quái/bình trên đảo cô
             * lập.
             */
            while (head < tail) {
                int current = queue[head++], col = current % width, row = current / width;
                for (int direction = 0; direction < 4; direction++) {
                    int nextX = col + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                    int nextY = row + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                    if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height) continue;
                    int next = nextY * width + nextX;
                    if (reachable[next] || !isWalkable(nextX * Common.TILE_SIZE, nextY * Common.TILE_SIZE)) continue;
                    reachable[next] = true; queue[tail++] = next;
                }
            }
            spawnReachable = reachable;
        }
        return spawnReachable[(y / Common.TILE_SIZE) * width + x / Common.TILE_SIZE];
    }

    /*
     * draw()
     * Từ focus và kích thước viewport suy ra camera, cắt phạm vi chunk/tile có thể thấy rồi vẽ layer/ảnh
     * đúng tọa độ màn hình. Culling chỉ giảm vẽ, không phải lọc entity mạng theo vùng quan tâm.
     */
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
