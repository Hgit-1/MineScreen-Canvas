package dev.minescreen.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lazy topology cache for client-only virtual worlds, notably Create contraptions.
 *
 * <p>Create restores block entities and their NBT into a render-only Level whose positions remain
 * in contraption-local coordinates. Its renderer has already put the moving transform on the
 * PoseStack before invoking an ordinary BER, so MineScreen must group and emit local geometry only;
 * applying a second world transform would make the panel drift away from the carriage.</p>
 *
 * <p>The cache is weakly keyed by Level and built only when a screen BER is actually requested.
 * Contraption topology is immutable while assembled; only the current BE is validated on a cache
 * hit. Rewalking every component for every tile would turn a large carriage screen into O(n²)
 * render-thread work. A changed contraption receives a new virtual Level/cache when reassembled.</p>
 */
public final class MovingScreenGroupManager {
    private static final int MAX_COMPONENT_TILES = 4096;
    private static final Map<Level, Cache> CACHES = new WeakHashMap<>();

    private MovingScreenGroupManager() {
    }

    public static ScreenGroup groupAt(ScreenBlockEntity screen) {
        Level level = screen == null ? null : screen.getLevel();
        if (level == null || level instanceof ClientLevel || !level.isClientSide
                || !ScreenTileIndex.isLive(level, screen)) {
            return null;
        }
        Cache cache = CACHES.computeIfAbsent(level, ignored -> new Cache());
        ScreenGroup cached = cache.byTile.get(screen.getBlockPos());
        if (cached != null) {
            return cached;
        }
        ScreenGroup rebuilt = screen.isLegacyAnchor()
                ? legacyGroup(level.dimension(), screen)
                : connectedGroup(level, screen);
        rebuilt.tiles().forEach(pos -> cache.byTile.put(pos, rebuilt));
        return rebuilt;
    }

    private static ScreenGroup connectedGroup(Level level, ScreenBlockEntity start) {
        Direction facing = start.facing();
        int plane = ScreenGeometry.planeCoordinate(start.getBlockPos(), facing);
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        Direction[] steps = {right, right.getOpposite(), up, up.getOpposite()};
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<ScreenBlockEntity> component = new ArrayList<>();
        queue.add(start.getBlockPos().immutable());

        while (!queue.isEmpty() && visited.size() < MAX_COMPONENT_TILES) {
            BlockPos pos = queue.removeFirst();
            if (!visited.add(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)
                    || screen.isLegacyAnchor() || screen.facing() != facing
                    || ScreenGeometry.planeCoordinate(pos, facing) != plane
                    || !ScreenTileIndex.isLive(level, screen)) {
                continue;
            }
            component.add(screen);
            for (Direction step : steps) {
                BlockPos neighbor = pos.relative(step).immutable();
                if (!visited.contains(neighbor)) {
                    queue.addLast(neighbor);
                }
            }
        }
        // The starting BE passed validation, so this is defensive rather than a normal path.
        return component.isEmpty() ? legacyGroup(level.dimension(), start)
                : makeGroup(level.dimension(), component);
    }

    private static ScreenGroup makeGroup(ResourceKey<Level> dimension,
            List<ScreenBlockEntity> component) {
        ScreenBlockEntity first = component.getFirst();
        Direction facing = first.facing();
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        int minRight = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        int minUp = Integer.MAX_VALUE;
        int maxUp = Integer.MIN_VALUE;
        UUID groupId = null;
        ScreenBlockEntity master = first;
        Set<BlockPos> tiles = new HashSet<>();
        for (ScreenBlockEntity screen : component) {
            BlockPos pos = screen.getBlockPos();
            tiles.add(pos.immutable());
            int rightCoordinate = ScreenGeometry.coordinate(pos, right);
            int upCoordinate = ScreenGeometry.coordinate(pos, up);
            minRight = Math.min(minRight, rightCoordinate);
            maxRight = Math.max(maxRight, rightCoordinate);
            minUp = Math.min(minUp, upCoordinate);
            maxUp = Math.max(maxUp, upCoordinate);
            if (groupId == null || screen.tileId().compareTo(groupId) < 0) {
                groupId = screen.tileId();
            }
            if (compareMaster(screen, master, right, up) < 0) {
                master = screen;
            }
        }
        BlockPos sample = first.getBlockPos();
        BlockPos origin = sample.relative(right,
                minRight - ScreenGeometry.coordinate(sample, right)).relative(up,
                        minUp - ScreenGeometry.coordinate(sample, up));
        int columns = maxRight - minRight + 1;
        int rows = maxUp - minUp + 1;
        return new ScreenGroup(groupId, dimension, facing, master.getBlockPos(), origin,
                columns, rows, tiles, bounds(origin, facing, columns, rows), false);
    }

    private static ScreenGroup legacyGroup(ResourceKey<Level> dimension,
            ScreenBlockEntity screen) {
        return new ScreenGroup(screen.tileId(), dimension, screen.facing(), screen.getBlockPos(),
                screen.getBlockPos(), screen.getScreenWidth(), screen.getScreenHeight(),
                Set.of(screen.getBlockPos()), bounds(screen.getBlockPos(), screen.facing(),
                        screen.getScreenWidth(), screen.getScreenHeight()), true);
    }

    private static AABB bounds(BlockPos origin, Direction facing, int columns, int rows) {
        Vec3 first = ScreenGeometry.origin(origin, facing);
        Vec3 second = first.add(ScreenGeometry.right(facing).scale(columns))
                .add(ScreenGeometry.up(facing).scale(rows));
        return new AABB(first, second).inflate(0.05D);
    }

    private static int compareMaster(ScreenBlockEntity first, ScreenBlockEntity second,
            Direction right, Direction up) {
        Comparator<ScreenBlockEntity> comparator = Comparator
                .comparingInt((ScreenBlockEntity screen) ->
                        ScreenGeometry.coordinate(screen.getBlockPos(), up))
                .thenComparingInt(screen -> ScreenGeometry.coordinate(screen.getBlockPos(), right))
                .thenComparing(ScreenBlockEntity::tileId);
        return comparator.compare(first, second);
    }

    private static final class Cache {
        private final Map<BlockPos, ScreenGroup> byTile = new HashMap<>();
    }
}
