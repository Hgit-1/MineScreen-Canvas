package dev.minescreen;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Linear topology for single-sided door LCDs and carriage information strips. */
public final class DoorLcdGroupResolver {
    private static final int MAX_LENGTH = 128;

    private DoorLcdGroupResolver() {}

    public static DoorLcdGroup resolve(Level level, BlockPos start) {
        if (!(level.getBlockEntity(start) instanceof CeilingDisplayBlockEntity first)
                || (!first.getBlockState().is(MineScreen.DOOR_LCD_BLOCK.get())
                        && !first.getBlockState().is(
                                MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get()))) {
            return null;
        }
        Block displayBlock = first.getBlockState().getBlock();
        Direction facing = first.getBlockState().getValue(
                BlockStateProperties.HORIZONTAL_FACING);
        Direction positive = ScreenGeometry.rightDirection(facing);
        BlockPos master = start.immutable();
        for (int index = 0; index < MAX_LENGTH; index++) {
            BlockPos previous = master.relative(positive.getOpposite());
            if (!same(level, previous, facing, displayBlock)) break;
            master = previous.immutable();
        }
        Set<BlockPos> tiles = new HashSet<>();
        UUID groupId = null;
        BlockPos cursor = master;
        for (int index = 0; index < MAX_LENGTH
                && same(level, cursor, facing, displayBlock); index++) {
            tiles.add(cursor.immutable());
            CeilingDisplayBlockEntity display = (CeilingDisplayBlockEntity) level.getBlockEntity(cursor);
            if (groupId == null || display.tileId().compareTo(groupId) < 0) groupId = display.tileId();
            cursor = cursor.relative(positive);
        }
        if (tiles.isEmpty() || groupId == null) return null;
        AABB bounds = null;
        for (BlockPos tile : tiles) {
            AABB tileBounds = new AABB(tile);
            bounds = bounds == null ? tileBounds : bounds.minmax(tileBounds);
        }
        return new DoorLcdGroup(groupId, master, facing, tiles.size(), tiles,
                level.dimension(), bounds == null ? new AABB(start) : bounds);
    }

    private static boolean same(Level level, BlockPos pos, Direction facing, Block block) {
        return level.getBlockState(pos).is(block)
                && level.getBlockState(pos).getValue(
                        BlockStateProperties.HORIZONTAL_FACING) == facing
                && level.getBlockEntity(pos) instanceof CeilingDisplayBlockEntity;
    }
}
