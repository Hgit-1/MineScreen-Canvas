package dev.minescreen.client.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenContentManager;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.network.ScreenStatePayload;
import dev.minescreen.network.ScreenStateRequestPayload;
import dev.minescreen.network.ScreenStateUpdatePayload;
import dev.minescreen.network.WebNavigationPayload;
import dev.minescreen.network.WebPeerAnnouncePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Client cache for authoritative metadata. Screen input itself remains client-to-source. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworkState {
    private static final Map<UUID, ScreenStatePayload> STATES = new HashMap<>();
    private static ResourceKey<Level> lastDimension;

    private ClientNetworkState() {
    }

    public static void accept(ScreenStatePayload payload) {
        ScreenStatePayload previous = STATES.put(payload.screenId(), payload);
        if (previous != null && isWebNavigationOnly(previous, payload)) {
            ScreenContentManager.applyRemoteWebNavigation(payload.screenId(), payload.sourceReference());
        } else if (previous == null && sameLocalContent(payload)) {
            ScreenContentManager.applyRemotePlaybackState(payload);
        } else if (previous == null || !sameContent(previous, payload)) {
            ScreenContentManager.onRemoteStateChanged(payload.screenId());
        } else {
            ScreenContentManager.applyRemotePlaybackState(payload);
        }
    }

    public static ScreenStatePayload state(UUID screenId) {
        return STATES.get(screenId);
    }

    public static ClientScreenProfile effectiveProfile(UUID screenId, ClientScreenProfile local) {
        ScreenStatePayload remote = STATES.get(screenId);
        if (remote == null) {
            return local;
        }
        ScreenContentType type;
        try {
            type = ScreenContentType.fromSerialized(remote.contentType());
        } catch (IllegalArgumentException exception) {
            return local;
        }
        // Local MP4 paths never leave the owning client. An empty VIDEO media id therefore keeps
        // that client's local mapping instead of replacing it with an unusable path.
        ClientScreenProfile effective = local.copy();
        if (type == ScreenContentType.VIDEO) {
            boolean locallyMapped = local.contentType == ScreenContentType.VIDEO
                    && local.source != null && !local.source.isBlank()
                    && (remote.sourceReference().isEmpty()
                            || remote.sourceReference().equals(local.mediaId));
            if (!locallyMapped) {
                // A media id intentionally is not interpreted as a filesystem path. Until this
                // client maps that id in its local group profile, retain its local/test source.
                return local;
            }
        }
        effective.contentType = type;
        if (type != ScreenContentType.VIDEO) {
            effective.source = remote.sourceReference();
        }
        effective.paused = remote.paused();
        effective.loop = remote.loop();
        effective.volume = remote.volume();
        effective.access = remote.access();
        Minecraft minecraft = Minecraft.getInstance();
        long elapsed = minecraft.level == null || remote.paused() ? 0L
                : Math.max(0L, minecraft.level.getGameTime() - remote.serverGameTime()) * 50L;
        effective.positionMs = Math.max(0L, remote.positionMs() + elapsed);
        return effective;
    }

    public static void sendProfile(ScreenGroup group, ClientScreenProfile profile) {
        // Integrated single-player profiles are intentionally client-local. This also lets the
        // default unrestricted single-player policy use HTTP/file sources without a server-side
        // multiplayer validation pass rejecting or publishing them.
        if (dev.minescreen.client.ClientSecurityPolicy.unrestrictedSingleplayer()) {
            return;
        }
        if (!hasChannel(ScreenStateUpdatePayload.TYPE.id())) {
            return;
        }
        ScreenContentSession session = ScreenContentManager.session(group.groupId());
        long position = session == null ? profile.positionMs : session.positionMs();
        String reference = switch (profile.contentType) {
            case WEB, VNC -> profile.source == null ? "" : profile.source;
            case VIDEO -> profile.mediaId == null ? "" : profile.mediaId;
            case IDLE -> "";
        };
        PacketDistributor.sendToServer(new ScreenStateUpdatePayload(group.groupId(),
                group.dimension().location(), group.master(), group.columns(), group.rows(),
                profile.contentType.name(), reference, Math.max(0L, position), profile.paused,
                profile.loop, profile.volume, profile.access));
    }

    public static boolean hasServer() {
        return hasChannel(ScreenStatePayload.TYPE.id());
    }

    /** Sends one compact URL update after the browser-side stability debounce. */
    public static void sendWebNavigation(ScreenGroup group, String url) {
        if (dev.minescreen.client.ClientSecurityPolicy.unrestrictedSingleplayer()
                || url == null || url.isBlank() || !hasChannel(WebNavigationPayload.TYPE.id())) {
            return;
        }
        PacketDistributor.sendToServer(new WebNavigationPayload(group.groupId(),
                group.dimension().location(), group.master(), url));
    }

    public static void sendWebPeerAnnouncement(ScreenGroup group, int listenPort, boolean active) {
        if (dev.minescreen.client.ClientSecurityPolicy.unrestrictedSingleplayer()
                || !MineScreenConfig.WEB_PEER_DISTRIBUTION.get()
                || !hasChannel(WebPeerAnnouncePayload.TYPE.id())) {
            return;
        }
        PacketDistributor.sendToServer(new WebPeerAnnouncePayload(group.groupId(),
                group.dimension().location(), group.master(), listenPort, active));
    }

    private static boolean hasChannel(net.minecraft.resources.ResourceLocation id) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && NetworkRegistry.hasChannel(listener, id);
    }

    private static boolean sameContent(ScreenStatePayload first, ScreenStatePayload second) {
        return first.dimension().equals(second.dimension()) && first.master().equals(second.master())
                && first.columns() == second.columns() && first.rows() == second.rows()
                && first.contentType().equals(second.contentType())
                && first.sourceReference().equals(second.sourceReference())
                && first.loop() == second.loop();
    }

    private static boolean sameLocalContent(ScreenStatePayload remote) {
        ClientScreenProfile local = ScreenContentManager.profile(remote.screenId());
        if (local.contentType == null || !local.contentType.name().equals(remote.contentType())) {
            return false;
        }
        String reference = switch (local.contentType) {
            case WEB, VNC -> local.source == null ? "" : local.source;
            case VIDEO -> local.mediaId == null ? "" : local.mediaId;
            case IDLE -> "";
        };
        return reference.equals(remote.sourceReference()) && local.loop == remote.loop();
    }

    private static boolean isWebNavigationOnly(ScreenStatePayload first, ScreenStatePayload second) {
        return first.dimension().equals(second.dimension()) && first.master().equals(second.master())
                && first.columns() == second.columns() && first.rows() == second.rows()
                && first.contentType().equals("WEB") && second.contentType().equals("WEB")
                && !first.sourceReference().equals(second.sourceReference())
                && first.loop() == second.loop();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (hasChannel(ScreenStateRequestPayload.TYPE.id())) {
            PacketDistributor.sendToServer(ScreenStateRequestPayload.INSTANCE);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        dev.minescreen.client.web.WebPeerService.shutdown();
        STATES.clear();
        lastDimension = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.dimension().equals(lastDimension)) {
            return;
        }
        lastDimension = minecraft.level.dimension();
        STATES.entrySet().removeIf(entry -> !entry.getValue().dimension()
                .equals(lastDimension.location()));
        if (hasChannel(ScreenStateRequestPayload.TYPE.id())) {
            PacketDistributor.sendToServer(ScreenStateRequestPayload.INSTANCE);
        }
    }
}
