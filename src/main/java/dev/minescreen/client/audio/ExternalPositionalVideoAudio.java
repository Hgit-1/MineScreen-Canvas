package dev.minescreen.client.audio;

import java.io.IOException;
import java.nio.file.Path;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.video.VideoSource;
import net.minecraft.client.Minecraft;

/** Positional OpenAL owner for the process-based FFmpeg compatibility backend. */
public final class ExternalPositionalVideoAudio implements AutoCloseable {
    private final Path ffmpeg;
    private final VideoSource source;
    private final boolean loop;
    private float volume;
    private ProcessPcmAudioStream stream;
    private VideoAudioSound sound;
    private long clockBaseMs;
    private long clockBaseNanos;
    private boolean unavailable;

    public ExternalPositionalVideoAudio(Path ffmpeg, VideoSource source, boolean loop,
            float volume) {
        this.ffmpeg = ffmpeg;
        this.source = source;
        this.loop = loop;
        this.volume = clamp(volume);
    }

    public void tick(ScreenGroup group, boolean shouldPlay, long positionMs) {
        if (unavailable || !shouldPlay || volume <= 0.0F) {
            if (!shouldPlay || volume <= 0.0F) stopPlayback();
            return;
        }
        if (sound == null || sound.isStopped()) startPlayback(group, positionMs);
        else sound.update(group, volume);
    }

    public long clockMs() {
        if (sound == null || sound.isStopped()) return -1L;
        return clockBaseMs + Math.max(0L, System.nanoTime() - clockBaseNanos) / 1_000_000L;
    }

    public void seek(long positionMs) {
        stopPlayback();
        clockBaseMs = Math.max(0L, positionMs);
    }

    public void setVolume(float volume) {
        this.volume = clamp(volume);
        if (sound != null && !sound.isStopped()) sound.setBaseVolume(this.volume);
        if (this.volume <= 0.0F) stopPlayback();
    }

    private void startPlayback(ScreenGroup group, long positionMs) {
        stopPlayback();
        try {
            stream = new ProcessPcmAudioStream(ffmpeg, source, positionMs, loop);
            sound = new VideoAudioSound(group, volume, stream);
            clockBaseMs = Math.max(0L, positionMs);
            clockBaseNanos = System.nanoTime();
            Minecraft.getInstance().getSoundManager().play(sound);
        } catch (IOException failure) {
            unavailable = true;
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (sound != null) {
            sound.stopNow();
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    @Override
    public void close() {
        stopPlayback();
    }
}
