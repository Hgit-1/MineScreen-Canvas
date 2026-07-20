package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ScreenStateRequestPayload() implements CustomPacketPayload {
    public static final ScreenStateRequestPayload INSTANCE = new ScreenStateRequestPayload();
    public static final Type<ScreenStateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "screen_state_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenStateRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
