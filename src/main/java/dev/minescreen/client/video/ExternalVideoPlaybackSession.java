package dev.minescreen.client.video;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Video-only emergency playback path for platforms where JavaCPP natives cannot load. */
public final class ExternalVideoPlaybackSession implements ScreenContentSession {
    private final VideoSource source;
    private final ClientScreenProfile profile;
    private final Path ffmpeg;
    private final Path ffprobe;
    private final ResourceLocation textureLocation;
    private FrameRingBuffer ring;
    private ExternalFfmpegVideoDecoder decoder;
    private DynamicTexture texture;
    private int width;
    private int height;
    private long positionMs;
    private boolean paused;
    private boolean suspended;
    private long lastRenderedNanos;

    public ExternalVideoPlaybackSession(ScreenGroup group, ClientScreenProfile profile,
            VideoSource source, Path ffmpeg, Path ffprobe) {
        this.source = source;
        this.profile = profile.copy();
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        width = dimensions[0];
        height = dimensions[1];
        positionMs = Math.max(0L, profile.positionMs);
        paused = profile.paused;
        UUID id = group.groupId();
        textureLocation = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "external_video/" + id.toString().replace('-', '_'));
        startBackend();
    }

    private void startBackend() {
        ring = new FrameRingBuffer(width, height);
        texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, false));
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
        decoder = new ExternalFfmpegVideoDecoder(source, ring, ffmpeg, ffprobe, width, height,
                MineScreenConfig.VIDEO_MAX_FPS.get());
        decoder.setLoop(profile.loop);
        decoder.setPaused(paused);
        if (positionMs > 0L) {
            decoder.seek(positionMs);
        }
        decoder.start();
    }

    @Override public ScreenContentType type() { return ScreenContentType.VIDEO; }

    @Override
    public ScreenRenderSource renderSource() {
        return new ScreenRenderSource(ScreenRenderType.screen(textureLocation), this::prepareTexture);
    }

    private void prepareTexture() {
        lastRenderedNanos = System.nanoTime();
        VideoFrame frame = ring.pollLatest();
        if (frame != null) {
            try {
                texture.bind();
                frame.image().upload(0, 0, 0, 0, 0, width, height,
                        false, false, false, false);
                positionMs = frame.ptsMicros() / 1000L;
            } finally {
                ring.release(frame);
            }
        }
        RenderSystem.setShaderTexture(0, textureLocation);
    }

    @Override
    public void tick(ScreenGroup group) {
        ScreenVisibility.State visibility = ScreenVisibility.evaluate(group);
        decoder.setTargetFps(visibility.far() ? MineScreenConfig.VIDEO_FAR_FPS.get()
                : MineScreenConfig.VIDEO_MAX_FPS.get());
        boolean shouldSuspend = !visibility.active() && decoder.decodedFrames() > 0L
                && System.nanoTime() - lastRenderedNanos > TimeUnit.MILLISECONDS.toNanos(750L);
        if (suspended != shouldSuspend) {
            suspended = shouldSuspend;
            decoder.setPaused(paused || suspended);
        }
    }

    @Override
    public void resize(ScreenGroup group) {
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        if (dimensions[0] == width && dimensions[1] == height) {
            return;
        }
        positionMs = decoder.positionMs();
        closeBackend();
        width = dimensions[0];
        height = dimensions[1];
        startBackend();
    }

    @Override public long positionMs() { return decoder.positionMs(); }
    @Override public long durationMs() { return decoder.durationMs(); }

    @Override
    public void seek(long positionMs) {
        this.positionMs = Math.max(0L, positionMs);
        decoder.seek(this.positionMs);
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused && decoder.ended()) {
            decoder.seek(0L);
        }
        decoder.setPaused(paused || suspended);
    }

    @Override public void setVolume(float volume) { /* Emergency backend is intentionally muted. */ }
    @Override public String errorMessage() { return decoder.errorMessage(); }

    @Override
    public String loadingStatusTranslationKey() {
        return decoder.decodedFrames() > 0L ? null : "screen.minescreen.video.loading";
    }

    @Override
    public void close() {
        closeBackend();
    }

    private void closeBackend() {
        if (decoder != null) decoder.close();
        if (ring != null) ring.close();
        Minecraft.getInstance().getTextureManager().release(textureLocation);
    }
}
