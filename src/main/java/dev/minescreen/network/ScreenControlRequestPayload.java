package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ScreenControlRequestPayload(UUID screenId, boolean acquire) implements CustomPacketPayload {
    public static final Type<ScreenControlRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "screen_control_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenControlRequestPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeUUID(payload.screenId);
                buffer.writeBoolean(payload.acquire);
            }, buffer -> new ScreenControlRequestPayload(buffer.readUUID(), buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
