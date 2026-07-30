package dev.minescreen;

public enum StationDisplayMode {
    MANUAL,
    STATION_NEXT,
    STATION_MAP,
    TEXT_SEQUENCE;

    public StationDisplayMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
