package dev.minescreen.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Nearby peers and a short-lived group token used to authenticate MineScreen's direct TCP link. */
public record WebPeerDirectoryPayload(UUID screenId, UUID token, List<Peer> peers)
        implements CustomPacketPayload {
    private static final int MAX_PEERS = 32;
    public static final Type<WebPeerDirectoryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "web_peer_directory"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WebPeerDirectoryPayload> STREAM_CODEC =
            StreamCodec.of(WebPeerDirectoryPayload::encode, WebPeerDirectoryPayload::decode);

    public WebPeerDirectoryPayload {
        peers = List.copyOf(peers);
        if (peers.size() > MAX_PEERS) {
            throw new IllegalArgumentException("Too many WEB peers");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, WebPeerDirectoryPayload payload) {
        buffer.writeUUID(payload.screenId);
        buffer.writeUUID(payload.token);
        buffer.writeVarInt(payload.peers.size());
        for (Peer peer : payload.peers) {
            buffer.writeUUID(peer.playerId);
            buffer.writeUtf(peer.host, 255);
            buffer.writeVarInt(peer.port);
        }
    }

    private static WebPeerDirectoryPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID screenId = buffer.readUUID();
        UUID token = buffer.readUUID();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_PEERS) {
            throw new IllegalArgumentException("Invalid WEB peer count: " + count);
        }
        List<Peer> peers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            peers.add(new Peer(buffer.readUUID(), buffer.readUtf(255), buffer.readVarInt()));
        }
        return new WebPeerDirectoryPayload(screenId, token, peers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Peer(UUID playerId, String host, int port) {
        public Peer {
            if (host == null || host.isBlank() || host.length() > 255
                    || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid WEB peer endpoint");
            }
        }
    }
}
