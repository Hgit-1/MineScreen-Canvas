package dev.minescreen.client.audio;

import java.nio.file.Path;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.video.VideoSource;
import net.minecraft.client.Minecraft;

/** Owns/restarts the FFmpeg audio worker and Minecraft/OpenAL streaming source for one video. */
public final class PositionalVideoAudio implements AutoCloseable {
    private final Path path;
    private final boolean loop;
    private final float volume;
    private FfmpegAudioDecoder decoder;
    private FfmpegPcmAudioStream stream;
    private VideoAudioSound sound;
    private long clockBaseMs;
    private long clockBaseNanos;
    private boolean unavailable;

    public PositionalVideoAudio(Path path, boolean loop, float volume) {
        this.path = path;
        this.loop = loop;
        this.volume = Math.max(0.0F, Math.min(1.0F, volume));
    }

    public void tick(ScreenGroup group, boolean shouldPlay, long videoPositionMs) {
        if (unavailable || !shouldPlay || volume <= 0.0F) {
            if (!shouldPlay) {
                stopPlayback();
            }
            return;
        }
        if (decoder != null && decoder.errorMessage() != null) {
            unavailable = !decoder.audioStreamFound();
            stopPlayback();
            return;
        }
        if (sound == null || sound.isStopped()) {
            startPlayback(group, videoPositionMs);
        } else {
            sound.update(group, volume);
        }
    }

    public long clockMs() {
        if (sound == null || sound.isStopped() || decoder == null || !decoder.audioStreamFound()) {
            return -1L;
        }
        return clockBaseMs + Math.max(0L, System.nanoTime() - clockBaseNanos) / 1_000_000L;
    }

    public void seek(long positionMs) {
        stopPlayback();
        clockBaseMs = Math.max(0L, positionMs);
    }

    private void startPlayback(ScreenGroup group, long positionMs) {
        stopPlayback();
        decoder = new FfmpegAudioDecoder(new VideoSource(path));
        decoder.setLoop(loop);
        decoder.seek(positionMs);
        decoder.start();
        stream = new FfmpegPcmAudioStream(decoder, positionMs);
        sound = new VideoAudioSound(group, volume, stream);
        clockBaseMs = Math.max(0L, positionMs);
        clockBaseNanos = System.nanoTime();
        Minecraft.getInstance().getSoundManager().play(sound);
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
            decoder = null;
        } else if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }

    @Override
    public void close() {
        stopPlayback();
    }
}
