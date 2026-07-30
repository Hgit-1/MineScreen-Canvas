package dev.minescreen;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Batch topology resolver used by both the server payload handler and the client cache. */
public final class TextDisplayGroupResolver {
    private TextDisplayGroupResolver() {
    }

    public static TextDisplayGroup resolve(Level level, BlockPos start) {
        if (level == null || !isBoard(level.getBlockState(start))) {
            return null;
        }
        BlockState source = level.getBlockState(start);
        net.minecraft.world.level.block.Block sourceBlock = source.getBlock();
        boolean traffic = source.is(MineScreen.TRAFFIC_DISPLAY_BLOCK.get());
        Direction facing = source.getValue(TextDisplayBlock.FACING);
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (visited.contains(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!isBoard(state)
                    || state.getBlock() != sourceBlock
                    || state.getValue(TextDisplayBlock.FACING) != facing
                    || state.is(MineScreen.TRAFFIC_DISPLAY_BLOCK.get()) != traffic) {
                continue;
            }
            visited.add(pos.immutable());
            queue.add(pos.relative(right));
            queue.add(pos.relative(right.getOpposite()));
            queue.add(pos.relative(up));
            queue.add(pos.relative(up.getOpposite()));
        }
        if (visited.isEmpty()) {
            return null;
        }
        int minRight = visited.stream().mapToInt(pos -> ScreenGeometry.coordinate(pos, right)).min()
                .orElse(0);
        int maxRight = visited.stream().mapToInt(pos -> ScreenGeometry.coordinate(pos, right)).max()
                .orElse(minRight);
        int minUp = visited.stream().mapToInt(pos -> ScreenGeometry.coordinate(pos, up)).min()
                .orElse(0);
        int maxUp = visited.stream().mapToInt(pos -> ScreenGeometry.coordinate(pos, up)).max()
                .orElse(minUp);
        BlockPos master = visited.stream()
                .min(Comparator.comparingInt((BlockPos pos) -> ScreenGeometry.coordinate(pos, up))
                        .thenComparingInt(pos -> ScreenGeometry.coordinate(pos, right))
                        .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(start);
        return new TextDisplayGroup(master, facing, visited, maxRight - minRight + 1,
                maxUp - minUp + 1, minRight, minUp, traffic);
    }

    public static boolean isBoard(BlockState state) {
        return state != null && state.getBlock() instanceof TextDisplayBlock;
    }
}
