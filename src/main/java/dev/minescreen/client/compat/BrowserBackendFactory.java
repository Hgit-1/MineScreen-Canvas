package dev.minescreen.client.compat;

import dev.minescreen.client.web.BrowserSession;

public interface BrowserBackendFactory {
    CapabilityResult probe();

    BrowserSession create(BrowserRequest request);
}
