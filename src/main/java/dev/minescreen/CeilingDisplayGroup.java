package dev.minescreen;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

public record CeilingDisplayGroup(UUID groupId, BlockPos master, Direction.Axis axis, int length,
        Set<BlockPos> tiles, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
        AABB bounds) {
    public CeilingDisplayGroup {
        master = master.immutable();
        tiles = Set.copyOf(tiles);
    }

    public ScreenGroup sideGroup(int side) {
        int safe = side == 1 ? 1 : 0;
        UUID sideId = UUID.nameUUIDFromBytes((groupId + ":ceiling-side:" + safe)
                .getBytes(StandardCharsets.UTF_8));
        Direction facing = axis == Direction.Axis.X
                ? safe == 0 ? Direction.NORTH : Direction.SOUTH
                : safe == 0 ? Direction.WEST : Direction.EAST;
        return new ScreenGroup(sideId, dimension, facing, master, master, length, 1, tiles,
                bounds.inflate(0.1D), false);
    }
}
