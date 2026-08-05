package dev.minescreen.client.compat;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;

public record BrowserRequest(ScreenGroup group, ClientScreenProfile profile, ScreenGroup stateGroup) {
}
