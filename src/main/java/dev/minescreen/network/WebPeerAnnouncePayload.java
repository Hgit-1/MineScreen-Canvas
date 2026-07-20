package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers a client's direct WEB-frame listener. This is signalling only: browser pixels never
 * enter the Minecraft connection.
 */
public record WebPeerAnnouncePayload(UUID screenId, ResourceLocation dimension, BlockPos master,
        int listenPort, boolean active) implements CustomPacketPayload {
    public static final Type<WebPeerAnnouncePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "web_peer_announce"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WebPeerAnnouncePayload> STREAM_CODEC =
            StreamCodec.of(WebPeerAnnouncePayload::encode, WebPeerAnnouncePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, WebPeerAnnouncePayload payload) {
        buffer.writeUUID(payload.screenId);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeBlockPos(payload.master);
        buffer.writeVarInt(payload.listenPort);
        buffer.writeBoolean(payload.active);
    }

    private static WebPeerAnnouncePayload decode(RegistryFriendlyByteBuf buffer) {
        return new WebPeerAnnouncePayload(buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readBlockPos(), buffer.readVarInt(), buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
