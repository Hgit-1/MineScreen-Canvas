package dev.minescreen.client.traffic;

import java.io.IOException;
import java.io.Reader;
import java.io.DataInputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreen;
import dev.minescreen.network.TrafficTemplateManifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Safe external traffic-template loader. RMP saves and JavaScript generators are converted once at
 * import time into MineScreen's bounded declarative scene; scripts are never retained or run while
 * rendering, ticking, or on a dedicated server.
 */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class TrafficTemplateRepository {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Manifests are an interchange format, not just MineScreen's current runtime DTO. Preserve
    // explicit nulls as well as unknown fields so a third-party editor can distinguish
    // "unset/inherit" from "property omitted" after a MineScreen import/export round trip.
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final long MAX_IMAGE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final long MAX_RMP_MAP_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_SCENE_ELEMENTS = 256;
    private static final java.util.regex.Pattern UNRESOLVED_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{[a-zA-Z0-9_.-]{1,64}}");
    private static final Map<String, Template> TEMPLATES = new HashMap<>();
    private static final Map<Path, CachedTemplate> FOLDER_CACHE = new HashMap<>();
    private static final Map<Path, CachedSynchronizedTemplate> SYNC_FOLDER_CACHE = new HashMap<>();
    private static final Map<String, SynchronizedTemplate> SYNCHRONIZED_TEMPLATES = new HashMap<>();
    private static int reloadDelay;
    private static net.minecraft.world.level.Level reloadLevel;

    private TrafficTemplateRepository() {
    }

    public static Template resolve(String id) {
        String safe = safeId(id);
        return TEMPLATES.getOrDefault(safe, Template.BUILTIN);
    }

    public static TrafficMetadata metadata(String id) {
        return resolve(id).metadata();
    }

    public static List<StationProfile> stationProfiles(String id) {
        Map<String, StationProfile> unique = new java.util.LinkedHashMap<>();
        for (DirectionProfile direction : metadata(id).directions().values()) {
            for (StationProfile station : direction.stations()) {
                String key = station.code().isBlank() ? station.displayName() : station.code();
                if (!key.isBlank()) unique.putIfAbsent(key, station);
            }
        }
        return List.copyOf(unique.values());
    }

    /** Maps a Create internal station name to the imported LCD station code/display name. */
    public static String mappedStationName(String id, String createStationName) {
        String actual = normalizeStation(createStationName);
        if (actual.isBlank()) return createStationName == null ? "" : createStationName;
        TrafficMetadata metadata = metadata(id);
        for (Map.Entry<String, String> entry : metadata.stationBindings().entrySet()) {
            if (!actual.equals(normalizeStation(entry.getValue()))) continue;
            for (StationProfile station : stationProfiles(id)) {
                if (station.code().equalsIgnoreCase(entry.getKey())) {
                    return station.displayName().isBlank() ? station.code()
                            : station.displayName();
                }
            }
            return entry.getKey();
        }
        return createStationName == null ? "" : createStationName;
    }

    /**
     * Accepts either a template-side code (for example C5) or an exact Create station name.
     * The server receives only the resolved Create name and does not need the client template.
     */
    public static String resolvedCreateStationName(String id, String codeOrName) {
        String requested = codeOrName == null ? "" : codeOrName.trim();
        if (requested.isBlank()) return "";
        for (Map.Entry<String, String> entry : metadata(id).stationBindings().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(requested)
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue().trim();
            }
        }
        return requested;
    }

    /**
     * Persists editor mappings inside the imported manifest. Script-scene templates are then
     * re-hashed by the existing template synchronization channel.
     */
    public static void updateStationBindings(String id, Map<String, String> bindings)
            throws IOException {
        String safe = requireSafeId(id);
        if (safe.equals(Template.BUILTIN.id())) {
            throw new IOException("Import a JS/JSON template before configuring station aliases");
        }
        Path folder = safeTemplateFolder(templateRoot().toAbsolutePath().normalize(), safe);
        Path manifest = folder.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)
                || Files.size(manifest) > TrafficTemplateManifest.MAX_BYTES) {
            throw new IOException("Template manifest is missing or too large");
        }
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        }
        if (!parsed.isJsonObject()) throw new IOException("Template manifest is invalid");
        JsonObject json = parsed.getAsJsonObject();
        JsonObject stationBindings = new JsonObject();
        int count = 0;
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (count >= 2048) break;
            String code = limited(entry.getKey(), 16).trim();
            String createName = limited(entry.getValue(), 128).trim();
            if (code.isBlank() || createName.isBlank()) continue;
            stationBindings.addProperty(code, createName);
            count++;
        }
        json.add("station_bindings", stationBindings);
        Path temporary = folder.resolve("manifest.json.tmp");
        Files.writeString(temporary, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
        reload();
    }

    public static TrafficStationLocator.StationMatch stationMatch(String id,
            net.minecraft.world.level.Level level) {
        return TrafficStationLocator.resolve(level, resolve(id).metadata().coordinateBinding());
    }

    public static Path templateRoot() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config/minescreen/traffic_templates");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        TrafficTemplateSyncClient.tick(minecraft.level);
        if (minecraft.level == null) {
            reloadLevel = null;
            reloadDelay = 0;
            return;
        }
        if (reloadLevel != minecraft.level) {
            reloadLevel = minecraft.level;
            reloadDelay = 0;
        }
        if (reloadDelay-- > 0) return;
        reloadDelay = 40;
        reload();
    }

    public static void reload() {
        Path root = templateRoot().toAbsolutePath().normalize();
        Map<String, Template> loaded = new HashMap<>();
        Map<String, SynchronizedTemplate> synchronizedLoaded = new HashMap<>();
        Set<Path> activeFolders = new HashSet<>();
        loaded.put(Template.BUILTIN.id(), Template.BUILTIN);
        try {
            Files.createDirectories(root);
            try (var directories = Files.list(root)) {
                directories.filter(Files::isDirectory).limit(128).forEach(folder -> {
                    Path normalized = folder.toAbsolutePath().normalize();
                    activeFolders.add(normalized);
                    try {
                        long fingerprint = fingerprint(normalized);
                        CachedTemplate cached = FOLDER_CACHE.get(normalized);
                        Template template;
                        if (cached != null && cached.fingerprint() == fingerprint) {
                            template = cached.template();
                        } else {
                            template = loadFolder(root, normalized);
                            if (template != null) {
                                FOLDER_CACHE.put(normalized,
                                        new CachedTemplate(fingerprint, template));
                            } else {
                                FOLDER_CACHE.remove(normalized);
                            }
                        }
                        if (template != null && !template.id().equals(Template.BUILTIN.id())) {
                            loaded.putIfAbsent(template.id(), template);
                            CachedSynchronizedTemplate synchronizedTemplate =
                                    SYNC_FOLDER_CACHE.get(normalized);
                            if (synchronizedTemplate == null
                                    || synchronizedTemplate.fingerprint() != fingerprint) {
                                SynchronizedTemplate next = loadSynchronizedTemplate(normalized,
                                        template);
                                synchronizedTemplate = new CachedSynchronizedTemplate(fingerprint,
                                        next);
                                SYNC_FOLDER_CACHE.put(normalized, synchronizedTemplate);
                            }
                            if (synchronizedTemplate.template() != null) {
                                synchronizedLoaded.putIfAbsent(template.id(),
                                        synchronizedTemplate.template());
                            }
                        }
                    } catch (IOException exception) {
                        LOGGER.warn("Unable to fingerprint traffic template folder {}", normalized,
                                exception);
                    }
                });
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to scan MineScreen traffic templates", exception);
        }
        for (Template old : TEMPLATES.values()) {
            if (old.texture() != null && loaded.values().stream()
                    .noneMatch(next -> old.texture().equals(next.texture()))) {
                Minecraft.getInstance().getTextureManager().release(old.texture());
            }
        }
        TEMPLATES.clear();
        TEMPLATES.putAll(loaded);
        SYNCHRONIZED_TEMPLATES.clear();
        SYNCHRONIZED_TEMPLATES.putAll(synchronizedLoaded);
        FOLDER_CACHE.keySet().removeIf(folder -> !activeFolders.contains(folder));
        SYNC_FOLDER_CACHE.keySet().removeIf(folder -> !activeFolders.contains(folder));
        TrafficTemplateSyncClient.repositoryReloaded();
    }

    /** Immutable local catalog used by the hash-based multiplayer manifest channel. */
    public static List<SynchronizedTemplateInfo> synchronizedTemplateCatalog() {
        return SYNCHRONIZED_TEMPLATES.values().stream()
                .map(template -> new SynchronizedTemplateInfo(template.id(), template.sha256(),
                        template.bytes().length))
                .sorted(java.util.Comparator.comparing(SynchronizedTemplateInfo::id)).toList();
    }

    public static byte[] synchronizedTemplateBytes(String id, String sha256) {
        SynchronizedTemplate template = SYNCHRONIZED_TEMPLATES.get(safeId(id));
        return template != null && template.sha256().equals(sha256)
                ? java.util.Arrays.copyOf(template.bytes(), template.bytes().length) : null;
    }

    public static boolean hasSynchronizedTemplate(String id, String sha256) {
        SynchronizedTemplate template = SYNCHRONIZED_TEMPLATES.get(safeId(id));
        return template != null && template.sha256().equals(sha256);
    }

    /** Installs bytes already validated by the common network boundary using an atomic replace. */
    public static void installSynchronizedTemplate(String id, String sha256, byte[] bytes)
            throws IOException {
        String safe = requireSafeId(id);
        TrafficTemplateManifest.validate(safe, bytes);
        if (!TrafficTemplateManifest.sha256(bytes).equals(sha256)) {
            throw new IOException("Traffic template hash mismatch");
        }
        Path folder = safeTemplateFolder(templateRoot(), safe);
        Files.createDirectories(folder);
        Path temporary = folder.resolve("manifest.json.tmp");
        Path target = folder.resolve("manifest.json");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        reload();
    }

    private static SynchronizedTemplate loadSynchronizedTemplate(Path folder, Template template) {
        if (!template.layout().equals("script_scene_v1") || template.texture() != null) return null;
        Path manifest = folder.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) manifest = folder.resolve("display-script.json");
        try {
            if (!Files.isRegularFile(manifest)
                    || Files.size(manifest) > TrafficTemplateManifest.MAX_BYTES) return null;
            byte[] bytes = Files.readAllBytes(manifest);
            TrafficTemplateManifest.validate(template.id(), bytes);
            return new SynchronizedTemplate(template.id(), TrafficTemplateManifest.sha256(bytes),
                    bytes);
        } catch (IOException exception) {
            LOGGER.warn("Traffic template {} is local-only and cannot be synchronized",
                    template.id(), exception);
            return null;
        }
    }

    /**
     * Imports a manifest, RMP save, sandboxed JS generator, PNG background, or bounded UTF-8 TXT
     * dynamic document.
     */
    public static TrafficImportResult importTemplate(Path sourceFile) throws IOException {
        return importTemplate(sourceFile, templateRoot().toAbsolutePath().normalize(), true);
    }

    /** Test boundary that validates and installs into an isolated folder without a live client. */
    static TrafficImportResult importTemplateForTest(Path sourceFile, Path root)
            throws IOException {
        return importTemplate(sourceFile, root.toAbsolutePath().normalize(), false);
    }

    private static TrafficImportResult importTemplate(Path sourceFile, Path root,
            boolean reloadAfterImport) throws IOException {
        Path source = sourceFile.toAbsolutePath().normalize();
        String extension = extension(source);
        if (extension.equals("png")) {
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_IMAGE_BYTES) {
                throw new IOException("Header image is missing or too large");
            }
            String base = source.getFileName().toString().replaceFirst("(?i)\\.png$", "")
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
            if (base.isBlank()) base = "station_header";
            String id = limited("header_" + base, 56);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("id", id);
            manifest.addProperty("name", source.getFileName().toString());
            manifest.addProperty("layout", "builtin_transit_v1");
            manifest.addProperty("background_color", "#FF101820");
            manifest.addProperty("text_color", "#FFFFFFFF");
            manifest.addProperty("accent_color", "#FFFFA726");
            manifest.addProperty("background_image", source.getFileName().toString());
            return importManifestObject(source, manifest, "image", root, reloadAfterImport);
        }
        if (extension.equals("txt")) {
            TrafficTextDocument.Parsed document = TrafficTextDocument.parse(source);
            TrafficImportResult installed = importManifestObject(source, document.manifest(),
                    "text", root, reloadAfterImport);
            return new TrafficImportResult(installed.templateId(), installed.defaults(), "text",
                    0, document.frameCount());
        }
        if (extension.equals("js")) {
            JsonObject generated = TrafficScriptSandbox.evaluate(source);
            JsonObject manifest = generated.has("template") && generated.get("template").isJsonObject()
                    ? generated.getAsJsonObject("template").deepCopy() : generated.deepCopy();
            if (generated.has("traffic") && generated.get("traffic").isJsonObject()
                    && !manifest.has("traffic")) {
                manifest.add("traffic", generated.getAsJsonObject("traffic").deepCopy());
            }
            return importManifestObject(source, manifest, "javascript", root,
                    reloadAfterImport);
        }
        if (!Files.isRegularFile(source) || Files.size(source) > 16L * 1024L * 1024L) {
            throw new IOException("Import file is missing or exceeds 16 MiB");
        }
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        } catch (RuntimeException exception) {
            throw new IOException("Import file is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) throw new IOException("Import root must be a JSON object");
        JsonObject json = parsed.getAsJsonObject();
        if (RmpTrafficImporter.looksLikeRmp(json)) {
            TrafficImportResult result = RmpTrafficImporter.importProject(source, json,
                    root);
            if (reloadAfterImport) reload();
            return result;
        }
        if (Files.size(source) > MAX_MANIFEST_BYTES) {
            throw new IOException("Traffic manifest exceeds 256 KiB");
        }
        return importManifestObject(source, unwrapManifest(json), "manifest", root,
                reloadAfterImport);
    }

    /** Compatibility wrapper retained for existing callers. */
    public static String importManifest(Path sourceManifest) throws IOException {
        return importTemplate(sourceManifest).templateId();
    }

    private static TrafficImportResult importManifestObject(Path source, JsonObject json,
            String sourceKind, Path root, boolean reloadAfterImport) throws IOException {
        if (json == null) throw new IOException("Empty traffic manifest");
        String fallback = source.getParent() == null ? "imported_template"
                : source.getParent().getFileName().toString();
        String id = requireSafeId(string(json, "id", fallback));
        if (id.equals(Template.BUILTIN.id())) throw new IOException("Reserved template id");
        String layout = string(json, "layout", "builtin_transit_v1");
        if (!layout.equals("builtin_transit_v1") && !layout.equals("script_scene_v1")
                && !layout.equals("rmp_native_v1")) {
            throw new IOException(
                    "Manifest layout must be builtin_transit_v1, script_scene_v1, or rmp_native_v1");
        }
        // Validate the generated scene before copying anything or replacing an installed template.
        parseScene(json);
        parseTextSequence(json);
        Path target = safeTemplateFolder(root, id);
        Files.createDirectories(target);
        String imageName = string(json, "background_image", "");
        if (!imageName.isBlank()) {
            if (!Path.of(imageName).getFileName().toString().equals(imageName)) {
                throw new IOException("background_image must be a sibling PNG file");
            }
            Path image = source.getParent().resolve(imageName).toAbsolutePath().normalize();
            if (!image.startsWith(source.getParent().toAbsolutePath().normalize())
                    || !Files.isRegularFile(image) || Files.size(image) > MAX_IMAGE_BYTES
                    || !image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                throw new IOException("Invalid template background image");
            }
            Files.copy(image, target.resolve(image.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        String rmpMapName = string(json, "rmp_map", "");
        if (layout.equals("rmp_native_v1")) {
            if (rmpMapName.isBlank()
                    || !Path.of(rmpMapName).getFileName().toString().equals(rmpMapName)) {
                throw new IOException("rmp_native_v1 requires a sibling rmp_map JSON file");
            }
            Path parent = source.getParent();
            Path map = parent == null ? null
                    : parent.resolve(rmpMapName).toAbsolutePath().normalize();
            if (map == null || !map.startsWith(parent.toAbsolutePath().normalize())
                    || !Files.isRegularFile(map) || Files.size(map) > MAX_RMP_MAP_BYTES
                    || !map.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".json")) {
                throw new IOException("Invalid or missing rmp_map JSON file");
            }
            Files.copy(map, target.resolve(map.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(target.resolve("manifest.json"), GSON.toJson(json), StandardCharsets.UTF_8);
        if (reloadAfterImport) reload();
        int stationCount = manifestStationCount(json);
        int lineCount = stationCount > 0 || json.has("traffic") ? 1 : 0;
        return new TrafficImportResult(id, defaults(json), sourceKind, stationCount, lineCount);
    }

    /**
     * LCD Studio and third-party generators may wrap the actual manifest in a top-level
     * {@code manifest} or {@code template} object. Accept both forms and retain route metadata
     * exported beside the wrapper.
     */
    private static JsonObject unwrapManifest(JsonObject root) {
        if (root.has("layout") || root.has("elements") || root.has("id")) return root;
        JsonObject nested = null;
        for (String key : List.of("manifest", "template")) {
            if (root.has(key) && root.get(key).isJsonObject()) {
                nested = root.getAsJsonObject(key).deepCopy();
                break;
            }
        }
        if (nested == null) return root;
        // Wrapper producers are allowed to add new route/style metadata without waiting for a
        // MineScreen release. Merge every sibling field (except the wrapper itself), with fields in
        // the actual manifest taking precedence. A fixed allow-list silently discarded third-party
        // style extensions and made a later client unable to recover them from the installed file.
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry.getKey().equals("manifest") || entry.getKey().equals("template")
                    || nested.has(entry.getKey())) {
                continue;
            }
            nested.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return nested;
    }

    /** Counts the usable route metadata exported by LCD Studio instead of reporting 0 for JS. */
    private static int manifestStationCount(JsonObject json) {
        int count = json.has("station_times") && json.get("station_times").isJsonArray()
                ? json.getAsJsonArray("station_times").size() : 0;
        if (json.has("directions") && json.get("directions").isJsonObject()) {
            for (JsonElement direction : json.getAsJsonObject("directions").asMap().values()) {
                if (!direction.isJsonObject()) continue;
                JsonObject profile = direction.getAsJsonObject();
                if (profile.has("stations") && profile.get("stations").isJsonArray()) {
                    count = Math.max(count, profile.getAsJsonArray("stations").size());
                }
            }
        }
        if (json.has("demo") && json.get("demo").isJsonObject()) {
            JsonObject demo = json.getAsJsonObject("demo");
            if (demo.has("route") && demo.get("route").isJsonObject()) {
                JsonObject route = demo.getAsJsonObject("route");
                if (route.has("stations") && route.get("stations").isJsonArray()) {
                    count = Math.max(count, route.getAsJsonArray("stations").size());
                }
            }
        }
        return Math.max(0, Math.min(2048, count));
    }

    private static Template loadFolder(Path root, Path folder) {
        Path manifest = folder.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            manifest = folder.resolve("display-script.json");
        }
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
                throw new IOException("Traffic manifest exceeds 256 KiB");
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to inspect traffic template manifest {}", manifest, exception);
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject stored = GSON.fromJson(reader, JsonObject.class);
            if (stored == null) return null;
            // Also accept a wrapped manifest copied into the template folder by an older version
            // or a third-party manager. Import normally stores the normalized unwrapped object.
            JsonObject json = unwrapManifest(stored);
            String id = safeId(string(json, "id", folder.getFileName().toString()));
            if (id.equals(Template.BUILTIN.id())) return null;
            String name = string(json, "name", id);
            int background = color(json, "background_color", 0xFF101820);
            int text = color(json, "text_color", 0xFFFFFFFF);
            int accent = color(json, "accent_color", 0xFFFFA726);
            String layout = string(json, "layout", "builtin_transit_v1");
            if (!layout.equals("builtin_transit_v1") && !layout.equals("rmp_native_v1")
                    && !layout.equals("script_scene_v1")) {
                LOGGER.warn("Traffic template {} requested unsupported layout {}", id, layout);
                layout = "builtin_transit_v1";
            }
            ResourceLocation texture = loadTexture(root, folder, id,
                    string(json, "background_image", ""));
            RmpDiagram diagram = layout.equals("rmp_native_v1")
                    ? loadRmpDiagram(root, folder, string(json, "rmp_map", "")) : null;
            List<SceneElement> scene = layout.equals("script_scene_v1")
                    ? parseScene(json) : List.of();
            TextSequence textSequence = layout.equals("script_scene_v1")
                    ? parseTextSequence(json) : TextSequence.EMPTY;
            TrafficMetadata metadata = parseTrafficMetadata(json);
            return new Template(id, name, background, text, accent, layout, texture, diagram,
                    scene, textSequence, metadata);
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Unable to load traffic template from {}", folder, exception);
            return null;
        }
    }

    private static ResourceLocation loadTexture(Path root, Path folder, String id, String fileName)
            throws IOException {
        if (fileName.isBlank()) return null;
        Path file = folder.resolve(fileName).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !file.startsWith(folder.toAbsolutePath().normalize())
                || !Files.isRegularFile(file) || Files.size(file) > MAX_IMAGE_BYTES
                || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IOException("Unsafe or unsupported background_image");
        }
        validatePngHeader(file);
        NativeImage image;
        try (var input = Files.newInputStream(file)) {
            image = NativeImage.read(input);
        }
        if (image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            image.close();
            throw new IOException("Template image exceeds pixel limit");
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "traffic_template/" + id);
        Minecraft.getInstance().getTextureManager().release(location);
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        return location;
    }

    /** Rejects decompression-bomb dimensions before NativeImage allocates the decoded pixel buffer. */
    private static void validatePngHeader(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            byte[] signature = input.readNBytes(8);
            byte[] expected = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            if (!java.util.Arrays.equals(signature, expected) || input.readInt() != 13
                    || input.readInt() != 0x49484452) {
                throw new IOException("Invalid PNG header");
            }
            int width = input.readInt();
            int height = input.readInt();
            if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                throw new IOException("Template image exceeds pixel limit");
            }
        }
    }

    private static long fingerprint(Path folder) throws IOException {
        long hash = 0xcbf29ce484222325L;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(32).toList()) {
                hash ^= file.getFileName().toString().hashCode();
                hash *= 0x100000001b3L;
                hash ^= Files.size(file);
                hash *= 0x100000001b3L;
                hash ^= Files.getLastModifiedTime(file).toMillis();
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static RmpDiagram loadRmpDiagram(Path root, Path folder, String fileName)
            throws IOException {
        if (fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IOException("rmp_map must name one sibling JSON file");
        }
        Path file = folder.resolve(fileName).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !file.startsWith(folder.toAbsolutePath().normalize())
                || !Files.isRegularFile(file) || Files.size(file) > MAX_RMP_MAP_BYTES) {
            throw new IOException("Unsafe or oversized rmp_map");
        }
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray rawNodes = json.has("nodes") && json.get("nodes").isJsonArray()
                ? json.getAsJsonArray("nodes") : new JsonArray();
        JsonArray rawEdges = json.has("edges") && json.get("edges").isJsonArray()
                ? json.getAsJsonArray("edges") : new JsonArray();
        if (rawNodes.isEmpty() || rawNodes.size() > 2_048 || rawEdges.size() > 4_096) {
            throw new IOException("Invalid normalized RMP map size");
        }
        List<RmpNode> nodes = new ArrayList<>();
        Map<String, Integer> indexById = new HashMap<>();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (JsonElement element : rawNodes) {
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            String nodeId = string(node, "id", "");
            double x = finite(node, "x");
            double y = finite(node, "y");
            if (nodeId.isBlank() || indexById.containsKey(nodeId)) continue;
            indexById.put(nodeId, nodes.size());
            nodes.add(new RmpNode(nodeId, x, y, limited(string(node, "name", ""), 96),
                    limited(string(node, "secondary_name", ""), 96)));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        List<RmpEdge> edges = new ArrayList<>();
        for (JsonElement element : rawEdges) {
            if (!element.isJsonObject()) continue;
            JsonObject edge = element.getAsJsonObject();
            Integer from = indexById.get(string(edge, "source", ""));
            Integer to = indexById.get(string(edge, "target", ""));
            if (from != null && to != null && !from.equals(to)) {
                edges.add(new RmpEdge(from, to, color(edge, "color", 0xFF3A9BFF)));
            }
        }
        if (nodes.isEmpty()) throw new IOException("Normalized RMP map has no nodes");
        return new RmpDiagram(List.copyOf(nodes), List.copyOf(edges), minX, minY, maxX, maxY);
    }

    static List<SceneElement> parseScene(JsonObject json) throws IOException {
        if (!json.has("elements")) return List.of();
        if (!json.get("elements").isJsonArray()) throw new IOException("elements must be an array");
        JsonArray elements = json.getAsJsonArray("elements");
        if (elements.size() > MAX_SCENE_ELEMENTS) {
            throw new IOException("Template scene exceeds 256 elements");
        }
        List<SceneElement> parsed = new ArrayList<>();
        for (JsonElement value : elements) {
            if (!value.isJsonObject()) throw new IOException("Every scene element must be an object");
            JsonObject element = value.getAsJsonObject();
            String type = string(element, "type", "").toLowerCase(Locale.ROOT);
            if (!type.equals("text") && !type.equals("rect") && !type.equals("line")
                    && !type.equals("ellipse")) {
                // Newer template generators may add decorative layers understood only by a
                // newer MineScreen version. Optional layers are ignored by older clients while
                // all supported layers keep their original style and order.
                if (bool(element, "optional", false)) continue;
                throw new IOException("Unsupported scene element: " + type);
            }
            double x = normalized(element, "x", 0.0D);
            double y = normalized(element, "y", 0.0D);
            double x2 = normalized(element, "x2", x);
            double y2 = normalized(element, "y2", y);
            double width = normalized(element, "width", 0.0D);
            double height = normalized(element, "height", 0.0D);
            double maxWidth = normalized(element, "max_width", 0.0D);
            double rawSize = type.equals("line")
                    ? number(element, "stroke_width",
                            number(element, "line_width", number(element, "size", 1.0D)))
                    : number(element, "size", 1.0D);
            double minimumSize = type.equals("line")
                    ? TrafficTemplateManifest.MIN_LINE_WIDTH
                    : TrafficTemplateManifest.MIN_TEXT_SIZE;
            double maximumSize = type.equals("line")
                    ? TrafficTemplateManifest.MAX_LINE_WIDTH
                    : TrafficTemplateManifest.MAX_TEXT_SIZE;
            float size = (float) Math.max(minimumSize, Math.min(maximumSize, rawSize));
            float rotation = (float) Math.max(-180.0D,
                    Math.min(180.0D, number(element, "rotation", 0.0D)));
            String text = limited(string(element, "text", ""), 256);
            String align = string(element, "align", "left").toLowerCase(Locale.ROOT);
            if (!align.equals("left") && !align.equals("center") && !align.equals("right")) {
                throw new IOException("Scene text align must be left, center, or right");
            }
            boolean vertical = bool(element, "vertical", false);
            boolean bold = bool(element, "bold", false);
            parsed.add(new SceneElement(type, x, y, x2, y2, width, height, maxWidth,
                    color(element, "color", 0xFFFFFFFF), size, rotation, text, align, vertical,
                    bold));
        }
        return Collections.unmodifiableList(parsed);
    }

    static TextSequence parseTextSequence(JsonObject json) throws IOException {
        if (!json.has("text_sequence")) return TextSequence.EMPTY;
        if (!json.get("text_sequence").isJsonObject()) {
            throw new IOException("text_sequence must be an object");
        }
        JsonObject sequence = json.getAsJsonObject("text_sequence");
        int defaultDuration = (int) number(sequence, "default_duration_ticks", 80);
        if (defaultDuration < 10 || defaultDuration > 72_000) {
            throw new IOException("text_sequence default duration is out of range");
        }
        if (!sequence.has("frames") || !sequence.get("frames").isJsonArray()) {
            throw new IOException("text_sequence frames must be an array");
        }
        JsonArray values = sequence.getAsJsonArray("frames");
        if (values.size() == 0 || values.size() > TrafficTextDocument.MAX_FRAMES) {
            throw new IOException("text_sequence must contain 1 to 64 frames");
        }
        List<TextFrame> frames = new ArrayList<>();
        int totalCharacters = 0;
        for (JsonElement value : values) {
            if (!value.isJsonObject()) throw new IOException("text_sequence frame must be an object");
            JsonObject frame = value.getAsJsonObject();
            String text = string(frame, "text", "");
            if (text.isBlank() || text.length() > TrafficTextDocument.MAX_FRAME_CHARACTERS) {
                throw new IOException("text_sequence frame text is blank or too long");
            }
            totalCharacters += text.length();
            if (totalCharacters > TrafficTextDocument.MAX_TOTAL_CHARACTERS) {
                throw new IOException("text_sequence contains too much text");
            }
            int duration = (int) number(frame, "duration_ticks", defaultDuration);
            if (duration < 10 || duration > 72_000) {
                throw new IOException("text_sequence frame duration is out of range");
            }
            String transition = string(frame, "transition", "fade").toLowerCase(Locale.ROOT);
            if (!Set.of("none", "fade", "slide_left", "typewriter", "blink")
                    .contains(transition)) {
                throw new IOException("Unsupported text_sequence transition: " + transition);
            }
            String align = string(frame, "align", "center").toLowerCase(Locale.ROOT);
            if (!Set.of("left", "center", "right").contains(align)) {
                throw new IOException("Unsupported text_sequence alignment: " + align);
            }
            frames.add(new TextFrame(text, duration, transition, align));
        }
        String position = string(sequence, "position", "bottom").toLowerCase(Locale.ROOT);
        if (!Set.of("top", "center", "bottom").contains(position)) {
            throw new IOException("Unsupported text_sequence position: " + position);
        }
        return new TextSequence(List.copyOf(frames), bool(sequence, "loop", true),
                defaultDuration, position);
    }

    private static TrafficImportResult.Defaults defaults(JsonObject json) {
        JsonObject traffic = json.has("traffic") && json.get("traffic").isJsonObject()
                ? json.getAsJsonObject("traffic") : new JsonObject();
        return new TrafficImportResult.Defaults(
                limited(string(traffic, "line", ""), 96),
                limited(string(traffic, "destination", ""), 96),
                limited(string(traffic, "current", ""), 96),
                limited(string(traffic, "next", ""), 96),
                limited(string(traffic, "eta", ""), 96),
                limited(string(traffic, "status", ""), 96));
    }

    /** Reads the bounded LCD Studio metadata without executing or retaining arbitrary scripts. */
    private static TrafficMetadata parseTrafficMetadata(JsonObject json) {
        String direction = string(json, "active_direction", "up").equals("down") ? "down" : "up";
        List<LanguageProfile> profiles = new ArrayList<>();
        if (json.has("language_profiles") && json.get("language_profiles").isJsonArray()) {
            for (JsonElement value : json.getAsJsonArray("language_profiles")) {
                if (!value.isJsonObject() || profiles.size() >= 8) continue;
                JsonObject item = value.getAsJsonObject();
                String id = limited(string(item, "id", ""), 24);
                String primary = limited(string(item, "primary", ""), 8);
                String secondary = limited(string(item, "secondary", ""), 8);
                if (!id.isBlank() && Set.of("zh", "ja", "en").contains(primary)
                        && Set.of("zh", "ja", "en").contains(secondary) && !primary.equals(secondary)) {
                    profiles.add(new LanguageProfile(id, primary, secondary));
                }
            }
        }
        if (profiles.isEmpty()) profiles = List.of(new LanguageProfile("zh-en", "zh", "en"));
        Map<String, DirectionProfile> directions = new HashMap<>();
        if (json.has("directions") && json.get("directions").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("directions").entrySet()) {
                if (directions.size() >= 4 || !entry.getValue().isJsonObject()) continue;
                JsonObject value = entry.getValue().getAsJsonObject();
                List<StationProfile> stations = new ArrayList<>();
                if (value.has("stations") && value.get("stations").isJsonArray()) {
                    for (JsonElement stationValue : value.getAsJsonArray("stations")) {
                        if (!stationValue.isJsonObject() || stations.size() >= 2048) continue;
                        JsonObject station = stationValue.getAsJsonObject();
                        String displayName = string(station, "display_name", string(station, "name", ""));
                        if (displayName.isBlank() && station.has("names") && station.get("names").isJsonObject()) {
                            JsonObject names = station.getAsJsonObject("names");
                            displayName = string(names, "zh", string(names, "ja", string(names, "en", "")));
                        }
                        stations.add(new StationProfile(limited(string(station, "code", ""), 16),
                                limited(displayName, 96),
                                Math.max(0, Math.min(3600, (int) number(station, "travel_minutes", 0)))));
                    }
                }
                directions.put(entry.getKey().equalsIgnoreCase("down") ? "down" : "up",
                        new DirectionProfile(limited(string(value, "label", entry.getKey()), 32),
                                List.copyOf(stations)));
            }
        }
        List<StationTime> stationTimes = new ArrayList<>();
        if (json.has("station_times") && json.get("station_times").isJsonArray()) {
            for (JsonElement value : json.getAsJsonArray("station_times")) {
                if (!value.isJsonObject() || stationTimes.size() >= 2048) continue;
                JsonObject item = value.getAsJsonObject();
                stationTimes.add(new StationTime(Math.max(0, Math.min(2048,
                        (int) number(item, "index", stationTimes.size()))),
                        limited(string(item, "code", ""), 16),
                        Math.max(0, Math.min(3600, (int) number(item, "travel_minutes", 0)))));
            }
        }
        JsonObject arrival = json.has("arrival") && json.get("arrival").isJsonObject()
                ? json.getAsJsonObject("arrival") : new JsonObject();
        String notice = string(arrival, "notice", "");
        if (arrival.has("notice") && arrival.get("notice").isJsonObject()) {
            JsonObject notices = arrival.getAsJsonObject("notice");
            notice = string(notices, "zh", string(notices, "en", string(notices, "ja", "")));
        }
        ArrivalMetadata arrivalMetadata = new ArrivalMetadata(bool(arrival, "enabled", false),
                limited(string(arrival, "page", "arrival"), 24), limited(notice, 256));
        JsonObject binding = json.has("coordinate_binding") && json.get("coordinate_binding").isJsonObject()
                ? json.getAsJsonObject("coordinate_binding") : new JsonObject();
        CoordinateBinding coordinate = new CoordinateBinding(
                limited(string(binding, "logical_station_id", ""), 96),
                limited(string(binding, "create_station_name", ""), 96),
                stringList(binding, "aliases", 32, 96),
                limited(string(binding, "dimension", "minecraft:overworld"), 128),
                number(binding, "x", 0), number(binding, "y", 0), number(binding, "z", 0),
                Math.max(0, Math.min(128, number(binding, "radius", 12))),
                bool(binding, "auto_create_platform", false));
        Map<String, String> stationBindings = new HashMap<>();
        if (json.has("station_bindings") && json.get("station_bindings").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : json.getAsJsonObject("station_bindings").entrySet()) {
                if (stationBindings.size() >= 2048 || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String code = limited(entry.getKey(), 16).trim();
                String createName = limited(entry.getValue().getAsString(), 128).trim();
                if (!code.isBlank() && !createName.isBlank()) {
                    stationBindings.put(code, createName);
                }
            }
        }
        Map<String, String> placeholderDefaults = new HashMap<>();
        if (json.has("placeholder_defaults")
                && json.get("placeholder_defaults").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : json.getAsJsonObject("placeholder_defaults").entrySet()) {
                if (placeholderDefaults.size() >= 64 || !entry.getValue().isJsonPrimitive()
                        || !entry.getKey().matches("[a-zA-Z0-9_.-]{1,48}")) continue;
                placeholderDefaults.put(entry.getKey(),
                        limited(entry.getValue().getAsString(), 256));
            }
        }
        return new TrafficMetadata(direction, List.copyOf(profiles), Map.copyOf(directions),
                List.copyOf(stationTimes), arrivalMetadata, coordinate,
                Map.copyOf(stationBindings), Map.copyOf(placeholderDefaults));
    }

    /**
     * Applies third-party static bindings and hides only still-unresolved binding tokens.
     * Unknown manifest fields remain stored in the copied JSON, allowing a later MineScreen
     * release to use them without requiring the user to export the style again.
     */
    public static String completePlaceholders(Template template, String text) {
        String expanded = text == null ? "" : text;
        if (template != null) {
            for (Map.Entry<String, String> entry
                    : template.metadata().placeholderDefaults().entrySet()) {
                expanded = expanded.replace("${" + entry.getKey() + "}", entry.getValue());
            }
        }
        return UNRESOLVED_PLACEHOLDER.matcher(expanded).replaceAll("");
    }

    private static String normalizeStation(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("station", "").replace("駅", "")
                .replaceAll("[\\s._-]+", "").trim();
    }

    private static String safeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9._-]{1,64}") ? id : Template.BUILTIN.id();
    }

    static String requireSafeId(String value) throws IOException {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9._-]{1,64}")) {
            throw new IOException("Template id must match [a-z0-9._-]{1,64}");
        }
        return id;
    }

    static Path safeTemplateFolder(Path root, String id) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path folder = normalizedRoot.resolve(requireSafeId(id)).normalize();
        if (!folder.startsWith(normalizedRoot)) throw new IOException("Unsafe template destination");
        return folder;
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsString() : fallback;
    }

    private static int color(JsonObject json, String key, int fallback) {
        String value = string(json, key, "").trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() == 6) value = "FF" + value;
        try {
            return value.length() == 8 ? (int) Long.parseLong(value, 16) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String limited(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static double finite(JsonObject object, String key) throws IOException {
        double value = number(object, key, Double.NaN);
        if (!Double.isFinite(value) || Math.abs(value) > 10_000_000.0D) {
            throw new IOException("Invalid RMP coordinate");
        }
        return value;
    }

    private static double normalized(JsonObject object, String key, double fallback)
            throws IOException {
        double value = number(object, key, fallback);
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IOException("Scene coordinate " + key + " must be between 0 and 1");
        }
        return value;
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static List<String> stringList(JsonObject object, String key, int maximumItems,
            int maximumLength) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (values.size() >= maximumItems || !element.isJsonPrimitive()) break;
            String value = limited(element.getAsString(), maximumLength).trim();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    public record Template(String id, String name, int backgroundColor, int textColor,
            int accentColor, String layout, ResourceLocation texture, RmpDiagram rmpDiagram,
            List<SceneElement> scene, TextSequence textSequence, TrafficMetadata metadata) {
        public static final Template BUILTIN = new Template("builtin_transit", "Built-in Transit",
                0xFF101820, 0xFFFFFFFF, 0xFFFFA726, "builtin_transit_v1", null, null,
                List.of(), TextSequence.EMPTY, TrafficMetadata.EMPTY);
    }

    public record TextSequence(List<TextFrame> frames, boolean loop, int defaultDurationTicks,
            String position) {
        public static final TextSequence EMPTY = new TextSequence(List.of(), true, 80, "bottom");

        public boolean present() {
            return !frames.isEmpty();
        }
    }

    public record TextFrame(String text, int durationTicks, String transition, String align) {
    }

    public record TrafficMetadata(String activeDirection, List<LanguageProfile> languageProfiles,
            Map<String, DirectionProfile> directions, List<StationTime> stationTimes,
            ArrivalMetadata arrival, CoordinateBinding coordinateBinding,
            Map<String, String> stationBindings, Map<String, String> placeholderDefaults) {
        public static final TrafficMetadata EMPTY = new TrafficMetadata("up", List.of(), Map.of(),
                List.of(), new ArrivalMetadata(false, "arrival", ""),
                new CoordinateBinding("", "", List.of(), "minecraft:overworld", 0, 0, 0, 0,
                        false), Map.of(), Map.of());
    }

    public record LanguageProfile(String id, String primary, String secondary) {}
    public record DirectionProfile(String label, List<StationProfile> stations) {}
    public record StationProfile(String code, String displayName, int travelMinutes) {}
    public record StationTime(int index, String code, int travelMinutes) {}
    public record ArrivalMetadata(boolean enabled, String page, String notice) {}
    public record CoordinateBinding(String logicalStationId, String createStationName,
            List<String> aliases, String dimension, double x, double y, double z, double radius,
            boolean autoCreatePlatform) {}

    public record RmpDiagram(List<RmpNode> nodes, List<RmpEdge> edges, double minX, double minY,
            double maxX, double maxY) {
    }

    public record RmpNode(String id, double x, double y, String name, String secondaryName) {
    }

    public record RmpEdge(int from, int to, int color) {
    }

    /** Normalized, bounded scene produced by a JS generator; coordinates are in the 0..1 range. */
    public record SceneElement(String type, double x, double y, double x2, double y2,
            double width, double height, double maxWidth, int color, float size, float rotation,
            String text, String align, boolean vertical, boolean bold) {
    }

    private record CachedTemplate(long fingerprint, Template template) {
    }

    private record CachedSynchronizedTemplate(long fingerprint, SynchronizedTemplate template) {
    }

    private record SynchronizedTemplate(String id, String sha256, byte[] bytes) {
    }

    public record SynchronizedTemplateInfo(String id, String sha256, int size) {
    }
}
