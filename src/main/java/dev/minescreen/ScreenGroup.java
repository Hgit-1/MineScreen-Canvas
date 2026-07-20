package dev.minescreen;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Immutable connected screen component used by both the renderer and the ray picker. */
public record ScreenGroup(
        UUID groupId,
        ResourceKey<Level> dimension,
        Direction facing,
        BlockPos master,
        BlockPos origin,
        int columns,
        int rows,
        Set<BlockPos> tiles,
        AABB bounds,
        boolean legacyAnchor) {
    public ScreenGroup {
        master = master.immutable();
        origin = origin.immutable();
        tiles = Collections.unmodifiableSet(Set.copyOf(tiles));
    }

    public boolean contains(BlockPos pos) {
        return tiles.contains(pos);
    }

    public int canvasWidth() {
        return ScreenCanvas.dimensions(columns, rows)[0];
    }

    public int canvasHeight() {
        return ScreenCanvas.dimensions(columns, rows)[1];
    }
}
