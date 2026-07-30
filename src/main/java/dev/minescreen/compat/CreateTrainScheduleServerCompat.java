package dev.minescreen.compat;

import java.util.List;
import java.util.UUID;

import dev.minescreen.network.CarriageStatusPayload;
import dev.minescreen.network.MovingCeilingDisplayUpdatePayload;
import dev.minescreen.network.MovingTrafficDisplayUpdatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Optional dedicated/integrated-server boundary for Create timetable prediction. */
public final class CreateTrainScheduleServerCompat {
    private static final Bridge BRIDGE = loadBridge();

    private CreateTrainScheduleServerCompat() {
    }

    public static Snapshot snapshot(ServerLevel level,
            BlockPos stationPos, String trainType, String turnaroundStation, int maximum) {
        return BRIDGE.snapshot(level, stationPos, trainType, turnaroundStation, maximum);
    }

    public static CarriageStatus carriageStatus(ServerLevel level, UUID trainId,
            Vec3 playerPosition, String turnaroundStation) {
        return BRIDGE.carriageStatus(level, trainId, playerPosition, turnaroundStation);
    }

    public static boolean updateMovingDisplay(ServerLevel level, ServerPlayer player,
            MovingCeilingDisplayUpdatePayload payload) {
        return BRIDGE.updateMovingDisplay(level, player, payload);
    }

    public static boolean updateMovingTrafficDisplay(ServerLevel level, ServerPlayer player,
            MovingTrafficDisplayUpdatePayload payload) {
        return BRIDGE.updateMovingTrafficDisplay(level, player, payload);
    }

    public static void tickServer(MinecraftServer server) {
        BRIDGE.tickServer(server);
    }

    private static Bridge loadBridge() {
        if (!ModList.get().isLoaded("create")) return Bridge.NONE;
        try {
            return (Bridge) Class.forName(
                    "dev.minescreen.compat.create.CreateTrainScheduleServerBridge")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return Bridge.NONE;
        }
    }

    public interface Bridge {
        Bridge NONE = new Bridge() {
            @Override
            public Snapshot snapshot(ServerLevel level, BlockPos stationPos, String trainType,
                    String turnaroundStation, int maximum) {
                return Snapshot.EMPTY;
            }

            @Override
            public CarriageStatus carriageStatus(ServerLevel level, UUID trainId,
                    Vec3 playerPosition, String turnaroundStation) {
                return CarriageStatus.EMPTY;
            }

            @Override
            public boolean updateMovingDisplay(ServerLevel level, ServerPlayer player,
                    MovingCeilingDisplayUpdatePayload payload) {
                return false;
            }

            @Override
            public boolean updateMovingTrafficDisplay(ServerLevel level, ServerPlayer player,
                    MovingTrafficDisplayUpdatePayload payload) {
                return false;
            }
        };

        Snapshot snapshot(ServerLevel level, BlockPos stationPos,
                String trainType, String turnaroundStation, int maximum);

        CarriageStatus carriageStatus(ServerLevel level, UUID trainId, Vec3 playerPosition,
                String turnaroundStation);

        boolean updateMovingDisplay(ServerLevel level, ServerPlayer player,
                MovingCeilingDisplayUpdatePayload payload);

        boolean updateMovingTrafficDisplay(ServerLevel level, ServerPlayer player,
                MovingTrafficDisplayUpdatePayload payload);

        default void tickServer(MinecraftServer server) {
        }
    }

    public record Snapshot(String stationName, List<Departure> departures, long gameTime,
            int totalTrains, int activeScheduleTrains, int filterMatchedTrains) {
        public static final Snapshot EMPTY = new Snapshot("", List.of(), 0L, 0, 0, 0);

        public Snapshot {
            stationName = stationName == null ? "" : stationName;
            departures = departures == null ? List.of() : List.copyOf(departures);
        }
    }

    public record Departure(UUID trainId, String trainName, String trainType, String origin,
            String destination, String direction, boolean loop, int carriageCount,
            long etaTicks, long dwellTicks, boolean waitingForSignal) {
        public Departure {
            trainId = trainId == null ? new UUID(0L, 0L) : trainId;
        }
    }

    public record CarriageStatus(UUID trainId, String trainName, String serviceType,
            String stationName, String terminalStation, int carriageCount,
            List<String> upcomingStops, long etaTicks, double distance,
            CarriageStatusPayload.Phase phase, long dwellTicks,
            boolean schedulePresent, long gameTime) {
        public static final CarriageStatus EMPTY = new CarriageStatus(new UUID(0L, 0L), "", "",
                "", "", 0, List.of(), -1L, Double.NaN,
                CarriageStatusPayload.Phase.NO_ROUTE, -1L, false, 0L);

        public CarriageStatus {
            upcomingStops = upcomingStops == null ? List.of() : List.copyOf(upcomingStops);
        }
    }
}
