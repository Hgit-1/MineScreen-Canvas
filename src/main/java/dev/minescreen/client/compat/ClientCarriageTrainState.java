package dev.minescreen.client.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.minescreen.EtaTimeline;
import dev.minescreen.network.CarriageStatusPayload;
import dev.minescreen.network.CarriageStatusRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** One-second server snapshots with client-side tick-accurate ETA/dwell countdown. */
public final class ClientCarriageTrainState {
    private static final long REFRESH_TICKS = 20L;
    private static final Map<Key, Cached> CACHE = new HashMap<>();
    private static final Map<Key, Long> LAST_REQUEST = new HashMap<>();

    private ClientCarriageTrainState() {
    }

    public static MovingStructureCompat.CarriageTrainStatus getOrRequest(UUID trainId,
            String turnaroundStation, MovingStructureCompat.CarriageTrainStatus fallback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || trainId == null || !hasChannel()) return fallback;
        Key key = new Key(trainId, normalize(turnaroundStation));
        long now = minecraft.level.getGameTime();
        Long requested = LAST_REQUEST.get(key);
        if (requested == null || now - requested >= REFRESH_TICKS) {
            LAST_REQUEST.put(key, now);
            PacketDistributor.sendToServer(new CarriageStatusRequestPayload(trainId,
                    turnaroundStation == null ? "" : turnaroundStation));
        }
        Cached cached = CACHE.get(key);
        if (cached == null) return fallback;
        if (fallback != null && fallback.phase() != MovingStructureCompat.ArrivalPhase.NO_ROUTE
                && (!fallback.stationName().equals(cached.status().stationName())
                        || (fallback.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED)
                                ^ (cached.status().phase()
                                        == MovingStructureCompat.ArrivalPhase.ARRIVED)
                        || (fallback.phase()
                                == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL)
                                ^ (cached.status().phase()
                                        == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL))) {
            // Create updates the navigation destination locally before the next one-second server
            // snapshot arrives. Do not keep rendering the previous station during that gap.
            return fallback;
        }
        long elapsed = Math.max(0L, now - cached.receivedAt());
        MovingStructureCompat.CarriageTrainStatus value = cached.status();
        long eta = value.phase() == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL
                ? value.etaTicks() : EtaTimeline.remaining(cached.arrivalAt(), now);
        long dwell = age(value.dwellTicks(), elapsed);
        return new MovingStructureCompat.CarriageTrainStatus(value.trainName(),
                value.serviceType(), value.stationName(), value.terminalStation(),
                value.carriageCount(), fallback == null ? value.carriageNumber()
                        : fallback.carriageNumber(),
                value.upcomingStops(), eta, value.distance(),
                value.phase(), dwell, value.schedulePresent());
    }

    public static void accept(CarriageStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        long now = minecraft.level.getGameTime();
        long transitTicks = Math.max(0L, now - payload.serverGameTime());
        MovingStructureCompat.ArrivalPhase phase = switch (payload.phase()) {
            case NO_ROUTE -> MovingStructureCompat.ArrivalPhase.NO_ROUTE;
            case EN_ROUTE -> MovingStructureCompat.ArrivalPhase.EN_ROUTE;
            case WAITING_SIGNAL -> MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL;
            case APPROACHING -> MovingStructureCompat.ArrivalPhase.APPROACHING;
            case ARRIVED -> MovingStructureCompat.ArrivalPhase.ARRIVED;
        };
        long eta = phase == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL
                ? payload.etaTicks() : age(payload.etaTicks(), transitTicks);
        long dwell = age(payload.dwellTicks(), transitTicks);
        Key key = new Key(payload.trainId(), normalize(payload.turnaroundStation()));
        Cached previous = CACHE.get(key);
        long arrivalAt = EtaTimeline.target(now, eta);
        if (previous != null && sameJourney(previous.status(), payload.stationName(), phase)) {
            long previousEta = previous.status().phase()
                    == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL
                    ? previous.status().etaTicks()
                    : EtaTimeline.remaining(previous.arrivalAt(), now);
            if (phase == MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL
                    && EtaTimeline.known(previousEta)) {
                // No progress is made while held at a signal. Keep the remaining journey time
                // fixed and let the predicted absolute arrival move later with world time.
                eta = previousEta;
                arrivalAt = EtaTimeline.target(now, eta);
            } else if (EtaTimeline.known(previousEta) && EtaTimeline.known(eta)) {
                long previousTarget = EtaTimeline.target(now, previousEta);
                long observedTarget = EtaTimeline.target(now, eta);
                arrivalAt = EtaTimeline.slewTarget(previousTarget, observedTarget,
                        Math.max(0L, now - previous.receivedAt()));
                eta = EtaTimeline.remaining(arrivalAt, now);
            }
        }
        var value = new MovingStructureCompat.CarriageTrainStatus(payload.trainName(),
                payload.serviceType(), payload.stationName(), payload.terminalStation(),
                payload.carriageCount(), 0, payload.upcomingStops(), eta, payload.distance(), phase,
                dwell, payload.schedulePresent());
        CACHE.put(key, new Cached(value, now, arrivalAt));
    }

    public static void clear() {
        CACHE.clear();
        LAST_REQUEST.clear();
    }

    private static long age(long ticks, long elapsed) {
        if (ticks < 0L || ticks >= Long.MAX_VALUE / 8L) return ticks;
        return Math.max(0L, ticks - elapsed);
    }

    private static boolean sameJourney(MovingStructureCompat.CarriageTrainStatus previous,
            String stationName, MovingStructureCompat.ArrivalPhase phase) {
        return previous.phase() != MovingStructureCompat.ArrivalPhase.ARRIVED
                && phase != MovingStructureCompat.ArrivalPhase.ARRIVED
                && previous.phase() != MovingStructureCompat.ArrivalPhase.NO_ROUTE
                && phase != MovingStructureCompat.ArrivalPhase.NO_ROUTE
                && previous.stationName().equals(stationName == null ? "" : stationName);
    }

    private static boolean hasChannel() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && NetworkRegistry.hasChannel(listener,
                CarriageStatusRequestPayload.TYPE.id());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record Key(UUID trainId, String turnaroundStation) {}

    private record Cached(MovingStructureCompat.CarriageTrainStatus status, long receivedAt,
            long arrivalAt) {
    }
}
