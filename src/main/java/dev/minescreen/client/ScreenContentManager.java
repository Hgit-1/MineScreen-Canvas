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
import dev.minescreen.client.web.McefBrowserSession;
import dev.minescreen.client.video.VideoPlaybackSession;
import dev.minescreen.client.vnc.VncScreenSession;
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
    private static final Map<UUID, ScreenGroup> GROUPS = new HashMap<>();
    private static final Map<UUID, Long> KEEP_ALIVE_UNTIL = new HashMap<>();

    private ScreenContentManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            closeAll();
            GROUPS.clear();
            KEEP_ALIVE_UNTIL.clear();
            return;
        }
        for (Map.Entry<UUID, ScreenContentSession> entry : List.copyOf(SESSIONS.entrySet())) {
            ScreenGroup group = GROUPS.get(entry.getKey());
            if (group != null) {
                entry.getValue().tick(group);
            }
        }
    }

    public static void onGroupsChanged(List<ScreenGroup> groups) {
        Map<UUID, ScreenGroup> next = new HashMap<>();
        groups.forEach(group -> next.put(group.groupId(), group));
        Set<UUID> topologyMigrated = migrateProfiles(next.values());

        Set<UUID> removed = new HashSet<>(SESSIONS.keySet());
        removed.removeAll(next.keySet());
        removed.addAll(topologyMigrated);
        for (Map.Entry<UUID, ScreenGroup> entry : next.entrySet()) {
            ScreenGroup previous = GROUPS.get(entry.getKey());
            if (previous != null && !sameTopology(previous, entry.getValue())) {
                // A cached failed/decoder/browser session can otherwise survive a master/origin or
                // irregular-tile change indefinitely. Recreate once per actual topology revision.
                removed.add(entry.getKey());
            }
        }
        removed.forEach(ScreenContentManager::closeSession);

        Map<UUID, ScreenGroup> previousGroups = new HashMap<>(GROUPS);
        GROUPS.clear();
        GROUPS.putAll(next);
        for (Map.Entry<UUID, ScreenContentSession> entry : List.copyOf(SESSIONS.entrySet())) {
            ScreenGroup oldGroup = previousGroups.get(entry.getKey());
            ScreenGroup group = GROUPS.get(entry.getKey());
            if (group != null) {
                if (oldGroup != null) {
                    entry.getValue().resize(group);
                }
            }
        }
    }

    public static ClientScreenProfile profile(UUID groupId) {
        ClientScreenProfile profile = PROFILES.computeIfAbsent(groupId, ignored -> new ClientScreenProfile());
        if (profile.contentType == null) {
            profile.contentType = ScreenContentType.IDLE;
        }
        if (profile.source == null) {
            profile.source = "";
        }
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
        return profile;
    }

    public static void updateProfile(UUID groupId, ClientScreenProfile profile) {
        PROFILES.put(groupId, profile.copy());
        ClientScreenProfileStore.save(PROFILES);
        ScreenGroup group = GROUPS.get(groupId);
        if (group != null) {
            dev.minescreen.client.network.ClientNetworkState.sendProfile(group, profile);
        }
        closeSession(groupId);
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
        PROFILES.put(groupId, profile.copy());
        ClientScreenProfileStore.save(PROFILES);
    }

    public static ScreenRenderSource sourceFor(ScreenGroup group) {
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
            session = createSession(group, profile);
            SESSIONS.put(group.groupId(), session);
        }
        return session.renderSource();
    }

    public static ScreenContentSession session(UUID groupId) {
        return SESSIONS.get(groupId);
    }

    public static String error(UUID groupId) {
        ScreenContentSession session = SESSIONS.get(groupId);
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
    }

    public static void applyRemotePlaybackState(dev.minescreen.network.ScreenStatePayload state) {
        ScreenContentSession session = SESSIONS.get(state.screenId());
        if (session == null || session.type() != ScreenContentType.VIDEO) {
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
        ScreenContentSession session = SESSIONS.get(groupId);
        if (session instanceof dev.minescreen.client.web.BrowserSession browser
                && url != null && !url.isBlank() && !url.equals(browser.currentUrl())) {
            browser.navigate(url);
        }
    }

    private static ScreenContentSession createSession(ScreenGroup group, ClientScreenProfile profile) {
        try {
            return switch (profile.contentType) {
                case VIDEO -> new VideoPlaybackSession(group, profile);
                case WEB -> new McefBrowserSession(group, profile);
                case VNC -> new VncScreenSession(group, profile);
                case IDLE -> null;
            };
        } catch (RuntimeException | LinkageError exception) {
            return new FailedContentSession(exception.getMessage());
        }
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
            session.close();
        }
    }

    private static void closeAll() {
        SESSIONS.values().forEach(ScreenContentSession::close);
        SESSIONS.clear();
    }

    private static final class FailedContentSession implements ScreenContentSession {
        private final String message;
        private final long retryAtNanos;

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
