package dev.minescreen.client.compat;

import dev.minescreen.client.traffic.CreateTrainScheduleService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Optional Create timetable boundary; no Create class is resolved without the mod installed. */
public final class CreateTrainScheduleCompat {
    private static final Bridge BRIDGE = loadBridge();

    private CreateTrainScheduleCompat() {
    }

    public static CreateTrainScheduleService.Snapshot snapshot(Level level, BlockPos stationPos,
            String trainType, String turnaroundStation, int maximum) {
        return BRIDGE.snapshot(level, stationPos, trainType, turnaroundStation, maximum);
    }

    private static Bridge loadBridge() {
        if (!ModList.get().isLoaded("create")) return Bridge.NONE;
        try {
            return (Bridge) Class.forName(
                    "dev.minescreen.client.compat.create.CreateTrainScheduleBridge")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return Bridge.NONE;
        }
    }

    public interface Bridge {
        Bridge NONE = (level, stationPos, trainType, turnaroundStation, maximum)
                -> CreateTrainScheduleService.Snapshot.EMPTY;

        CreateTrainScheduleService.Snapshot snapshot(Level level, BlockPos stationPos,
                String trainType, String turnaroundStation, int maximum);
    }
}
