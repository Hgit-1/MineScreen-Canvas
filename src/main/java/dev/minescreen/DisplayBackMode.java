package dev.minescreen;

public enum DisplayBackMode {
    OFF,
    SAME,
    INDEPENDENT;

    public DisplayBackMode next() { return values()[(ordinal() + 1) % values().length]; }
}
