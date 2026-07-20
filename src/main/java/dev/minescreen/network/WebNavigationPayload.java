package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Debounced active-page navigation only; never contains pixels, DOM, cookies or form data. */
public record WebNavigationPayload(UUID screenId, ResourceLocation dimension, BlockPos master,
        String url) implements CustomPacketPayload {
    public static final Type<WebNavigationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "web_navigation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WebNavigationPayload> STREAM_CODEC =
            StreamCodec.of(WebNavigationPayload::encode, WebNavigationPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, WebNavigationPayload payload) {
        buffer.writeUUID(payload.screenId);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeBlockPos(payload.master);
        buffer.writeUtf(payload.url, 2048);
    }

    private static WebNavigationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new WebNavigationPayload(buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readBlockPos(), buffer.readUtf(2048));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
