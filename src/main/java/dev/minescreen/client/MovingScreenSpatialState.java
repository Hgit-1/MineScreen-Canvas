package dev.minescreen.client;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.compat.MovingStructureCompat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Last rendered world-space pose for each screen group hosted by a moving virtual Level. */
public final class MovingScreenSpatialState {
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private MovingScreenSpatialState() {
    }

    public static void update(Level virtualLevel, ScreenGroup localGroup, float partialTick) {
        MovingStructureCompat.Placement placement = MovingStructureCompat.locate(virtualLevel,
                localGroup.bounds(), partialTick);
        if (placement == null) {
            return;
        }
        ENTRIES.put(localGroup.groupId(), new Entry(new WeakReference<>(virtualLevel), localGroup,
                partialTick, placement, System.nanoTime()));
    }

    /** Derives a split-region pose from the already-associated parent virtual Level. */
    public static void updateDerived(ScreenGroup parent, ScreenGroup child) {
        Entry parentEntry = current(parent.groupId());
        Level virtualLevel = parentEntry == null ? null : parentEntry.virtualLevel().get();
        if (virtualLevel == null) {
            return;
        }
        MovingStructureCompat.Placement placement = MovingStructureCompat.locate(virtualLevel,
                child.bounds(), parentEntry.partialTick());
        if (placement != null) {
            ENTRIES.put(child.groupId(), new Entry(new WeakReference<>(virtualLevel), child,
                    parentEntry.partialTick(), placement, System.nanoTime()));
        }
    }

    public static AABB bounds(ScreenGroup group) {
        Entry entry = current(group.groupId());
        return entry == null ? group.bounds() : entry.placement().bounds();
    }

    public static Vec3 center(ScreenGroup group) {
        Entry entry = current(group.groupId());
        return entry == null ? group.bounds().getCenter() : entry.placement().center();
    }

    public static ResourceKey<Level> dimension(ScreenGroup group) {
        Entry entry = current(group.groupId());
        return entry == null ? group.dimension() : entry.placement().dimension();
    }

    public static Level virtualLevel(ScreenGroup group) {
        Entry entry = current(group.groupId());
        return entry == null ? null : entry.virtualLevel().get();
    }

    /** Returns the contraption-local group even when the caller retained its pre-assembly copy. */
    public static ScreenGroup localGroup(ScreenGroup group) {
        Entry entry = current(group.groupId());
        return entry == null ? null : entry.localGroup();
    }

    public static Vec3 toWorld(ScreenGroup group, Vec3 localPosition) {
        Entry entry = current(group.groupId());
        Level virtualLevel = entry == null ? null : entry.virtualLevel().get();
        if (virtualLevel == null) {
            return localPosition;
        }
        Vec3 transformed = MovingStructureCompat.toWorld(virtualLevel, localPosition,
                entry.partialTick());
        return transformed == null ? localPosition : transformed;
    }

    public static Vec3 toLocal(ScreenGroup group, Vec3 worldPosition) {
        Entry entry = current(group.groupId());
        Level virtualLevel = entry == null ? null : entry.virtualLevel().get();
        if (virtualLevel == null) {
            return null;
        }
        return MovingStructureCompat.toLocal(virtualLevel, worldPosition, entry.partialTick());
    }

    public static boolean isMoving(ScreenGroup group) {
        return current(group.groupId()) != null;
    }

    public static void clear(UUID groupId) {
        ENTRIES.remove(groupId);
    }

    public static void clearAll() {
        ENTRIES.clear();
    }

    public static void removeExpired() {
        long now = System.nanoTime();
        ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos() > TTL_NANOS
                || entry.getValue().virtualLevel().get() == null);
    }

    private static Entry current(UUID groupId) {
        Entry entry = ENTRIES.get(groupId);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.lastSeenNanos() > TTL_NANOS
                || entry.virtualLevel().get() == null) {
            ENTRIES.remove(groupId);
            return null;
        }
        return entry;
    }

    private record Entry(WeakReference<Level> virtualLevel, ScreenGroup localGroup,
            float partialTick,
            MovingStructureCompat.Placement placement, long lastSeenNanos) {
    }
}
