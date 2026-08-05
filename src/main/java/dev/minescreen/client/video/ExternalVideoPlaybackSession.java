package dev.minescreen.client.video;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.MineScreenClientConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.compat.CompatibilityManager;
import dev.minescreen.client.compat.PlatformFingerprint;
import dev.minescreen.client.audio.ExternalPositionalVideoAudio;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Process-based video/audio playback path backed by a verified downloaded or system FFmpeg. */
public final class ExternalVideoPlaybackSession implements ScreenContentSession {
    private final VideoSource source;
    private final ClientScreenProfile profile;
    private final Path ffmpeg;
    private final Path ffprobe;
    private final ResourceLocation textureLocation;
    private final ExternalPositionalVideoAudio audio;
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
        audio = new ExternalPositionalVideoAudio(ffmpeg, source, profile.loop, profile.volume);
    }

    private void startBackend() {
        ring = new FrameRingBuffer(width, height);
        texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, false));
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
        decoder = new ExternalFfmpegVideoDecoder(source, ring, ffmpeg, ffprobe, width, height,
                nearFps());
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
        decoder.setTargetFps(visibility.far() ? farFps() : nearFps());
        boolean shouldSuspend = !visibility.active() && decoder.decodedFrames() > 0L
                && System.nanoTime() - lastRenderedNanos > TimeUnit.MILLISECONDS.toNanos(750L);
        if (suspended != shouldSuspend) {
            suspended = shouldSuspend;
            decoder.setPaused(paused || suspended);
        }
        boolean stopped = !profile.loop && decoder.ended();
        audio.tick(group, !paused && !suspended && !stopped, decoder.positionMs());
        long audioClock = audio.clockMs();
        if (!paused && !suspended && audioClock >= 0L
                && Math.abs(audioClock - decoder.positionMs()) > 250L) {
            decoder.seek(audioClock);
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
        audio.seek(this.positionMs);
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused && decoder.ended()) {
            decoder.seek(0L);
        }
        decoder.setPaused(paused || suspended);
    }

    @Override public void setVolume(float volume) { audio.setVolume(volume); }
    @Override public String errorMessage() { return decoder.errorMessage(); }

    @Override
    public String loadingStatusTranslationKey() {
        return decoder.decodedFrames() > 0L ? null : "screen.minescreen.video.loading";
    }

    @Override
    public void close() {
        closeBackend();
        audio.close();
    }

    private void closeBackend() {
        if (decoder != null) decoder.close();
        if (ring != null) ring.close();
        Minecraft.getInstance().getTextureManager().release(textureLocation);
    }

    private static boolean mobileRuntime() {
        PlatformFingerprint.OsFamily os = CompatibilityManager.platform().os();
        return os == PlatformFingerprint.OsFamily.ANDROID
                || os == PlatformFingerprint.OsFamily.HARMONY;
    }

    private static int nearFps() {
        return mobileRuntime() ? MineScreenClientConfig.ANDROID_VIDEO_MAX_FPS.get()
                : MineScreenConfig.VIDEO_MAX_FPS.get();
    }

    private static int farFps() {
        return mobileRuntime() ? MineScreenClientConfig.ANDROID_VIDEO_FAR_FPS.get()
                : MineScreenConfig.VIDEO_FAR_FPS.get();
    }
}
