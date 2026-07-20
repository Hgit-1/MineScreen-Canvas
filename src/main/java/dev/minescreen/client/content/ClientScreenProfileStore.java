package dev.minescreen.client.content;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;

/** JSON persistence for client-only sources. No value in this file is synchronized to a server. */
public final class ClientScreenProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<SerializedProfile>>() {
    }.getType();
    private static final java.lang.reflect.Type LEGACY_TYPE =
            new TypeToken<Map<String, ClientScreenProfile>>() {
            }.getType();

    private ClientScreenProfileStore() {
    }

    public static Map<UUID, ClientScreenProfile> load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            Map<UUID, ClientScreenProfile> result = new HashMap<>();
            if (root.isJsonArray()) {
                List<SerializedProfile> values = GSON.fromJson(root, LIST_TYPE);
                if (values != null) {
                    for (SerializedProfile value : values) {
                        if (value != null && value.groupId != null) {
                            try {
                                result.put(UUID.fromString(value.groupId), value.toProfile());
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
            } else if (root.isJsonObject()) {
                // Stage 2 development snapshots used a UUID-keyed object. Read it once so upgrades
                // retain local paths, then the next save writes the documented list schema.
                Map<String, ClientScreenProfile> legacy = GSON.fromJson(root, LEGACY_TYPE);
                if (legacy != null) {
                    legacy.forEach((key, value) -> {
                        try {
                            result.put(UUID.fromString(key), value == null ? new ClientScreenProfile() : value);
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return new HashMap<>();
        }
    }

    public static void save(Map<UUID, ClientScreenProfile> profiles) {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            List<SerializedProfile> serialized = new ArrayList<>();
            profiles.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> serialized.add(new SerializedProfile(entry.getKey(), entry.getValue())));
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(serialized, LIST_TYPE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/minescreen-screens.json");
    }

    private static final class SerializedProfile {
        @SerializedName("group_id")
        String groupId;
        @SerializedName("content_type")
        String contentType = "IDLE";
        String source = "";
        @SerializedName("resolution_percent")
        int resolutionPercent;
        boolean loop = true;
        boolean paused;
        @SerializedName("position_ms")
        long positionMs;
        float volume = 1.0F;
        @SerializedName("vnc_read_only")
        boolean vncReadOnly;
        @SerializedName("media_id")
        String mediaId = "";
        String access = "OWNER_ONLY";
        @SerializedName("disabled_tiles")
        List<Long> disabledTiles = new ArrayList<>();
        @SerializedName("web_tabs")
        List<String> webTabs = new ArrayList<>();

        SerializedProfile() {
        }

        SerializedProfile(UUID groupId, ClientScreenProfile profile) {
            this.groupId = groupId.toString();
            this.contentType = (profile.contentType == null ? ScreenContentType.IDLE : profile.contentType).name();
            this.source = profile.source == null ? "" : profile.source;
            this.resolutionPercent = profile.resolutionPercent;
            this.loop = profile.loop;
            this.paused = profile.paused;
            this.positionMs = profile.positionMs;
            this.volume = profile.volume;
            this.vncReadOnly = profile.vncReadOnly;
            this.mediaId = profile.mediaId == null ? "" : profile.mediaId;
            this.access = profile.access == null ? "OWNER_ONLY" : profile.access.name();
            this.disabledTiles = profile.disabledTiles == null ? new ArrayList<>()
                    : new ArrayList<>(profile.disabledTiles);
            this.webTabs = profile.webTabs == null ? new ArrayList<>()
                    : new ArrayList<>(profile.webTabs);
        }

        ClientScreenProfile toProfile() {
            ClientScreenProfile profile = new ClientScreenProfile();
            profile.contentType = ScreenContentType.fromSerialized(contentType);
            profile.source = source == null ? "" : source;
            profile.resolutionPercent = resolutionPercent <= 0 ? 0
                    : Math.max(25, Math.min(100, resolutionPercent));
            profile.loop = loop;
            profile.paused = paused;
            profile.positionMs = Math.max(0L, positionMs);
            profile.volume = Math.max(0.0F, Math.min(1.0F, volume));
            profile.vncReadOnly = vncReadOnly;
            profile.mediaId = mediaId == null ? "" : mediaId;
            profile.access = dev.minescreen.network.ScreenAccess.safeValueOf(access);
            profile.disabledTiles = disabledTiles == null ? new java.util.HashSet<>()
                    : new java.util.HashSet<>(disabledTiles);
            profile.webTabs = webTabs == null ? new ArrayList<>()
                    : webTabs.stream().filter(java.util.Objects::nonNull).filter(value -> !value.isBlank())
                            .limit(16).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return profile;
        }
    }
}
