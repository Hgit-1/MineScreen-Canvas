package dev.minescreen;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** One horizontal row of 45-degree single-sided door LCD tiles. */
public record DoorLcdGroup(UUID groupId, BlockPos master, Direction facing, int length,
        Set<BlockPos> tiles, ResourceKey<Level> dimension, AABB bounds) {
    public DoorLcdGroup {
        master = master.immutable();
        tiles = Set.copyOf(tiles);
    }

    public ScreenGroup screenGroup() {
        UUID faceId = UUID.nameUUIDFromBytes((groupId + ":door-lcd-face")
                .getBytes(StandardCharsets.UTF_8));
        return new ScreenGroup(faceId, dimension, facing, master, master, length, 1, tiles,
                bounds.inflate(0.1D), false);
    }
}
