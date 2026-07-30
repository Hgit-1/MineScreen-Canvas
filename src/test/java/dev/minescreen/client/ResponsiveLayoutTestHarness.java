package dev.minescreen.client;

/** Regression coverage for small-window/high-GUI-scale editor layouts and pointer transforms. */
public final class ResponsiveLayoutTestHarness {
    private ResponsiveLayoutTestHarness() {
    }

    public static void main(String[] args) {
        verify(1_920, 1_080, 960, 540);
        verify(1_280, 720, 1_120, 680);
        verify(854, 480, 1_120, 680);
        verify(427, 240, 1_120, 680);
        verify(320, 180, 1_280, 720);
        verify(100, 100, 1_000, 400);
        verify(0, 0, 0, 0);
        System.out.println("responsiveLayoutTest=passed; viewports=7; pointerRoundTrip=true");
    }

    private static void verify(int width, int height, int desiredWidth, int desiredHeight) {
        ResponsiveLayout.Viewport viewport =
                ResponsiveLayout.fit(width, height, desiredWidth, desiredHeight);
        require(viewport.scale() >= 0.01F && viewport.scale() <= 1.0F,
                "Scale outside safe range");
        require(viewport.logicalWidth() >= Math.max(1, desiredWidth),
                "Logical width clips the requested panel");
        require(viewport.logicalHeight() >= Math.max(1, desiredHeight),
                "Logical height clips the requested panel");
        require(viewport.logicalWidth() * viewport.scale() <= Math.max(1, width) + 1.0F,
                "Fitted width exceeds the actual viewport");
        require(viewport.logicalHeight() * viewport.scale() <= Math.max(1, height) + 1.0F,
                "Fitted height exceeds the actual viewport");

        double actualX = Math.max(1, width) * 0.73D;
        double actualY = Math.max(1, height) * 0.41D;
        double reconstructedX = viewport.logicalX(actualX) * viewport.scale();
        double reconstructedY = viewport.logicalY(actualY) * viewport.scale();
        require(Math.abs(actualX - reconstructedX) < 0.0001D
                        && Math.abs(actualY - reconstructedY) < 0.0001D,
                "Pointer transform is not reversible");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
