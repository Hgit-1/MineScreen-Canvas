package dev.minescreen.client.vnc;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/** Immutable RGBA dirty rectangle allocated off-heap for a render-thread row copy. */
public record RfbFramebufferUpdate(int framebufferWidth, int framebufferHeight, int x, int y,
        int width, int height, ByteBuffer rgba) implements AutoCloseable {
    @Override
    public void close() {
        MemoryUtil.memFree(rgba);
    }
}
