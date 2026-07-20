package dev.minescreen.client.video;

import java.nio.file.Path;

public record VideoSource(Path path) {
    public static VideoSource local(String source) {
        return new VideoSource(Path.of(source));
    }
}
