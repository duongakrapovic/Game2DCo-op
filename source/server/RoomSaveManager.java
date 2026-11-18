package source.server;

import source.common.GameData;
import source.common.SaveManager;
import java.util.HashMap;
import java.util.Map;

/** Owns slot identity; disconnected slots retain progress and EMPTY slots stay empty. */
public final class RoomSaveManager {
    private final SaveManager.OnlineSaveData data;
    private final source.common.GameMap map;
    private final java.util.function.Consumer<SaveManager.OnlineSaveData> persist;
    private final Map<String, SaveManager.PlayerSlotData> slotsById = new HashMap<>();
    private final java.util.Set<String> connected = new java.util.HashSet<>();

    public RoomSaveManager(String roomName, source.common.GameMap map) {
        this(SaveManager.loadOrCreateOnlineSave(roomName, map), map, SaveManager::saveOnline);
    }

    RoomSaveManager(SaveManager.OnlineSaveData data, source.common.GameMap map,
            java.util.function.Consumer<SaveManager.OnlineSaveData> persist) {
        this.map = map;
        this.data = data;
        this.persist = persist;
        for (SaveManager.PlayerSlotData slot : data.slots) {
            if (!"EMPTY".equals(slot.status)) slot.status = "DISCONNECTED";
        }
    }

    public boolean join(GameData.PlayerState player) {
        SaveManager.PlayerSlotData match = null;
        for (SaveManager.PlayerSlotData slot : data.slots) {
            if (!"EMPTY".equals(slot.status) && player.name.equalsIgnoreCase(slot.playerName)) {
                if (slotsById.containsValue(slot)) {
                    boolean active = slotsById.entrySet().stream()
                            .anyMatch(entry -> entry.getValue() == slot && connected.contains(entry.getKey()));
                    throw new IllegalStateException(active
                            ? "Ten nguoi choi nay dang o trong phong. Hay dung ten khac."
                            : "Ten nguoi choi nay dang duoc giu cho ket noi lai (toi da 20 giay). Hay thu lai sau.");
                }
                match = slot; break;
            }
        }
        if (match == null) {
            for (SaveManager.PlayerSlotData slot : data.slots) {
                if ("EMPTY".equals(slot.status)) { match = slot; break; }
            }
            if (match == null) {
                String names = data.slots.stream().map(slot -> slot.playerName)
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Phong chua day nguoi online, nhung 4 slot save da co chu: "
                        + names + ". Hay dung dung ten cu hoac tao phong voi ten moi; save cu duoc giu nguyen.");
            }
            match.playerName = player.name;
            update(match, player);
        } else {
            if (match.x > 0 || match.y > 0) { player.x = match.x; player.y = match.y; }
            if (match.hp > 0) { player.hp = match.hp; player.maxHp = match.maxHp; }
        }
        java.awt.geom.Point2D.Float spawn = map.validatedPlayerSpawn(player.x, player.y);
        player.x = spawn.x; player.y = spawn.y;
        update(match, player);
        slotsById.put(player.id, match);
        connected.add(player.id);
        match.status = "ACTIVE";
        persist.accept(data);
        return true;
    }

    public void attach(String id) {
        connected.add(id);
        SaveManager.PlayerSlotData slot = slotsById.get(id);
        if (slot != null) slot.status = "ACTIVE";
    }

    public void detach(GameData.PlayerState player, boolean remove) {
        SaveManager.PlayerSlotData slot = slotsById.get(player.id);
        connected.remove(player.id);
        if (slot != null) {
            update(slot, player); slot.status = "DISCONNECTED";
            if (remove) slotsById.remove(player.id);
            persist.accept(data);
        }
    }

    public void syncAndSaveOnline(Iterable<GameData.PlayerState> players) {
        for (GameData.PlayerState player : players) {
            SaveManager.PlayerSlotData slot = slotsById.get(player.id);
            if (slot != null) {
                update(slot, player);
                slot.status = connected.contains(player.id) ? "ACTIVE" : "DISCONNECTED";
            }
        }
        persist.accept(data);
    }

    private static void update(SaveManager.PlayerSlotData slot, GameData.PlayerState p) {
        slot.x = p.x; slot.y = p.y; slot.hp = p.hp; slot.maxHp = p.maxHp;
    }
}
