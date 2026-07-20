package dev.minescreen.network;

public enum ScreenAccess {
    OWNER_ONLY,
    ANYONE,
    OPERATORS;

    public static ScreenAccess safeValueOf(String value) {
        try {
            return value == null ? OWNER_ONLY : valueOf(value);
        } catch (IllegalArgumentException exception) {
            return OWNER_ONLY;
        }
    }
}
