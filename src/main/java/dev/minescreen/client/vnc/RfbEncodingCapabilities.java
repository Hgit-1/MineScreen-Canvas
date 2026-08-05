package dev.minescreen.client.vnc;

/** Pure-Java decoder probe kept independent of Minecraft, NeoForge and optional native code. */
public final class RfbEncodingCapabilities {
    private RfbEncodingCapabilities() {
    }

    public static boolean jpegAvailable() {
        try {
            for (String name : javax.imageio.ImageIO.getReaderFormatNames()) {
                if ("jpeg".equalsIgnoreCase(name) || "jpg".equalsIgnoreCase(name)) return true;
            }
        } catch (Throwable ignored) {
            // A reduced mobile Java runtime may omit desktop ImageIO. Raw/CopyRect still work.
        }
        return false;
    }
}
