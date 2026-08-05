package dev.minescreen.client.compat;

import java.nio.file.Path;
import java.util.Locale;

/** Makes an extracted FFmpeg executable find the shared libraries stored beside it. */
public final class FfmpegProcessEnvironment {
    private FfmpegProcessEnvironment() {
    }

    public static ProcessBuilder configure(ProcessBuilder builder, Path executable) {
        Path directory = executable == null ? null : executable.toAbsolutePath().getParent();
        if (directory == null) return builder;
        builder.directory(directory.toFile());
        String separator = System.getProperty("path.separator", ":");
        prepend(builder, "PATH", directory, separator);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            prepend(builder, "DYLD_LIBRARY_PATH", directory, separator);
        } else if (!os.contains("windows")) {
            // Android's linker also consults LD_LIBRARY_PATH for a subprocess launched by Java.
            prepend(builder, "LD_LIBRARY_PATH", directory, separator);
        }
        return builder;
    }

    private static void prepend(ProcessBuilder builder, String key, Path directory,
            String separator) {
        String old = builder.environment().get(key);
        builder.environment().put(key, directory.toString()
                + (old == null || old.isBlank() ? "" : separator + old));
    }
}
