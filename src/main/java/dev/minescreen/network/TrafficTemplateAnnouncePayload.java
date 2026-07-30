package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Announces a bounded compiled traffic template by content hash; it carries no template bytes. */
public record TrafficTemplateAnnouncePayload(String templateId, String sha256, int size)
        implements CustomPacketPayload {
    public static final Type<TrafficTemplateAnnouncePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "traffic_template_announce"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrafficTemplateAnnouncePayload> STREAM_CODEC =
            StreamCodec.of(TrafficTemplateAnnouncePayload::encode,
                    TrafficTemplateAnnouncePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer,
            TrafficTemplateAnnouncePayload payload) {
        buffer.writeUtf(payload.templateId, 64);
        buffer.writeUtf(payload.sha256, 64);
        buffer.writeVarInt(payload.size);
    }

    private static TrafficTemplateAnnouncePayload decode(RegistryFriendlyByteBuf buffer) {
        return new TrafficTemplateAnnouncePayload(buffer.readUtf(64), buffer.readUtf(64),
                buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
