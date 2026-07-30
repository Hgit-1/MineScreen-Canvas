package dev.minescreen.compat.create;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.TrainNameFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Observes completed Create station-to-station runs on the server thread.
 *
 * <p>Acceleration out of the platform, braking into it, curves and schedule/add-on speed limits
 * remain part of the learned runtime. Ticks affected by a reserved red signal are counted
 * separately so a one-off obstruction does not permanently slow every later ETA. Scheduled dwell
 * time is outside the segment and is already handled by the timetable estimator.</p>
 */
final class CreateTrainSegmentLearningService {
    private static final Map<MinecraftServer, TrackerState> STATES = new WeakHashMap<>();

    private CreateTrainSegmentLearningService() {
    }

    static void tick(MinecraftServer server) {
        if (!MineScreenConfig.CREATE_ETA_LEARNING.get()) return;
        ServerLevel level = server.overworld();
        GlobalRailwayManager railways = Create.RAILWAYS == null ? null
                : Create.RAILWAYS.sided(level);
        if (railways == null || railways.trains == null) return;
        TrackerState state = STATES.computeIfAbsent(server, ignored -> new TrackerState());
        CreateTrainSegmentRuntimeData data = CreateTrainSegmentRuntimeData.get(server);
        long gameTime = level.getGameTime();
        Set<UUID> seen = new HashSet<>();
        for (Train train : railways.trains.values()) {
            if (train == null || train.invalid || train.id == null) continue;
            seen.add(train.id);
            observe(data, state.trains.computeIfAbsent(train.id, ignored -> new TrainTracker()),
                    train, gameTime);
        }
        state.trains.entrySet().removeIf(entry -> !seen.contains(entry.getKey())
                && gameTime - entry.getValue().lastSeen > 200L);
    }

    static long estimateCurrent(ServerLevel level, Train train) {
        if (!MineScreenConfig.CREATE_ETA_LEARNING.get() || train == null || train.id == null) {
            return -1L;
        }
        TrackerState state = STATES.get(level.getServer());
        TrainTracker tracker = state == null ? null : state.trains.get(train.id);
        if (tracker == null || tracker.active == null) return -1L;
        return CreateTrainSegmentRuntimeData.get(level.getServer()).estimate(tracker.active.key);
    }

    static long estimateSegment(ServerLevel level, Train train, GlobalStation from,
            GlobalStation to) {
        if (!MineScreenConfig.CREATE_ETA_LEARNING.get()) return -1L;
        CreateTrainSegmentRuntimeData.RouteKey key = key(train, from, to);
        return key == null ? -1L
                : CreateTrainSegmentRuntimeData.get(level.getServer()).estimate(key);
    }

    private static void observe(CreateTrainSegmentRuntimeData data, TrainTracker tracker,
            Train train, long gameTime) {
        tracker.lastSeen = gameTime;
        GlobalStation station = train.getCurrentStation();
        if (station != null && station.id != null) {
            if (tracker.active != null && station.id.equals(tracker.active.key.toStationId())) {
                long elapsed = Math.max(0L, gameTime - tracker.active.startedAt);
                long signal = Math.min(Math.max(0L, tracker.active.signalTicks),
                        Math.max(0L, elapsed - 1L));
                long running = elapsed - signal;
                data.record(tracker.active.key, tracker.active.fromName, station.name,
                        running, signal, gameTime);
            }
            tracker.active = null;
            tracker.lastStationId = station.id;
            tracker.lastStationName = safe(station.name);
            return;
        }

        GlobalStation destination = train.navigation == null ? null
                : train.navigation.destination;
        if (destination == null || destination.id == null) {
            tracker.active = null;
            return;
        }

        if (tracker.active == null) {
            if (tracker.lastStationId == null) return;
            CreateTrainSegmentRuntimeData.RouteKey key = key(train, tracker.lastStationId,
                    destination.id);
            if (key == null) return;
            tracker.active = new ActiveSegment(key, tracker.lastStationName,
                    safe(destination.name), gameTime, gameTime);
            return;
        }

        CreateTrainSegmentRuntimeData.RouteKey expected = key(train, tracker.lastStationId,
                destination.id);
        if (expected == null || !expected.equals(tracker.active.key)) {
            // Navigation was rerouted or the train was renamed/reversed mid-segment. Discard the
            // partial sample; restarting it here would learn an artificially short journey.
            tracker.active = null;
            tracker.lastStationId = null;
            tracker.lastStationName = "";
            return;
        }
        long delta = Math.max(0L, Math.min(20L, gameTime - tracker.active.lastTick));
        if (signalAffectingMotion(train)) tracker.active.signalTicks += delta;
        tracker.active.lastTick = gameTime;
    }

    private static CreateTrainSegmentRuntimeData.RouteKey key(Train train, GlobalStation from,
            GlobalStation to) {
        return from == null || to == null || from.id == null || to.id == null
                ? null : key(train, from.id, to.id);
    }

    private static CreateTrainSegmentRuntimeData.RouteKey key(Train train, UUID from, UUID to) {
        if (train == null || train.graph == null || train.graph.id == null
                || from == null || to == null) return null;
        TrainNameFormat name = TrainNameFormat.parse(
                train.name == null ? "" : train.name.getString());
        return new CreateTrainSegmentRuntimeData.RouteKey(train.graph.id, from, to,
                train.currentlyBackwards, name.serviceTypeOrLegacy());
    }

    private static boolean signalAffectingMotion(Train train) {
        return train.navigation != null && train.navigation.waitingForSignal != null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class TrackerState {
        private final Map<UUID, TrainTracker> trains = new HashMap<>();
    }

    private static final class TrainTracker {
        private UUID lastStationId;
        private String lastStationName = "";
        private ActiveSegment active;
        private long lastSeen;
    }

    private static final class ActiveSegment {
        private final CreateTrainSegmentRuntimeData.RouteKey key;
        private final String fromName;
        @SuppressWarnings("unused")
        private final String toName;
        private final long startedAt;
        private long lastTick;
        private long signalTicks;

        private ActiveSegment(CreateTrainSegmentRuntimeData.RouteKey key, String fromName,
                String toName, long startedAt, long lastTick) {
            this.key = key;
            this.fromName = fromName;
            this.toName = toName;
            this.startedAt = startedAt;
            this.lastTick = lastTick;
        }
    }
}
