package dev.minescreen.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Compact Create timetable result. It contains state/ETA only, never schedules or frame data. */
public record TrainSchedulePayload(BlockPos stationPos, String trainType, String turnaroundStation,
        String stationName,
        long serverGameTime, int totalTrains, int activeScheduleTrains, int filterMatchedTrains,
        List<Entry> departures) implements CustomPacketPayload {
    private static final int MAX_DEPARTURES = 8;
    public static final Type<TrainSchedulePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "train_schedule"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainSchedulePayload> STREAM_CODEC =
            StreamCodec.of(TrainSchedulePayload::encode, TrainSchedulePayload::decode);

    public TrainSchedulePayload {
        trainType = limited(trainType, 96);
        turnaroundStation = limited(turnaroundStation, 128);
        stationName = limited(stationName, 128);
        departures = departures == null ? List.of()
                : departures.stream().limit(MAX_DEPARTURES).toList();
    }

    private static void encode(RegistryFriendlyByteBuf buffer, TrainSchedulePayload value) {
        buffer.writeBlockPos(value.stationPos);
        buffer.writeUtf(value.trainType, 96);
        buffer.writeUtf(value.turnaroundStation, 128);
        buffer.writeUtf(value.stationName, 128);
        buffer.writeVarLong(Math.max(0L, value.serverGameTime));
        buffer.writeVarInt(Math.max(0, value.totalTrains));
        buffer.writeVarInt(Math.max(0, value.activeScheduleTrains));
        buffer.writeVarInt(Math.max(0, value.filterMatchedTrains));
        int count = value.departures.size();
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) value.departures.get(index).encode(buffer);
    }

    private static TrainSchedulePayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos stationPos = buffer.readBlockPos();
        String trainType = buffer.readUtf(96);
        String turnaroundStation = buffer.readUtf(128);
        String stationName = buffer.readUtf(128);
        long gameTime = buffer.readVarLong();
        int total = Math.max(0, buffer.readVarInt());
        int active = Math.max(0, buffer.readVarInt());
        int matched = Math.max(0, buffer.readVarInt());
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_DEPARTURES) {
            throw new IllegalArgumentException("Invalid departure count: " + count);
        }
        List<Entry> departures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) departures.add(Entry.decode(buffer));
        return new TrainSchedulePayload(stationPos, trainType, turnaroundStation, stationName,
                gameTime, total, active, matched, departures);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(UUID trainId, String trainName, String trainType, String origin,
            String destination,
            String direction, boolean loop, int carriageCount, long etaTicks,
            long dwellTicks, boolean waitingForSignal) {
        public Entry {
            trainId = trainId == null ? new UUID(0L, 0L) : trainId;
            trainName = limited(trainName, 128);
            trainType = limited(trainType, 96);
            origin = limited(origin, 128);
            destination = limited(destination, 128);
            direction = limited(direction, 8);
            carriageCount = Math.max(0, Math.min(128, carriageCount));
            etaTicks = Math.max(0L, etaTicks);
            dwellTicks = Math.max(-1L, dwellTicks);
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(trainId);
            buffer.writeUtf(trainName, 128);
            buffer.writeUtf(trainType, 96);
            buffer.writeUtf(origin, 128);
            buffer.writeUtf(destination, 128);
            buffer.writeUtf(direction, 8);
            buffer.writeBoolean(loop);
            buffer.writeVarInt(carriageCount);
            buffer.writeVarLong(etaTicks);
            buffer.writeLong(dwellTicks);
            buffer.writeBoolean(waitingForSignal);
        }

        private static Entry decode(RegistryFriendlyByteBuf buffer) {
            return new Entry(buffer.readUUID(), buffer.readUtf(128), buffer.readUtf(96),
                    buffer.readUtf(128),
                    buffer.readUtf(128), buffer.readUtf(8), buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readVarLong(), buffer.readLong(), buffer.readBoolean());
        }

    }

    private static String limited(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }
}
