package dev.minescreen.client.video;

import com.mojang.blaze3d.platform.NativeImage;

public final class VideoFrame {
    private final NativeImage image;
    private final long ptsMicros;
    private final int slot;

    VideoFrame(NativeImage image, long ptsMicros, int slot) {
        this.image = image;
        this.ptsMicros = ptsMicros;
        this.slot = slot;
    }

    public NativeImage image() {
        return image;
    }

    public long ptsMicros() {
        return ptsMicros;
    }

    int slot() {
        return slot;
    }
}
