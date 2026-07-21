package dev.minescreen.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server validation counterpart for physically adjacent coplanar screen groups. */
final class ServerScreenGroupResolver {
    private static final int MAX_NETWORK_NODES = 16_384;

    private ServerScreenGroupResolver() {
    }

    static ScreenGroup resolve(ServerLevel level, BlockPos startPos) {
        if (!(level.getBlockEntity(startPos) instanceof ScreenBlockEntity start)) {
            return null;
        }
        if (start.isLegacyAnchor()) {
            return new ScreenGroup(start.tileId(), level.dimension(), start.facing(), startPos, startPos,
                    start.getScreenWidth(), start.getScreenHeight(), Set.of(startPos.immutable()),
                    bounds(startPos, start.facing(), start.getScreenWidth(), start.getScreenHeight()), true);
        }
        Direction facing = start.facing();
        int plane = ScreenGeometry.planeCoordinate(startPos, facing);
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        Direction[] screenSteps = {right, right.getOpposite(), up, up.getOpposite()};
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> addedScreens = new HashSet<>();
        List<ScreenBlockEntity> component = new ArrayList<>();
        queue.add(startPos.immutable());
        visited.add(startPos.immutable());

        while (!queue.isEmpty() && visited.size() <= MAX_NETWORK_NODES) {
            BlockPos current = queue.removeFirst();
            if (level.getBlockEntity(current) instanceof ScreenBlockEntity screen) {
                if (screen.isLegacyAnchor() || screen.facing() != facing
                        || ScreenGeometry.planeCoordinate(current, facing) != plane
                        || !addedScreens.add(current)) {
                    continue;
                }
                component.add(screen);
                for (Direction step : screenSteps) {
                    enqueue(level, current.relative(step), facing, plane, visited, queue);
                }
            }
        }
        if (component.isEmpty() || visited.size() > MAX_NETWORK_NODES) {
            return null;
        }
        return makeGroup(level, component);
    }

    private static void enqueue(ServerLevel level, BlockPos pos, Direction facing, int plane,
            Set<BlockPos> visited, ArrayDeque<BlockPos> queue) {
        if (loaded(level, pos) && level.getBlockEntity(pos) instanceof ScreenBlockEntity candidate
                && !candidate.isLegacyAnchor() && candidate.facing() == facing
                && ScreenGeometry.planeCoordinate(pos, facing) == plane
                && visited.add(pos.immutable())) {
            queue.addLast(pos.immutable());
        }
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static ScreenGroup makeGroup(ServerLevel level, List<ScreenBlockEntity> component) {
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
        for (ScreenBlockEntity screen : component) {
            BlockPos pos = screen.getBlockPos();
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
        Set<BlockPos> tiles = new HashSet<>();
        component.forEach(screen -> tiles.add(screen.getBlockPos().immutable()));
        int columns = maxRight - minRight + 1;
        int rows = maxUp - minUp + 1;
        return new ScreenGroup(groupId, level.dimension(), facing, master.getBlockPos(), origin,
                columns, rows, tiles, bounds(origin, facing, columns, rows), false);
    }

    private static int compareMaster(ScreenBlockEntity first, ScreenBlockEntity second,
            Direction right, Direction up) {
        int vertical = Integer.compare(ScreenGeometry.coordinate(first.getBlockPos(), up),
                ScreenGeometry.coordinate(second.getBlockPos(), up));
        if (vertical != 0) {
            return vertical;
        }
        int horizontal = Integer.compare(ScreenGeometry.coordinate(first.getBlockPos(), right),
                ScreenGeometry.coordinate(second.getBlockPos(), right));
        return horizontal != 0 ? horizontal : first.tileId().compareTo(second.tileId());
    }

    private static AABB bounds(BlockPos origin, Direction facing, int columns, int rows) {
        Vec3 first = ScreenGeometry.origin(origin, facing);
        Vec3 second = first.add(ScreenGeometry.right(facing).scale(columns))
                .add(ScreenGeometry.up(facing).scale(rows));
        return new AABB(first, second).inflate(0.05D);
    }
}
