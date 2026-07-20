package dev.minescreen.network;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Optional stage-5 metadata channel. All registered payloads are state-only, never frame data. */
public final class MineScreenNetwork {
    public static final String PROTOCOL_VERSION = "1";
    private static final Set<String> CONTENT_TYPES = Set.of("IDLE", "VIDEO", "WEB", "VNC");
    private static final Map<NavigationKey, Long> LAST_WEB_NAVIGATION = new HashMap<>();

    private MineScreenNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(ScreenStateUpdatePayload.TYPE, ScreenStateUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleStateUpdate);
        registrar.playToClient(ScreenStatePayload.TYPE, ScreenStatePayload.STREAM_CODEC,
                MineScreenNetwork::handleState);
        registrar.playToServer(ScreenControlRequestPayload.TYPE, ScreenControlRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleControl);
        registrar.playToServer(ScreenStateRequestPayload.TYPE, ScreenStateRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleStateRequest);
        registrar.playToServer(WebNavigationPayload.TYPE, WebNavigationPayload.STREAM_CODEC,
                MineScreenNetwork::handleWebNavigation);
        registrar.playToServer(WebPeerAnnouncePayload.TYPE, WebPeerAnnouncePayload.STREAM_CODEC,
                MineScreenNetwork::handleWebPeerAnnounce);
        registrar.playToClient(WebPeerDirectoryPayload.TYPE, WebPeerDirectoryPayload.STREAM_CODEC,
                MineScreenNetwork::handleWebPeerDirectory);
    }

    private static void handleStateUpdate(ScreenStateUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        dev.minescreen.ScreenGroup actualGroup = ServerScreenGroupResolver.resolve(level, payload.master());
        if (!level.dimension().location().equals(payload.dimension())
                || player.distanceToSqr(payload.master().getX() + 0.5D, payload.master().getY() + 0.5D,
                        payload.master().getZ() + 0.5D) > 256.0D
                || actualGroup == null || !actualGroup.groupId().equals(payload.screenId())
                || !actualGroup.master().equals(payload.master())
                || actualGroup.columns() != payload.columns() || actualGroup.rows() != payload.rows()
                || payload.columns() > 128 || payload.rows() > 128
                || !CONTENT_TYPES.contains(payload.contentType())
                || !validSourceReference(payload.contentType(), payload.sourceReference())) {
            return;
        }
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        if (state == null) {
            state = new ServerScreenStateData.State(payload.screenId(), payload.dimension(), payload.master(),
                    payload.columns(), payload.rows(), payload.contentType(), payload.sourceReference(),
                    Math.max(0L, payload.positionMs()), level.getGameTime(), payload.paused(), payload.loop(),
                    clampVolume(payload.volume()), payload.access(), player.getUUID());
        } else {
            if (!state.owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                return;
            }
            state.master = payload.master().immutable();
            state.columns = payload.columns();
            state.rows = payload.rows();
            state.contentType = payload.contentType();
            state.sourceReference = payload.sourceReference();
            state.positionMs = Math.max(0L, payload.positionMs());
            state.updatedGameTime = level.getGameTime();
            state.paused = payload.paused();
            state.loop = payload.loop();
            state.volume = clampVolume(payload.volume());
            state.access = payload.access();
        }
        data.put(state);
        sendStateNear(level, state);
    }

    private static void handleWebNavigation(WebNavigationPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || !"WEB".equals(state.contentType)
                || !level.dimension().location().equals(payload.dimension())
                || !state.master.equals(payload.master())
                || actualGroup == null || !actualGroup.groupId().equals(state.screenId)
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                        state.master.getZ() + 0.5D) > 256.0D
                || !mayNavigate(player, state) || !validSourceReference("WEB", payload.url())) {
            return;
        }
        long gameTime = level.getGameTime();
        NavigationKey key = new NavigationKey(player.getUUID(), state.screenId);
        Long previousTick = LAST_WEB_NAVIGATION.get(key);
        // Server-side abuse guard. The client already waits for a URL to remain stable, but an
        // untrusted client cannot force a broadcast more than once per four server ticks.
        if (previousTick != null && gameTime - previousTick < 4L) {
            return;
        }
        LAST_WEB_NAVIGATION.put(key, gameTime);
        if (payload.url().equals(state.sourceReference)) {
            return;
        }
        state.sourceReference = payload.url();
        data.put(state);
        sendStateNear(level, state);
    }

    private static void handleWebPeerAnnounce(WebPeerAnnouncePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData.State state = ServerScreenStateData.get(level).get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || !"WEB".equals(state.contentType)
                || !level.dimension().location().equals(payload.dimension())
                || !state.master.equals(payload.master())
                || actualGroup == null || !actualGroup.groupId().equals(payload.screenId())
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                        state.master.getZ() + 0.5D) > synchronizationRadius() * synchronizationRadius()) {
            return;
        }
        WebPeerRegistry.announce(player, payload, state);
    }

    private static void handleWebPeerDirectory(WebPeerDirectoryPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.web.WebPeerService.acceptDirectory(payload);
        }
    }

    private static void handleControl(ScreenControlRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || actualGroup == null || !actualGroup.groupId().equals(state.screenId)
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                state.master.getZ() + 0.5D) > 256.0D) {
            return;
        }
        // Protocol-compatible retirement of the old exclusive lease. New clients never request a
        // lease, and any request from an older client clears stale ownership instead of locking out
        // other players. Each client sends input directly to its own browser/RFB connection.
        if (state.controller != null) {
            state.controller = null;
            data.setDirty();
        }
        sendStateNear(level, state);
    }

    private static void handleStateRequest(ScreenStateRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        double radius = synchronizationRadius();
        for (ServerScreenStateData.State state : ServerScreenStateData.get(level).states()) {
            if (player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                    state.master.getZ() + 0.5D) <= radius * radius) {
                context.reply(state.payload(level.getGameTime()));
            }
        }
    }

    private static void handleState(ScreenStatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.network.ClientNetworkState.accept(payload);
        }
    }

    private static boolean validSourceReference(String contentType, String value) {
        if (value == null || value.length() > 2048 || value.indexOf('\0') >= 0) {
            return false;
        }
        if (contentType.equals("IDLE")) {
            return value.isEmpty();
        }
        if (contentType.equals("VIDEO")) {
            // A video reference is a media id, never a client filesystem path.
            return value.isEmpty() || value.matches("[A-Za-z0-9._-]{1,128}");
        }
        try {
            URI uri = URI.create(value.contains("://") ? value : "vnc://" + value);
            if (uri.getUserInfo() != null || uri.getHost() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (contentType.equals("WEB")) {
                return scheme.equals("https") || scheme.equals("http") && MineScreenConfig.ALLOW_HTTP.get();
            }
            return contentType.equals("VNC") && scheme.equals("vnc");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static float clampVolume(float volume) {
        return Float.isFinite(volume) ? Math.max(0.0F, Math.min(1.0F, volume)) : 1.0F;
    }

    static void sendStateNear(ServerLevel level, ServerScreenStateData.State state) {
        PacketDistributor.sendToPlayersNear(level, null, state.master.getX() + 0.5D,
                state.master.getY() + 0.5D, state.master.getZ() + 0.5D,
                synchronizationRadius(), state.payload(level.getGameTime()));
    }

    static void releasePlayer(java.util.UUID playerId) {
        LAST_WEB_NAVIGATION.keySet().removeIf(key -> key.player().equals(playerId));
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            WebPeerRegistry.removePlayer(server, playerId);
        }
    }

    private static double synchronizationRadius() {
        return Math.max(32.0D, MineScreenConfig.MAX_RENDER_DISTANCE.get() + 16.0D);
    }

    private static boolean mayNavigate(ServerPlayer player, ServerScreenStateData.State state) {
        if (state.owner.equals(player.getUUID()) || player.hasPermissions(2)) {
            return true;
        }
        return state.access == ScreenAccess.ANYONE;
    }

    private record NavigationKey(java.util.UUID player, java.util.UUID screen) {
    }
}
