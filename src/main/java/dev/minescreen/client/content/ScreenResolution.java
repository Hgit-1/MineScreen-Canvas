package dev.minescreen.client.content;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;

/** Resolves a per-profile render viewport without changing the physical joined-screen geometry. */
public final class ScreenResolution {
    private ScreenResolution() {
    }

    public static int percent(ClientScreenProfile profile) {
        int configured = profile.resolutionPercent <= 0
                ? MineScreenConfig.DEFAULT_RESOLUTION_PERCENT.get()
                : profile.resolutionPercent;
        return Math.max(25, Math.min(100, configured));
    }

    public static int[] dimensions(ScreenGroup group, ClientScreenProfile profile) {
        double scale = percent(profile) / 100.0D;
        int width = Math.max(64, (int) Math.round(group.canvasWidth() * scale));
        int height = Math.max(64, (int) Math.round(group.canvasHeight() * scale));
        return new int[] {width, height};
    }
}
