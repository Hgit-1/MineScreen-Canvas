package dev.minescreen.client.content;

/** Quarter-turn mapping shared by host layout, world rendering and pointer input. */
public final class ScreenRotation {
    private ScreenRotation() {
    }

    public static int normalize(int turns) {
        return Math.floorMod(turns, 4);
    }

    public static int logicalWidth(int physicalWidth, int physicalHeight, int turns) {
        return (normalize(turns) & 1) == 0 ? physicalWidth : physicalHeight;
    }

    public static int logicalHeight(int physicalWidth, int physicalHeight, int turns) {
        return (normalize(turns) & 1) == 0 ? physicalHeight : physicalWidth;
    }

    /** Physical normalized coordinate -> content/canvas normalized U. */
    public static float contentU(float physicalU, float physicalV, int turns) {
        return switch (normalize(turns)) {
            case 1 -> physicalV;
            case 2 -> 1.0F - physicalU;
            case 3 -> 1.0F - physicalV;
            default -> physicalU;
        };
    }

    /** Physical normalized coordinate -> content/canvas normalized V (top to bottom). */
    public static float contentV(float physicalU, float physicalV, int turns) {
        return switch (normalize(turns)) {
            case 1 -> 1.0F - physicalU;
            case 2 -> 1.0F - physicalV;
            case 3 -> physicalU;
            default -> physicalV;
        };
    }

    /** Content/canvas normalized coordinate -> physical normalized U. */
    public static float physicalU(float contentU, float contentV, int turns) {
        return switch (normalize(turns)) {
            case 1 -> 1.0F - contentV;
            case 2 -> 1.0F - contentU;
            case 3 -> contentV;
            default -> contentU;
        };
    }

    /** Content/canvas normalized coordinate -> physical normalized V (top to bottom). */
    public static float physicalV(float contentU, float contentV, int turns) {
        return switch (normalize(turns)) {
            case 1 -> contentU;
            case 2 -> 1.0F - contentV;
            case 3 -> 1.0F - contentU;
            default -> contentV;
        };
    }
}
