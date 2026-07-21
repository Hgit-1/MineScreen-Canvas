package dev.minescreen.client.video;

import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

/** Converts native FFmpeg result codes into actionable client-visible messages. */
public final class FfmpegErrors {
    private FfmpegErrors() {
    }

    public static IllegalStateException failure(String operation, int result) {
        byte[] buffer = new byte[512];
        if (av_strerror(result, buffer, buffer.length) >= 0) {
            int length = 0;
            while (length < buffer.length && buffer[length] != 0) {
                length++;
            }
            return new IllegalStateException(operation + ": "
                    + new String(buffer, 0, length, StandardCharsets.UTF_8));
        }
        return new IllegalStateException(operation + " (FFmpeg error " + result + ")");
    }
}
