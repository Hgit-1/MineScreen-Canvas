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
        Path source = Files.isRegularFile(path) ? path : legacyPath();
        if (!Files.isRegularFile(source)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(source)) {
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
            if (!source.equals(path)) {
                // Copy-only recovery from the short-lived WebDisplays-branded development build.
                save(result);
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

    private static Path legacyPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/webdisplays-screens.json");
    }

    private static final class SerializedProfile {
        @SerializedName("group_id")
        String groupId;
        @SerializedName("content_type")
        String contentType = "IDLE";
        String source = "";
        @SerializedName("video_source")
        String videoSource = "";
        @SerializedName("web_source")
        String webSource = "";
        @SerializedName("vnc_source")
        String vncSource = "";
        @SerializedName("resolution_percent")
        int resolutionPercent;
        boolean loop = true;
        boolean paused;
        @SerializedName("position_ms")
        long positionMs;
        float volume = 1.0F;
        @SerializedName("vnc_read_only")
        boolean vncReadOnly;
        @SerializedName("vnc_fps")
        int vncFps;
        @SerializedName("media_id")
        String mediaId = "";
        String access = "OWNER_ONLY";
        @SerializedName("disabled_tiles")
        List<Long> disabledTiles = new ArrayList<>();
        @SerializedName("web_tabs")
        List<String> webTabs = new ArrayList<>();
        @SerializedName("web_split_layout")
        String webSplitLayout = "SINGLE";
        @SerializedName("host_surface_layout")
        String hostSurfaceLayout = "FREE";
        @SerializedName("host_surface_order")
        List<String> hostSurfaceOrder = new ArrayList<>();
        @SerializedName("host_surface_positions")
        Map<String, HostSurfacePlacement> hostSurfacePositions = new HashMap<>();
        @SerializedName("host_surface_rotations")
        Map<String, Integer> hostSurfaceRotations = new HashMap<>();
        @SerializedName("content_regions")
        Map<String, ScreenRegionProfile> regions = new HashMap<>();
        @SerializedName("tile_regions")
        Map<String, Integer> tileRegions = new HashMap<>();

        SerializedProfile() {
        }

        SerializedProfile(UUID groupId, ClientScreenProfile profile) {
            this.groupId = groupId.toString();
            this.contentType = (profile.contentType == null ? ScreenContentType.IDLE : profile.contentType).name();
            this.source = profile.source == null ? "" : profile.source;
            this.videoSource = profile.videoSource == null ? "" : profile.videoSource;
            this.webSource = profile.webSource == null ? "" : profile.webSource;
            this.vncSource = profile.vncSource == null ? "" : profile.vncSource;
            this.resolutionPercent = profile.resolutionPercent;
            this.loop = profile.loop;
            this.paused = profile.paused;
            this.positionMs = profile.positionMs;
            this.volume = profile.volume;
            this.vncReadOnly = profile.vncReadOnly;
            this.vncFps = profile.vncFps;
            this.mediaId = profile.mediaId == null ? "" : profile.mediaId;
            this.access = profile.access == null ? "OWNER_ONLY" : profile.access.name();
            this.disabledTiles = profile.disabledTiles == null ? new ArrayList<>()
                    : new ArrayList<>(profile.disabledTiles);
            this.webTabs = profile.webTabs == null ? new ArrayList<>()
                    : new ArrayList<>(profile.webTabs);
            this.webSplitLayout = (profile.webSplitLayout == null
                    ? WebSplitLayout.SINGLE : profile.webSplitLayout).name();
            this.hostSurfaceLayout = (profile.hostSurfaceLayout == null
                    ? HostSurfaceLayout.FREE : profile.hostSurfaceLayout).name();
            this.hostSurfaceOrder = profile.hostSurfaceOrder == null ? new ArrayList<>()
                    : new ArrayList<>(profile.hostSurfaceOrder);
            this.hostSurfacePositions = profile.hostSurfacePositions == null ? new HashMap<>()
                    : new HashMap<>(profile.hostSurfacePositions);
            this.hostSurfaceRotations = profile.hostSurfaceRotations == null ? new HashMap<>()
                    : new HashMap<>(profile.hostSurfaceRotations);
            if (profile.regions != null) {
                profile.regions.forEach((id, region) -> {
                    if (id != null && id > 0 && id < ScreenRegionLayout.MAX_REGIONS
                            && region != null) {
                        regions.put(Integer.toString(id), region.copy());
                    }
                });
            }
            if (profile.tileRegions != null) {
                profile.tileRegions.forEach((pos, id) -> {
                    if (pos != null && id != null && id > 0
                            && id < ScreenRegionLayout.MAX_REGIONS) {
                        tileRegions.put(Long.toString(pos), id);
                    }
                });
            }
        }

        ClientScreenProfile toProfile() {
            ClientScreenProfile profile = new ClientScreenProfile();
            profile.contentType = ScreenContentType.fromSerialized(contentType);
            profile.source = source == null ? "" : source;
            profile.videoSource = videoSource == null ? "" : videoSource;
            profile.webSource = webSource == null ? "" : webSource;
            profile.vncSource = vncSource == null ? "" : vncSource;
            profile.normalizeSources();
            profile.resolutionPercent = resolutionPercent <= 0 ? 0
                    : Math.max(25, Math.min(100, resolutionPercent));
            profile.loop = loop;
            profile.paused = paused;
            profile.positionMs = Math.max(0L, positionMs);
            profile.volume = Math.max(0.0F, Math.min(1.0F, volume));
            profile.vncReadOnly = vncReadOnly;
            profile.vncFps = vncFps <= 0 ? 0 : Math.max(1, Math.min(60, vncFps));
            profile.mediaId = mediaId == null ? "" : mediaId;
            profile.access = dev.minescreen.network.ScreenAccess.safeValueOf(access);
            profile.disabledTiles = disabledTiles == null ? new java.util.HashSet<>()
                    : new java.util.HashSet<>(disabledTiles);
            profile.webTabs = webTabs == null ? new ArrayList<>()
                    : webTabs.stream().filter(java.util.Objects::nonNull).filter(value -> !value.isBlank())
                            .limit(16).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            profile.webSplitLayout = WebSplitLayout.safeValueOf(webSplitLayout);
            profile.hostSurfaceLayout = HostSurfaceLayout.safeValueOf(hostSurfaceLayout);
            profile.hostSurfaceOrder = hostSurfaceOrder == null ? new ArrayList<>()
                    : hostSurfaceOrder.stream().filter(java.util.Objects::nonNull)
                            .filter(value -> {
                                try {
                                    UUID.fromString(value);
                                    return true;
                                } catch (IllegalArgumentException exception) {
                                    return false;
                                }
                            }).distinct().limit(64)
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            profile.hostSurfacePositions = new HashMap<>();
            if (hostSurfacePositions != null) {
                hostSurfacePositions.forEach((key, placement) -> {
                    try {
                        UUID.fromString(key);
                        if (placement != null && profile.hostSurfacePositions.size() < 64) {
                            profile.hostSurfacePositions.put(key,
                                    new HostSurfacePlacement(placement.x(), placement.y()));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
            profile.hostSurfaceRotations = new HashMap<>();
            if (hostSurfaceRotations != null) {
                hostSurfaceRotations.forEach((key, turns) -> {
                    try {
                        UUID.fromString(key);
                        if (turns != null && profile.hostSurfaceRotations.size() < 64) {
                            profile.hostSurfaceRotations.put(key, ScreenRotation.normalize(turns));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
            profile.regions = new HashMap<>();
            if (regions != null) {
                regions.forEach((key, value) -> {
                    try {
                        int id = Integer.parseInt(key);
                        if (id > 0 && id < ScreenRegionLayout.MAX_REGIONS && value != null) {
                            profile.regions.put(id,
                                    ScreenRegionProfile.fromProfile(value.toProfile()));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
            profile.tileRegions = new HashMap<>();
            if (tileRegions != null) {
                tileRegions.forEach((key, value) -> {
                    try {
                        if (value != null && value > 0
                                && value < ScreenRegionLayout.MAX_REGIONS) {
                            profile.tileRegions.put(Long.parseLong(key), value);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
            return profile;
        }
    }
}
