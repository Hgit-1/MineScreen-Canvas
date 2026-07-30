package dev.minescreen.client.traffic;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.compat.CreateTrainScheduleCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client-side, cached view of Create's live train timetable predictions. */
public final class CreateTrainScheduleService {
    private static final long CACHE_TICKS = 10L;
    public static final long UNKNOWN_ETA_TICKS = Long.MAX_VALUE / 4L;
    private static final Map<Level, Map<Key, Cached>> CACHE = new WeakHashMap<>();

    private CreateTrainScheduleService() {
    }

    public static Snapshot snapshot(Level level, BlockPos stationPos, String trainType,
            int maximum) {
        return snapshot(level, stationPos, trainType, "", maximum);
    }

    public static Snapshot snapshot(Level level, BlockPos stationPos, String trainType,
            String turnaroundStation, int maximum) {
        if (level == null || stationPos == null) return Snapshot.EMPTY;
        // Create intentionally sends clients only a boolean "schedule present" marker, not the
        // schedule entries required by submitPredictions(). Prefer MineScreen's compact server
        // snapshot whenever the optional server mod is available. This prevents a valid running
        // timetable from being reported as unbound merely because the local dummy schedule is
        // empty. A client-only installation still falls through to the legacy local best effort.
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            Snapshot remote = ClientTrainScheduleState.getOrRequest(clientLevel, stationPos,
                    trainType, turnaroundStation, maximum);
            if (remote != null) return remote;
        }
        Key key = new Key(stationPos.immutable(), trainType == null ? "" : trainType.trim(),
                turnaroundStation == null ? "" : turnaroundStation.trim());
        Map<Key, Cached> byKey = CACHE.computeIfAbsent(level, ignored -> new java.util.HashMap<>());
        Cached cached = byKey.get(key);
        if (cached != null && level.getGameTime() - cached.gameTime() < CACHE_TICKS) {
            return cached.snapshot();
        }
        Snapshot value = CreateTrainScheduleCompat.snapshot(level, stationPos, key.trainType(),
                key.turnaroundStation(), Math.max(1, Math.min(8, maximum)));
        byKey.put(key, new Cached(level.getGameTime(), value));
        return value;
    }

    /**
     * Resolves a display to an explicit LCD Studio coordinate binding or the nearest Create
     * station, then uses the station position as the shared cache key. Multiple boards along one
     * platform therefore consume one timetable scan instead of scanning once per rendered tile.
     */
    public static ResolvedSnapshot snapshotForDisplay(Level level, BlockPos displayPos,
            String bindingTemplateId, String preferredStationName, String trainType, int maximum) {
        return snapshotForDisplay(level, displayPos, bindingTemplateId, "", preferredStationName,
                trainType, "", maximum);
    }

    public static ResolvedSnapshot snapshotForDisplay(Level level, BlockPos displayPos,
            String bindingTemplateId, String explicitStationName, String preferredStationName,
            String trainType, String turnaroundStation, int maximum) {
        if (level == null || displayPos == null) return ResolvedSnapshot.EMPTY;
        int radius = MineScreenConfig.CREATE_STATION_SCAN_RADIUS.get();
        String explicit = explicitStationName == null ? "" : explicitStationName.trim();
        TrafficStationLocator.StationMatch match = null;
        if (!explicit.isBlank()) {
            match = TrafficStationLocator.resolveNamedNearby(level, displayPos, explicit, radius);
            // An explicit editor selection is authoritative. Falling through to some other nearby
            // station makes the UI appear to ignore the saved binding and can show departures for
            // the wrong platform when two Create stations are close together.
            if (match == null) return ResolvedSnapshot.EMPTY;
        }
        if (match == null) match = TrafficTemplateRepository.stationMatch(bindingTemplateId, level);
        if (match == null) {
            match = TrafficStationLocator.resolveNearby(level, displayPos, preferredStationName,
                    radius);
        }
        if (match == null) return ResolvedSnapshot.EMPTY;
        return new ResolvedSnapshot(match.position(), snapshot(level, match.position(), trainType,
                turnaroundStation, maximum));
    }

    public record Snapshot(String stationName, List<Departure> departures, long gameTime,
            int totalTrains, int activeScheduleTrains, int filterMatchedTrains) {
        public static final Snapshot EMPTY = new Snapshot("", List.of(), 0L, 0, 0, 0);

        public Snapshot(String stationName, List<Departure> departures, long gameTime) {
            this(stationName, departures, gameTime, 0, 0, 0);
        }

        public Snapshot {
            stationName = stationName == null ? "" : stationName;
            departures = departures == null ? List.of() : List.copyOf(departures);
            totalTrains = Math.max(0, totalTrains);
            activeScheduleTrains = Math.max(0, activeScheduleTrains);
            filterMatchedTrains = Math.max(0, filterMatchedTrains);
        }

        public DepartureState departureState(String trainType) {
            if (!departures.isEmpty()) return DepartureState.AVAILABLE;
            if (totalTrains == 0) return DepartureState.NO_TRAINS;
            if (activeScheduleTrains == 0) return DepartureState.NO_ACTIVE_SCHEDULES;
            if (trainType != null && !trainType.isBlank() && filterMatchedTrains == 0) {
                return DepartureState.FILTER_NO_MATCH;
            }
            return DepartureState.NO_STATION_PREDICTIONS;
        }
    }

    public enum DepartureState {
        AVAILABLE,
        NO_TRAINS,
        NO_ACTIVE_SCHEDULES,
        FILTER_NO_MATCH,
        NO_STATION_PREDICTIONS
    }

    public record Departure(java.util.UUID trainId, String trainName, String trainType,
            String origin,
            String destination, String direction, boolean loop, int carriageCount,
            long etaTicks, long dwellTicks, boolean waitingForSignal) {
        public Departure {
            trainId = trainId == null ? new java.util.UUID(0L, 0L) : trainId;
            trainName = safe(trainName);
            trainType = safe(trainType);
            origin = safe(origin);
            destination = safe(destination);
            direction = safe(direction);
            carriageCount = Math.max(0, Math.min(128, carriageCount));
            etaTicks = Math.max(0L, etaTicks);
            dwellTicks = Math.max(-1L, dwellTicks);
        }

        public int etaSeconds() {
            if (!etaKnown()) return -1;
            return (int) Math.max(0L, Math.round(etaTicks / 20.0D));
        }

        public boolean etaKnown() {
            return etaTicks < UNKNOWN_ETA_TICKS;
        }

        public boolean dwelling() {
            return etaKnown() && etaTicks <= 0L;
        }

        public boolean dwellKnown() {
            return dwellTicks >= 0L;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    public record ResolvedSnapshot(BlockPos stationPosition, Snapshot snapshot) {
        public static final ResolvedSnapshot EMPTY = new ResolvedSnapshot(BlockPos.ZERO,
                Snapshot.EMPTY);
    }

    private record Key(BlockPos position, String trainType, String turnaroundStation) {
    }

    private record Cached(long gameTime, Snapshot snapshot) {
    }
}
