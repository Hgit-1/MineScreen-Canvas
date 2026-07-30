package dev.minescreen.client.traffic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Imports Rail Map Painter's native JSON save into a compact, version-independent MineScreen map.
 * This is a clean-room data adapter: it reads documented JSON fields and does not bundle or execute
 * Rail Map Painter code, styles, fonts, embedded images, or premium assets.
 */
final class RmpTrafficImporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_NODES = 2_048;
    private static final int MAX_EDGES = 4_096;
    private static final double MAX_COORDINATE = 10_000_000.0D;

    private RmpTrafficImporter() {
    }

    static boolean looksLikeRmp(JsonObject json) {
        return json != null && json.has("version") && json.has("graph")
                && json.get("graph").isJsonObject();
    }

    static TrafficImportResult importProject(Path source, JsonObject save, Path templateRoot)
            throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_SOURCE_BYTES) {
            throw new IOException("RMP project is missing or exceeds 16 MiB");
        }
        JsonObject graph = object(save, "graph");
        JsonArray rawNodes = array(graph, "nodes");
        JsonArray rawEdges = array(graph, "edges");
        if (rawNodes.size() > MAX_NODES || rawEdges.size() > MAX_EDGES) {
            throw new IOException("RMP project exceeds the 2048-station/4096-line import limit");
        }

        JsonArray nodes = new JsonArray();
        Map<String, JsonObject> nodesById = new HashMap<>();
        for (JsonElement element : rawNodes) {
            if (!element.isJsonObject()) continue;
            JsonObject raw = element.getAsJsonObject();
            String key = string(raw, "key", "");
            JsonObject attributes = objectOrNull(raw, "attributes");
            if (attributes == null || key.isBlank() || key.length() > 160
                    || !key.startsWith("stn_") || !bool(attributes, "visible", true)) {
                continue;
            }
            double x = finiteNumber(attributes, "x");
            double y = finiteNumber(attributes, "y");
            String type = string(attributes, "type", "");
            JsonObject typed = type.isBlank() ? null : objectOrNull(attributes, type);
            JsonArray names = typed == null ? null : arrayOrNull(typed, "names");
            String primary = firstName(names, 0);
            String secondary = firstName(names, 1);

            JsonObject node = new JsonObject();
            node.addProperty("id", key);
            node.addProperty("x", x);
            node.addProperty("y", y);
            node.addProperty("name", primary);
            node.addProperty("secondary_name", secondary);
            nodes.add(node);
            nodesById.put(key, node);
        }
        if (nodes.isEmpty()) {
            throw new IOException("RMP project contains no supported visible station nodes");
        }

        JsonArray edges = new JsonArray();
        String firstLineColor = "#FF3A9BFF";
        for (JsonElement element : rawEdges) {
            if (!element.isJsonObject()) continue;
            JsonObject raw = element.getAsJsonObject();
            JsonObject attributes = objectOrNull(raw, "attributes");
            String sourceId = string(raw, "source", "");
            String targetId = string(raw, "target", "");
            if (attributes == null || !bool(attributes, "visible", true)
                    || !nodesById.containsKey(sourceId) || !nodesById.containsKey(targetId)) {
                continue;
            }
            String color = findColor(attributes, 0);
            if (color == null) color = "#FF3A9BFF";
            if (edges.isEmpty()) firstLineColor = color;
            JsonObject edge = new JsonObject();
            edge.addProperty("source", sourceId);
            edge.addProperty("target", targetId);
            edge.addProperty("color", color);
            edges.add(edge);
        }

        byte[] sourceBytes = Files.readAllBytes(source);
        String base = source.getFileName().toString().replaceFirst("(?i)\\.(json|rmp)$", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("^[_\\-.]+|[_\\-.]+$", "");
        if (base.isBlank()) base = "project";
        if (base.length() > 42) base = base.substring(0, 42);
        String id = requireSafeId("rmp_" + base + "_" + shortHash(sourceBytes));
        Path folder = safeFolder(templateRoot, id);
        Files.createDirectories(folder);

        String graphName = "";
        JsonObject graphAttributes = objectOrNull(graph, "attributes");
        if (graphAttributes != null) graphName = limit(string(graphAttributes, "name", ""), 96);
        if (graphName.isBlank()) graphName = limit(base.replace('_', ' '), 96);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("format", "minescreen_rmp_import_v1");
        normalized.addProperty("source", "Rail Map Painter native JSON");
        normalized.addProperty("source_version", integer(save, "version", -1));
        normalized.addProperty("name", graphName);
        normalized.add("nodes", nodes);
        normalized.add("edges", edges);
        Files.writeString(folder.resolve("rmp-map.json"), GSON.toJson(normalized),
                StandardCharsets.UTF_8);

        // Graphology serialization order is not a train route order. RMP supplies the static map
        // layer only; never guess current/next station or overwrite MineScreen's live PIDS fields.
        TrafficImportResult.Defaults defaults = TrafficImportResult.Defaults.EMPTY;
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("name", "RMP · " + graphName);
        manifest.addProperty("layout", "rmp_native_v1");
        manifest.addProperty("background_color", "#FF071019");
        manifest.addProperty("text_color", "#FFFFFFFF");
        manifest.addProperty("accent_color", firstLineColor);
        manifest.addProperty("rmp_map", "rmp-map.json");
        Files.writeString(folder.resolve("manifest.json"), GSON.toJson(manifest),
                StandardCharsets.UTF_8);
        return new TrafficImportResult(id, defaults, "rmp", nodes.size(), edges.size());
    }

    private static String firstName(JsonArray names, int index) {
        if (names == null || index >= names.size() || !names.get(index).isJsonPrimitive()) return "";
        return limit(names.get(index).getAsString().replace('\n', ' ').trim(), 96);
    }

    private static double finiteNumber(JsonObject object, String key) throws IOException {
        try {
            double value = object.get(key).getAsDouble();
            if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) throw new IOException(
                    "RMP project contains an invalid station coordinate");
            return value;
        } catch (NullPointerException | NumberFormatException | UnsupportedOperationException exception) {
            throw new IOException("RMP project contains an invalid station coordinate", exception);
        }
    }

    private static String findColor(JsonElement element, int depth) {
        if (element == null || depth > 6) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return normalizeColor(element.getAsString());
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String found = findColor(child, depth + 1);
                if (found != null) return found;
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).contains("color")
                        || entry.getKey().toLowerCase(Locale.ROOT).contains("colour")
                        || entry.getValue().isJsonObject() || entry.getValue().isJsonArray()) {
                    String found = findColor(entry.getValue(), depth + 1);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static String normalizeColor(String value) {
        String color = value == null ? "" : value.trim();
        if (!color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) return null;
        return color.length() == 7 ? "#FF" + color.substring(1).toUpperCase(Locale.ROOT)
                : "#" + color.substring(1).toUpperCase(Locale.ROOT);
    }

    private static String shortHash(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireSafeId(String value) throws IOException {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9._-]{1,64}")) {
            throw new IOException("Template id must match [a-z0-9._-]{1,64}");
        }
        return id;
    }

    private static Path safeFolder(Path root, String id) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path folder = normalizedRoot.resolve(requireSafeId(id)).normalize();
        if (!folder.startsWith(normalizedRoot)) throw new IOException("Unsafe template destination");
        return folder;
    }

    private static JsonObject object(JsonObject parent, String key) throws IOException {
        JsonObject result = objectOrNull(parent, key);
        if (result == null) throw new IOException("RMP project is missing object: " + key);
        return result;
    }

    private static JsonObject objectOrNull(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject parent, String key) throws IOException {
        JsonArray result = arrayOrNull(parent, key);
        if (result == null) throw new IOException("RMP project is missing array: " + key);
        return result;
    }

    private static JsonArray arrayOrNull(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : null;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try { return json.has(key) ? json.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try { return json.has(key) ? json.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String string(JsonObject json, String key, String fallback) {
        try { return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
