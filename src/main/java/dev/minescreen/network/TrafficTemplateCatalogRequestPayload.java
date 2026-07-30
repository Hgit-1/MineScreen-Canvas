package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client join/reload request for the server's template hash catalog. */
public record TrafficTemplateCatalogRequestPayload() implements CustomPacketPayload {
    public static final Type<TrafficTemplateCatalogRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                    "traffic_template_catalog_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrafficTemplateCatalogRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new TrafficTemplateCatalogRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
