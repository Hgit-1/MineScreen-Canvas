package dev.minescreen.client.compat;

import dev.minescreen.client.content.ScreenContentSession;

public interface VideoBackendFactory {
    CapabilityResult probe();

    ScreenContentSession create(VideoRequest request);
}
