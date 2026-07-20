package dev.minescreen.network;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.minescreen.MineScreenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side P2P signalling directory. It distributes observed endpoints and an unpredictable
 * token only; compressed WEB frames use a separate client-to-client socket.
 */
final class WebPeerRegistry {
    private static final int MAX_PEERS = 32;
    private static final int EXPIRY_TICKS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, Group> GROUPS = new HashMap<>();

    private WebPeerRegistry() {
    }

    static void announce(ServerPlayer player, WebPeerAnnouncePayload payload,
            ServerScreenStateData.State state) {
        if (!MineScreenConfig.WEB_PEER_DISTRIBUTION.get()) {
            return;
        }
        if (!payload.active()) {
            remove(player.getUUID(), payload.screenId());
            return;
        }
        if (payload.listenPort() < 1 || payload.listenPort() > 65535) {
            return;
        }
        String host = observedHost(player.connection.getRemoteAddress());
        if (host == null) {
            return;
        }
        Group group = GROUPS.computeIfAbsent(payload.screenId(), ignored -> new Group(randomToken()));
        if (group.peers.size() >= MAX_PEERS && !group.peers.containsKey(player.getUUID())) {
            return;
        }
        group.peers.put(player.getUUID(), new Entry(player.getUUID(), host, payload.listenPort(),
                player.server.getTickCount()));
        sendDirectory(player.server, payload.screenId(), group);
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        for (Map.Entry<UUID, Group> groupEntry : List.copyOf(GROUPS.entrySet())) {
            Group group = groupEntry.getValue();
            boolean removed = group.peers.values().removeIf(peer ->
                    now - peer.lastSeenTick > EXPIRY_TICKS
                            || server.getPlayerList().getPlayer(peer.playerId) == null);
            if (group.peers.isEmpty()) {
                GROUPS.remove(groupEntry.getKey());
            } else if (removed) {
                sendDirectory(server, groupEntry.getKey(), group);
            }
        }
    }

    static void removePlayer(MinecraftServer server, UUID playerId) {
        for (Map.Entry<UUID, Group> groupEntry : List.copyOf(GROUPS.entrySet())) {
            Group group = groupEntry.getValue();
            if (group.peers.remove(playerId) == null) {
                continue;
            }
            if (group.peers.isEmpty()) {
                GROUPS.remove(groupEntry.getKey());
            } else {
                sendDirectory(server, groupEntry.getKey(), group);
            }
        }
    }

    private static void remove(UUID playerId, UUID screenId) {
        Group group = GROUPS.get(screenId);
        if (group == null || group.peers.remove(playerId) == null) {
            return;
        }
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (group.peers.isEmpty()) {
            GROUPS.remove(screenId);
        } else if (server != null) {
            sendDirectory(server, screenId, group);
        }
    }

    private static void sendDirectory(MinecraftServer server, UUID screenId, Group group) {
        List<WebPeerDirectoryPayload.Peer> peers = group.peers.values().stream()
                .sorted(Comparator.comparing(Entry::playerId))
                .map(entry -> new WebPeerDirectoryPayload.Peer(entry.playerId, entry.host, entry.port))
                .toList();
        WebPeerDirectoryPayload payload = new WebPeerDirectoryPayload(screenId, group.token, peers);
        for (Entry entry : new ArrayList<>(group.peers.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static String observedHost(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) {
            return null;
        }
        return inet.getAddress() == null ? inet.getHostString()
                : inet.getAddress().getHostAddress();
    }

    private static UUID randomToken() {
        return new UUID(RANDOM.nextLong(), RANDOM.nextLong());
    }

    private static final class Group {
        private final UUID token;
        private final Map<UUID, Entry> peers = new HashMap<>();

        private Group(UUID token) {
            this.token = token;
        }
    }

    private record Entry(UUID playerId, String host, int port, int lastSeenTick) {
    }
}
