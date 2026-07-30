package dev.minescreen.compat.create;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.SegmentRuntimeStatistics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-persistent Create segment history.
 *
 * <p>Only compact timing samples are stored. Framebuffers, media data and player information are
 * never included. The data lives in the overworld data storage so a multi-dimension railway has a
 * single model.</p>
 */
final class CreateTrainSegmentRuntimeData extends SavedData {
    private static final String DATA_NAME = "minescreen_create_segment_runtimes";
    private static final Factory<CreateTrainSegmentRuntimeData> FACTORY =
            new Factory<>(CreateTrainSegmentRuntimeData::new,
                    CreateTrainSegmentRuntimeData::load);

    private final Map<RouteKey, RouteSamples> routes = new HashMap<>();

    static CreateTrainSegmentRuntimeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    long estimate(RouteKey key) {
        int minimum = MineScreenConfig.CREATE_ETA_MIN_SAMPLES.get();
        RouteSamples exact = routes.get(key);
        long result = exact == null ? -1L
                : SegmentRuntimeStatistics.robustEstimate(exact.runningTicks(), minimum);
        if (result > 0L) return result;
        if (exact != null) return -1L;

        // A new service type may not yet have enough runs. Fall back to the same physical,
        // directional segment across service types before reverting to Create's own prediction.
        List<Long> physical = new ArrayList<>();
        for (Map.Entry<RouteKey, RouteSamples> entry : routes.entrySet()) {
            if (entry.getKey().samePhysicalSegment(key)) {
                physical.addAll(entry.getValue().runningTicks());
            }
        }
        return SegmentRuntimeStatistics.robustEstimate(physical, minimum);
    }

    void record(RouteKey key, String fromName, String toName, long runningTicks,
            long signalTicks, long gameTime) {
        if (runningTicks <= 0L || runningTicks > 24_000L || signalTicks < 0L) return;
        RouteSamples samples = routes.computeIfAbsent(key, ignored -> new RouteSamples());
        samples.fromName = safe(fromName);
        samples.toName = safe(toName);
        samples.lastUpdated = gameTime;
        int window = MineScreenConfig.CREATE_ETA_SAMPLE_WINDOW.get();
        samples.samples.addLast(new Sample(runningTicks, Math.min(signalTicks, 24_000L)));
        while (samples.samples.size() > window) samples.samples.removeFirst();
        prune();
        setDirty();
    }

    private void prune() {
        int maximum = MineScreenConfig.CREATE_ETA_MAX_SEGMENTS.get();
        if (routes.size() <= maximum) return;
        int remove = routes.size() - maximum;
        for (RouteKey key : routes.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastUpdated))
                .limit(remove).map(Map.Entry::getKey).toList()) {
            routes.remove(key);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<RouteKey, RouteSamples> route : routes.entrySet()) {
            CompoundTag entry = new CompoundTag();
            RouteKey key = route.getKey();
            RouteSamples samples = route.getValue();
            entry.putUUID("graph", key.graphId());
            entry.putUUID("from", key.fromStationId());
            entry.putUUID("to", key.toStationId());
            entry.putBoolean("backwards", key.backwards());
            entry.putString("service", key.serviceType());
            entry.putString("from_name", samples.fromName);
            entry.putString("to_name", samples.toName);
            entry.putLong("updated", samples.lastUpdated);
            long[] running = new long[samples.samples.size()];
            long[] signal = new long[samples.samples.size()];
            int index = 0;
            for (Sample sample : samples.samples) {
                running[index] = sample.runningTicks();
                signal[index] = sample.signalTicks();
                index++;
            }
            entry.putLongArray("running", running);
            entry.putLongArray("signal", signal);
            list.add(entry);
        }
        tag.put("routes", list);
        return tag;
    }

    private static CreateTrainSegmentRuntimeData load(CompoundTag tag,
            HolderLookup.Provider registries) {
        CreateTrainSegmentRuntimeData data = new CreateTrainSegmentRuntimeData();
        ListTag list = tag.getList("routes", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            CompoundTag entry = (CompoundTag) raw;
            try {
                RouteKey key = new RouteKey(entry.getUUID("graph"), entry.getUUID("from"),
                        entry.getUUID("to"), entry.getBoolean("backwards"),
                        entry.getString("service"));
                RouteSamples samples = new RouteSamples();
                samples.fromName = entry.getString("from_name");
                samples.toName = entry.getString("to_name");
                samples.lastUpdated = entry.getLong("updated");
                long[] running = entry.getLongArray("running");
                long[] signal = entry.getLongArray("signal");
                int count = Math.min(running.length,
                        MineScreenConfig.CREATE_ETA_SAMPLE_WINDOW.get());
                int start = Math.max(0, running.length - count);
                for (int index = start; index < running.length; index++) {
                    long runtime = running[index];
                    if (runtime <= 0L || runtime > 24_000L) continue;
                    long wait = index < signal.length ? Math.max(0L, signal[index]) : 0L;
                    samples.samples.addLast(new Sample(runtime, Math.min(wait, 24_000L)));
                }
                if (!samples.samples.isEmpty()) data.routes.put(key, samples);
            } catch (RuntimeException ignored) {
                // One corrupt route entry must not discard all learned ETA data.
            }
        }
        data.prune();
        return data;
    }

    record RouteKey(UUID graphId, UUID fromStationId, UUID toStationId, boolean backwards,
            String serviceType) {
        RouteKey {
            graphId = graphId == null ? new UUID(0L, 0L) : graphId;
            fromStationId = fromStationId == null ? new UUID(0L, 0L) : fromStationId;
            toStationId = toStationId == null ? new UUID(0L, 0L) : toStationId;
            serviceType = safe(serviceType).trim().toLowerCase(Locale.ROOT);
        }

        boolean samePhysicalSegment(RouteKey other) {
            return graphId.equals(other.graphId)
                    && fromStationId.equals(other.fromStationId)
                    && toStationId.equals(other.toStationId)
                    && backwards == other.backwards;
        }
    }

    private record Sample(long runningTicks, long signalTicks) {
    }

    private static final class RouteSamples {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private String fromName = "";
        private String toName = "";
        private long lastUpdated;

        private List<Long> runningTicks() {
            return samples.stream().map(Sample::runningTicks).toList();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
