package dev.minescreen.client.content;

/** Top-left logical tile coordinate of one physical screen plane in a custom host canvas. */
public record HostSurfacePlacement(int x, int y) {
    public static final int LIMIT = 128;

    public HostSurfacePlacement {
        x = Math.max(-LIMIT, Math.min(LIMIT, x));
        y = Math.max(-LIMIT, Math.min(LIMIT, y));
    }

    public HostSurfacePlacement offset(int deltaX, int deltaY) {
        return new HostSurfacePlacement(x + deltaX, y + deltaY);
    }
}
