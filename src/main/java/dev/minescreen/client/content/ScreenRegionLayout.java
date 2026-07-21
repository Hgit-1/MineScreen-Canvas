package dev.minescreen.client.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Projects physical tile assignments into independent rectangular content canvases. */
public final class ScreenRegionLayout {
    public static final int MAX_REGIONS = 4;
    private static final Map<UUID, CachedLayout> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private ScreenRegionLayout() {
    }

    public static int regionAt(ClientScreenProfile profile, BlockPos tile) {
        int id = profile.tileRegions == null ? 0
                : profile.tileRegions.getOrDefault(tile.asLong(), 0);
        return id >= 0 && id < MAX_REGIONS ? id : 0;
    }

    public static ClientScreenProfile profileFor(ClientScreenProfile root, int regionId) {
        if (regionId <= 0) {
            return root;
        }
        ScreenRegionProfile region = root.regions == null ? null : root.regions.get(regionId);
        return region == null ? new ClientScreenProfile() : region.toProfile();
    }

    public static UUID sessionId(UUID parentId, int regionId) {
        if (regionId <= 0) {
            return parentId;
        }
        return UUID.nameUUIDFromBytes((parentId + ":region:" + regionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static Canvas canvas(ScreenGroup parent, ClientScreenProfile profile, int regionId) {
        return canvases(parent, profile).stream().filter(canvas -> canvas.regionId() == regionId)
                .findFirst().orElse(null);
    }

    public static List<Canvas> canvases(ScreenGroup parent, ClientScreenProfile profile) {
        if (parent.legacyAnchor()) {
            return List.of(new Canvas(0, parent, 0, 0, parent.columns(), parent.rows()));
        }
        long signature = signature(parent, profile);
        CachedLayout cached = CACHE.get(parent.groupId());
        if (cached != null && cached.signature() == signature) {
            return cached.canvases();
        }
        Map<Integer, Set<BlockPos>> byRegion = new HashMap<>();
        for (BlockPos tile : parent.tiles()) {
            int id = regionAt(profile, tile);
            byRegion.computeIfAbsent(id, ignored -> new HashSet<>()).add(tile.immutable());
        }
        List<Canvas> result = new ArrayList<>();
        byRegion.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Canvas canvas = makeCanvas(parent, entry.getKey(), entry.getValue());
            if (canvas != null) {
                result.add(canvas);
            }
        });
        List<Canvas> immutable = List.copyOf(result);
        CACHE.put(parent.groupId(), new CachedLayout(signature, immutable));
        return immutable;
    }

    private static long signature(ScreenGroup parent, ClientScreenProfile profile) {
        long value = 31L * parent.dimension().location().hashCode() + parent.facing().ordinal();
        value = value * 31L + parent.origin().asLong();
        value = value * 31L + parent.columns();
        value = value * 31L + parent.rows();
        for (BlockPos tile : parent.tiles()) {
            long tileValue = tile.asLong() * 31L + regionAt(profile, tile);
            value += Long.rotateLeft(tileValue, (int) (tile.asLong() & 31L));
        }
        return value;
    }

    private static Canvas makeCanvas(ScreenGroup parent, int regionId, Set<BlockPos> tiles) {
        if (tiles.isEmpty()) {
            return null;
        }
        Direction right = ScreenGeometry.rightDirection(parent.facing());
        Direction up = ScreenGeometry.upDirection(parent.facing());
        int parentRight = ScreenGeometry.coordinate(parent.origin(), right);
        int parentUp = ScreenGeometry.coordinate(parent.origin(), up);
        int minColumn = Integer.MAX_VALUE;
        int maxColumn = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        for (BlockPos tile : tiles) {
            int column = ScreenGeometry.coordinate(tile, right) - parentRight;
            int row = ScreenGeometry.coordinate(tile, up) - parentUp;
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        BlockPos origin = parent.origin().relative(right, minColumn).relative(up, minRow);
        int columns = maxColumn - minColumn + 1;
        int rows = maxRow - minRow + 1;
        BlockPos master = tiles.stream().min(Comparator
                .comparingInt((BlockPos pos) -> ScreenGeometry.coordinate(pos, up))
                .thenComparingInt(pos -> ScreenGeometry.coordinate(pos, right))
                .thenComparingLong(BlockPos::asLong)).orElse(parent.master());
        Vec3 first = ScreenGeometry.origin(origin, parent.facing());
        Vec3 second = first.add(ScreenGeometry.right(parent.facing()).scale(columns))
                .add(ScreenGeometry.up(parent.facing()).scale(rows));
        ScreenGroup regionGroup = new ScreenGroup(sessionId(parent.groupId(), regionId),
                parent.dimension(), parent.facing(), master, origin, columns, rows, tiles,
                new AABB(first, second).inflate(0.05D), false);
        return new Canvas(regionId, regionGroup, minColumn, minRow, columns, rows);
    }

    public record Canvas(int regionId, ScreenGroup group, int minColumn, int minRow,
            int columns, int rows) {
        public boolean contains(BlockPos pos) {
            return group.tiles().contains(pos);
        }
    }

    private record CachedLayout(long signature, List<Canvas> canvases) {
    }
}
