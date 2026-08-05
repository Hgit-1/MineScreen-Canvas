package dev.minescreen.client.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.minescreen.MineScreenClientConfig;

/** Finds existing programs without executing a shell or downloading native code. */
final class ExternalProgramDetector {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

    private ExternalProgramDetector() {
    }

    static Optional<Program> browser(PlatformFingerprint platform) {
        if (!platform.desktopLike()) {
            return Optional.empty();
        }
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addConfigured(candidates, MineScreenClientConfig.EXTERNAL_BROWSER_PATH.get());
        switch (platform.os()) {
            case WINDOWS -> addWindowsBrowsers(candidates);
            case MACOS -> {
                candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
                candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
                candidates.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            }
            case LINUX -> addPathPrograms(candidates, List.of("supermium", "chromium",
                    "chromium-browser", "google-chrome", "google-chrome-stable", "microsoft-edge"));
            default -> {
            }
        }
        return firstWorking(candidates, "--version", platform);
    }

    static Optional<Program> ffmpeg(PlatformFingerprint platform) {
        if (platform.os() == PlatformFingerprint.OsFamily.UNKNOWN) {
            return Optional.empty();
        }
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addConfigured(candidates, MineScreenClientConfig.EXTERNAL_FFMPEG_PATH.get());
        addPathPrograms(candidates, List.of(platform.os() == PlatformFingerprint.OsFamily.WINDOWS
                ? "ffmpeg.exe" : "ffmpeg"));
        return firstWorking(candidates, "-version", platform);
    }

    static Optional<Program> ffprobe(PlatformFingerprint platform, Path ffmpeg) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addConfigured(candidates, MineScreenClientConfig.EXTERNAL_FFPROBE_PATH.get());
        if (ffmpeg != null && ffmpeg.getParent() != null) {
            candidates.add(ffmpeg.getParent().resolve(platform.os() == PlatformFingerprint.OsFamily.WINDOWS
                    ? "ffprobe.exe" : "ffprobe"));
        }
        addPathPrograms(candidates, List.of(platform.os() == PlatformFingerprint.OsFamily.WINDOWS
                ? "ffprobe.exe" : "ffprobe"));
        return firstWorking(candidates, "-version", platform);
    }

    private static void addWindowsBrowsers(LinkedHashSet<Path> candidates) {
        List<String> roots = List.of(nullToEmpty(System.getenv("ProgramFiles")),
                nullToEmpty(System.getenv("ProgramFiles(x86)")),
                nullToEmpty(System.getenv("LOCALAPPDATA")));
        List<String> relative = List.of("Supermium/supermium.exe",
                "Supermium/Application/supermium.exe",
                "Chromium/Application/chrome.exe",
                "Google/Chrome/Application/chrome.exe",
                "Microsoft/Edge/Application/msedge.exe");
        for (String root : roots) {
            if (root.isBlank()) {
                continue;
            }
            for (String child : relative) {
                candidates.add(Path.of(root).resolve(child));
            }
        }
        addPathPrograms(candidates, List.of("supermium.exe", "chromium.exe", "chrome.exe",
                "msedge.exe"));
    }

    private static void addConfigured(LinkedHashSet<Path> candidates, String configured) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            candidates.add(Path.of(configured.trim()));
        } catch (RuntimeException ignored) {
        }
    }

    private static void addPathPrograms(LinkedHashSet<Path> candidates, List<String> names) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return;
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(
                System.getProperty("path.separator", ";")))) {
            // Empty PATH entries mean the current directory. Never trust them for executables.
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path directory;
            try {
                String cleaned = entry.trim();
                if (cleaned.length() >= 2 && cleaned.startsWith("\"")
                        && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                directory = Path.of(cleaned);
                // Relative PATH elements resolve against the current working directory and are
                // therefore not trusted as installed programs.
                if (!directory.isAbsolute()) continue;
                directory = directory.normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            for (String name : names) {
                candidates.add(directory.resolve(name));
            }
        }
    }

    private static Optional<Program> firstWorking(LinkedHashSet<Path> candidates, String argument,
            PlatformFingerprint platform) {
        for (Path candidate : candidates) {
            Path executable = canonicalExecutable(candidate, platform);
            if (executable == null) {
                continue;
            }
            Probe probe = probe(executable, argument);
            if (probe.success()) {
                return Optional.of(new Program(executable, probe.output()));
            }
        }
        return Optional.empty();
    }

    private static Path canonicalExecutable(Path candidate, PlatformFingerprint platform) {
        try {
            Path path = candidate.toAbsolutePath().normalize().toRealPath();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                return null;
            }
            if (platform.os() != PlatformFingerprint.OsFamily.WINDOWS
                    && !Files.isExecutable(path)) {
                return null;
            }
            return path;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Probe probe(Path executable, String argument) {
        Process process = null;
        try {
            process = FfmpegProcessEnvironment.configure(
                    new ProcessBuilder(List.of(executable.toString(), argument))
                            .redirectErrorStream(true), executable).start();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Probe(false, "probe timed out");
            }
            byte[] bytes = process.getInputStream().readNBytes(4096);
            String output = new String(bytes, StandardCharsets.UTF_8).replace('\r', ' ')
                    .replace('\n', ' ').trim();
            return new Probe(process.exitValue() == 0, output);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Probe(false, exception.getClass().getSimpleName());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Program(Path path, String version) {
        Program {
            version = version == null ? "" : version;
        }

        String conciseVersion() {
            String clean = version.trim();
            return clean.length() <= 160 ? clean : clean.substring(0, 160);
        }
    }

    private record Probe(boolean success, String output) {
    }
}
