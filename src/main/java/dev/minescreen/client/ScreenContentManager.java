package dev.minescreen.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ClientScreenProfileStore;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenRegionLayout;
import dev.minescreen.client.content.ScreenRegionProfile;
import dev.minescreen.client.content.HostSurfaceLayout;
import dev.minescreen.client.content.HostSurfacePlacement;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Owns one content session per logical group and migrates local profiles across topology changes. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenContentManager {
    private static final Map<UUID, ClientScreenProfile> PROFILES = ClientScreenProfileStore.load();
    private static final Map<UUID, ScreenContentSession> SESSIONS = new HashMap<>();
    private static final Map<RegionSessionKey, ScreenContentSession> REGION_SESSIONS = new HashMap<>();
    private static final Map<UUID, PanoramaSession> PANORAMA_SESSIONS = new HashMap<>();
    private static final Map<UUID, ScreenGroup> GROUPS = new HashMap<>();
    private static final Map<UUID, Long> KEEP_ALIVE_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> FAILURE_NOTICE_UNTIL = new HashMap<>();

    private ScreenContentManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AsyncContentSession.beginClientTick();
        if (Minecraft.getInstance().level == null) {
            closeAll();
            GROUPS.clear();
            KEEP_ALIVE_UNTIL.clear();
            FAILURE_NOTICE_UNTIL.clear();
            return;
        }
        for (Map.Entry<UUID, ScreenContentSession> entry : List.copyOf(SESSIONS.entrySet())) {
            ScreenGroup group = GROUPS.get(entry.getKey());
            if (group != null) {
                if (!ScreenPowerManager.isPowered(group)) {
                    closeSession(entry.getKey());
                    continue;
                }
                entry.getValue().tick(primaryCanvasGroup(group));
            }
        }
        for (Map.Entry<RegionSessionKey, ScreenContentSession> entry
                : List.copyOf(REGION_SESSIONS.entrySet())) {
            ScreenGroup parent = GROUPS.get(entry.getKey().groupId());
            if (parent != null && !ScreenPowerManager.isPowered(parent)) {
                closeRegionSession(entry.getKey());
                continue;
            }
            ScreenRegionLayout.Canvas canvas = parent == null ? null
                    : ScreenRegionLayout.canvas(parent, profile(parent.groupId()),
                            entry.getKey().regionId());
            if (canvas == null) {
                closeRegionSession(entry.getKey());
            } else {
                entry.getValue().tick(contentCanvasGroup(parent, canvas.group()));
            }
        }
        ScreenHostNetworkManager.ensure(Minecraft.getInstance().level);
        for (Map.Entry<UUID, PanoramaSession> entry : List.copyOf(PANORAMA_SESSIONS.entrySet())) {
            ScreenHostNetworkManager.HostNetwork network =
                    ScreenHostNetworkManager.network(entry.getKey());
            if (network == null || !network.panoramic()) {
                closePanoramaSession(entry.getKey());
            } else if (!ScreenPowerManager.isPowered(network.rootGroup())) {
                closePanoramaSession(entry.getKey());
            } else {
                PanoramaSession holder = entry.getValue();
                if (network.signature() != holder.signature()) {
                    holder.session().resize(network.canvas());
                    holder = new PanoramaSession(network.signature(), network.rootGroupId(),
                            holder.session());
                    PANORAMA_SESSIONS.put(entry.getKey(), holder);
                }
                holder.session().tick(network.canvas());
            }
        }
    }

    public static void onGroupsChanged(List<ScreenGroup> groups) {
        // Preserve live Chromium/decoder sessions across a physical topology refresh. Each backend
        // has an explicit resize path; destroying everything here reset browser tabs and could keep
        // a sparse-frame video permanently on its uninitialized texture while chunks/cables changed.
        ScreenHostNetworkManager.invalidate();
        ScreenPowerManager.invalidate();
        Map<UUID, ScreenGroup> next = new HashMap<>();
        groups.forEach(group -> next.put(group.groupId(), group));
        Set<UUID> topologyMigrated = migrateProfiles(next.values());

        Set<UUID> removed = new HashSet<>(SESSIONS.keySet());
        removed.removeAll(next.keySet());
        removed.addAll(topologyMigrated);
        removed.forEach(ScreenContentManager::closeSession);

        Map<UUID, ScreenGroup> previousGroups = new HashMap<>(GROUPS);
        GROUPS.clear();
        GROUPS.putAll(next);
        for (Map.Entry<UUID, ScreenContentSession> entry : List.copyOf(SESSIONS.entrySet())) {
            ScreenGroup oldGroup = previousGroups.get(entry.getKey());
            ScreenGroup group = GROUPS.get(entry.getKey());
            if (group != null) {
                if (oldGroup != null) {
                    entry.getValue().resize(primaryCanvasGroup(group));
                }
            }
        }
        for (Map.Entry<RegionSessionKey, ScreenContentSession> entry
                : List.copyOf(REGION_SESSIONS.entrySet())) {
            ScreenGroup parent = GROUPS.get(entry.getKey().groupId());
            ScreenRegionLayout.Canvas canvas = parent == null ? null
                    : ScreenRegionLayout.canvas(parent, profile(parent.groupId()),
                            entry.getKey().regionId());
            if (canvas == null) {
                closeRegionSession(entry.getKey());
            } else {
                entry.getValue().resize(contentCanvasGroup(parent, canvas.group()));
            }
        }
    }

    public static ClientScreenProfile profile(UUID groupId) {
        ClientScreenProfile profile = PROFILES.computeIfAbsent(groupId, ignored -> new ClientScreenProfile());
        if (profile.contentType == null) {
            profile.contentType = ScreenContentType.IDLE;
        }
        profile.normalizeSources();
        if (profile.mediaId == null) {
            profile.mediaId = "";
        }
        if (profile.access == null) {
            profile.access = dev.minescreen.network.ScreenAccess.OWNER_ONLY;
        }
        if (profile.disabledTiles == null) {
            profile.disabledTiles = new java.util.HashSet<>();
        }
        if (profile.webTabs == null) {
            profile.webTabs = new java.util.ArrayList<>();
        }
        if (profile.webSplitLayout == null) {
            profile.webSplitLayout = dev.minescreen.client.content.WebSplitLayout.SINGLE;
        }
        if (profile.hostSurfaceLayout == null) {
            profile.hostSurfaceLayout = HostSurfaceLayout.FREE;
        }
        if (profile.hostSurfaceOrder == null) {
            profile.hostSurfaceOrder = new java.util.ArrayList<>();
        }
        if (profile.hostSurfacePositions == null) {
            profile.hostSurfacePositions = new java.util.HashMap<>();
        }
        if (profile.hostSurfaceRotations == null) {
            profile.hostSurfaceRotations = new java.util.HashMap<>();
        }
        if (profile.regions == null) {
            profile.regions = new java.util.HashMap<>();
        }
        if (profile.tileRegions == null) {
            profile.tileRegions = new java.util.HashMap<>();
        }
        return profile;
    }

    public static ClientScreenProfile profile(UUID groupId, int regionId) {
        ClientScreenProfile root = profile(groupId);
        return ScreenRegionLayout.profileFor(root, regionId);
    }

    public static void updateProfile(UUID groupId, ClientScreenProfile profile) {
        ClientScreenProfile normalized = profile.copy();
        normalized.normalizeSources();
        PROFILES.put(groupId, normalized);
        ClientScreenProfileStore.save(PROFILES);
        ScreenGroup group = GROUPS.get(groupId);
        if (group != null) {
            dev.minescreen.client.network.ClientNetworkState.sendProfile(group, normalized);
        }
        closeSession(groupId);
        closePanoramaForGroup(groupId);
    }

    /** Persists and synchronizes volume without destroying Chromium or decoder state. */
    public static void updateVolume(UUID groupId, float volume) {
        ClientScreenProfile profile = profile(groupId);
        profile.volume = Math.max(0.0F, Math.min(1.0F, volume));
        ClientScreenProfileStore.save(PROFILES);
        ScreenContentSession session = SESSIONS.get(groupId);
        if (session != null) {
            session.setVolume(profile.volume);
        }
        ScreenHostNetworkManager.HostNetwork host = hostNetwork(groupId);
        if (host != null && host.rootGroupId().equals(groupId)) {
            PanoramaSession panorama = PANORAMA_SESSIONS.get(host.networkId());
            if (panorama != null) {
                panorama.session().setVolume(profile.volume);
            }
        }
        ScreenGroup group = GROUPS.get(groupId);
        if (group != null) {
            dev.minescreen.client.network.ClientNetworkState.sendProfile(group, profile);
        }
    }

    public static void updateVolume(UUID groupId, int regionId, float volume) {
        if (regionId <= 0) {
            updateVolume(groupId, volume);
            return;
        }
        ClientScreenProfile root = profile(groupId);
        ScreenRegionProfile region = root.regions.computeIfAbsent(regionId,
                ignored -> new ScreenRegionProfile());
        region.volume = Math.max(0.0F, Math.min(1.0F, volume));
        ClientScreenProfileStore.save(PROFILES);
        ScreenContentSession session = REGION_SESSIONS.get(new RegionSessionKey(groupId, regionId));
        if (session != null) {
            session.setVolume(region.volume);
        }
    }

    /** Changes browser pane arrangement without reloading any tab. */
    public static void updateWebSplitLayout(UUID groupId,
            dev.minescreen.client.content.WebSplitLayout layout) {
        ClientScreenProfile profile = profile(groupId);
        profile.webSplitLayout = layout == null
                ? dev.minescreen.client.content.WebSplitLayout.SINGLE : layout;
        ClientScreenProfileStore.save(PROFILES);
        ScreenContentSession session = session(groupId);
        if (session instanceof dev.minescreen.client.web.BrowserSession browser) {
            browser.setSplitLayout(profile.webSplitLayout);
        }
    }

    public static void updateWebSplitLayout(UUID groupId, int regionId,
            dev.minescreen.client.content.WebSplitLayout layout) {
        if (regionId <= 0) {
            updateWebSplitLayout(groupId, layout);
            return;
        }
        ClientScreenProfile root = profile(groupId);
        ScreenRegionProfile region = root.regions.computeIfAbsent(regionId,
                ignored -> new ScreenRegionProfile());
        region.webSplitLayout = layout == null
                ? dev.minescreen.client.content.WebSplitLayout.SINGLE : layout;
        ClientScreenProfileStore.save(PROFILES);
        ScreenContentSession session = REGION_SESSIONS.get(new RegionSessionKey(groupId, regionId));
        if (session instanceof dev.minescreen.client.web.BrowserSession browser) {
            browser.setSplitLayout(region.webSplitLayout);
        }
    }

    public static void updateProfile(UUID groupId, int regionId, ClientScreenProfile nextProfile) {
        if (regionId <= 0) {
            updateProfile(groupId, nextProfile);
            return;
        }
        ClientScreenProfile normalized = nextProfile.copy();
        normalized.normalizeSources();
        ClientScreenProfile root = profile(groupId);
        root.regions.put(regionId, ScreenRegionProfile.fromProfile(normalized));
        ClientScreenProfileStore.save(PROFILES);
        closeRegionSession(new RegionSessionKey(groupId, regionId));
    }

    public static void saveLocalProfile(UUID groupId, int regionId, ClientScreenProfile nextProfile) {
        if (regionId <= 0) {
            saveLocalProfile(groupId, nextProfile);
            return;
        }
        ClientScreenProfile normalized = nextProfile.copy();
        normalized.normalizeSources();
        ClientScreenProfile root = profile(groupId);
        root.regions.put(regionId, ScreenRegionProfile.fromProfile(normalized));
        ClientScreenProfileStore.save(PROFILES);
    }

    public static void assignTile(UUID groupId, net.minecraft.core.BlockPos tile, int regionId) {
        ClientScreenProfile root = profile(groupId);
        int safeId = Math.max(0, Math.min(ScreenRegionLayout.MAX_REGIONS - 1, regionId));
        if (safeId == 0) {
            root.tileRegions.remove(tile.asLong());
        } else {
            root.regions.computeIfAbsent(safeId, ignored -> new ScreenRegionProfile());
            root.tileRegions.put(tile.asLong(), safeId);
        }
        ClientScreenProfileStore.save(PROFILES);
        refreshAssignedCanvases(groupId);
    }

    public static void setTileEnabled(UUID groupId, net.minecraft.core.BlockPos pos, boolean enabled) {
        ClientScreenProfile profile = profile(groupId);
        if (enabled) {
            profile.disabledTiles.remove(pos.asLong());
        } else {
            profile.disabledTiles.add(pos.asLong());
        }
        ClientScreenProfileStore.save(PROFILES);
    }

    public static void saveLocalProfile(UUID groupId, ClientScreenProfile profile) {
        ClientScreenProfile normalized = profile.copy();
        normalized.normalizeSources();
        PROFILES.put(groupId, normalized);
        ClientScreenProfileStore.save(PROFILES);
    }

    /**
     * Snapshots the live browser tab set before a topology/chunk lifecycle restart. Chromium's
     * history objects are native and cannot be migrated, but retaining every URL and the active
     * tab prevents a distance or cable refresh from collapsing the device back to its first tab.
     */
    public static void saveBrowserState(UUID groupId, int regionId,
            dev.minescreen.client.web.BrowserSession browser) {
        if (browser == null || browser.type() != ScreenContentType.WEB) {
            return;
        }
        String current = browser.restorableActiveUrl();
        String active = restorableWebUrl(current) ? current : "";
        if (active.isBlank()) {
            return;
        }
        java.util.LinkedHashSet<String> background = new java.util.LinkedHashSet<>();
        for (String url : browser.restorableUrls()) {
            if (restorableWebUrl(url) && !url.equals(active)) {
                background.add(url);
            }
        }
        ClientScreenProfile next = profile(groupId, regionId).copy();
        java.util.List<String> restored = new java.util.ArrayList<>(background);
        if (java.util.Objects.equals(next.source, active)
                && java.util.Objects.equals(next.webTabs, restored)) {
            return;
        }
        next.setSourceFor(ScreenContentType.WEB, active);
        next.webTabs = restored;
        if (regionId <= 0) {
            PROFILES.put(groupId, next);
        } else {
            ClientScreenProfile root = profile(groupId);
            root.regions.put(regionId, ScreenRegionProfile.fromProfile(next));
        }
        ClientScreenProfileStore.save(PROFILES);
    }

    private static boolean restorableWebUrl(String url) {
        return url != null && !url.isBlank() && !url.equalsIgnoreCase("about:blank");
    }

    public static ScreenRenderSource sourceFor(ScreenGroup group) {
        if (!ScreenPowerManager.isPowered(group)) {
            return ScreenTextureManager.blackRenderSource();
        }
        PanoramaRender panorama = panoramaFor(group);
        if (panorama != null) {
            return panorama.source();
        }
        ClientScreenProfile profile = dev.minescreen.client.network.ClientNetworkState.effectiveProfile(
                group.groupId(), profile(group.groupId()));
        if (profile.contentType == ScreenContentType.IDLE || profile.source.isBlank()) {
            return ScreenTextureManager.idleRenderSource();
        }
        ScreenContentSession session = SESSIONS.get(group.groupId());
        if (session instanceof FailedContentSession failed && failed.retryReady()) {
            closeSession(group.groupId());
            session = null;
        }
        if (session == null) {
            session = createSession(primaryCanvasGroup(group), profile);
            SESSIONS.put(group.groupId(), session);
        }
        return session.renderSource();
    }

    /** Shared main-screen source plus the normalized slice assigned to one physical plane. */
    public static PanoramaRender panoramaFor(ScreenGroup group) {
        ScreenHostNetworkManager.HostNetwork network = ScreenHostNetworkManager.networkFor(group);
        if (network == null || !network.panoramic()) {
            return null;
        }
        ScreenHostNetworkManager.Surface surface = network.surface(group.groupId());
        if (surface == null) {
            return null;
        }
        if (!ScreenPowerManager.isPowered(group)) {
            return new PanoramaRender(ScreenTextureManager.blackRenderSource(), surface, network);
        }
        ClientScreenProfile root = dev.minescreen.client.network.ClientNetworkState.effectiveProfile(
                network.rootGroupId(), profile(network.rootGroupId()));
        if (root.contentType == ScreenContentType.IDLE) {
            return new PanoramaRender(ScreenTextureManager.idleRenderSource(), surface, network);
        }
        if (root.source == null || root.source.isBlank()) {
            return null;
        }
        PanoramaSession holder = PANORAMA_SESSIONS.get(network.networkId());
        if (holder == null) {
            ScreenContentSession session = createSession(network.canvas(), root,
                    network.rootGroup(), network.rootGroupId());
            if (session == null) {
                return null;
            }
            holder = new PanoramaSession(network.signature(), network.rootGroupId(), session);
            PANORAMA_SESSIONS.put(network.networkId(), holder);
        } else if (holder.signature() != network.signature()) {
            holder.session().resize(network.canvas());
            holder = new PanoramaSession(network.signature(), network.rootGroupId(),
                    holder.session());
            PANORAMA_SESSIONS.put(network.networkId(), holder);
        }
        return new PanoramaRender(holder.session().renderSource(), surface, network);
    }

    /** Full panoramic source used by the host GUI and the miniature host monitor. */
    public static PanoramaRender panoramaFor(ScreenHostNetworkManager.HostNetwork network) {
        return network == null ? null : panoramaFor(network.rootGroup());
    }

    public static void setHostSurfaceLayout(UUID anyGroupId, HostSurfaceLayout layout) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(anyGroupId);
        UUID rootId = network == null ? anyGroupId : network.rootGroupId();
        ClientScreenProfile root = profile(rootId);
        HostSurfaceLayout next = layout == null ? HostSurfaceLayout.FREE : layout;
        if (next == HostSurfaceLayout.CUSTOM && network != null
                && root.hostSurfacePositions.isEmpty()) {
            seedSurfacePositions(root, network);
        }
        boolean wasJoined = network != null && network.panoramic();
        boolean willJoin = network != null && next != HostSurfaceLayout.FREE
                && network.groups().size() > 1;
        root.hostSurfaceLayout = next;
        ClientScreenProfileStore.save(PROFILES);
        if (network != null && wasJoined != willJoin) {
            network.groups().forEach(member -> {
                closeSession(member.groupId());
                REGION_SESSIONS.keySet().stream()
                        .filter(key -> key.groupId().equals(member.groupId())).toList()
                        .forEach(ScreenContentManager::closeRegionSession);
            });
            closePanoramaSession(network.networkId());
        }
        ScreenHostNetworkManager.invalidate();
    }

    /** Compatibility helper: moves one plane one step later in the automatic layout order. */
    public static void moveHostSurface(UUID anyGroupId, UUID surfaceGroupId) {
        moveHostSurfaceOrder(anyGroupId, surfaceGroupId, 1);
    }

    public static void moveHostSurfaceOrder(UUID anyGroupId, UUID surfaceGroupId, int delta) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(anyGroupId);
        if (network == null || network.groups().size() < 2 || delta == 0) {
            return;
        }
        ClientScreenProfile root = profile(network.rootGroupId());
        java.util.List<String> order = network.groups().stream()
                .map(group -> group.groupId().toString())
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int index = order.indexOf(surfaceGroupId.toString());
        if (index < 0) {
            return;
        }
        String moving = order.remove(index);
        int target = Math.floorMod(index + delta, order.size() + 1);
        order.add(target, moving);
        root.hostSurfaceOrder = order;
        ClientScreenProfileStore.save(PROFILES);
        ScreenHostNetworkManager.invalidate();
    }

    /** Moves one physical plane in logical tile units and switches the host to CUSTOM layout. */
    public static void moveHostSurfacePosition(UUID anyGroupId, UUID surfaceGroupId,
            int deltaX, int deltaY) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(anyGroupId);
        if (network == null || network.groups().size() < 2 || deltaX == 0 && deltaY == 0) {
            return;
        }
        ClientScreenProfile root = profile(network.rootGroupId());
        seedSurfacePositions(root, network);
        String key = surfaceGroupId.toString();
        HostSurfacePlacement placement = root.hostSurfacePositions.get(key);
        if (placement == null) {
            return;
        }
        boolean wasJoined = network.panoramic();
        root.hostSurfacePositions.put(key, placement.offset(deltaX, deltaY));
        root.hostSurfaceLayout = HostSurfaceLayout.CUSTOM;
        ClientScreenProfileStore.save(PROFILES);
        if (!wasJoined) {
            network.groups().forEach(member -> {
                closeSession(member.groupId());
                REGION_SESSIONS.keySet().stream()
                        .filter(region -> region.groupId().equals(member.groupId())).toList()
                        .forEach(ScreenContentManager::closeRegionSession);
            });
        }
        ScreenHostNetworkManager.invalidate();
    }

    /** Rotates one physical plane clockwise without changing the orientation of its neighbors. */
    public static void rotateHostSurface(UUID anyGroupId, UUID surfaceGroupId) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(anyGroupId);
        if (network == null || network.surface(surfaceGroupId) == null) {
            return;
        }
        ClientScreenProfile root = profile(network.rootGroupId());
        String key = surfaceGroupId.toString();
        int current = root.hostSurfaceRotations.getOrDefault(key, 0);
        root.hostSurfaceRotations.put(key,
                dev.minescreen.client.content.ScreenRotation.normalize(current + 1));
        ClientScreenProfileStore.save(PROFILES);
        ScreenHostNetworkManager.invalidate();
        if (!network.panoramic()) {
            // Independent content resolution changes aspect for 90/270 degree rotations.
            closeSession(surfaceGroupId);
            REGION_SESSIONS.keySet().stream().filter(entry -> entry.groupId().equals(surfaceGroupId))
                    .toList().forEach(ScreenContentManager::closeRegionSession);
        }
    }

    private static void seedSurfacePositions(ClientScreenProfile root,
            ScreenHostNetworkManager.HostNetwork network) {
        for (ScreenGroup member : network.groups()) {
            ScreenHostNetworkManager.Surface surface = network.surface(member.groupId());
            if (surface != null) {
                root.hostSurfacePositions.putIfAbsent(member.groupId().toString(),
                        new HostSurfacePlacement(surface.column(), surface.row()));
            }
        }
    }

    public static ScreenRenderSource sourceFor(ScreenGroup parent, int regionId) {
        if (!ScreenPowerManager.isPowered(parent)) {
            return ScreenTextureManager.blackRenderSource();
        }
        if (regionId <= 0) {
            return sourceFor(parent);
        }
        ClientScreenProfile root = profile(parent.groupId());
        ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(parent, root, regionId);
        if (canvas == null) {
            return ScreenTextureManager.idleRenderSource();
        }
        ClientScreenProfile regionProfile = ScreenRegionLayout.profileFor(root, regionId);
        if (regionProfile.contentType == ScreenContentType.IDLE || regionProfile.source.isBlank()) {
            return ScreenTextureManager.idleRenderSource();
        }
        RegionSessionKey key = new RegionSessionKey(parent.groupId(), regionId);
        ScreenContentSession session = REGION_SESSIONS.get(key);
        if (session instanceof FailedContentSession failed && failed.retryReady()) {
            closeRegionSession(key);
            session = null;
        }
        if (session == null) {
            session = createSession(contentCanvasGroup(parent, canvas.group()), regionProfile);
            REGION_SESSIONS.put(key, session);
        }
        return session.renderSource();
    }

    public static ScreenContentSession session(UUID groupId) {
        ScreenContentSession session = SESSIONS.get(groupId);
        if (session != null) {
            return session;
        }
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(groupId);
        PanoramaSession panorama = network == null ? null : PANORAMA_SESSIONS.get(network.networkId());
        return panorama == null ? null : panorama.session();
    }

    public static ScreenContentSession session(UUID groupId, int regionId) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(groupId);
        if (network != null && network.panoramic()) {
            return session(groupId);
        }
        return regionId <= 0 ? session(groupId)
                : REGION_SESSIONS.get(new RegionSessionKey(groupId, regionId));
    }

    public static String error(UUID groupId) {
        ScreenContentSession session = session(groupId);
        return session == null ? null : session.errorMessage();
    }

    public static String error(UUID groupId, int regionId) {
        ScreenContentSession session = session(groupId, regionId);
        return session == null ? null : session.errorMessage();
    }

    public static void requestHostKeepAlive(UUID groupId) {
        KEEP_ALIVE_UNTIL.put(groupId, System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L));
    }

    public static boolean hostKeepsAlive(UUID groupId) {
        Long deadline = KEEP_ALIVE_UNTIL.get(groupId);
        if (deadline == null) {
            return false;
        }
        if (System.nanoTime() - deadline >= 0L) {
            KEEP_ALIVE_UNTIL.remove(groupId);
            return false;
        }
        return true;
    }

    public static void onRemoteStateChanged(UUID groupId) {
        closeSession(groupId);
        closePanoramaForGroup(groupId);
    }

    public static void applyRemotePlaybackState(dev.minescreen.network.ScreenStatePayload state) {
        ScreenContentSession session = SESSIONS.get(state.screenId());
        if (session == null) {
            ScreenHostNetworkManager.HostNetwork host = hostNetwork(state.screenId());
            PanoramaSession panorama = host == null ? null : PANORAMA_SESSIONS.get(host.networkId());
            if (panorama != null && panorama.rootGroupId().equals(state.screenId())) {
                session = panorama.session();
            }
        }
        if (session == null) {
            return;
        }
        session.setVolume(state.volume());
        if (session.type() != ScreenContentType.VIDEO) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long elapsed = minecraft.level == null || state.paused() ? 0L
                : Math.max(0L, minecraft.level.getGameTime() - state.serverGameTime()) * 50L;
        long target = Math.max(0L, state.positionMs() + elapsed);
        session.setPaused(state.paused());
        if (Math.abs(session.positionMs() - target) > 500L) {
            session.seek(target);
        }
    }

    public static void applyRemoteWebNavigation(UUID groupId, String url) {
        ScreenContentSession session = session(groupId);
        if (session instanceof dev.minescreen.client.web.BrowserSession browser
                && url != null && !url.isBlank() && !url.equals(browser.currentUrl())) {
            browser.navigate(url);
        }
    }

    private static ScreenContentSession createSession(ScreenGroup group, ClientScreenProfile profile) {
        return createSession(group, profile, group, group.groupId());
    }

    private static ScreenContentSession createSession(ScreenGroup group, ClientScreenProfile profile,
            ScreenGroup stateGroup, UUID credentialGroupId) {
        return profile.contentType == ScreenContentType.IDLE ? null
                : new AsyncContentSession(group, profile, stateGroup, credentialGroupId);
    }

    private static ScreenGroup primaryCanvasGroup(ScreenGroup parent) {
        ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(parent,
                profile(parent.groupId()), 0);
        return contentCanvasGroup(parent, canvas == null ? parent : canvas.group());
    }

    private static ScreenGroup contentCanvasGroup(ScreenGroup parent, ScreenGroup canvas) {
        ScreenHostNetworkManager.HostNetwork network = ScreenHostNetworkManager.networkFor(parent);
        if (network == null || network.panoramic()) {
            return canvas;
        }
        int turns = ScreenHostNetworkManager.rotationFor(parent);
        if ((turns & 1) == 0) {
            return canvas;
        }
        return new ScreenGroup(canvas.groupId(), canvas.dimension(), canvas.facing(), canvas.master(),
                canvas.origin(), canvas.rows(), canvas.columns(), canvas.tiles(), canvas.bounds(),
                canvas.legacyAnchor());
    }

    private static Set<UUID> migrateProfiles(Collection<ScreenGroup> nextGroups) {
        Set<UUID> sessionsToClose = new HashSet<>();
        for (ScreenGroup old : List.copyOf(GROUPS.values())) {
            ScreenGroup sameId = nextGroups.stream()
                    .filter(group -> group.groupId().equals(old.groupId())).findFirst().orElse(null);
            if (sameId != null && sameId.tiles().equals(old.tiles())) {
                continue;
            }
            ScreenGroup target = nextGroups.stream().filter(group -> group.dimension().equals(old.dimension()))
                    .max(java.util.Comparator.comparingInt(group -> overlap(old, group))).orElse(null);
            if (target != null && overlap(old, target) > 0 && PROFILES.containsKey(old.groupId())) {
                if (!target.groupId().equals(old.groupId())) {
                    ClientScreenProfile migrating = PROFILES.get(old.groupId());
                    ScreenContentSession session = SESSIONS.get(old.groupId());
                    if (session != null) {
                        migrating.positionMs = session.positionMs();
                    }
                    if (!PROFILES.containsKey(target.groupId())) {
                        PROFILES.put(target.groupId(), migrating);
                        dev.minescreen.client.vnc.VncCredentialStore.migrate(old.groupId(), target.groupId());
                        for (int region = 1; region < ScreenRegionLayout.MAX_REGIONS; region++) {
                            dev.minescreen.client.vnc.VncCredentialStore.migrate(
                                    ScreenRegionLayout.sessionId(old.groupId(), region),
                                    ScreenRegionLayout.sessionId(target.groupId(), region));
                        }
                    }
                    PROFILES.remove(old.groupId());
                    sessionsToClose.add(old.groupId());
                }
                ClientScreenProfileStore.save(PROFILES);
            }
        }
        return sessionsToClose;
    }

    private static int overlap(ScreenGroup a, ScreenGroup b) {
        int count = 0;
        for (net.minecraft.core.BlockPos pos : a.tiles()) {
            if (b.tiles().contains(pos)) {
                count++;
            }
        }
        return count;
    }

    private static boolean sameTopology(ScreenGroup first, ScreenGroup second) {
        return first.dimension().equals(second.dimension()) && first.facing() == second.facing()
                && first.master().equals(second.master()) && first.origin().equals(second.origin())
                && first.columns() == second.columns() && first.rows() == second.rows()
                && first.legacyAnchor() == second.legacyAnchor()
                && first.tiles().equals(second.tiles());
    }

    private static void closeSession(UUID id) {
        ScreenContentSession session = SESSIONS.remove(id);
        if (session != null) {
            if (session.type() == ScreenContentType.WEB
                    && session instanceof dev.minescreen.client.web.BrowserSession browser) {
                saveBrowserState(id, 0, browser);
            }
            session.close();
        }
    }

    private static void closeAll() {
        List.copyOf(SESSIONS.keySet()).forEach(ScreenContentManager::closeSession);
        closeAllRegionSessions();
        closePanoramaSessions();
    }

    private static void closeRegionSession(RegionSessionKey key) {
        ScreenContentSession session = REGION_SESSIONS.remove(key);
        if (session != null) {
            if (session.type() == ScreenContentType.WEB
                    && session instanceof dev.minescreen.client.web.BrowserSession browser) {
                saveBrowserState(key.groupId(), key.regionId(), browser);
            }
            session.close();
        }
    }

    private static void closeAllRegionSessions() {
        List.copyOf(REGION_SESSIONS.keySet()).forEach(ScreenContentManager::closeRegionSession);
    }

    public static void closePanoramaSessions() {
        List.copyOf(PANORAMA_SESSIONS.keySet()).forEach(ScreenContentManager::closePanoramaSession);
    }

    private static void closePanoramaSession(UUID networkId) {
        PanoramaSession holder = PANORAMA_SESSIONS.remove(networkId);
        if (holder != null) {
            if (holder.session().type() == ScreenContentType.WEB
                    && holder.session() instanceof dev.minescreen.client.web.BrowserSession browser) {
                saveBrowserState(holder.rootGroupId(), 0, browser);
            }
            holder.session().close();
        }
    }

    private static void closePanoramaForGroup(UUID groupId) {
        ScreenHostNetworkManager.HostNetwork network = hostNetwork(groupId);
        if (network != null) {
            closePanoramaSession(network.networkId());
        }
    }

    private static ScreenHostNetworkManager.HostNetwork hostNetwork(UUID groupId) {
        ScreenGroup group = GROUPS.get(groupId);
        return group == null ? null : ScreenHostNetworkManager.networkFor(group);
    }

    /** Tile assignment resizes Chromium in place; fixed-size video/VNC backends are recreated. */
    private static void refreshAssignedCanvases(UUID groupId) {
        ScreenGroup parent = GROUPS.get(groupId);
        if (parent == null) {
            closeSession(groupId);
            REGION_SESSIONS.keySet().stream().filter(key -> key.groupId().equals(groupId))
                    .toList().forEach(ScreenContentManager::closeRegionSession);
            return;
        }
        ScreenContentSession primary = SESSIONS.get(groupId);
        ScreenRegionLayout.Canvas primaryCanvas = ScreenRegionLayout.canvas(parent,
                profile(groupId), 0);
        if (primaryCanvas == null) {
            closeSession(groupId);
        } else if (primary instanceof dev.minescreen.client.web.BrowserSession) {
            primary.resize(contentCanvasGroup(parent, primaryCanvas.group()));
        } else if (primary != null) {
            closeSession(groupId);
        }
        for (Map.Entry<RegionSessionKey, ScreenContentSession> entry
                : List.copyOf(REGION_SESSIONS.entrySet())) {
            if (!entry.getKey().groupId().equals(groupId)) {
                continue;
            }
            ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(parent, profile(groupId),
                    entry.getKey().regionId());
            if (canvas == null) {
                closeRegionSession(entry.getKey());
            } else if (entry.getValue() instanceof dev.minescreen.client.web.BrowserSession) {
                entry.getValue().resize(contentCanvasGroup(parent, canvas.group()));
            } else {
                closeRegionSession(entry.getKey());
            }
        }
    }

    private record RegionSessionKey(UUID groupId, int regionId) {
    }

    private record PanoramaSession(long signature, UUID rootGroupId,
            ScreenContentSession session) {
    }

    public record PanoramaRender(ScreenRenderSource source,
            ScreenHostNetworkManager.Surface surface,
            ScreenHostNetworkManager.HostNetwork network) {
    }

    private static final class FailedContentSession implements ScreenContentSession {
        private final String message;
        private final long retryAtNanos;
        private boolean reported;

        private FailedContentSession(String message) {
            this.message = message == null ? "Content failed" : message;
            retryAtNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10L);
        }

        private boolean retryReady() {
            return System.nanoTime() - retryAtNanos >= 0L;
        }

        @Override
        public ScreenContentType type() {
            return ScreenContentType.IDLE;
        }

        @Override
        public ScreenRenderSource renderSource() {
            return ScreenTextureManager.idleRenderSource();
        }

        @Override
        public void tick(ScreenGroup group) {
            if (reported) {
                return;
            }
            reported = true;
            long now = System.nanoTime();
            long nextNotice = FAILURE_NOTICE_UNTIL.getOrDefault(group.groupId(), 0L);
            if (now < nextNotice) {
                return;
            }
            FAILURE_NOTICE_UNTIL.put(group.groupId(), now
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(60L));
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "screen.minescreen.content_failed", message), false);
            }
        }

        @Override
        public void close() {
        }

        @Override
        public String errorMessage() {
            return message;
        }

        @Override
        public String toString() {
            return message;
        }
    }
}
