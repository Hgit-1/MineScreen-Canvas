package dev.minescreen.client.traffic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Converts a bounded UTF-8 text document into a declarative synchronized traffic template.
 *
 * <p>Pages are separated by a line containing {@code ---}. Directives start with {@code @};
 * unknown directives are rejected so a typo never silently changes playback. The original local
 * path is deliberately not retained in the generated manifest.</p>
 */
final class TrafficTextDocument {
    static final long MAX_BYTES = 128L * 1024L;
    static final int MAX_FRAMES = 64;
    static final int MAX_FRAME_CHARACTERS = 2_048;
    static final int MAX_TOTAL_CHARACTERS = 65_536;
    private static final Set<String> TRANSITIONS =
            Set.of("none", "fade", "slide_left", "typewriter", "blink");
    private static final Set<String> ALIGNMENTS = Set.of("left", "center", "right");
    private static final Set<String> POSITIONS = Set.of("top", "center", "bottom");

    private TrafficTextDocument() {
    }

    static Parsed parse(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_BYTES) {
            throw new IOException("TXT document is missing or exceeds 128 KiB");
        }
        String document = decode(Files.readAllBytes(source)).replace("\r\n", "\n")
                .replace('\r', '\n');
        String base = source.getFileName().toString().replaceFirst("(?i)\\.txt$", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        if (base.isBlank()) base = "notice";
        String id = "text_" + base;
        if (id.length() > 64) id = id.substring(0, 64);

        Options options = new Options(source.getFileName().toString());
        List<Frame> frames = new ArrayList<>();
        FrameBuilder frame = new FrameBuilder();
        boolean contentStarted = false;
        int totalCharacters = 0;
        for (String rawLine : document.split("\n", -1)) {
            String trimmed = rawLine.trim();
            if (trimmed.equals("---")) {
                totalCharacters += finishFrame(frames, frame, options);
                frame = new FrameBuilder();
                contentStarted = !frames.isEmpty();
                continue;
            }
            if (trimmed.startsWith("@")) {
                parseDirective(trimmed, options, frame, contentStarted || frame.hasContent());
                continue;
            }
            frame.lines.add(rawLine);
        }
        totalCharacters += finishFrame(frames, frame, options);
        if (frames.isEmpty()) throw new IOException("TXT document contains no display text");
        if (frames.size() > MAX_FRAMES || totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new IOException("TXT document exceeds the frame or character limit");
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("name", options.title);
        manifest.addProperty("layout", "script_scene_v1");
        manifest.addProperty("background_color", options.backgroundColor);
        manifest.addProperty("text_color", options.textColor);
        manifest.addProperty("accent_color", options.accentColor);
        manifest.add("elements", new JsonArray());
        JsonObject sequence = new JsonObject();
        sequence.addProperty("loop", options.loop);
        sequence.addProperty("default_duration_ticks", options.defaultDurationTicks);
        sequence.addProperty("position", options.position);
        JsonArray serializedFrames = new JsonArray();
        for (Frame value : frames) {
            JsonObject item = new JsonObject();
            item.addProperty("text", value.text());
            item.addProperty("duration_ticks", value.durationTicks());
            item.addProperty("transition", value.transition());
            item.addProperty("align", value.align());
            serializedFrames.add(item);
        }
        sequence.add("frames", serializedFrames);
        manifest.add("text_sequence", sequence);
        return new Parsed(manifest, frames.size());
    }

    private static int finishFrame(List<Frame> frames, FrameBuilder builder, Options options)
            throws IOException {
        String text = String.join("\n", builder.lines).strip();
        if (text.isBlank()) return 0;
        if (frames.size() >= MAX_FRAMES) throw new IOException("TXT document exceeds 64 frames");
        if (text.length() > MAX_FRAME_CHARACTERS) {
            throw new IOException("A TXT frame exceeds 2048 characters");
        }
        frames.add(new Frame(text,
                builder.durationTicks == null ? options.defaultDurationTicks
                        : builder.durationTicks,
                builder.transition == null ? options.defaultTransition : builder.transition,
                builder.align == null ? options.defaultAlign : builder.align));
        return text.length();
    }

    private static void parseDirective(String line, Options options, FrameBuilder frame,
            boolean contentStarted) throws IOException {
        int equals = line.indexOf('=');
        if (equals < 2) throw new IOException("TXT directive must use @name=value: " + line);
        String key = line.substring(1, equals).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(equals + 1).trim();
        switch (key) {
            case "title" -> {
                requireGlobal(key, contentStarted);
                options.title = limited(value, 96);
            }
            case "loop" -> {
                requireGlobal(key, contentStarted);
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IOException("@loop must be true or false");
                }
                options.loop = Boolean.parseBoolean(value);
            }
            case "default_duration" -> {
                requireGlobal(key, contentStarted);
                options.defaultDurationTicks = durationTicks(value);
            }
            case "text_color" -> {
                requireGlobal(key, contentStarted);
                options.textColor = color(value, key);
            }
            case "background_color" -> {
                requireGlobal(key, contentStarted);
                options.backgroundColor = color(value, key);
            }
            case "accent_color" -> {
                requireGlobal(key, contentStarted);
                options.accentColor = color(value, key);
            }
            case "default_transition" -> {
                requireGlobal(key, contentStarted);
                options.defaultTransition = transition(value);
            }
            case "default_align" -> {
                requireGlobal(key, contentStarted);
                options.defaultAlign = alignment(value);
            }
            case "position" -> {
                requireGlobal(key, contentStarted);
                options.position = position(value);
            }
            case "duration" -> frame.durationTicks = durationTicks(value);
            case "transition" -> frame.transition = transition(value);
            case "align" -> frame.align = alignment(value);
            default -> throw new IOException("Unsupported TXT directive: @" + key);
        }
    }

    private static void requireGlobal(String key, boolean contentStarted) throws IOException {
        if (contentStarted) {
            throw new IOException("@" + key + " must appear before the first text page");
        }
    }

    private static int durationTicks(String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        double ticks;
        try {
            if (normalized.endsWith("ms")) {
                ticks = Double.parseDouble(normalized.substring(0, normalized.length() - 2))
                        / 50.0D;
            } else if (normalized.endsWith("s")) {
                ticks = Double.parseDouble(normalized.substring(0, normalized.length() - 1))
                        * 20.0D;
            } else if (normalized.endsWith("t")) {
                ticks = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
            } else {
                ticks = Double.parseDouble(normalized) * 20.0D;
            }
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid TXT duration: " + value, exception);
        }
        if (!Double.isFinite(ticks) || ticks < 10.0D || ticks > 72_000.0D) {
            throw new IOException("TXT duration must be between 0.5 and 3600 seconds");
        }
        return (int) Math.round(ticks);
    }

    private static String transition(String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!TRANSITIONS.contains(normalized)) {
            throw new IOException("TXT transition must be none, fade, slide_left, typewriter, or blink");
        }
        return normalized;
    }

    private static String alignment(String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!ALIGNMENTS.contains(normalized)) {
            throw new IOException("TXT align must be left, center, or right");
        }
        return normalized;
    }

    private static String position(String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!POSITIONS.contains(normalized)) {
            throw new IOException("TXT position must be top, center, or bottom");
        }
        return normalized;
    }

    private static String color(String value, String key) throws IOException {
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        if (normalized.length() == 6) normalized = "FF" + normalized;
        if (!normalized.matches("[0-9a-fA-F]{8}")) {
            throw new IOException("@" + key + " must be #RRGGBB or #AARRGGBB");
        }
        return "#" + normalized.toUpperCase(Locale.ROOT);
    }

    private static String limited(String value, int maximum) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("TXT document must be UTF-8", exception);
        }
    }

    record Parsed(JsonObject manifest, int frameCount) {
    }

    private record Frame(String text, int durationTicks, String transition, String align) {
    }

    private static final class FrameBuilder {
        private final List<String> lines = new ArrayList<>();
        private Integer durationTicks;
        private String transition;
        private String align;

        private boolean hasContent() {
            return lines.stream().anyMatch(line -> !line.isBlank());
        }
    }

    private static final class Options {
        private String title;
        private boolean loop = true;
        private int defaultDurationTicks = 80;
        private String defaultTransition = "fade";
        private String defaultAlign = "center";
        private String textColor = "#FFFFFFFF";
        private String backgroundColor = "#D9101820";
        private String accentColor = "#FF4AA8FF";
        private String position = "bottom";

        private Options(String title) {
            this.title = limited(title, 96);
        }
    }
}
