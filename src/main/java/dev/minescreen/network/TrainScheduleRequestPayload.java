package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client request for a compact server-authored Create departure snapshot. */
public record TrainScheduleRequestPayload(ResourceLocation dimension, BlockPos stationPos,
        String trainType, String turnaroundStation, int maximum) implements CustomPacketPayload {
    public static final Type<TrainScheduleRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "train_schedule_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainScheduleRequestPayload> STREAM_CODEC =
            StreamCodec.of(TrainScheduleRequestPayload::encode, TrainScheduleRequestPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, TrainScheduleRequestPayload value) {
        buffer.writeResourceLocation(value.dimension);
        buffer.writeBlockPos(value.stationPos);
        buffer.writeUtf(value.trainType == null ? "" : value.trainType, 96);
        buffer.writeUtf(value.turnaroundStation == null ? "" : value.turnaroundStation, 128);
        buffer.writeVarInt(Math.max(1, Math.min(8, value.maximum)));
    }

    private static TrainScheduleRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        return new TrainScheduleRequestPayload(buffer.readResourceLocation(), buffer.readBlockPos(),
                buffer.readUtf(96), buffer.readUtf(128),
                Math.max(1, Math.min(8, buffer.readVarInt())));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
