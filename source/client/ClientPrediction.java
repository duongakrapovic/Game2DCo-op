package source.client;

import source.common.MapManager;
import source.common.PhysicsEngine;
import source.common.Player;

import java.util.LinkedList;
import java.util.Queue;

public class ClientPrediction {
    public static class Input {
        public final int seq;
        public final boolean left, right, up, down;
        public Input(int seq, boolean left, boolean right, boolean up, boolean down) {
            this.seq = seq; this.left = left; this.right = right; this.up = up; this.down = down;
        }
    }

    private final Player localPlayer;
    private final MapManager mapManager;
    private final Queue<Input> pendingInputs = new LinkedList<>();
    private int nextSeq = 0;

    private float maxErrorObserved = 0;
    private int correctionCount = 0;
    private boolean predictionEnabled = true;

    public ClientPrediction(MapManager mapManager) {
        this.mapManager = mapManager;
        this.localPlayer = new Player("local", "Me");
    }

    public synchronized Input step(boolean l, boolean r, boolean u, boolean d) {
        Input input = new Input(nextSeq++, l, r, u, d);
        if (predictionEnabled) {
            pendingInputs.add(input);
            localPlayer.left = l;
            localPlayer.right = r;
            localPlayer.up = u;
            localPlayer.down = d;
            PhysicsEngine.updatePlayer(localPlayer, mapManager);
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

            for (Input in : pendingInputs) {
                localPlayer.left = in.left;
                localPlayer.right = in.right;
                localPlayer.up = in.up;
                localPlayer.down = in.down;
                PhysicsEngine.updatePlayer(localPlayer, mapManager);
            }
        }
    }

    public void resync() {
        pendingInputs.clear();
        localPlayer.respawnToHome();
    }

    public float x() { return localPlayer.x; }
    public float y() { return localPlayer.y; }
    public int pendingCount() { return pendingInputs.size(); }
    public float maxError() { return maxErrorObserved; }
    public int correctionCount() { return correctionCount; }
    public boolean isEnabled() { return predictionEnabled; }
    public void setEnabled(boolean enabled) { this.predictionEnabled = enabled; }
}