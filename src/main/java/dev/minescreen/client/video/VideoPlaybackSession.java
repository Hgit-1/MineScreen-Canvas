package dev.minescreen.client.video;

import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.audio.PositionalVideoAudio;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Client-side video session with one persistent texture and a decoder-owned three-frame ring. */
public final class VideoPlaybackSession implements ScreenContentSession {
    private FrameRingBuffer ring;
    private FfmpegVideoDecoder decoder;
    private DynamicTexture texture;
    private final ResourceLocation textureLocation;
    private final VideoSource source;
    private final ClientScreenProfile profile;
    private int width;
    private int height;
    private final boolean loop;
    private final PositionalVideoAudio audio;
    private long positionMs;
    private boolean paused;
    private boolean backendSuspended;
    private long suspendedAtNanos;
    private long suspendedPositionMs;
    private volatile long lastRenderedNanos;

    public VideoPlaybackSession(ScreenGroup group, ClientScreenProfile profile) {
        this(group, profile, VideoSource.resolve(profile.source));
    }

    /** Finalization constructor used after the bounded asynchronous source preflight. */
    public VideoPlaybackSession(ScreenGroup group, ClientScreenProfile profile, VideoSource source) {
        this.source = source;
        this.profile = profile.copy();
        int[] dimensions = ScreenResolution.dimensions(group, this.profile);
        width = dimensions[0];
        height = dimensions[1];
        UUID groupId = group.groupId();
        textureLocation = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "video/" + groupId.toString().replace('-', '_'));
        loop = profile.loop;
        paused = profile.paused;
        positionMs = Math.max(0L, profile.positionMs);
        startVideoBackend();
        audio = new PositionalVideoAudio(source, loop, profile.volume);
    }

    private void startVideoBackend() {
        ring = new FrameRingBuffer(width, height);
        texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, false));
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
        decoder = new FfmpegVideoDecoder(source, ring, width, height,
                MineScreenConfig.VIDEO_MAX_FPS.get());
        decoder.setLoop(loop);
        decoder.setPaused(paused || backendSuspended);
        if (positionMs > 0L) {
            decoder.seekMicros(positionMs * 1000L);
        }
        decoder.start();
    }

    @Override
    public ScreenContentType type() {
        return ScreenContentType.VIDEO;
    }

    @Override
    public ScreenRenderSource renderSource() {
        return new ScreenRenderSource(ScreenRenderType.screen(textureLocation), this::prepareTexture);
    }

    /** Immediate GUI previews do not enter RenderType state, so upload and bind explicitly. */
    private void prepareTexture() {
        lastRenderedNanos = System.nanoTime();
        uploadLatest();
        RenderSystem.setShaderTexture(0, textureLocation);
    }

    /** Render-thread only: upload the newest complete frame into the existing texture id. */
    private void uploadLatest() {
        RenderSystem.assertOnRenderThreadOrInit();
        VideoFrame next = ring.pollLatest();
        if (next == null) {
            return;
        }
        try {
            texture.bind();
            next.image().upload(0, 0, 0, 0, 0, width, height,
                    false, false, false, false);
            positionMs = next.ptsMicros() / 1000L;
        } finally {
            // glTexSubImage2D has consumed the CPU bytes before this call returns.
            ring.release(next);
        }
    }

    @Override
    public void tick(ScreenGroup group) {
        ScreenVisibility.State visibility = ScreenVisibility.evaluate(group);
        decoder.setTargetFps(visibility.far() ? MineScreenConfig.VIDEO_FAR_FPS.get()
                : MineScreenConfig.VIDEO_MAX_FPS.get());
        long now = System.nanoTime();
        boolean recentlyRendered = now - lastRenderedNanos
                < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750L);
        // Always allow the decoder to produce its first complete frame. Suspending a newly-created
        // session before that point leaves its persistent DynamicTexture transparent forever when
        // a host preview or large screen enters visibility on the following frame.
        if (!visibility.active() && !recentlyRendered && decoder.decodedFrames() > 0L) {
            audio.tick(group, false, decoder.positionMs());
            if (!backendSuspended) {
                positionMs = decoder.positionMs();
                suspendedPositionMs = positionMs;
                suspendedAtNanos = now;
                backendSuspended = true;
                decoder.setPaused(true);
            }
            return;
        }
        if (backendSuspended) {
            backendSuspended = false;
            if (!paused) {
                long target = suspendedPositionMs + (now - suspendedAtNanos) / 1_000_000L;
                long duration = durationMs();
                if (duration > 0L) {
                    target = loop ? target % duration : Math.min(target, duration);
                }
                seek(target);
            }
        }
        boolean playbackStopped = !loop && decoder.ended();
        decoder.setPaused(paused || playbackStopped);
        audio.tick(group, !paused && !playbackStopped, decoder.positionMs());
        long audioClock = audio.clockMs();
        if (!paused && audioClock >= 0L && Math.abs(audioClock - decoder.positionMs()) > 250L) {
            // OpenAL playback is the master clock once an audio stream is active. A coarse seek is
            // preferable to gradually accumulating lip-sync drift.
            decoder.seekMicros(audioClock * 1000L);
        }
    }

    @Override
    public void resize(ScreenGroup group) {
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        int nextWidth = dimensions[0];
        int nextHeight = dimensions[1];
        if (nextWidth == width && nextHeight == height) {
            return;
        }
        positionMs = decoder.positionMs();
        closeVideoBackend();
        width = nextWidth;
        height = nextHeight;
        startVideoBackend();
    }

    @Override
    public long positionMs() {
        return decoder.positionMs();
    }

    @Override
    public long durationMs() {
        return decoder.durationMs();
    }

    @Override
    public void seek(long positionMs) {
        this.positionMs = Math.max(0L, positionMs);
        decoder.seekMicros(this.positionMs * 1000L);
        audio.seek(this.positionMs);
    }

    @Override
    public void setPaused(boolean paused) {
        positionMs = decoder.positionMs();
        if (this.paused && !paused && decoder.ended()) {
            seek(0L);
        }
        this.paused = paused;
        decoder.setPaused(paused || backendSuspended);
    }

    @Override
    public void setVolume(float volume) {
        audio.setVolume(volume);
    }

    @Override
    public String errorMessage() {
        return decoder.errorMessage();
    }

    public boolean hasDecodedFrame() {
        return decoder.decodedFrames() > 0L;
    }

    public FfmpegVideoDecoder.Stage decoderStage() {
        return decoder.stage();
    }

    @Override
    public void close() {
        closeVideoBackend();
        audio.close();
    }

    private void closeVideoBackend() {
        decoder.close();
        ring.close();
        Minecraft.getInstance().getTextureManager().release(textureLocation);
    }
}
