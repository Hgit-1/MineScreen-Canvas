package dev.minescreen.client.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import dev.minescreen.network.MovingCeilingDisplayStatePayload;
import dev.minescreen.network.MovingTrafficDisplayUpdatePayload;

/**
 * Optional moving-structure API entry point.
 *
 * <p>This class deliberately has no Create references. The Create-backed implementation is loaded
 * by name only when NeoForge reports that Create is present, preventing absent optional classes
 * from being resolved on normal clients or dedicated servers.</p>
 */
public final class MovingStructureCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineScreen/CreateCompat");
    private static final Bridge BRIDGE = loadBridge();

    private MovingStructureCompat() {
    }

    public static Placement locate(Level virtualLevel, AABB localBounds, float partialTick) {
        return BRIDGE.locate(virtualLevel, localBounds, partialTick);
    }

    public static Vec3 toWorld(Level virtualLevel, Vec3 localPosition, float partialTick) {
        return BRIDGE.toWorld(virtualLevel, localPosition, partialTick);
    }

    public static Vec3 toLocal(Level virtualLevel, Vec3 worldPosition, float partialTick) {
        return BRIDGE.toLocal(virtualLevel, worldPosition, partialTick);
    }

    /** Adds render-only light relays to a Create virtual carriage; never mutates the real Level. */
    public static void illuminateTestPanel(Level virtualLevel, BlockPos localPosition,
            Direction facing) {
        BRIDGE.illuminateTestPanel(virtualLevel, localPosition, facing);
    }

    /** Live route state of the Create carriage that owns this render-only Level. */
    public static CarriageTrainStatus carriageTrainStatus(Level virtualLevel) {
        return BRIDGE.carriageTrainStatus(virtualLevel, "");
    }

    public static CarriageTrainStatus carriageTrainStatus(Level virtualLevel,
            String turnaroundStation) {
        return BRIDGE.carriageTrainStatus(virtualLevel, turnaroundStation);
    }

    /** Returns a local block hit when the crosshair is pointing at a carriage display. */
    public static MovingDisplayHit findDisplayHit() {
        return BRIDGE.findDisplayHit();
    }

    /** Applies a server-authoritative update to an already rendered carriage. */
    public static void applyDisplayState(MovingCeilingDisplayStatePayload payload) {
        BRIDGE.applyDisplayState(payload);
    }

    public static void applyTrafficDisplayState(MovingTrafficDisplayUpdatePayload payload) {
        BRIDGE.applyTrafficDisplayState(payload);
    }

    private static Bridge loadBridge() {
        if (!ModList.get().isLoaded("create")) {
            return Bridge.NONE;
        }
        try {
            Class<?> implementation = Class.forName(
                    "dev.minescreen.client.compat.create.CreateMovingStructureBridge");
            Bridge bridge = (Bridge) implementation.getDeclaredConstructor().newInstance();
            LOGGER.info("Enabled Create carriage spatial integration");
            return bridge;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.error("Create is installed but MineScreen could not initialize its carriage "
                    + "spatial integration", exception);
            return Bridge.NONE;
        }
    }

    public interface Bridge {
        Bridge NONE = new Bridge() {
            @Override
            public Placement locate(Level virtualLevel, AABB localBounds, float partialTick) {
                return null;
            }

            @Override
            public Vec3 toWorld(Level virtualLevel, Vec3 localPosition, float partialTick) {
                return null;
            }

            @Override
            public Vec3 toLocal(Level virtualLevel, Vec3 worldPosition, float partialTick) {
                return null;
            }

            @Override
            public void illuminateTestPanel(Level virtualLevel, BlockPos localPosition,
                    Direction facing) {
            }

            @Override
            public CarriageTrainStatus carriageTrainStatus(Level virtualLevel,
                    String turnaroundStation) {
                return null;
            }

            @Override
            public MovingDisplayHit findDisplayHit() {
                return null;
            }

            @Override
            public void applyDisplayState(MovingCeilingDisplayStatePayload payload) {
            }

            @Override
            public void applyTrafficDisplayState(MovingTrafficDisplayUpdatePayload payload) {
            }
        };

        Placement locate(Level virtualLevel, AABB localBounds, float partialTick);

        Vec3 toWorld(Level virtualLevel, Vec3 localPosition, float partialTick);

        Vec3 toLocal(Level virtualLevel, Vec3 worldPosition, float partialTick);

        void illuminateTestPanel(Level virtualLevel, BlockPos localPosition, Direction facing);

        CarriageTrainStatus carriageTrainStatus(Level virtualLevel, String turnaroundStation);

        MovingDisplayHit findDisplayHit();

        void applyDisplayState(MovingCeilingDisplayStatePayload payload);

        void applyTrafficDisplayState(MovingTrafficDisplayUpdatePayload payload);
    }

    public record Placement(ResourceKey<Level> dimension, AABB bounds, Vec3 center) {
    }

    public record MovingDisplayHit(Level virtualLevel, int entityId, BlockPos localPos,
            Direction direction, Vec3 localLocation, BlockEntity blockEntity) {
    }

    public enum ArrivalPhase {
        NO_ROUTE,
        EN_ROUTE,
        WAITING_SIGNAL,
        APPROACHING,
        ARRIVED
    }

    public record CarriageTrainStatus(String trainName, String serviceType, String stationName,
            String terminalStation, int carriageCount, int carriageNumber,
            List<String> upcomingStops, long etaTicks, double distance, ArrivalPhase phase,
            long dwellTicks, boolean schedulePresent) {
        public CarriageTrainStatus(String trainName, String stationName, long etaTicks,
                double distance, ArrivalPhase phase) {
            this(trainName, "", stationName, "", 0, 0, List.of(), etaTicks, distance, phase,
                    -1L, false);
        }

        public CarriageTrainStatus {
            trainName = trainName == null ? "" : trainName;
            serviceType = serviceType == null ? "" : serviceType;
            stationName = stationName == null ? "" : stationName;
            terminalStation = terminalStation == null ? "" : terminalStation;
            carriageCount = Math.max(0, carriageCount);
            carriageNumber = Math.max(0, Math.min(carriageCount, carriageNumber));
            upcomingStops = upcomingStops == null ? List.of() : List.copyOf(upcomingStops);
            distance = Double.isFinite(distance) ? Math.max(0.0D, distance) : Double.NaN;
            phase = phase == null ? ArrivalPhase.NO_ROUTE : phase;
        }

        public boolean etaKnown() {
            return etaTicks >= 0L && etaTicks < Long.MAX_VALUE / 8L;
        }
        public boolean dwellKnown() { return dwellTicks >= 0L; }
    }
}
