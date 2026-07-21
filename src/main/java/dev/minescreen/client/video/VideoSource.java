package dev.minescreen.client.video;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import dev.minescreen.client.ClientSecurityPolicy;
import dev.minescreen.client.web.BrowserRequestPolicy;

/** A validated FFmpeg input. The original URL is client-local and is never a media id. */
public record VideoSource(String ffmpegInput, Kind kind, Path localPath) {
    /** Large enough for signed CDN URLs while still bounding profile/config memory use. */
    public static final int MAX_INPUT_LENGTH = 65_535;

    public enum Kind {
        LOCAL_FILE,
        HTTP,
        HTTPS
    }

    /**
     * Resolves either a local MP4 path/file URI or an HTTP(S) media URL. Remote URLs are checked
     * by the same DNS/IP/allow-list policy as WEB before FFmpeg receives them.
     */
    public static VideoSource resolve(String value) {
        String source = value == null ? "" : value.trim();
        if (source.isEmpty()) {
            throw new ValidationException(Problem.EMPTY, "Video source is empty");
        }
        if (source.length() > MAX_INPUT_LENGTH) {
            throw new ValidationException(Problem.TOO_LONG,
                    "Video source exceeds " + MAX_INPUT_LENGTH + " characters");
        }

        URI uri = parseExplicitUri(source);
        if (uri != null) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new ValidationException(Problem.INVALID_URL,
                            "Video URL does not contain a valid host");
                }
                if (!BrowserRequestPolicy.isAllowed(source)) {
                    throw new ValidationException(Problem.BLOCKED_URL,
                            "Video URL is blocked by the current network policy");
                }
                return new VideoSource(source, scheme.equals("https") ? Kind.HTTPS : Kind.HTTP,
                        null);
            }
            if (scheme.equals("file")) {
                try {
                    return local(Path.of(uri));
                } catch (RuntimeException exception) {
                    throw new ValidationException(Problem.INVALID_FILE,
                            "Invalid local video file URI", exception);
                }
            }
            throw new ValidationException(Problem.UNSUPPORTED_SCHEME,
                    "Unsupported video URL scheme: " + scheme);
        }
        try {
            return local(Path.of(source));
        } catch (RuntimeException exception) {
            throw new ValidationException(Problem.INVALID_FILE, "Invalid local video path",
                    exception);
        }
    }

    private static VideoSource local(Path value) {
        if (!ClientSecurityPolicy.localFilesAllowed()) {
            throw new ValidationException(Problem.BLOCKED_FILE,
                    "Local video files are blocked by the current security policy");
        }
        Path path = value.toAbsolutePath().normalize();
        Path fileName = path.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")
                || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ValidationException(Problem.INVALID_FILE,
                    "Video file is not a readable MP4: " + path);
        }
        return new VideoSource(path.toString(), Kind.LOCAL_FILE, path);
    }

    /** Returns a URI only when the input has an explicit non-drive-letter scheme. */
    private static URI parseExplicitUri(String source) {
        int colon = source.indexOf(':');
        if (colon < 0 || colon == 1 && Character.isLetter(source.charAt(0))) {
            return null;
        }
        if (colon == 0 || !Character.isLetter(source.charAt(0))) {
            return null;
        }
        for (int i = 1; i < colon; i++) {
            char character = source.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '+' && character != '-'
                    && character != '.') {
                return null;
            }
        }
        try {
            return URI.create(source);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(Problem.INVALID_URL, "Invalid video URL", exception);
        }
    }

    public enum Problem {
        EMPTY,
        TOO_LONG,
        INVALID_FILE,
        BLOCKED_FILE,
        INVALID_URL,
        BLOCKED_URL,
        UNSUPPORTED_SCHEME
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final Problem problem;

        public ValidationException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public ValidationException(Problem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }
}
