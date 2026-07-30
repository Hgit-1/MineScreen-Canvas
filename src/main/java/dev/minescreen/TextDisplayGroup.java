package dev.minescreen;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A connected component of same-facing, same-kind text boards. */
public record TextDisplayGroup(BlockPos master, Direction facing, Set<BlockPos> tiles,
        int columns, int rows, int minRight, int minUp, boolean traffic) {
    public TextDisplayGroup {
        master = master.immutable();
        tiles = Set.copyOf(tiles);
        columns = Math.max(1, columns);
        rows = Math.max(1, rows);
    }

    public boolean contains(BlockPos pos) {
        return tiles.contains(pos);
    }

    public int column(BlockPos pos) {
        return ScreenGeometry.coordinate(pos, ScreenGeometry.rightDirection(facing)) - minRight;
    }

    public int row(BlockPos pos) {
        return ScreenGeometry.coordinate(pos, ScreenGeometry.upDirection(facing)) - minUp;
    }
}
