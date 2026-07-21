package dev.minescreen.client.content;

/** How one computer presents the screen planes on its connected cable network. */
public enum HostSurfaceLayout {
    /** Every plane retains its own profile/session and appears as a separate preview pane. */
    FREE,
    /** One IDLE/VIDEO/WEB/VNC canvas is sliced left-to-right across connected planes. */
    PANORAMA_HORIZONTAL,
    /** One IDLE/VIDEO/WEB/VNC canvas is sliced top-to-bottom across connected planes. */
    PANORAMA_VERTICAL,
    /** Physical planes use individually editable logical X/Y tile coordinates. */
    CUSTOM;

    public HostSurfaceLayout next() {
        HostSurfaceLayout[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static HostSurfaceLayout safeValueOf(String value) {
        try {
            return value == null ? FREE : valueOf(value);
        } catch (IllegalArgumentException exception) {
            return FREE;
        }
    }
}
