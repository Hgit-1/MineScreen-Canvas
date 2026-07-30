package dev.minescreen.network;

import java.util.Arrays;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded chunk of a compiled declarative traffic template. */
public record TrafficTemplateChunkPayload(String templateId, String sha256, int totalSize,
        int index, int totalChunks, byte[] data) implements CustomPacketPayload {
    public static final Type<TrafficTemplateChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "traffic_template_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrafficTemplateChunkPayload> STREAM_CODEC =
            StreamCodec.of(TrafficTemplateChunkPayload::encode, TrafficTemplateChunkPayload::decode);

    public TrafficTemplateChunkPayload {
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, TrafficTemplateChunkPayload payload) {
        buffer.writeUtf(payload.templateId, 64);
        buffer.writeUtf(payload.sha256, 64);
        buffer.writeVarInt(payload.totalSize);
        buffer.writeVarInt(payload.index);
        buffer.writeVarInt(payload.totalChunks);
        buffer.writeByteArray(payload.data);
    }

    private static TrafficTemplateChunkPayload decode(RegistryFriendlyByteBuf buffer) {
        return new TrafficTemplateChunkPayload(buffer.readUtf(64), buffer.readUtf(64),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(TrafficTemplateManifest.CHUNK_BYTES));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
