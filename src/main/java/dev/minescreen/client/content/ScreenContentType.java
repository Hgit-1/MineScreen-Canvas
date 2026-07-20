package dev.minescreen.client.content;

public enum ScreenContentType {
    IDLE,
    VIDEO,
    WEB,
    VNC;

    /** Reads Stage-2 TEST profiles/states without keeping TEST as a visible mode. */
    public static ScreenContentType fromSerialized(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("TEST")) {
            return IDLE;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return IDLE;
        }
    }
}
