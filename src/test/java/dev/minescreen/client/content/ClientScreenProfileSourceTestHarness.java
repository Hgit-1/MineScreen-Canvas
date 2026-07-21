package dev.minescreen.client.content;

/** Dependency-free migration/switching checks for the three independent content addresses. */
public final class ClientScreenProfileSourceTestHarness {
    private ClientScreenProfileSourceTestHarness() {
    }

    public static void main(String[] arguments) {
        ClientScreenProfile legacy = new ClientScreenProfile();
        legacy.contentType = ScreenContentType.WEB;
        legacy.source = "https://example.com/old";
        legacy.normalizeSources();
        require(legacy.webSource.equals(legacy.source), "legacy WEB source was not migrated");

        legacy.switchContentType(ScreenContentType.VIDEO, legacy.source);
        require(legacy.source.isEmpty(), "WEB URL leaked into an empty VIDEO source");
        legacy.setSourceFor(ScreenContentType.VIDEO, "C:\\media\\movie.mp4");
        legacy.switchContentType(ScreenContentType.VNC, legacy.source);
        require(legacy.source.isEmpty(), "VIDEO path leaked into an empty VNC source");
        legacy.setSourceFor(ScreenContentType.VNC, "vnc://127.0.0.1:5900");
        legacy.switchContentType(ScreenContentType.WEB, legacy.source);
        require(legacy.source.equals("https://example.com/old"), "WEB source was not restored");
        legacy.switchContentType(ScreenContentType.VIDEO, legacy.source);
        require(legacy.source.equals("C:\\media\\movie.mp4"), "VIDEO source was not restored");

        ClientScreenProfile collision = new ClientScreenProfile();
        collision.contentType = ScreenContentType.WEB;
        collision.videoSource = "/tmp/movie.mp4";
        collision.webSource = "file:///tmp/movie.mp4";
        collision.source = collision.webSource;
        collision.normalizeSources();
        require(collision.webSource.isEmpty(), "VIDEO source leaked into WEB source");
        require(collision.source.isEmpty(), "contaminated WEB source remained active");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
