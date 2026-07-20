package dev.minescreen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Shared orientation math for tile groups, rendering and ray picking. */
public final class ScreenGeometry {
    private ScreenGeometry() {
    }

    public static Direction rightDirection(Direction facing) {
        return switch (facing) {
            // FACING is the outward normal. Screen-right must be computed from an observer looking
            // at that outward face, not from an observer standing behind the panel.
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.WEST;
        };
    }

    public static Vec3 origin(BlockPos pos, Direction facing) {
        Vec3 local = switch (facing) {
            // Start at the viewer-left lower corner, then advance along screen-right. The previous
            // values started at viewer-right and advanced farther right, shifting every rendered
            // canvas by one block into air.
            case NORTH -> new Vec3(1.0D, 0.0D, -0.002D);
            case SOUTH -> new Vec3(0.0D, 0.0D, 1.002D);
            case EAST -> new Vec3(1.002D, 0.0D, 1.0D);
            case WEST -> new Vec3(-0.002D, 0.0D, 0.0D);
            default -> Vec3.ZERO;
        };
        return Vec3.atLowerCornerOf(pos).add(local);
    }

    public static Vec3 right(Direction facing) {
        return Vec3.atLowerCornerOf(rightDirection(facing).getNormal());
    }

    public static Vec3 up() {
        return new Vec3(0.0D, 1.0D, 0.0D);
    }

    public static Vec3 normal(Direction facing) {
        return Vec3.atLowerCornerOf(facing.getNormal());
    }
}
