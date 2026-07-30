package dev.minescreen;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Linear-only topology: prism displays never join vertically or across their cross-section. */
public final class CeilingDisplayGroupResolver {
    private static final int MAX_LENGTH = 128;
    private CeilingDisplayGroupResolver() {}

    public static CeilingDisplayGroup resolve(Level level, BlockPos start) {
        if (!(level.getBlockEntity(start) instanceof CeilingDisplayBlockEntity first)
                || !first.getBlockState().is(MineScreen.CEILING_DISPLAY_BLOCK.get())) return null;
        Direction.Axis axis = first.getBlockState().getValue(CeilingDisplayBlock.AXIS);
        Direction positive = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        BlockPos master = start.immutable();
        for (int i = 0; i < MAX_LENGTH; i++) {
            BlockPos previous = master.relative(positive.getOpposite());
            if (!same(level, previous, axis)) break;
            master = previous.immutable();
        }
        Set<BlockPos> tiles = new HashSet<>();
        UUID groupId = null;
        BlockPos cursor = master;
        for (int i = 0; i < MAX_LENGTH && same(level, cursor, axis); i++) {
            tiles.add(cursor.immutable());
            CeilingDisplayBlockEntity display = (CeilingDisplayBlockEntity) level.getBlockEntity(cursor);
            if (groupId == null || display.tileId().compareTo(groupId) < 0) groupId = display.tileId();
            cursor = cursor.relative(positive);
        }
        if (tiles.isEmpty() || groupId == null) return null;
        double maxX = master.getX() + (axis == Direction.Axis.X ? tiles.size() : 1);
        double maxZ = master.getZ() + (axis == Direction.Axis.Z ? tiles.size() : 1);
        return new CeilingDisplayGroup(groupId, master, axis, tiles.size(), tiles,
                level.dimension(), new AABB(master.getX(), master.getY(), master.getZ(),
                        maxX, master.getY() + 1.0D, maxZ));
    }

    private static boolean same(Level level, BlockPos pos, Direction.Axis axis) {
        return level.getBlockState(pos).is(MineScreen.CEILING_DISPLAY_BLOCK.get())
                && level.getBlockState(pos).getValue(CeilingDisplayBlock.AXIS) == axis
                && level.getBlockEntity(pos) instanceof CeilingDisplayBlockEntity;
    }
}
