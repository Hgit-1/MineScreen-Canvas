package dev.minescreen.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Shared validation boundary for multiplayer traffic templates.
 *
 * <p>Only the bounded declarative {@code script_scene_v1} result is synchronized. JavaScript
 * source, filesystem paths, images and RMP sidecar files never cross the game connection and are
 * never executed by a server or a receiving client.</p>
 */
public final class TrafficTemplateManifest {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_ELEMENTS = 256;
    public static final int CHUNK_BYTES = 24 * 1024;
    public static final int MAX_CHUNKS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    public static final double MIN_TEXT_SIZE = 0.05D;
    public static final double MAX_TEXT_SIZE = 4.0D;
    public static final double MIN_LINE_WIDTH = 0.05D;
    public static final double MAX_LINE_WIDTH = 64.0D;

    private TrafficTemplateManifest() {
    }

    public static JsonObject validate(String expectedId, byte[] bytes) throws IOException {
        if (!validId(expectedId) || bytes == null || bytes.length < 2 || bytes.length > MAX_BYTES) {
            throw new IOException("Invalid or oversized traffic template");
        }
        String json = decodeUtf8(bytes);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("Traffic template is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) throw new IOException("Traffic template root must be an object");
        JsonObject object = parsed.getAsJsonObject();
        String id = string(object, "id").toLowerCase(Locale.ROOT);
        if (!expectedId.equals(id)) throw new IOException("Traffic template id mismatch");
        if (!"script_scene_v1".equals(string(object, "layout"))) {
            throw new IOException("Only script_scene_v1 manifests can be synchronized");
        }
        if (!string(object, "background_image").isBlank()
                || !string(object, "rmp_map").isBlank()) {
            throw new IOException("Synchronized templates cannot reference local sidecar files");
        }
        JsonArray elements = object.has("elements") && object.get("elements").isJsonArray()
                ? object.getAsJsonArray("elements") : new JsonArray();
        if (elements.size() > MAX_ELEMENTS) throw new IOException("Too many scene elements");
        for (JsonElement raw : elements) {
            if (!raw.isJsonObject()) throw new IOException("Scene element must be an object");
            validateElement(raw.getAsJsonObject());
        }
        validateTextSequence(object);
        return object;
    }

    private static void validateTextSequence(JsonObject manifest) throws IOException {
        if (!manifest.has("text_sequence")) return;
        if (!manifest.get("text_sequence").isJsonObject()) {
            throw new IOException("text_sequence must be an object");
        }
        JsonObject sequence = manifest.getAsJsonObject("text_sequence");
        if (sequence.has("position")) {
            String position = sequence.get("position").getAsString().toLowerCase(Locale.ROOT);
            if (!Set.of("top", "center", "bottom").contains(position)) {
                throw new IOException("Unsupported text_sequence position");
            }
        }
        if (sequence.has("default_duration_ticks")) {
            duration(sequence, "default_duration_ticks");
        }
        if (!sequence.has("frames") || !sequence.get("frames").isJsonArray()) {
            throw new IOException("text_sequence frames must be an array");
        }
        JsonArray frames = sequence.getAsJsonArray("frames");
        if (frames.size() == 0 || frames.size() > 64) {
            throw new IOException("text_sequence must contain 1 to 64 frames");
        }
        int totalCharacters = 0;
        for (JsonElement raw : frames) {
            if (!raw.isJsonObject()) throw new IOException("text_sequence frame must be an object");
            JsonObject frame = raw.getAsJsonObject();
            String text = string(frame, "text");
            if (text.isBlank() || text.length() > 2_048) {
                throw new IOException("text_sequence frame text is blank or too long");
            }
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isISOControl(character) && character != '\n'
                        && character != '\t') {
                    throw new IOException("text_sequence contains unsupported control characters");
                }
            }
            totalCharacters += text.length();
            if (totalCharacters > 65_536) {
                throw new IOException("text_sequence contains too much text");
            }
            if (frame.has("duration_ticks")) duration(frame, "duration_ticks");
            String transition = string(frame, "transition").toLowerCase(Locale.ROOT);
            if (!transition.isBlank() && !transition.equals("none")
                    && !transition.equals("fade") && !transition.equals("slide_left")
                    && !transition.equals("typewriter") && !transition.equals("blink")) {
                throw new IOException("Unsupported text_sequence transition");
            }
            String align = string(frame, "align").toLowerCase(Locale.ROOT);
            if (!align.isBlank() && !align.equals("left") && !align.equals("center")
                    && !align.equals("right")) {
                throw new IOException("Unsupported text_sequence alignment");
            }
        }
    }

    private static void duration(JsonObject object, String key) throws IOException {
        double duration = number(object, key);
        if (duration < 10.0D || duration > 72_000.0D || duration != Math.rint(duration)) {
            throw new IOException("text_sequence duration is out of range");
        }
    }

    private static void validateElement(JsonObject element) throws IOException {
        String type = string(element, "type").toLowerCase(Locale.ROOT);
        if (!type.equals("text") && !type.equals("rect") && !type.equals("line")
                && !type.equals("ellipse")) {
            // A newer template may contain a layer that this MineScreen version cannot draw yet.
            // Keep the original declarative JSON intact for synchronization/upgrades, while old
            // clients safely skip only explicitly optional layers. Required unknown layers remain
            // an error so a misspelled type cannot silently erase important display content.
            if (bool(element, "optional")) return;
            throw new IOException("Unsupported scene element: " + type);
        }
        for (String key : new String[] {"x", "y", "x2", "y2", "width", "height"}) {
            if (element.has(key)) normalized(element, key);
        }
        if (type.equals("text") && element.has("size")) {
            double size = number(element, "size");
            if (size < MIN_TEXT_SIZE || size > MAX_TEXT_SIZE) {
                throw new IOException("Invalid scene text size");
            }
        }
        if (type.equals("line")) {
            String widthKey = element.has("stroke_width") ? "stroke_width"
                    : element.has("line_width") ? "line_width"
                    : element.has("size") ? "size" : "";
            if (!widthKey.isBlank()) {
                double width = number(element, widthKey);
                if (width < MIN_LINE_WIDTH || width > MAX_LINE_WIDTH) {
                    throw new IOException("Invalid scene line width");
                }
            }
        }
        if (element.has("rotation")) {
            double rotation = number(element, "rotation");
            if (rotation < -180.0D || rotation > 180.0D) {
                throw new IOException("Invalid scene rotation");
            }
        }
        if (string(element, "text").length() > 256) {
            throw new IOException("Scene text exceeds 256 characters");
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static boolean validId(String value) {
        return value != null && value.matches("[a-z0-9._-]{1,64}");
    }

    public static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Traffic template is not UTF-8", exception);
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void normalized(JsonObject object, String key) throws IOException {
        double value = number(object, key);
        if (value < 0.0D || value > 1.0D) {
            throw new IOException("Scene coordinate " + key + " must be between 0 and 1");
        }
    }

    private static double number(JsonObject object, String key) throws IOException {
        try {
            double value = object.get(key).getAsDouble();
            if (!Double.isFinite(value)) throw new IOException("Non-finite scene number");
            return value;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid scene number: " + key, exception);
        }
    }
}
