package dev.minescreen.client.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.NativeImage;
import dev.minescreen.MineScreen;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.video.NativeImageAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

/** Decodes the newest peer JPEG off-thread and uploads it into one reused DynamicTexture. */
final class PeerFrameTexture implements AutoCloseable {
    private final ResourceLocation location;
    private final Executor codecExecutor;
    private final AtomicReference<byte[]> encoded = new AtomicReference<>();
    private final AtomicReference<DecodedFrame> decoded = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private DynamicTexture texture;
    private NativeImage image;
    private int width;
    private int height;
    private volatile boolean closed;

    PeerFrameTexture(UUID groupId, Executor codecExecutor) {
        this.codecExecutor = codecExecutor;
        location = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "web_peer/" + groupId.toString().replace('-', '_'));
    }

    void accept(byte[] jpeg) {
        if (closed || jpeg.length == 0) {
            return;
        }
        encoded.set(jpeg);
        scheduleDecode();
    }

    boolean ready() {
        return texture != null || decoded.get() != null;
    }

    ScreenRenderSource renderSource() {
        uploadLatest();
        return texture == null ? null
                : new ScreenRenderSource(ScreenRenderType.screen(location), this::uploadLatest);
    }

    private void scheduleDecode() {
        if (!decoding.compareAndSet(false, true)) {
            return;
        }
        codecExecutor.execute(() -> {
            try {
                byte[] next;
                while (!closed && (next = encoded.getAndSet(null)) != null) {
                    DecodedFrame frame = decode(next);
                    DecodedFrame old = decoded.getAndSet(frame);
                    if (old != null) {
                        old.close();
                    }
                }
            } finally {
                decoding.set(false);
                if (encoded.get() != null && !closed) {
                    scheduleDecode();
                }
            }
        });
    }

    private static DecodedFrame decode(byte[] jpeg) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (source == null || source.getWidth() < 1 || source.getHeight() < 1
                    || (long) source.getWidth() * source.getHeight() > 8_294_400L) {
                throw new IOException("Invalid peer WEB JPEG");
            }
            int width = source.getWidth();
            int height = source.getHeight();
            ByteBuffer rgba = MemoryUtil.memAlloc(Math.multiplyExact(width * height, 4));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = source.getRGB(x, y);
                    rgba.put((byte) (argb >>> 16));
                    rgba.put((byte) (argb >>> 8));
                    rgba.put((byte) argb);
                    rgba.put((byte) 0xFF);
                }
            }
            rgba.flip();
            return new DecodedFrame(width, height, rgba);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to decode peer WEB frame", exception);
        }
    }

    private void uploadLatest() {
        DecodedFrame frame = decoded.getAndSet(null);
        if (frame == null || closed) {
            if (frame != null) {
                frame.close();
            }
            return;
        }
        try {
            if (texture == null || width != frame.width || height != frame.height) {
                replace(frame.width, frame.height);
            }
            NativeImageAccess.copyRgba(image, MemoryUtil.memAddress(frame.rgba),
                    (long) width * height * 4L);
            texture.bind();
            image.upload(0, 0, 0, 0, 0, width, height,
                    false, false, false, false);
        } finally {
            frame.close();
        }
    }

    private void replace(int nextWidth, int nextHeight) {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        }
        image = new NativeImage(NativeImage.Format.RGBA, nextWidth, nextHeight, false);
        texture = new DynamicTexture(image);
        width = nextWidth;
        height = nextHeight;
        Minecraft.getInstance().getTextureManager().register(location, texture);
    }

    @Override
    public void close() {
        closed = true;
        encoded.set(null);
        DecodedFrame pending = decoded.getAndSet(null);
        if (pending != null) {
            pending.close();
        }
        Runnable release = () -> {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(location);
                texture = null;
                image = null;
            }
        };
        if (Minecraft.getInstance().isSameThread()) {
            release.run();
        } else {
            Minecraft.getInstance().execute(release);
        }
    }

    private record DecodedFrame(int width, int height, ByteBuffer rgba) implements AutoCloseable {
        @Override
        public void close() {
            MemoryUtil.memFree(rgba);
        }
    }
}
