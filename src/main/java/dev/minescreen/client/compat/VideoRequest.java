package dev.minescreen.client.compat;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.video.VideoSource;

public record VideoRequest(ScreenGroup group, ClientScreenProfile profile, VideoSource source) {
}
