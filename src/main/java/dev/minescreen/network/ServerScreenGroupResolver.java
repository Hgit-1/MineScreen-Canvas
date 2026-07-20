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

/** Server-side counterpart used only to validate a client-claimed joined group. */
final class ServerScreenGroupResolver {
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
        Direction right = ScreenGeometry.rightDirection(start.facing());
        Direction[] steps = {right, right.getOpposite(), Direction.UP, Direction.DOWN};
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<ScreenBlockEntity> component = new ArrayList<>();
        queue.add(startPos);
        visited.add(startPos);
        while (!queue.isEmpty() && component.size() <= 16_384) {
            BlockPos pos = queue.removeFirst();
            if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)
                    || screen.isLegacyAnchor() || screen.facing() != start.facing()) {
                continue;
            }
            component.add(screen);
            for (Direction step : steps) {
                BlockPos next = pos.relative(step);
                if (level.hasChunk(SectionPos.blockToSectionCoord(next.getX()),
                        SectionPos.blockToSectionCoord(next.getZ())) && visited.add(next)
                        && level.getBlockEntity(next) instanceof ScreenBlockEntity candidate
                        && !candidate.isLegacyAnchor() && candidate.facing() == start.facing()) {
                    queue.addLast(next);
                }
            }
        }
        if (component.isEmpty() || component.size() > 16_384) {
            return null;
        }
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        UUID groupId = null;
        ScreenBlockEntity master = component.getFirst();
        for (ScreenBlockEntity screen : component) {
            int horizontal = horizontal(screen.getBlockPos(), right);
            minH = Math.min(minH, horizontal);
            maxH = Math.max(maxH, horizontal);
            minY = Math.min(minY, screen.getBlockPos().getY());
            maxY = Math.max(maxY, screen.getBlockPos().getY());
            if (groupId == null || screen.tileId().compareTo(groupId) < 0) {
                groupId = screen.tileId();
            }
            if (compareMaster(screen, master, right) < 0) {
                master = screen;
            }
        }
        BlockPos sample = component.getFirst().getBlockPos();
        int sampleH = horizontal(sample, right);
        BlockPos origin = sample.offset(right.getStepX() * (minH - sampleH), minY - sample.getY(),
                right.getStepZ() * (minH - sampleH));
        Set<BlockPos> tiles = new HashSet<>();
        component.forEach(screen -> tiles.add(screen.getBlockPos().immutable()));
        int columns = maxH - minH + 1;
        int rows = maxY - minY + 1;
        return new ScreenGroup(groupId, level.dimension(), start.facing(), master.getBlockPos(), origin,
                columns, rows, tiles, bounds(origin, start.facing(), columns, rows), false);
    }

    private static int compareMaster(ScreenBlockEntity first, ScreenBlockEntity second, Direction right) {
        int y = Integer.compare(first.getBlockPos().getY(), second.getBlockPos().getY());
        if (y != 0) {
            return y;
        }
        int horizontal = Integer.compare(horizontal(first.getBlockPos(), right),
                horizontal(second.getBlockPos(), right));
        return horizontal != 0 ? horizontal : first.tileId().compareTo(second.tileId());
    }

    private static int horizontal(BlockPos pos, Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    private static AABB bounds(BlockPos origin, Direction facing, int columns, int rows) {
        Vec3 first = ScreenGeometry.origin(origin, facing);
        Vec3 second = first.add(ScreenGeometry.right(facing).scale(columns))
                .add(ScreenGeometry.up().scale(rows));
        return new AABB(first, second).inflate(0.05D);
    }
}
