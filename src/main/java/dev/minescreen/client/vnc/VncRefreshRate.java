package dev.minescreen.client.vnc;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.content.ClientScreenProfile;

/** Shared per-screen VNC refresh presets; 0 means follow the NeoForge default. */
public final class VncRefreshRate {
    private static final int[] PRESETS = {0, 5, 10, 15, 20, 30, 60};

    private VncRefreshRate() {
    }

    public static int resolve(ClientScreenProfile profile) {
        return profile.vncFps <= 0 ? MineScreenConfig.VNC_MAX_FPS.get()
                : Math.max(1, Math.min(60, profile.vncFps));
    }

    public static int next(int currentOverride) {
        return step(currentOverride, 1);
    }

    public static int previous(int currentOverride) {
        return step(currentOverride, -1);
    }

    private static int step(int currentOverride, int delta) {
        for (int index = 0; index < PRESETS.length; index++) {
            if (PRESETS[index] == currentOverride) {
                return PRESETS[Math.floorMod(index + delta, PRESETS.length)];
            }
        }
        return 0;
    }
}
