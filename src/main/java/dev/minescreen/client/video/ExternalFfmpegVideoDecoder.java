package dev.minescreen.client.video;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.mojang.blaze3d.platform.NativeImage;
import dev.minescreen.client.compat.FfmpegProcessEnvironment;
import org.lwjgl.system.MemoryUtil;

/**
 * Emergency decoder backed by an already-installed ffmpeg/ffprobe pair. The worker owns the
 * process and writes only complete RGBA frames to the existing three-slot ring; it never calls
 * Minecraft or OpenGL APIs.
 */
final class ExternalFfmpegVideoDecoder implements AutoCloseable {
    private final VideoSource source;
    private final FrameRingBuffer ring;
    private final Path ffmpeg;
    private final Path ffprobe;
    private final int width;
    private final int height;
    private final Object processLock = new Object();
    /**
     * A versioned restart command prevents a seek from being consumed by both the process-reader
     * loop and the outer lifecycle loop. Only the lifecycle loop acknowledges a version; newer
     * commands therefore cannot be mistaken for a normal end-of-file when a process is destroyed.
     */
    private final AtomicReference<RestartRequest> restartRequest =
            new AtomicReference<>(new RestartRequest(0L, -1L));
    private volatile int targetFps;
    private volatile boolean loop;
    private volatile boolean paused;
    private volatile boolean running = true;
    private volatile boolean ended;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile long decodedFrames;
    private volatile String errorMessage;
    private volatile Process process;
    private Thread thread;

    ExternalFfmpegVideoDecoder(VideoSource source, FrameRingBuffer ring, Path ffmpeg, Path ffprobe,
            int width, int height, int maxFps) {
        this.source = source;
        this.ring = ring;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.width = width;
        this.height = height;
        targetFps = Math.max(1, Math.min(30, maxFps));
    }

    void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::run, "minescreen-system-ffmpeg");
        thread.setDaemon(true);
        thread.start();
    }

    void setLoop(boolean loop) {
        this.loop = loop;
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    void setTargetFps(int fps) {
        int next = Math.max(1, Math.min(30, fps));
        if (targetFps != next) {
            targetFps = next;
            requestRestart(positionMs);
        }
    }

    void seek(long targetMs) {
        ended = false;
        requestRestart(Math.max(0L, targetMs));
    }

    long positionMs() {
        return positionMs;
    }

    long durationMs() {
        return durationMs;
    }

    long decodedFrames() {
        return decodedFrames;
    }

    boolean ended() {
        return ended;
    }

    String errorMessage() {
        return errorMessage;
    }

    private void run() {
        durationMs = probeDuration();
        RestartRequest acknowledged = restartRequest.get();
        long acknowledgedSequence = acknowledged.sequence();
        long startMs = Math.max(0L, acknowledged.positionMs());
        while (running) {
            if (paused) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20L));
                continue;
            }
            RestartRequest pending = restartRequest.get();
            if (pending.sequence() != acknowledgedSequence) {
                acknowledgedSequence = pending.sequence();
                startMs = Math.max(0L, pending.positionMs());
                ended = false;
            }
            try {
                DecodeResult result = decodeProcess(startMs, acknowledgedSequence);
                if (!running) {
                    break;
                }
                pending = restartRequest.get();
                if (pending.sequence() != acknowledgedSequence) {
                    acknowledgedSequence = pending.sequence();
                    startMs = Math.max(0L, pending.positionMs());
                    ended = false;
                    continue;
                }
                if (result == DecodeResult.END && loop) {
                    startMs = 0L;
                    ended = false;
                    continue;
                }
                if (result == DecodeResult.END) {
                    ended = true;
                    break;
                }
                if (result == DecodeResult.RESTART) {
                    startMs = positionMs;
                }
            } catch (Throwable failure) {
                pending = restartRequest.get();
                if (running && pending.sequence() == acknowledgedSequence) {
                    errorMessage = failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage();
                    break;
                }
                acknowledgedSequence = pending.sequence();
                startMs = Math.max(0L, pending.positionMs());
                ended = false;
            }
        }
        stopProcess();
    }

    private DecodeResult decodeProcess(long startMs, long commandSequence)
            throws IOException, InterruptedException {
        int fps = targetFps;
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
        command.addAll(List.of("-i", source.ffmpegInput()));
        if (startMs > 0L) {
            // Output-side seeking is slower than input-side keyframe seeking, but it is reliable
            // across the old/third-party FFmpeg builds used by emergency compatibility mode.
            command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0D)));
        }
        command.addAll(List.of("-an", "-sn", "-dn", "-vf",
                "scale=" + width + ":" + height
                        + ":force_original_aspect_ratio=decrease,pad=" + width + ":" + height
                        + ":(ow-iw)/2:(oh-ih)/2:black,fps=" + fps,
                "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1"));
        Process current;
        synchronized (processLock) {
            if (!running || restartRequest.get().sequence() != commandSequence) {
                return DecodeResult.RESTART;
            }
            current = FfmpegProcessEnvironment.configure(new ProcessBuilder(command), ffmpeg)
                    .start();
            process = current;
        }
        StringBuilder errors = new StringBuilder();
        Thread errorThread = Thread.ofPlatform().daemon(true).name("minescreen-ffmpeg-errors")
                .start(() -> drainErrors(current.getErrorStream(), errors));
        int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer bytes = MemoryUtil.memAlloc(frameBytes);
        long frameIndex = 0L;
        long clockStart = System.nanoTime();
        try (ReadableByteChannel output = Channels.newChannel(
                new BufferedInputStream(current.getInputStream(), Math.min(frameBytes, 1 << 20)))) {
            while (running && restartRequest.get().sequence() == commandSequence) {
                if (paused) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20L));
                    clockStart = System.nanoTime() - frameIndex * 1_000_000_000L / fps;
                    continue;
                }
                bytes.clear();
                while (bytes.hasRemaining()) {
                    int count = output.read(bytes);
                    if (count < 0) {
                        break;
                    }
                }
                if (bytes.hasRemaining()) {
                    break;
                }
                // Channels leave position at capacity. Flip before deriving the native source
                // address; memAddress(buffer) intentionally includes the current position.
                bytes.flip();
                long ptsMs = startMs + frameIndex * 1000L / fps;
                positionMs = ptsMs;
                NativeImage target = ring.acquireWritable();
                if (target != null) {
                    NativeImageAccess.copyRgba(target, bytes);
                    ring.publish(target, ptsMs * 1000L);
                    decodedFrames++;
                }
                frameIndex++;
                long due = clockStart + frameIndex * 1_000_000_000L / fps;
                while (running && restartRequest.get().sequence() == commandSequence && !paused) {
                    long remaining = due - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10L)));
                }
            }
        } finally {
            MemoryUtil.memFree(bytes);
            current.destroy();
            if (!current.waitFor(500L, TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
            }
            errorThread.join(250L);
            synchronized (processLock) {
                if (process == current) {
                    process = null;
                }
            }
        }
        if (restartRequest.get().sequence() != commandSequence || !running) {
            return DecodeResult.RESTART;
        }
        int exit = current.exitValue();
        if (exit != 0 && errors.length() > 0) {
            throw new IOException("System FFmpeg: " + errors.toString().trim());
        }
        return DecodeResult.END;
    }

    private long probeDuration() {
        Process probe = null;
        try {
            probe = FfmpegProcessEnvironment.configure(new ProcessBuilder(List.of(
                    ffprobe.toString(), "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=nw=1:nk=1", source.ffmpegInput()))
                    .redirectErrorStream(true), ffprobe).start();
            if (!probe.waitFor(8L, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return 0L;
            }
            String value = new String(probe.getInputStream().readNBytes(256), StandardCharsets.UTF_8)
                    .trim();
            return Math.max(0L, Math.round(Double.parseDouble(value) * 1000.0D));
        } catch (IOException | InterruptedException | NumberFormatException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return 0L;
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroyForcibly();
            }
        }
    }

    private static void drainErrors(InputStream input, StringBuilder target) {
        try (input) {
            byte[] bytes = input.readNBytes(8192);
            target.append(new String(bytes, StandardCharsets.UTF_8).replace('\r', ' ')
                    .replace('\n', ' '));
        } catch (IOException ignored) {
        }
    }

    private void stopProcess() {
        synchronized (processLock) {
            Process current = process;
            if (current != null) {
                current.destroy();
            }
        }
    }

    private void requestRestart(long targetMs) {
        synchronized (processLock) {
            restartRequest.updateAndGet(previous ->
                    new RestartRequest(previous.sequence() + 1L, Math.max(0L, targetMs)));
            Process current = process;
            if (current != null) {
                current.destroy();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        stopProcess();
        Thread current = thread;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum DecodeResult {
        END,
        RESTART
    }

    private record RestartRequest(long sequence, long positionMs) {
    }
}
