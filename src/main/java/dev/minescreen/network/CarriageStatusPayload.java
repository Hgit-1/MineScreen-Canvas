package dev.minescreen.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authored passenger information; no full Create schedule is exposed. */
public record CarriageStatusPayload(UUID trainId, String turnaroundStation, String trainName,
        String serviceType,
        String stationName, String terminalStation, int carriageCount,
        List<String> upcomingStops, long etaTicks, double distance, Phase phase,
        long dwellTicks, boolean schedulePresent, long serverGameTime)
        implements CustomPacketPayload {
    private static final int MAX_UPCOMING_STOPS = 8;
    public static final Type<CarriageStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "carriage_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CarriageStatusPayload> STREAM_CODEC =
            StreamCodec.of(CarriageStatusPayload::encode, CarriageStatusPayload::decode);

    public CarriageStatusPayload {
        trainName = limited(trainName, 128);
        turnaroundStation = limited(turnaroundStation, 128);
        serviceType = limited(serviceType, 64);
        stationName = limited(stationName, 128);
        terminalStation = limited(terminalStation, 128);
        carriageCount = Math.max(0, Math.min(128, carriageCount));
        upcomingStops = (upcomingStops == null ? List.<String>of() : upcomingStops).stream()
                .limit(MAX_UPCOMING_STOPS).map(value -> limited(value, 128)).toList();
        etaTicks = Math.max(-1L, etaTicks);
        dwellTicks = Math.max(-1L, dwellTicks);
        phase = phase == null ? Phase.NO_ROUTE : phase;
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CarriageStatusPayload value) {
        buffer.writeUUID(value.trainId);
        buffer.writeUtf(value.turnaroundStation, 128);
        buffer.writeUtf(value.trainName, 128);
        buffer.writeUtf(value.serviceType, 64);
        buffer.writeUtf(value.stationName, 128);
        buffer.writeUtf(value.terminalStation, 128);
        buffer.writeVarInt(value.carriageCount);
        buffer.writeVarInt(value.upcomingStops.size());
        for (String stop : value.upcomingStops) buffer.writeUtf(stop, 128);
        buffer.writeLong(value.etaTicks);
        buffer.writeDouble(value.distance);
        buffer.writeEnum(value.phase);
        buffer.writeLong(value.dwellTicks);
        buffer.writeBoolean(value.schedulePresent);
        buffer.writeVarLong(Math.max(0L, value.serverGameTime));
    }

    private static CarriageStatusPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID trainId = buffer.readUUID();
        String turnaroundStation = buffer.readUtf(128);
        String trainName = buffer.readUtf(128);
        String serviceType = buffer.readUtf(64);
        String stationName = buffer.readUtf(128);
        String terminalStation = buffer.readUtf(128);
        int carriageCount = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_UPCOMING_STOPS) {
            throw new IllegalArgumentException("Invalid upcoming stop count: " + count);
        }
        List<String> upcomingStops = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            upcomingStops.add(buffer.readUtf(128));
        }
        return new CarriageStatusPayload(trainId, turnaroundStation, trainName, serviceType,
                stationName,
                terminalStation, carriageCount, upcomingStops, buffer.readLong(),
                buffer.readDouble(),
                buffer.readEnum(Phase.class), buffer.readLong(),
                buffer.readBoolean(), buffer.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String limited(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    public enum Phase {
        NO_ROUTE,
        EN_ROUTE,
        WAITING_SIGNAL,
        APPROACHING,
        ARRIVED
    }
}
