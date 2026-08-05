package dev.minescreen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Distribution-level regression gate. This deliberately inspects source resources and the final
 * archive instead of trusting compilation alone: missing models, malformed data, locale drift,
 * unexpanded metadata and accidentally packaged local paths are release failures.
 */
public final class ReleaseIntegrityTestHarness {
    private static final List<String> BLOCK_IDS = List.of(
            "screen", "screen_cable", "speaker", "fixed_keyboard", "computer",
            "text_display", "animated_text_display", "traffic_display", "electric_display",
            "ceiling_display", "door_lcd", "carriage_info_display", "train_light_panel");
    private static final List<String> STANDALONE_ITEM_IDS =
            List.of("keyboard", "screen_configurator");
    private static final List<String> FORBIDDEN_ARCHIVE_TEXT = List.of(
            "C:\\Users\\", "C:/Users/", "/home/", "100301");

    private ReleaseIntegrityTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "usage: <project-dir> <jar> <expected-version>");
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        Path resources = project.resolve("src/main/resources");
        Path archive = Path.of(args[1]).toAbsolutePath().normalize();
        String version = args[2];

        require(Files.isDirectory(resources), "resource directory is missing");
        require(Files.isRegularFile(archive), "distribution jar is missing: " + archive);
        validateJson(resources);
        validateLocales(resources);
        validateResponsiveLayouts();
        validateContentResources(resources);
        validateLocalModelReferences(resources);
        validateArchive(archive, version);
        System.out.println("releaseIntegrityTest=passed; version=" + version
                + "; blocks=" + BLOCK_IDS.size());
    }

    private static void validateResponsiveLayouts() {
        for (int[] size : List.of(
                new int[] {960, 540}, new int[] {640, 360},
                new int[] {427, 240}, new int[] {320, 180})) {
            validateViewport(size[0], size[1], 772, 442);
            validateViewport(size[0], size[1], 692, 304);
            validateViewport(size[0], size[1], 632, 334);
            validateViewport(size[0], size[1], 576, 310);
        }
    }

    private static void validateViewport(int actualWidth, int actualHeight,
            int desiredWidth, int desiredHeight) {
        dev.minescreen.client.ResponsiveLayout.Viewport viewport =
                dev.minescreen.client.ResponsiveLayout.fit(
                        actualWidth, actualHeight, desiredWidth, desiredHeight);
        require(viewport.scale() > 0.0F && viewport.scale() <= 1.0F,
                "invalid responsive UI scale");
        require(viewport.logicalWidth() >= desiredWidth
                        && viewport.logicalHeight() >= desiredHeight,
                "responsive layout clips desired panel at " + actualWidth + "x" + actualHeight);
        double probeX = actualWidth * 0.73D;
        double probeY = actualHeight * 0.41D;
        require(Math.abs(viewport.logicalX(probeX) * viewport.scale() - probeX) < 0.001D
                        && Math.abs(viewport.logicalY(probeY) * viewport.scale() - probeY) < 0.001D,
                "responsive pointer transform drift");
    }

    private static void validateJson(Path resources) throws IOException {
        try (var paths = Files.walk(resources)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                } catch (RuntimeException exception) {
                    throw new AssertionError("Malformed JSON: " + path, exception);
                }
            }
        }
    }

    private static void validateLocales(Path resources) throws IOException {
        JsonObject english = readObject(resources.resolve("assets/minescreen/lang/en_us.json"));
        JsonObject chinese = readObject(resources.resolve("assets/minescreen/lang/zh_cn.json"));
        require(english.keySet().equals(chinese.keySet()),
                "en_us/zh_cn translation keys differ: "
                        + symmetricDifference(english.keySet(), chinese.keySet()));
        require(english.size() >= 400, "unexpectedly small translation catalog");
        for (String key : english.keySet()) {
            require(!text(english, key).isBlank(), "blank en_us translation: " + key);
            require(!text(chinese, key).isBlank(), "blank zh_cn translation: " + key);
        }
        for (String id : BLOCK_IDS) {
            require(english.has("block.minescreen." + id), "missing English block name: " + id);
            require(chinese.has("block.minescreen." + id), "missing Chinese block name: " + id);
        }
        for (String id : STANDALONE_ITEM_IDS) {
            require(english.has("item.minescreen." + id), "missing English item name: " + id);
            require(chinese.has("item.minescreen." + id), "missing Chinese item name: " + id);
        }
    }

    private static void validateContentResources(Path resources) {
        for (String id : BLOCK_IDS) {
            required(resources, "assets/minescreen/blockstates/" + id + ".json");
            required(resources, "assets/minescreen/models/block/" + id + ".json");
            required(resources, "assets/minescreen/models/item/" + id + ".json");
            required(resources, "data/minescreen/loot_table/blocks/" + id + ".json");
            required(resources, "data/minescreen/recipe/" + id + ".json");
        }
        for (String id : STANDALONE_ITEM_IDS) {
            required(resources, "assets/minescreen/models/item/" + id + ".json");
            required(resources, "data/minescreen/recipe/" + id + ".json");
        }
        required(resources, "minescreen.png");
        required(resources, "minescreen.mixins.json");
        required(resources, "META-INF/licenses/rhino-MPL-2.0.txt");
        required(resources, "META-INF/licenses/javacpp-Apache-2.0.txt");
        required(resources, "META-INF/licenses/ffmpeg-LGPL-2.1.txt");
    }

    private static void validateLocalModelReferences(Path resources) throws IOException {
        Path assets = resources.resolve("assets/minescreen");
        try (var paths = Files.walk(assets.resolve("models"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject model = readObject(path);
                if (model.has("parent")) {
                    validateModelReference(resources, text(model, "parent"), path);
                }
                if (model.has("textures") && model.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> texture
                            : model.getAsJsonObject("textures").entrySet()) {
                        if (texture.getValue().isJsonPrimitive()) {
                            validateTextureReference(resources,
                                    texture.getValue().getAsString(), path);
                        }
                    }
                }
            }
        }
        try (var paths = Files.walk(assets.resolve("blockstates"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                collectLocalReferences(JsonParser.parseString(
                        Files.readString(path, StandardCharsets.UTF_8)), "model")
                        .forEach(reference -> validateModelReference(resources, reference, path));
            }
        }
    }

    private static List<String> collectLocalReferences(JsonElement element, String key) {
        List<String> result = new ArrayList<>();
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> result.addAll(
                    collectLocalReferences(child, key)));
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (key.equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    result.add(entry.getValue().getAsString());
                } else {
                    result.addAll(collectLocalReferences(entry.getValue(), key));
                }
            }
        }
        return result;
    }

    private static void validateModelReference(Path resources, String reference, Path owner) {
        if (!reference.startsWith("minescreen:")) return;
        String value = reference.substring("minescreen:".length());
        Path target = resources.resolve("assets/minescreen/models/" + value + ".json");
        require(Files.isRegularFile(target),
                "missing local model " + reference + " referenced by " + owner);
    }

    private static void validateTextureReference(Path resources, String reference, Path owner) {
        if (!reference.startsWith("minescreen:")) return;
        String value = reference.substring("minescreen:".length());
        Path target = resources.resolve("assets/minescreen/textures/" + value + ".png");
        require(Files.isRegularFile(target),
                "missing local texture " + reference + " referenced by " + owner);
    }

    private static void validateArchive(Path archive, String version) throws IOException {
        Set<String> names = new HashSet<>();
        Map<String, Integer> duplicates = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!names.add(entry.getName())) duplicates.merge(entry.getName(), 2, Integer::sum);
                require(!entry.getName().startsWith("dev/minescreen/")
                                || !entry.getName().contains("TestHarness"),
                        "test harness leaked into distribution: " + entry.getName());
                if (!entry.isDirectory() && entry.getSize() <= 2L * 1024L * 1024L
                        && firstPartyEntry(entry.getName()) && !nativeBinary(entry.getName())) {
                    scanForLocalData(zip, entry);
                }
            }
            require(duplicates.isEmpty(), "duplicate archive entries: " + duplicates);
            for (String required : List.of(
                    "META-INF/neoforge.mods.toml",
                    "minescreen.mixins.json",
                    "minescreen.png",
                    "org/bytedeco/ffmpeg/global/avformat.class",
                    "META-INF/minescreen/script/rhino-1.9.1.jar",
                    "META-INF/licenses/minescreen-MIT.txt",
                    "META-INF/THIRD_PARTY_NOTICES.md",
                    "META-INF/licenses/rhino-MPL-2.0.txt",
                    "META-INF/licenses/javacpp-Apache-2.0.txt",
                    "META-INF/licenses/ffmpeg-LGPL-2.1.txt")) {
                require(names.contains(required), "missing archive entry: " + required);
            }
            for (String platform : List.of(
                    "windows-x86_64", "linux-x86_64", "linux-arm64",
                    "macosx-x86_64", "macosx-arm64")) {
                require(names.stream().anyMatch(name -> name.startsWith(
                                "org/bytedeco/ffmpeg/" + platform + "/")),
                        "missing FFmpeg native platform: " + platform);
                require(names.stream().anyMatch(name -> name.startsWith(
                                "org/bytedeco/javacpp/" + platform + "/")),
                        "missing JavaCPP native platform: " + platform);
            }
            ZipEntry metadataEntry = zip.getEntry("META-INF/neoforge.mods.toml");
            String metadata;
            try (InputStream input = zip.getInputStream(metadataEntry)) {
                metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            require(metadata.contains("version=\"" + version + "\""),
                    "neoforge.mods.toml version does not match " + version);
            require(!metadata.contains("${"), "unexpanded metadata placeholder in distribution");
            int mcefDependency = metadata.indexOf("modId=\"mcef\"");
            require(mcefDependency >= 0 && metadata.substring(mcefDependency,
                            Math.min(metadata.length(), mcefDependency + 256))
                            .contains("type=\"optional\"")
                            && metadata.substring(mcefDependency,
                            Math.min(metadata.length(), mcefDependency + 256))
                            .contains("side=\"CLIENT\""),
                    "MCEF must remain an optional client-only dependency");
            int createDependency = metadata.indexOf("modId=\"create\"");
            require(createDependency >= 0 && metadata.substring(createDependency,
                            Math.min(metadata.length(), createDependency + 256))
                            .contains("side=\"BOTH\""),
                    "Optional Create integration must be declared on both runtime sides");
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            require(manifestEntry != null, "distribution manifest is missing");
            String jarManifest;
            try (InputStream input = zip.getInputStream(manifestEntry)) {
                jarManifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            require(jarManifest.contains("Implementation-Version: " + version),
                    "distribution manifest version does not match " + version);
        }
    }

    private static void scanForLocalData(ZipFile zip, ZipEntry entry) throws IOException {
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) {
            bytes = input.readAllBytes();
        }
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN_ARCHIVE_TEXT) {
            require(!content.contains(forbidden),
                    "local path or credential leaked into " + entry.getName());
        }
    }

    private static boolean nativeBinary(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
                || lower.endsWith(".a");
    }

    private static boolean firstPartyEntry(String name) {
        return name.startsWith("dev/minescreen/")
                || name.startsWith("assets/minescreen/")
                || name.startsWith("data/minescreen/")
                || name.equals("META-INF/neoforge.mods.toml")
                || name.equals("minescreen.mixins.json");
    }

    private static JsonObject readObject(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        require(parsed.isJsonObject(), "JSON root must be an object: " + path);
        return parsed.getAsJsonObject();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static Set<String> symmetricDifference(Set<String> first, Set<String> second) {
        Set<String> difference = new HashSet<>(first);
        difference.removeAll(second);
        Set<String> reverse = new HashSet<>(second);
        reverse.removeAll(first);
        difference.addAll(reverse);
        return difference;
    }

    private static void required(Path root, String relative) {
        require(Files.isRegularFile(root.resolve(relative)), "missing resource: " + relative);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
