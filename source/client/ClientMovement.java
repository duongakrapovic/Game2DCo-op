package source.client;

import source.common.GameData;
import source.common.GameMap;
import source.common.GamePhysics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMovement {

    public static class PredictionInput {
        public final int seq;
        public final boolean left, right, up, down, attack;
        public PredictionInput(int seq, boolean left, boolean right, boolean up, boolean down, boolean attack) {
            this.seq = seq; this.left = left; this.right = right; this.up = up; this.down = down; this.attack = attack;
        }
    }

    public static class Snap {
        public final long timestamp;
        public final float x, y;
        public final String direction;
        public final int spriteNum;

        public Snap(long timestamp, float x, float y, String direction, int spriteNum) {
            this.timestamp = timestamp;
            this.x = x; this.y = y;
            this.direction = direction;
            this.spriteNum = spriteNum;
        }
    }

    private final GameData.PlayerState localPlayer;
    private final GameMap mapManager;
    private final Queue<PredictionInput> pendingInputs = new LinkedList<>();
    private final Map<String, List<Snap>> playerSnaps = new ConcurrentHashMap<>();

    private int nextSeq = 0;
    private float maxErrorObserved = 0;
    private int correctionCount = 0;

    private boolean predictionEnabled = true;
    private boolean interpolationEnabled = true;
    private long interpolationDelayMs = 100;

    public ClientMovement(GameMap mapManager) {
        this.mapManager = mapManager;
        this.localPlayer = new GameData.PlayerState("local", "Me");
    }

    public synchronized PredictionInput step(boolean l, boolean r, boolean u, boolean d, boolean atk) {
        PredictionInput input = new PredictionInput(nextSeq++, l, r, u, d, atk);
        if (predictionEnabled) {
            pendingInputs.add(input);
            localPlayer.left = l;
            localPlayer.right = r;
            localPlayer.up = u;
            localPlayer.down = d;
            localPlayer.isAttacking = atk;
            GamePhysics.updatePlayer(localPlayer, mapManager);
        }
        return input;
    }

    public synchronized void onServerState(float serverX, float serverY, int lastAckSeq) {
        while (!pendingInputs.isEmpty() && pendingInputs.peek().seq <= lastAckSeq) {
            pendingInputs.poll();
        }

        float err = (float) Math.hypot(localPlayer.x - serverX, localPlayer.y - serverY);
        if (err > maxErrorObserved) maxErrorObserved = err;

        if (err > 1.5f) {
            correctionCount++;
            localPlayer.x = serverX;
            localPlayer.y = serverY;

            for (PredictionInput in : pendingInputs) {
                localPlayer.left = in.left;
                localPlayer.right = in.right;
                localPlayer.up = in.up;
                localPlayer.down = in.down;
                localPlayer.isAttacking = in.attack;
                GamePhysics.updatePlayer(localPlayer, mapManager);
            }
        }
    }

    public void addSnap(String id, float x, float y, String dir, int sprite) {
        long now = System.currentTimeMillis();
        List<Snap> list = playerSnaps.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(new Snap(now, x, y, dir, sprite));
        if (list.size() > 30) list.remove(0);
    }

    public synchronized Snap getInterpolatedState(String id) {
        List<Snap> list = playerSnaps.get(id);
        if (list == null || list.isEmpty()) return null;

        if (!interpolationEnabled) {
            return list.get(list.size() - 1);
        }

        long renderTime = System.currentTimeMillis() - interpolationDelayMs;

        for (int i = list.size() - 1; i >= 0; i--) {
            Snap s2 = list.get(i);
            if (s2.timestamp <= renderTime && i + 1 < list.size()) {
                Snap s1 = list.get(i + 1);
                long diff = s1.timestamp - s2.timestamp;
                if (diff <= 0) return s2;

                float factor = (float) (renderTime - s2.timestamp) / diff;
                factor = Math.max(0f, Math.min(1f, factor));

                float rx = s2.x + (s1.x - s2.x) * factor;
                float ry = s2.y + (s1.y - s2.y) * factor;
                return new Snap(renderTime, rx, ry, s1.direction, s1.spriteNum);
            }
        }
        return list.get(list.size() - 1);
    }

    public float x() { return localPlayer.x; }
    public float y() { return localPlayer.y; }
    public int pendingCount() { return pendingInputs.size(); }
    public float maxError() { return maxErrorObserved; }
    public int correctionCount() { return correctionCount; }
    public boolean isPredictionEnabled() { return predictionEnabled; }
    public void setPredictionEnabled(boolean enabled) { this.predictionEnabled = enabled; }
    public boolean isInterpolationEnabled() { return interpolationEnabled; }
    public void setInterpolationEnabled(boolean enabled) { this.interpolationEnabled = enabled; }
    public long getInterpolationDelayMs() { return interpolationDelayMs; }
}