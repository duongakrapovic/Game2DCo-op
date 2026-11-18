package source.server;

import source.common.*;
import java.awt.geom.Point2D;
import java.util.concurrent.atomic.AtomicInteger;

/** Admission tests use in-memory slots and a no-I/O persistence callback. */
public final class RoomAdmissionRegressionChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final GameMap MAP = new GameMap() {
        @Override public Point2D.Float validatedPlayerSpawn(float x, float y) {
            return new Point2D.Float(x, y);
        }
    };
    private static void rejected(RoomSaveManager saves, String id, String name, String expected) {
        try {
            saves.join(new GameData.PlayerState(id, name));
            throw new AssertionError("Unexpected admission: " + name);
        } catch (IllegalStateException error) {
            check(error.getMessage().contains(expected), "Specific admission error: " + error.getMessage());
            check(!error.getMessage().equals("Phong da day!"), "Do not conflate admission with capacity");
        }
    }
    public static void main(String[] args) {
        AtomicInteger writes = new AtomicInteger();
        SaveManager.OnlineSaveData data = new SaveManager.OnlineSaveData("memory-only");
        RoomSaveManager saves = new RoomSaveManager(data, MAP, ignored -> writes.incrementAndGet());
        GameData.PlayerState first = new GameData.PlayerState("P1", "Player");
        check(saves.join(first), "First entry");
        rejected(saves, "P2", "PLAYER", "dang o trong phong");
        check(data.slots.get(1).status.equals("EMPTY"), "Duplicate name does not allocate another slot");
        first.x = 123.5f; first.y = 234.25f; first.hp = 75;
        saves.detach(first, false);
        rejected(saves, "P2", "Player", "20 giay");
        saves.attach(first.id);
        rejected(saves, "P2", "Player", "dang o trong phong");
        saves.detach(first, true); // Explicit leave releases runtime ownership, retaining saved progress.
        GameData.PlayerState returning = new GameData.PlayerState("P2", "Player");
        check(saves.join(returning), "Immediate re-entry after explicit leave");
        check(returning.x == 123.5f && returning.y == 234.25f && returning.hp == 75, "Re-entry restores progress");
        check(data.slots.get(0).playerName.equals("Player"), "Same saved slot reused");
        for (int i = 1; i < 4; i++) check(data.slots.get(i).status.equals("EMPTY"), "No extra slot consumed on re-entry");

        SaveManager.OnlineSaveData occupied = new SaveManager.OnlineSaveData("memory-only");
        for (int i = 0; i < 4; i++) {
            SaveManager.PlayerSlotData slot = occupied.slots.get(i);
            slot.status = "DISCONNECTED"; slot.playerName = "Saved" + i;
            slot.x = 100 + i; slot.y = 200; slot.hp = 80; slot.maxHp = 100;
        }
        RoomSaveManager restored = new RoomSaveManager(occupied, MAP, ignored -> writes.incrementAndGet());
        rejected(restored, "NEW", "NewRandomName", "4 slot save da co chu");
        for (int i = 0; i < 4; i++) check(occupied.slots.get(i).playerName.equals("Saved" + i), "Saved owner preserved");
        GameData.PlayerState owner = new GameData.PlayerState("NEW", "Saved2");
        check(restored.join(owner), "Saved owner can enter despite four historical slots");
        check(owner.x == 102 && owner.hp == 80, "Saved owner restored");
        check(writes.get() > 0, "Persistence callback exercised without filesystem writes");
        System.out.println("PASS: " + checks + " room-admission assertions (no save files read or written)");
    }
}
