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
            case UP, DOWN -> Direction.EAST;
        };
    }

    /** Screen-up axis chosen so right x up always equals the outward face normal. */
    public static Direction upDirection(Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> Direction.UP;
            case UP -> Direction.NORTH;
            case DOWN -> Direction.SOUTH;
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
            case UP -> new Vec3(0.0D, 1.002D, 1.0D);
            case DOWN -> new Vec3(0.0D, -0.002D, 0.0D);
        };
        return Vec3.atLowerCornerOf(pos).add(local);
    }

    public static Vec3 right(Direction facing) {
        return Vec3.atLowerCornerOf(rightDirection(facing).getNormal());
    }

    public static Vec3 up(Direction facing) {
        return Vec3.atLowerCornerOf(upDirection(facing).getNormal());
    }

    public static Vec3 normal(Direction facing) {
        return Vec3.atLowerCornerOf(facing.getNormal());
    }

    public static int coordinate(BlockPos pos, Direction axis) {
        return pos.getX() * axis.getStepX() + pos.getY() * axis.getStepY()
                + pos.getZ() * axis.getStepZ();
    }

    public static int planeCoordinate(BlockPos pos, Direction facing) {
        return coordinate(pos, facing);
    }
}
