package dev.minescreen;

/** Deterministic joined-canvas sizing with an aspect-preserving cap. */
public final class ScreenCanvas {
    private ScreenCanvas() {
    }

    public static int canvasWidth(int columns) {
        return dimensions(Math.max(1, columns), 1)[0];
    }

    public static int canvasHeight(int rows) {
        return dimensions(1, Math.max(1, rows))[1];
    }

    public static int[] dimensions(int columns, int rows) {
        long width = (long) columns * MineScreenConfig.PIXELS_PER_TILE.get();
        long height = (long) rows * MineScreenConfig.PIXELS_PER_TILE.get();
        double factor = 1.0D;
        factor = Math.min(factor, (double) MineScreenConfig.MAX_CANVAS_WIDTH.get() / Math.max(1L, width));
        factor = Math.min(factor, (double) MineScreenConfig.MAX_CANVAS_HEIGHT.get() / Math.max(1L, height));
        factor = Math.min(factor, Math.sqrt((double) MineScreenConfig.MAX_CANVAS_PIXELS.get()
                / Math.max(1L, width * height)));
        return new int[] {Math.max(1, (int) Math.floor(width * factor)),
                Math.max(1, (int) Math.floor(height * factor))};
    }

}
