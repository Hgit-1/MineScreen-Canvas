package dev.minescreen.client.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ClientSecurityPolicy;
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
    private final FrameRingBuffer ring;
    private final FfmpegVideoDecoder decoder;
    private final DynamicTexture texture;
    private final ResourceLocation textureLocation;
    private final int width;
    private final int height;
    private final boolean loop;
    private final PositionalVideoAudio audio;
    private long positionMs;
    private boolean paused;
    private boolean backendSuspended;
    private long suspendedAtNanos;
    private long suspendedPositionMs;

    public VideoPlaybackSession(ScreenGroup group, ClientScreenProfile profile) {
        Path path = VideoSource.local(profile.source).path().toAbsolutePath().normalize();
        if (!ClientSecurityPolicy.localFilesAllowed()) {
            throw new IllegalStateException("Local video requires allow_file_protocol=true");
        }
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalStateException("Stage 2 accepts local MP4 files only");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("Video file is not readable: " + path);
        }
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        width = dimensions[0];
        height = dimensions[1];
        UUID groupId = group.groupId();
        ring = new FrameRingBuffer(width, height);
        textureLocation = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "video/" + groupId.toString().replace('-', '_'));
        texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, false));
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
        decoder = new FfmpegVideoDecoder(new VideoSource(path), ring, width, height,
                MineScreenConfig.VIDEO_MAX_FPS.get());
        loop = profile.loop;
        paused = profile.paused;
        positionMs = Math.max(0L, profile.positionMs);
        decoder.setLoop(loop);
        decoder.setPaused(paused);
        if (positionMs > 0L) {
            decoder.seekMicros(positionMs * 1000L);
        }
        decoder.start();
        audio = new PositionalVideoAudio(path, loop, profile.volume);
    }

    @Override
    public ScreenContentType type() {
        return ScreenContentType.VIDEO;
    }

    @Override
    public ScreenRenderSource renderSource() {
        return new ScreenRenderSource(ScreenRenderType.screen(textureLocation), this::uploadLatest);
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
        if (!visibility.active()) {
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
    public String errorMessage() {
        return decoder.errorMessage();
    }

    @Override
    public void close() {
        decoder.close();
        audio.close();
        ring.close();
        Minecraft.getInstance().getTextureManager().release(textureLocation);
    }
}
