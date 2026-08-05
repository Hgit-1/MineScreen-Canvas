package dev.minescreen.client.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;

import dev.minescreen.client.compat.FfmpegProcessEnvironment;
import dev.minescreen.client.video.VideoSource;
import net.minecraft.client.sounds.AudioStream;

/** Pulls 48 kHz stereo PCM from the same verified external FFmpeg runtime as video. */
final class ProcessPcmAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 16, 2, true, false);
    private final Process process;
    private final InputStream input;
    private volatile boolean closed;

    ProcessPcmAudioStream(Path ffmpeg, VideoSource source, long positionMs, boolean loop)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
        if (loop) command.addAll(List.of("-stream_loop", "-1"));
        if (positionMs > 0L) {
            command.addAll(List.of("-ss",
                    String.format(Locale.ROOT, "%.3f", positionMs / 1000.0D)));
        }
        command.addAll(List.of("-i", source.ffmpegInput(), "-vn", "-sn", "-dn", "-ac", "2",
                "-ar", "48000", "-f", "s16le", "pipe:1"));
        process = FfmpegProcessEnvironment.configure(new ProcessBuilder(command), ffmpeg).start();
        input = new BufferedInputStream(process.getInputStream(), 64 * 1024);
        Thread.ofPlatform().daemon(true).name("minescreen-ffmpeg-audio-errors")
                .start(() -> {
                    try (InputStream errors = process.getErrorStream()) {
                        errors.transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                    }
                });
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public synchronized ByteBuffer read(int capacity) throws IOException {
        if (closed) return null;
        int requested = Math.max(4096, Math.min(capacity, 64 * 1024));
        byte[] bytes = input.readNBytes(requested);
        if (bytes.length == 0) return null;
        ByteBuffer result = ByteBuffer.allocateDirect(bytes.length);
        result.put(bytes).flip();
        return result;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            input.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
