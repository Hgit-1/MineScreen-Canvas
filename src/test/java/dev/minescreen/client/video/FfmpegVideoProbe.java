package dev.minescreen.client.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Manual probe for the exact FFmpeg/scaler/NativeImage/ring path used by world rendering. */
public final class FfmpegVideoProbe {
    private FfmpegVideoProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 && arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: videoDecoderProbe -PvideoProbeSource=<mp4-or-url>"
                            + " [-PvideoProbeWidth=<px> -PvideoProbeHeight=<px>]");
        }
        String input = arguments[0];
        int width = arguments.length == 3 ? Integer.parseInt(arguments[1]) : 320;
        int height = arguments.length == 3 ? Integer.parseInt(arguments[2]) : 180;
        boolean remote = input.startsWith("http://") || input.startsWith("https://");
        Path path = remote ? null : Path.of(input).toAbsolutePath().normalize();
        if (!remote && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing probe video: " + path);
        }
        VideoSource source = new VideoSource(remote ? input : path.toString(),
                remote ? (input.startsWith("https://") ? VideoSource.Kind.HTTPS
                        : VideoSource.Kind.HTTP) : VideoSource.Kind.LOCAL_FILE,
                path);
        try (FrameRingBuffer ring = new FrameRingBuffer(width, height);
                FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(source, ring,
                        width, height, 30)) {
            decoder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
            while (System.nanoTime() < deadline) {
                VideoFrame frame = ring.pollLatest();
                if (frame != null) {
                    try {
                        System.out.printf("decodedFrame=true size=%dx%d ptsMs=%d durationMs=%d%n",
                                width, height, frame.ptsMicros() / 1000L, decoder.durationMs());
                        return;
                    } finally {
                        ring.release(frame);
                    }
                }
                if (decoder.errorMessage() != null) {
                    throw new IllegalStateException(decoder.errorMessage());
                }
                Thread.sleep(10L);
            }
            throw new IllegalStateException("Decoder produced no complete frame within 20 seconds"
                    + (decoder.errorMessage() == null ? "" : ": " + decoder.errorMessage()));
        }
    }
}
