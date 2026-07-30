package dev.minescreen.client;

/**
 * Pure layout math for fitting a logical MineScreen panel into small Minecraft GUI viewports.
 *
 * <p>Minecraft's GUI scale can reduce a 1080p window to roughly 427x240 logical pixels. Editors
 * keep one stable logical layout and uniformly scale the panel, glyphs, widgets and pointer
 * coordinates together instead of clipping controls.</p>
 */
public final class ResponsiveLayout {
    private ResponsiveLayout() {
    }

    public static Viewport fit(int actualWidth, int actualHeight,
            int desiredWidth, int desiredHeight) {
        int safeWidth = Math.max(1, actualWidth);
        int safeHeight = Math.max(1, actualHeight);
        int safeDesiredWidth = Math.max(1, desiredWidth);
        int safeDesiredHeight = Math.max(1, desiredHeight);
        float scale = Math.min(1.0F, Math.min(
                safeWidth / (float) safeDesiredWidth,
                safeHeight / (float) safeDesiredHeight));
        scale = Math.max(0.01F, scale);
        return new Viewport(scale,
                Math.max(safeDesiredWidth, (int) Math.floor(safeWidth / scale)),
                Math.max(safeDesiredHeight, (int) Math.floor(safeHeight / scale)));
    }

    public record Viewport(float scale, int logicalWidth, int logicalHeight) {
        public double logicalX(double actualX) {
            return actualX / scale;
        }

        public double logicalY(double actualY) {
            return actualY / scale;
        }

        public int logicalX(int actualX) {
            return (int) Math.floor(logicalX((double) actualX));
        }

        public int logicalY(int actualY) {
            return (int) Math.floor(logicalY((double) actualY));
        }
    }
}
