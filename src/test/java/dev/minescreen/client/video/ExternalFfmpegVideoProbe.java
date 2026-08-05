package dev.minescreen.client.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Manual integration probe for the real ProcessBuilder/raw-RGBA emergency decoder path. */
public final class ExternalFfmpegVideoProbe {
    private ExternalFfmpegVideoProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Usage: <ffmpeg> <ffprobe> <local-mp4>");
        }
        Path ffmpeg = executable(arguments[0]);
        Path ffprobe = executable(arguments[1]);
        Path media = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(media)) throw new IllegalArgumentException("Missing MP4: " + media);
        VideoSource source = new VideoSource(media.toString(), VideoSource.Kind.LOCAL_FILE, media);
        try (FrameRingBuffer ring = new FrameRingBuffer(320, 180);
                ExternalFfmpegVideoDecoder decoder = new ExternalFfmpegVideoDecoder(source, ring,
                        ffmpeg, ffprobe, 320, 180, 15)) {
            decoder.start();
            long first = awaitFrame(decoder, ring, 20L, 0L);
            decoder.setPaused(true);
            Thread.sleep(200L);
            decoder.seek(1_000L);
            decoder.setPaused(false);
            long sought = awaitFrame(decoder, ring, 20L, 900L);
            if (decoder.durationMs() <= 0L) {
                throw new AssertionError("ffprobe returned no positive duration");
            }
            System.out.printf("externalFfmpegProbe=passed firstMs=%d soughtMs=%d durationMs=%d%n",
                    first, sought, decoder.durationMs());
        }
    }

    private static long awaitFrame(ExternalFfmpegVideoDecoder decoder, FrameRingBuffer ring,
            long timeoutSeconds, long minimumPtsMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            VideoFrame frame = ring.pollLatest();
            if (frame != null) {
                try {
                    long pts = frame.ptsMicros() / 1000L;
                    if (pts >= minimumPtsMs) return pts;
                } finally {
                    ring.release(frame);
                }
            }
            if (decoder.errorMessage() != null) {
                throw new IllegalStateException(decoder.errorMessage());
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("No external FFmpeg frame within timeout; positionMs="
                + decoder.positionMs() + ", decodedFrames=" + decoder.decodedFrames()
                + ", ended=" + decoder.ended() + ", error=" + decoder.errorMessage());
    }

    private static Path executable(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing program: " + path);
        return path;
    }
}
