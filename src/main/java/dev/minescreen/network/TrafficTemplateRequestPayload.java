package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests the bytes for one previously announced template hash. */
public record TrafficTemplateRequestPayload(String templateId, String sha256)
        implements CustomPacketPayload {
    public static final Type<TrafficTemplateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "traffic_template_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrafficTemplateRequestPayload> STREAM_CODEC =
            StreamCodec.of(TrafficTemplateRequestPayload::encode,
                    TrafficTemplateRequestPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer,
            TrafficTemplateRequestPayload payload) {
        buffer.writeUtf(payload.templateId, 64);
        buffer.writeUtf(payload.sha256, 64);
    }

    private static TrafficTemplateRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        return new TrafficTemplateRequestPayload(buffer.readUtf(64), buffer.readUtf(64));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
