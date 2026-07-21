package dev.minescreen.client.video;

import static org.bytedeco.ffmpeg.global.avformat.avformat_version;

/** Proves that the built mod jar alone exposes and loads its embedded Windows FFmpeg natives. */
public final class PackagedFfmpegProbe {
    private PackagedFfmpegProbe() {
    }

    public static void main(String[] arguments) {
        int version = avformat_version();
        if (version <= 0) {
            throw new IllegalStateException("Packaged FFmpeg returned an invalid version");
        }
        System.out.printf("packagedFfmpeg=true avformatVersion=%d%n", version);
    }
}
