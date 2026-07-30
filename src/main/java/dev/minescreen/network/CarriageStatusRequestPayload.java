package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests the passenger-information state of one nearby Create train. */
public record CarriageStatusRequestPayload(UUID trainId, String turnaroundStation)
        implements CustomPacketPayload {
    public static final Type<CarriageStatusRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "carriage_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CarriageStatusRequestPayload> STREAM_CODEC =
            StreamCodec.of((buffer, value) -> {
                buffer.writeUUID(value.trainId);
                buffer.writeUtf(value.turnaroundStation == null ? "" : value.turnaroundStation,
                        128);
            }, buffer -> new CarriageStatusRequestPayload(buffer.readUUID(),
                    buffer.readUtf(128)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
