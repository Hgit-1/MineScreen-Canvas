package dev.minescreen.client.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;

import dev.minescreen.client.compat.CreateTrainScheduleCompat;
import dev.minescreen.client.traffic.CreateTrainScheduleService;
import dev.minescreen.TrainNameFormat;
import dev.minescreen.TrainEtaEstimator;
import dev.minescreen.compat.create.CreateStationPathEstimator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Create 6.x implementation for station departures and live train ETA. */
public final class CreateTrainScheduleBridge implements CreateTrainScheduleCompat.Bridge {
    private static final long DEFAULT_SEGMENT_ETA_TICKS = 30L * 20L;
    @Override
    public CreateTrainScheduleService.Snapshot snapshot(Level level, BlockPos stationPos,
            String trainType, String turnaroundStation, int maximum) {
        if (!(level.getBlockEntity(stationPos) instanceof StationBlockEntity blockEntity)) {
            return CreateTrainScheduleService.Snapshot.EMPTY;
        }
        GlobalStation station = blockEntity.getStation();
        if (station == null) return CreateTrainScheduleService.Snapshot.EMPTY;
        String wanted = trainType == null ? "" : trainType.trim().toLowerCase(Locale.ROOT);
        GlobalRailwayManager railways = CreateClient.RAILWAYS == null
                ? null : CreateClient.RAILWAYS.sided(level);
        if (railways == null || railways.trains == null) {
            return new CreateTrainScheduleService.Snapshot(
                    station.name == null ? "" : station.name, List.of(), level.getGameTime(),
                    0, 0, 0);
        }
        List<CreateTrainScheduleService.Departure> departures = new ArrayList<>();
        int totalTrains = 0;
        int activeScheduleTrains = 0;
        int filterMatchedTrains = 0;
        for (Train train : railways.trains.values()) {
            if (train == null || train.invalid) continue;
            totalTrains++;
            if (train.runtime != null && train.runtime.getSchedule() != null
                    && !train.runtime.paused) activeScheduleTrains++;
            if (!matchesType(train, wanted)) continue;
            filterMatchedTrains++;
            boolean dwelling = station.id != null && station.id.equals(train.currentStation);
            long eta = dwelling ? 0L : train.runtime != null
                    && train.runtime.getSchedule() != null
                    && !train.runtime.paused ? scheduledEta(train, station.name) : -1L;
            if (eta < 0L) eta = etaToStation(train, station);
            if (eta < 0L) continue;
            RouteEndpoints endpoints = routeEndpoints(train, station.name, turnaroundStation);
            String destination = endpoints.destination().isBlank()
                    && train.navigation != null && train.navigation.destination != null
                    ? train.navigation.destination.name : endpoints.destination();
            TrainNameFormat name = TrainNameFormat.parse(
                    train.name == null ? "" : train.name.getString());
            String type = name.serviceTypeOrLegacy();
            String direction = train.currentlyBackwards ? "down" : "up";
            int carriageCount = train.carriages == null ? 0 : train.carriages.size();
            departures.add(new CreateTrainScheduleService.Departure(train.id, name.displayName(),
                    type, endpoints.origin(), destination, direction, endpoints.loop(),
                    carriageCount, eta, -1L, !dwelling && stoppedForSignal(train)));
        }
        departures.sort(Comparator.comparingLong(CreateTrainScheduleService.Departure::etaTicks)
                .thenComparing(CreateTrainScheduleService.Departure::trainName));
        if (departures.size() > maximum) departures = new ArrayList<>(departures.subList(0, maximum));
        return new CreateTrainScheduleService.Snapshot(station.name == null ? "" : station.name,
                departures, level.getGameTime(), totalTrains, activeScheduleTrains,
                filterMatchedTrains);
    }

    private static boolean matchesType(Train train, String wanted) {
        if (wanted.isBlank()) return true;
        String name = train.name == null ? "" : train.name.getString();
        String icon = train.icon == null || train.icon.getId() == null ? "" : train.icon.getId().toString();
        return name.toLowerCase(Locale.ROOT).contains(wanted)
                || icon.toLowerCase(Locale.ROOT).contains(wanted);
    }

    private static RouteEndpoints routeEndpoints(Train train, String stationName) {
        return routeEndpoints(train, stationName, "");
    }

    private static RouteEndpoints routeEndpoints(Train train, String stationName,
            String turnaroundStation) {
        if (train.runtime == null || train.runtime.getSchedule() == null) {
            return RouteEndpoints.EMPTY;
        }
        try {
            var schedule = train.runtime.getSchedule();
            List<ScheduledStop> scheduledStops = new ArrayList<>();
            for (int entryIndex = 0; entryIndex < schedule.entries.size(); entryIndex++) {
                var entry = schedule.entries.get(entryIndex);
                if (entry != null && entry.instruction instanceof DestinationInstruction target) {
                    addScheduledStop(scheduledStops, entryIndex, target.getFilter());
                }
            }
            if (scheduledStops.isEmpty()) return RouteEndpoints.EMPTY;
            int activeIndex = activeStopIndex(train, scheduledStops, stationName,
                    schedule.entries.size(), schedule.cyclic);
            if (TrainNameFormat.parse(train.name == null ? "" : train.name.getString()).loop()) {
                return loopEndpoints(scheduledStops, activeIndex, stationName);
            }
            int explicitTurnaround = namedStopIndex(scheduledStops, turnaroundStation);
            if (explicitTurnaround > 0 && explicitTurnaround < scheduledStops.size() - 1
                    || explicitTurnaround > 0 && schedule.cyclic) {
                String returnTerminus = schedule.cyclic
                        && !sameStation(scheduledStops.getFirst().name(),
                                scheduledStops.getLast().name())
                        ? scheduledStops.getFirst().name() : scheduledStops.getLast().name();
                boolean returnWorking = activeIndex > explicitTurnaround
                        || activeIndex == explicitTurnaround && train.currentStation != null;
                return returnWorking
                        ? new RouteEndpoints(scheduledStops.get(explicitTurnaround).name(),
                                returnTerminus, false)
                        : new RouteEndpoints(scheduledStops.getFirst().name(),
                                scheduledStops.get(explicitTurnaround).name(), false);
            }
            String direction = activeIndex < 0 ? ""
                    : directionMarker(scheduledStops.get(activeIndex).name());
            if (activeIndex >= 0 && direction.isBlank()) {
                int directionalIndex = directionalNeighbour(scheduledStops, activeIndex,
                        train.currentStation != null, schedule.cyclic);
                if (directionalIndex >= 0) {
                    activeIndex = directionalIndex;
                    direction = directionMarker(scheduledStops.get(activeIndex).name());
                }
            }
            if (activeIndex >= 0 && !direction.isBlank()) {
                int start = activeIndex;
                int end = activeIndex;
                int examined = 0;
                while (examined++ < scheduledStops.size() - 1) {
                    int previous = start - 1;
                    if (previous < 0) {
                        if (!schedule.cyclic) break;
                        previous = scheduledStops.size() - 1;
                    }
                    String marker = directionMarker(scheduledStops.get(previous).name());
                    if (marker.equals(direction)) start = previous;
                    else {
                        if (marker.isBlank()) start = previous;
                        break;
                    }
                }
                examined = 0;
                while (examined++ < scheduledStops.size() - 1) {
                    int next = end + 1;
                    if (next >= scheduledStops.size()) {
                        if (!schedule.cyclic) break;
                        next = 0;
                    }
                    String marker = directionMarker(scheduledStops.get(next).name());
                    if (marker.equals(direction)) end = next;
                    else {
                        if (marker.isBlank()) end = next;
                        break;
                    }
                }
                return new RouteEndpoints(scheduledStops.get(start).name(),
                        scheduledStops.get(end).name(), false);
            }

            int turnaround = palindromeTurnaround(scheduledStops, schedule.cyclic);
            if (turnaround < 0 && closedWorking(scheduledStops, schedule.cyclic)) {
                turnaround = defaultTurnaround(scheduledStops, schedule.cyclic);
            }
            if (turnaround > 0 && activeIndex >= 0) {
                boolean returnWorking = activeIndex > turnaround
                        || activeIndex == turnaround && train.currentStation != null;
                String returnTerminus = schedule.cyclic
                        && !sameStation(scheduledStops.getFirst().name(),
                                scheduledStops.getLast().name())
                        ? scheduledStops.getFirst().name() : scheduledStops.getLast().name();
                return returnWorking
                        ? new RouteEndpoints(scheduledStops.get(turnaround).name(),
                                returnTerminus, false)
                        : new RouteEndpoints(scheduledStops.getFirst().name(),
                                scheduledStops.get(turnaround).name(), false);
            }
            if (schedule.cyclic && scheduledStops.size() > 1) {
                if (activeIndex >= 0) {
                    return new RouteEndpoints(
                            scheduledStops.get(Math.floorMod(activeIndex - 1,
                                    scheduledStops.size())).name(),
                            scheduledStops.get((activeIndex + 1)
                                    % scheduledStops.size()).name(), false);
                }
                return RouteEndpoints.EMPTY;
            }
            return new RouteEndpoints(scheduledStops.getFirst().name(),
                    scheduledStops.getLast().name(), false);
        } catch (RuntimeException ignored) {
            return RouteEndpoints.EMPTY;
        }
    }

    private static void addScheduledStop(List<ScheduledStop> stops, int entryIndex,
            String station) {
        String value = station == null ? "" : station.trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        if (stops.isEmpty() || !stops.getLast().name().equalsIgnoreCase(value)) {
            stops.add(new ScheduledStop(entryIndex, value));
        }
    }

    private static int namedStopIndex(List<ScheduledStop> stops, String stationName) {
        String requested = stationName == null ? "" : stationName.trim();
        if (requested.isBlank()) return -1;
        for (int index = 0; index < stops.size(); index++) {
            if (destinationMatches(stops.get(index).name(), requested)
                    || sameStation(stops.get(index).name(), requested)) return index;
        }
        return -1;
    }

    private static RouteEndpoints loopEndpoints(List<ScheduledStop> stops, int activeIndex,
            String boardStation) {
        if (stops.isEmpty()) return RouteEndpoints.EMPTY;
        int active = activeIndex < 0 ? 0 : Math.min(activeIndex, stops.size() - 1);
        for (int offset = 0; offset < stops.size(); offset++) {
            int candidate = (active + offset) % stops.size();
            if (!sameStation(stops.get(candidate).name(), boardStation)) {
                active = candidate;
                break;
            }
        }
        int following = active;
        for (int offset = 1; offset <= stops.size(); offset++) {
            int candidate = (active + offset) % stops.size();
            if (!sameStation(stops.get(active).name(), stops.get(candidate).name())
                    && !sameStation(stops.get(candidate).name(), boardStation)) {
                following = candidate;
                break;
            }
        }
        return new RouteEndpoints(stops.get(active).name(), stops.get(following).name(), true);
    }

    private static boolean closedWorking(List<ScheduledStop> stops, boolean cyclic) {
        return stops.size() >= 3 && (cyclic
                || sameStation(stops.getFirst().name(), stops.getLast().name()));
    }

    private static int defaultTurnaround(List<ScheduledStop> stops, boolean cyclic) {
        boolean implicitClosure = cyclic
                && !sameStation(stops.getFirst().name(), stops.getLast().name());
        int routeSize = stops.size() + (implicitClosure ? 1 : 0);
        int pivot = routeSize / 2;
        return Math.max(1, Math.min(stops.size() - 1, pivot));
    }

    private static int activeStopIndex(Train train, List<ScheduledStop> stops, String fallbackName,
            int entryCount, boolean cyclic) {
        int currentEntry = Math.max(0, train.runtime.currentEntry);
        for (int index = 0; index < stops.size(); index++) {
            if (stops.get(index).entryIndex() == currentEntry) return index;
        }
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < stops.size(); index++) {
            int distance = stops.get(index).entryIndex() - currentEntry;
            if (distance < 0 && cyclic && entryCount > 0) distance += entryCount;
            if (distance >= 0 && distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        if (bestIndex >= 0) return bestIndex;
        for (int index = 0; index < stops.size(); index++) {
            if (destinationMatches(stops.get(index).name(), fallbackName)) return index;
        }
        return stops.size() - 1;
    }

    private static int directionalNeighbour(List<ScheduledStop> stops, int activeIndex,
            boolean preferForward, boolean cyclic) {
        int forward = findDirectionalNeighbour(stops, activeIndex, 1, cyclic);
        int backward = findDirectionalNeighbour(stops, activeIndex, -1, cyclic);
        return preferForward && forward >= 0 ? forward
                : !preferForward && backward >= 0 ? backward
                : forward >= 0 ? forward : backward;
    }

    private static int findDirectionalNeighbour(List<ScheduledStop> stops, int activeIndex,
            int step, boolean cyclic) {
        for (int offset = 1; offset < stops.size(); offset++) {
            int candidate = activeIndex + step * offset;
            if (cyclic) candidate = Math.floorMod(candidate, stops.size());
            else if (candidate < 0 || candidate >= stops.size()) break;
            if (!directionMarker(stops.get(candidate).name()).isBlank()) return candidate;
        }
        return -1;
    }

    private static int palindromeTurnaround(List<ScheduledStop> stops, boolean cyclic) {
        if (stops.size() < 3) return -1;
        boolean implicitClosure = cyclic
                && !sameStation(stops.getFirst().name(), stops.getLast().name());
        int routeSize = stops.size() + (implicitClosure ? 1 : 0);
        if (!sameStation(stops.getFirst().name(),
                routeStop(stops, routeSize - 1).name())) return -1;
        int bestPivot = -1;
        int bestPairs = 0;
        for (int pivot = 1; pivot < routeSize - 1; pivot++) {
            int pairs = Math.min(pivot, routeSize - 1 - pivot);
            boolean mirrored = pairs > 0;
            for (int distance = 1; distance <= pairs; distance++) {
                if (!sameStation(routeStop(stops, pivot - distance).name(),
                        routeStop(stops, pivot + distance).name())) {
                    mirrored = false;
                    break;
                }
            }
            if (mirrored && pairs > bestPairs) {
                bestPairs = pairs;
                bestPivot = pivot;
            }
        }
        return bestPivot;
    }

    private static ScheduledStop routeStop(List<ScheduledStop> stops, int index) {
        return index == stops.size() ? stops.getFirst() : stops.get(index);
    }

    private static boolean sameStation(String first, String second) {
        return stripDirection(first).equalsIgnoreCase(stripDirection(second));
    }

    private static String stripDirection(String value) {
        return (value == null ? "" : value).replaceAll(
                "(?iu)\\s*(上行|下行|上り|下り|upbound|downbound)\\s*", "")
                .replace("[", "").replace("]", "")
                .replace("【", "").replace("】", "").trim();
    }

    private static void addExactStop(List<String> stops, String station) {
        String value = station == null ? "" : station.trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        if (stops.isEmpty() || !stops.getLast().equalsIgnoreCase(value)) stops.add(value);
    }

    private record RouteEndpoints(String origin, String destination, boolean loop) {
        private static final RouteEndpoints EMPTY = new RouteEndpoints("", "", false);
    }

    private record ScheduledStop(int entryIndex, String name) {}

    private static String directionMarker(String stationName) {
        String value = stationName == null ? "" : stationName.toLowerCase(Locale.ROOT);
        if (value.contains("上行") || value.contains("上り") || value.contains("upbound")) {
            return "up";
        }
        if (value.contains("下行") || value.contains("下り") || value.contains("downbound")) {
            return "down";
        }
        return "";
    }

    private static long etaToStation(Train train, GlobalStation station) {
        if (station.id != null && station.id.equals(train.currentStation)) return 0L;
        if (train.navigation == null || train.navigation.destination != station) return -1L;
        return etaFromDistance(train, Math.max(0.0D,
                train.navigation.distanceToDestination));
    }

    private static long scheduledEta(Train train, String stationName) {
        if (train.runtime == null || train.runtime.getSchedule() == null
                || stationName == null || stationName.isBlank()) return -1L;
        long best = Long.MAX_VALUE;
        boolean matchingButUnknown = false;
        try {
            for (var prediction : train.runtime.submitPredictions()) {
                if (prediction == null || prediction.destination == null) continue;
                if (destinationMatches(prediction.destination, stationName)) {
                    if (prediction.ticks < 0) matchingButUnknown = true;
                    else best = Math.min(best, prediction.ticks);
                }
            }
        } catch (RuntimeException ignored) {
            // Create may rebuild the runtime list while a train is being unloaded.
        }
        long estimated = estimateFullScheduleEta(train, stationName);
        if (estimated >= 0L
                && estimated < CreateTrainScheduleService.UNKNOWN_ETA_TICKS) return estimated;
        if (best != Long.MAX_VALUE) return best;
        return estimated >= 0L ? estimated : matchingButUnknown
                ? CreateTrainScheduleService.UNKNOWN_ETA_TICKS : -1L;
    }

    private static long estimateFullScheduleEta(Train train, String stationName) {
        try {
            var runtime = train.runtime;
            var schedule = runtime.getSchedule();
            int entryCount = schedule.entries.size();
            if (entryCount == 0) return -1L;
            int currentEntry = Math.max(0, runtime.currentEntry);
            if (currentEntry >= entryCount) {
                if (!schedule.cyclic) return -1L;
                currentEntry %= entryCount;
            }
            long fallbackSegment = typicalSegmentTicks(train);
            long elapsed = remainingCurrentLegTicks(train, currentEntry, fallbackSegment);
            if (elapsed >= CreateTrainScheduleService.UNKNOWN_ETA_TICKS) {
                return CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
            }
            GlobalStation stopAtEntry = scheduledStop(train, currentEntry, null, true);
            for (int offset = 0; offset < entryCount; offset++) {
                int entryIndex = currentEntry + offset;
                if (entryIndex >= entryCount) {
                    if (!schedule.cyclic) break;
                    entryIndex %= entryCount;
                }
                var entry = schedule.entries.get(entryIndex);
                if (entry != null && entry.instruction instanceof DestinationInstruction target
                        && destinationMatches(target.getFilter(), stationName)) {
                    return Math.max(0L, elapsed);
                }
                long dwell = scheduledDwellTicks(entry);
                if (offset == 0 && train.getCurrentStation() != null) {
                    long remainingDwell = remainingDwellTicks(train);
                    if (remainingDwell >= 0L) dwell = remainingDwell;
                }
                elapsed = saturatingAdd(elapsed, dwell);
                if (offset + 1 >= entryCount) break;
                int nextIndex = entryIndex + 1;
                if (nextIndex >= entryCount) {
                    if (!schedule.cyclic) break;
                    nextIndex = 0;
                }
                GlobalStation nextStop = scheduledStop(train, nextIndex, stopAtEntry, false);
                long segmentTicks = futureSegmentTicks(train, nextIndex, stopAtEntry, nextStop);
                if (segmentTicks >= CreateTrainScheduleService.UNKNOWN_ETA_TICKS) {
                    return CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
                }
                elapsed = saturatingAdd(elapsed, segmentTicks);
                stopAtEntry = nextStop;
            }
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    private static long remainingCurrentLegTicks(Train train, int entryIndex, long fallback) {
        if (train.getCurrentStation() != null) return 0L;
        long recorded = recordedSegmentTicks(train, entryIndex);
        if (train.navigation != null) {
            long planned = etaFromDistance(train,
                    Math.max(0.0D, train.navigation.distanceToDestination));
            long hybrid = hybridCurrentLegTicks(train, recorded, planned);
            if (hybrid >= 0L
                    && hybrid < CreateTrainScheduleService.UNKNOWN_ETA_TICKS) return hybrid;
        }
        long learned = recorded > 0L ? recorded : learnedSegmentTicks(train, entryIndex, fallback);
        if (train.navigation != null && train.navigation.distanceStartedAt > 0.001D) {
            double fraction = Math.max(0.0D, Math.min(1.0D,
                    train.navigation.distanceToDestination
                            / train.navigation.distanceStartedAt));
            return Math.max(1L, Math.round(learned * fraction));
        }
        return learned;
    }

    private static long hybridCurrentLegTicks(Train train, long recorded, long physics) {
        if (train.navigation == null) return physics;
        long unknown = CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
        List<Long> candidates = new ArrayList<>(3);
        long calibratedPhysics = physics;
        if (recorded > 0L && physics >= 0L && physics < unknown
                && train.navigation.distanceStartedAt > 0.001D) {
            double maximum = Math.max(0.001D, train.maxSpeed());
            double turn = Math.max(0.001D, train.maxTurnSpeed());
            double cruise = Math.min(maximum, (maximum + turn) * 0.5D);
            long nominalWhole = TrainEtaEstimator.estimateTicks(
                    train.navigation.distanceStartedAt, 0.0D, cruise, train.acceleration(),
                    24_000L, unknown);
            if (nominalWhole > 0L && nominalWhole < unknown) {
                double calibration = Math.max(0.70D,
                        Math.min(1.60D, recorded / (double) nominalWhole));
                calibratedPhysics = Math.max(1L, Math.round(physics * calibration));
            }
        }
        if (calibratedPhysics >= 0L && calibratedPhysics < unknown) {
            candidates.add(calibratedPhysics);
        }
        if (recorded > 0L && train.navigation.distanceStartedAt > 0.001D) {
            double fraction = Math.max(0.0D, Math.min(1.0D,
                    train.navigation.distanceToDestination
                            / train.navigation.distanceStartedAt));
            long byDistance = Math.max(1L, Math.round(recorded * fraction));
            candidates.add(byDistance);
            long byElapsed = Math.max(1L, recorded - Math.max(0, train.runtime.ticksInTransit));
            candidates.add(stoppedForSignal(train) ? byDistance : byElapsed);
        }
        if (candidates.isEmpty()) return unknown;
        candidates.sort(Long::compareTo);
        return candidates.get(candidates.size() / 2);
    }

    private static long typicalSegmentTicks(Train train) {
        List<Long> learned = new ArrayList<>();
        if (train.runtime != null && train.runtime.predictionTicks != null) {
            for (Integer ticks : train.runtime.predictionTicks) {
                if (ticks != null && ticks > 0) learned.add((long) ticks);
            }
        }
        if (!learned.isEmpty()) {
            learned.sort(Long::compareTo);
            return learned.get(learned.size() / 2);
        }
        if (train.navigation != null && train.navigation.distanceStartedAt > 0.001D) {
            long planned = cruiseSegmentEta(train, train.navigation.distanceStartedAt);
            if (planned < CreateTrainScheduleService.UNKNOWN_ETA_TICKS) return planned;
        }
        return DEFAULT_SEGMENT_ETA_TICKS;
    }

    private static long learnedSegmentTicks(Train train, int entryIndex, long fallback) {
        long learned = recordedSegmentTicks(train, entryIndex);
        if (learned > 0L) return learned;
        return Math.max(1L, fallback);
    }

    private static long recordedSegmentTicks(Train train, int entryIndex) {
        if (train.runtime == null || train.runtime.predictionTicks == null
                || entryIndex < 0 || entryIndex >= train.runtime.predictionTicks.size()) return -1L;
        Integer learned = train.runtime.predictionTicks.get(entryIndex);
        return learned == null || learned <= 0 ? -1L : learned;
    }

    private static long futureSegmentTicks(Train train, int destinationEntry,
            GlobalStation from, GlobalStation to) {
        long learned = recordedSegmentTicks(train, destinationEntry);
        if (learned > 0L) return learned;
        double distance = CreateStationPathEstimator.distance(train.graph, from, to);
        if (!Double.isFinite(distance)) return CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
        double maximum = Math.max(0.001D, train.maxSpeed());
        double turn = Math.max(0.001D, train.maxTurnSpeed());
        double scheduledCruise = Math.min(maximum, (maximum + turn) * 0.5D);
        return TrainEtaEstimator.estimateTicks(distance, 0.0D, scheduledCruise,
                train.acceleration(), 24_000L,
                CreateTrainScheduleService.UNKNOWN_ETA_TICKS);
    }

    private static GlobalStation scheduledStop(Train train, int entryIndex,
            GlobalStation previous, boolean currentEntry) {
        if (currentEntry) {
            GlobalStation present = train.getCurrentStation();
            if (present != null) return present;
            if (train.navigation != null && train.navigation.destination != null) {
                return train.navigation.destination;
            }
        }
        if (train.runtime == null || train.runtime.getSchedule() == null
                || entryIndex < 0
                || entryIndex >= train.runtime.getSchedule().entries.size()) return null;
        var entry = train.runtime.getSchedule().entries.get(entryIndex);
        if (entry == null || !(entry.instruction instanceof DestinationInstruction destination)) {
            return null;
        }
        return CreateStationPathEstimator.resolveNext(train.graph, destination.getFilter(),
                previous);
    }

    private static long scheduledDwellTicks(
            com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        if (entry == null || entry.conditions == null) return 0L;
        for (var group : entry.conditions) {
            long duration = 0L;
            boolean scheduled = false;
            for (var condition : group) {
                if (condition instanceof ScheduledDelay delay) {
                    duration += Math.max(0, delay.totalWaitTicks());
                    scheduled = true;
                } else {
                    scheduled = false;
                    break;
                }
            }
            if (scheduled) return duration;
        }
        return 0L;
    }

    private static long remainingDwellTicks(Train train) {
        if (train.runtime == null || train.runtime.getSchedule() == null
                || train.runtime.conditionProgress == null
                || train.runtime.conditionContext == null) return -1L;
        try {
            var entries = train.runtime.getSchedule().entries;
            if (train.runtime.currentEntry < 0 || train.runtime.currentEntry >= entries.size()) {
                return -1L;
            }
            var groups = entries.get(train.runtime.currentEntry).conditions;
            long shortest = Long.MAX_VALUE;
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                if (groupIndex >= train.runtime.conditionProgress.size()
                        || groupIndex >= train.runtime.conditionContext.size()) continue;
                int conditionIndex = train.runtime.conditionProgress.get(groupIndex);
                var group = groups.get(groupIndex);
                if (conditionIndex >= group.size()) return 0L;
                if (conditionIndex < 0
                        || !(group.get(conditionIndex) instanceof TimedWaitCondition timed)) continue;
                int elapsed = train.runtime.conditionContext.get(groupIndex).getInt("Time");
                long remaining = Math.max(0L, (long) timed.totalWaitTicks() - elapsed);
                boolean predictable = true;
                for (int later = conditionIndex + 1; later < group.size(); later++) {
                    if (!(group.get(later) instanceof ScheduledDelay delay)) {
                        predictable = false;
                        break;
                    }
                    remaining = saturatingAdd(remaining, delay.totalWaitTicks());
                }
                if (predictable) shortest = Math.min(shortest, remaining);
            }
            return shortest == Long.MAX_VALUE ? -1L : shortest;
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static boolean stoppedForSignal(Train train) {
        return train.navigation != null && train.navigation.waitingForSignal != null
                && Math.abs(train.speed) < 0.02D && Math.abs(train.targetSpeed) < 0.02D;
    }

    private static long saturatingAdd(long left, long right) {
        long unknown = CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
        if (left >= unknown || right >= unknown || right > unknown - left) return unknown;
        return Math.max(0L, left) + Math.max(0L, right);
    }

    private static long etaFromDistance(Train train, double distance) {
        return TrainEtaEstimator.estimateTicks(distance, train.speed,
                Math.min(Math.abs(train.targetSpeed), Math.max(0.001D, train.maxSpeed())),
                train.acceleration(), 24_000L,
                CreateTrainScheduleService.UNKNOWN_ETA_TICKS);
    }

    private static long cruiseSegmentEta(Train train, double distance) {
        double maximum = Math.max(0.001D, train.maxSpeed());
        double cruise = Math.min(Math.abs(train.throttle) * maximum,
                (maximum + Math.max(0.001D, train.maxTurnSpeed())) * 0.5D);
        if (!Double.isFinite(cruise) || cruise < 0.001D) cruise = Math.abs(train.targetSpeed);
        if (!Double.isFinite(cruise) || cruise < 0.001D) {
            return CreateTrainScheduleService.UNKNOWN_ETA_TICKS;
        }
        return Math.max(1L, Math.round(Math.max(0.0D, distance) / cruise) * 2L);
    }

    /** Create destination instructions accept '*' and '?' wildcards. */
    private static boolean destinationMatches(String destinationFilter, String stationName) {
        String filter = destinationFilter == null ? "" : destinationFilter.trim();
        String station = stationName == null ? "" : stationName.trim();
        if (filter.isBlank() || station.isBlank()) return false;
        if (filter.equalsIgnoreCase(station)) return true;
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < filter.length(); index++) {
            char character = filter.charAt(index);
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return station.matches("(?iu)" + regex.append('$'));
    }
}
