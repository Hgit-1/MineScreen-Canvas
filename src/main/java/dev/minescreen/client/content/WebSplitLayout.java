package dev.minescreen.client.content;

/** Browser viewport arrangements. Pane coordinates use normalized top-left screen space. */
public enum WebSplitLayout {
    SINGLE(1),
    VERTICAL(2),
    HORIZONTAL(2),
    QUAD(4);

    private final int paneCount;

    WebSplitLayout(int paneCount) {
        this.paneCount = paneCount;
    }

    public int paneCount() {
        return paneCount;
    }

    public WebSplitLayout next() {
        WebSplitLayout[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static WebSplitLayout safeValueOf(String value) {
        try {
            return value == null ? SINGLE : valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return SINGLE;
        }
    }
}
