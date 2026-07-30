package dev.minescreen.client.traffic;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import dev.minescreen.EtaTimeline;
import dev.minescreen.network.TrainSchedulePayload;
import dev.minescreen.network.TrainScheduleRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Client cache for server-authored Create timetable predictions. */
public final class ClientTrainScheduleState {
    private static final long REFRESH_TICKS = 20L;
    private static final Map<Key, Cached> CACHE = new HashMap<>();
    private static final Map<Key, Long> LAST_REQUEST = new HashMap<>();

    private ClientTrainScheduleState() {
    }

    /** Returns null when the connected server does not provide the state channel. */
    public static CreateTrainScheduleService.Snapshot getOrRequest(ClientLevel level,
            BlockPos stationPos, String trainType, String turnaroundStation, int maximum) {
        if (!hasChannel()) return null;
        Key key = new Key(level.dimension().location().toString(), stationPos.immutable(),
                normalize(trainType), normalize(turnaroundStation));
        long now = level.getGameTime();
        Long lastRequest = LAST_REQUEST.get(key);
        if (lastRequest == null || now - lastRequest >= REFRESH_TICKS) {
            LAST_REQUEST.put(key, now);
            PacketDistributor.sendToServer(new TrainScheduleRequestPayload(
                    level.dimension().location(), stationPos, trainType == null ? "" : trainType,
                    turnaroundStation == null ? "" : turnaroundStation,
                    Math.max(1, Math.min(8, maximum))));
        }
        Cached cached = CACHE.get(key);
        if (cached == null) return CreateTrainScheduleService.Snapshot.EMPTY;
        return age(cached, now, maximum);
    }

    public static void accept(TrainSchedulePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Key key = new Key(minecraft.level.dimension().location().toString(),
                payload.stationPos().immutable(), normalize(payload.trainType()),
                normalize(payload.turnaroundStation()));
        long now = minecraft.level.getGameTime();
        long transitTicks = Math.max(0L, now - payload.serverGameTime());
        Cached previous = CACHE.get(key);
        Map<String, CreateTrainScheduleService.Departure> previousDepartures =
                previous == null ? Map.of() : index(previous.snapshot().departures());
        Map<String, Long> arrivalTargets = new LinkedHashMap<>();
        var departures = payload.departures().stream().map(value -> {
            long eta = value.waitingForSignal() ? value.etaTicks()
                    : age(value.etaTicks(), transitTicks);
            long dwell = age(value.dwellTicks(), transitTicks);
            var departure = new CreateTrainScheduleService.Departure(value.trainId(),
                    value.trainName(),
                    value.trainType(), value.origin(), value.destination(), value.direction(),
                    value.loop(), value.carriageCount(), eta, dwell, value.waitingForSignal());
            String id = identity(departure);
            long target = EtaTimeline.target(now, eta);
            CreateTrainScheduleService.Departure old = previousDepartures.get(id);
            if (old != null && !departure.dwelling() && !old.dwelling()
                    && EtaTimeline.known(eta)) {
                long oldEta;
                if (old.waitingForSignal()) {
                    oldEta = old.etaTicks();
                } else {
                    long oldTarget = previous.arrivalTargets().getOrDefault(id,
                            EtaTimeline.target(previous.receivedGameTime(), old.etaTicks()));
                    oldEta = EtaTimeline.remaining(oldTarget, now);
                }
                if (EtaTimeline.known(oldEta)) {
                    if (departure.waitingForSignal()) {
                        // A signal hold advances the absolute arrival clock by one tick per tick:
                        // remaining running time is frozen until the signal clears.
                        eta = oldEta;
                        target = EtaTimeline.target(now, eta);
                    } else {
                        long oldTarget = EtaTimeline.target(now, oldEta);
                        target = EtaTimeline.slewTarget(oldTarget, target,
                                Math.max(0L, now - previous.receivedGameTime()));
                        eta = EtaTimeline.remaining(target, now);
                    }
                    departure = new CreateTrainScheduleService.Departure(value.trainId(),
                            value.trainName(),
                            value.trainType(), value.origin(), value.destination(),
                            value.direction(), value.loop(), value.carriageCount(), eta, dwell,
                            value.waitingForSignal());
                }
            }
            arrivalTargets.put(id, target);
            return departure;
        }).toList();
        CreateTrainScheduleService.Snapshot snapshot = new CreateTrainScheduleService.Snapshot(
                payload.stationName(), departures, payload.serverGameTime(), payload.totalTrains(),
                payload.activeScheduleTrains(), payload.filterMatchedTrains());
        CACHE.put(key, new Cached(snapshot, now, Map.copyOf(arrivalTargets)));
    }

    public static void clear() {
        CACHE.clear();
        LAST_REQUEST.clear();
    }

    private static CreateTrainScheduleService.Snapshot age(Cached cached, long now, int maximum) {
        CreateTrainScheduleService.Snapshot snapshot = cached.snapshot();
        long elapsedTicks = Math.max(0L, now - cached.receivedGameTime());
        int limit = Math.max(1, Math.min(8, maximum));
        var departures = snapshot.departures().stream().limit(limit).map(value -> {
            long target = cached.arrivalTargets().getOrDefault(identity(value),
                    EtaTimeline.target(cached.receivedGameTime(), value.etaTicks()));
            long eta = value.waitingForSignal() ? value.etaTicks()
                    : EtaTimeline.remaining(target, now);
            long dwell = value.dwellKnown()
                    ? Math.max(0L, value.dwellTicks() - elapsedTicks) : -1L;
            return new CreateTrainScheduleService.Departure(value.trainId(), value.trainName(),
                    value.trainType(),
                    value.origin(), value.destination(), value.direction(),
                    value.loop(), value.carriageCount(), eta, dwell, value.waitingForSignal());
        }).toList();
        return new CreateTrainScheduleService.Snapshot(snapshot.stationName(), departures,
                snapshot.gameTime(), snapshot.totalTrains(), snapshot.activeScheduleTrains(),
                snapshot.filterMatchedTrains());
    }

    private static boolean hasChannel() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && NetworkRegistry.hasChannel(listener,
                TrainScheduleRequestPayload.TYPE.id());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long age(long ticks, long elapsed) {
        if (!EtaTimeline.known(ticks)) return ticks;
        return Math.max(0L, ticks - Math.max(0L, elapsed));
    }

    private static Map<String, CreateTrainScheduleService.Departure> index(
            java.util.List<CreateTrainScheduleService.Departure> departures) {
        Map<String, CreateTrainScheduleService.Departure> indexed = new HashMap<>();
        for (CreateTrainScheduleService.Departure departure : departures) {
            indexed.put(identity(departure), departure);
        }
        return indexed;
    }

    private static String identity(CreateTrainScheduleService.Departure departure) {
        if (departure.trainId().getMostSignificantBits() != 0L
                || departure.trainId().getLeastSignificantBits() != 0L) {
            return departure.trainId().toString();
        }
        return normalize(departure.trainName());
    }

    private record Key(String dimension, BlockPos stationPos, String trainType,
            String turnaroundStation) {
    }

    private record Cached(CreateTrainScheduleService.Snapshot snapshot, long receivedGameTime,
            Map<String, Long> arrivalTargets) {
    }
}
