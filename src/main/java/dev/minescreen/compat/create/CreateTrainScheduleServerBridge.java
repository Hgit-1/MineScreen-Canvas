package dev.minescreen.compat.create;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;

import dev.minescreen.compat.CreateTrainScheduleServerCompat;
import dev.minescreen.TrainNameFormat;
import dev.minescreen.TrainEtaEstimator;
import dev.minescreen.PassengerArrivalLogic;
import dev.minescreen.network.CarriageStatusPayload;
import dev.minescreen.network.MovingCeilingDisplayStatePayload;
import dev.minescreen.network.MovingCeilingDisplayUpdatePayload;
import dev.minescreen.network.MovingTrafficDisplayUpdatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side Create 6.x timetable reader; clients receive only its compact result. */
public final class CreateTrainScheduleServerBridge
        implements CreateTrainScheduleServerCompat.Bridge {
    private static final long UNKNOWN_ETA_TICKS = Long.MAX_VALUE / 4L;
    private static final long DEFAULT_SEGMENT_ETA_TICKS = 30L * 20L;
    private static final Field UPDATE_TAGS = findUpdateTagsField();

    @Override
    public CreateTrainScheduleServerCompat.Snapshot snapshot(ServerLevel level, BlockPos stationPos,
            String trainType, String turnaroundStation, int maximum) {
        if (!level.isLoaded(stationPos)
                || !(level.getBlockEntity(stationPos) instanceof StationBlockEntity blockEntity)) {
            return CreateTrainScheduleServerCompat.Snapshot.EMPTY;
        }
        GlobalStation station = blockEntity.getStation();
        if (station == null) return CreateTrainScheduleServerCompat.Snapshot.EMPTY;
        String wanted = trainType == null ? "" : trainType.trim().toLowerCase(Locale.ROOT);
        GlobalRailwayManager railways = Create.RAILWAYS == null ? null
                : Create.RAILWAYS.sided(level);
        if (railways == null || railways.trains == null) {
            return namedEmpty(level, station, 0, 0, 0);
        }
        List<CreateTrainScheduleServerCompat.Departure> departures = new ArrayList<>();
        int totalTrains = 0;
        int activeScheduleTrains = 0;
        int filterMatchedTrains = 0;
        for (Train train : railways.trains.values()) {
            if (train == null || train.invalid) continue;
            totalTrains++;
            boolean activeSchedule = train.runtime != null && train.runtime.getSchedule() != null
                    && !train.runtime.paused;
            if (activeSchedule) activeScheduleTrains++;
            if (!matchesType(train, wanted)) continue;
            filterMatchedTrains++;
            boolean dwelling = station.id != null && station.id.equals(train.currentStation);
            // The schedule prediction is independent of the instantaneous acceleration/braking
            // state and therefore remains readable on a passenger information board. A train
            // physically berthed at this platform must remain ETA 0 even when a cyclic schedule
            // already predicts its next visit to the same name.
            long eta = dwelling ? 0L
                    : activeSchedule ? scheduledEta(level, train, station.name) : -1L;
            if (eta < 0L) eta = etaToStation(level, train, station);
            if (eta < 0L) continue;
            RouteEndpoints endpoints = routeEndpoints(train, station.name, turnaroundStation);
            String destination = endpoints.destination().isBlank()
                    && train.navigation != null && train.navigation.destination != null
                    ? safe(train.navigation.destination.name) : endpoints.destination();
            List<String> upcomingStops = stopsToTerminus(train, station.name, endpoints,
                    destination, turnaroundStation, railways.trains.values());
            TrainNameFormat name = TrainNameFormat.parse(
                    train.name == null ? "" : train.name.getString());
            int carriageCount = train.carriages == null ? 0 : train.carriages.size();
            departures.add(new CreateTrainScheduleServerCompat.Departure(train.id,
                    name.displayName(), name.serviceTypeOrLegacy(), endpoints.origin(), destination,
                    train.currentlyBackwards ? "down" : "up", endpoints.loop(),
                    carriageCount, eta,
                    dwelling && activeSchedule ? remainingDwellTicks(train) : -1L,
                    !dwelling && stoppedForSignal(train), upcomingStops));
        }
        departures.sort(Comparator.comparingLong(CreateTrainScheduleServerCompat.Departure::etaTicks)
                .thenComparingInt(dep -> countSkippedStops(dep.upcomingStops()) > 0 ? 0 : 1)
                .thenComparing(CreateTrainScheduleServerCompat.Departure::trainName));
        int safeMaximum = Math.max(1, Math.min(8, maximum));
        if (departures.size() > safeMaximum) {
            departures = new ArrayList<>(departures.subList(0, safeMaximum));
        }
        return new CreateTrainScheduleServerCompat.Snapshot(safe(station.name), departures,
                level.getGameTime(), totalTrains, activeScheduleTrains, filterMatchedTrains);
    }

    private static CreateTrainScheduleServerCompat.Snapshot namedEmpty(ServerLevel level,
            GlobalStation station, int total, int active, int matched) {
        return new CreateTrainScheduleServerCompat.Snapshot(safe(station.name), List.of(),
                level.getGameTime(), total, active, matched);
    }

    @Override
    public CreateTrainScheduleServerCompat.CarriageStatus carriageStatus(ServerLevel level,
            java.util.UUID trainId, Vec3 playerPosition, String turnaroundStation) {
        GlobalRailwayManager railways = Create.RAILWAYS == null ? null
                : Create.RAILWAYS.sided(level);
        Train train = railways == null || railways.trains == null
                ? null : railways.trains.get(trainId);
        if (train == null || train.invalid) return CreateTrainScheduleServerCompat.CarriageStatus.EMPTY;
        double playerDistance = train.distanceToLocationSqr(level, playerPosition);
        if (Double.isFinite(playerDistance) && playerDistance > 65_536.0D) {
            return CreateTrainScheduleServerCompat.CarriageStatus.EMPTY;
        }
        TrainNameFormat parsedName = TrainNameFormat.parse(
                train.name == null ? "" : train.name.getString());
        String trainName = parsedName.displayName();
        String serviceType = parsedName.serviceTypeOrLegacy();
        int carriageCount = train.carriages == null ? 0 : train.carriages.size();
        RouteSummary route = routeSummary(train, turnaroundStation, railways.trains.values());
        boolean schedulePresent = train.runtime != null && train.runtime.getSchedule() != null
                && !train.runtime.paused;
        // Create can retain the completed Navigation object briefly after currentStation is set.
        // Arrival must win, otherwise the old route is re-estimated and the ETA grows again while
        // the train is already standing at the platform.
        GlobalStation current = train.getCurrentStation();
        if (current != null) {
            return new CreateTrainScheduleServerCompat.CarriageStatus(train.id, trainName,
                    serviceType, safe(current.name), route.terminal(), carriageCount,
                    route.upcomingStops(), 0L, 0.0D, CarriageStatusPayload.Phase.ARRIVED,
                    schedulePresent ? remainingDwellTicks(train) : -1L,
                    schedulePresent, level.getGameTime());
        }
        GlobalStation destination = train.navigation == null ? null
                : train.navigation.destination;
        if (destination != null) {
            double distance = Math.max(0.0D, train.navigation.distanceToDestination);
            boolean signalHold = stoppedForSignal(train);
            // ScheduleRuntime predictions use route/planned-speed data and therefore do not jump
            // whenever the physical train accelerates or brakes. Fall back to the same planned
            // speed model if a prediction has not been populated yet.
            long eta = signalHold ? UNKNOWN_ETA_TICKS
                    : schedulePresent ? scheduledEta(level, train, destination.name) : -1L;
            if (eta < 0L) eta = plannedEta(train, distance);
            long noticeTicks = dev.minescreen.MineScreenConfig
                    .CARRIAGE_ARRIVAL_NOTICE_SECONDS.get() * 20L;
            CarriageStatusPayload.Phase phase = switch (PassengerArrivalLogic.select(false,
                    signalHold, eta, noticeTicks, UNKNOWN_ETA_TICKS)) {
                case EN_ROUTE -> CarriageStatusPayload.Phase.EN_ROUTE;
                case WAITING_SIGNAL -> CarriageStatusPayload.Phase.WAITING_SIGNAL;
                case APPROACHING -> CarriageStatusPayload.Phase.APPROACHING;
                case ARRIVED -> CarriageStatusPayload.Phase.ARRIVED;
            };
            return new CreateTrainScheduleServerCompat.CarriageStatus(train.id, trainName,
                    serviceType, safe(destination.name), route.terminal(), carriageCount,
                    route.upcomingStops(), eta, distance, phase, -1L, schedulePresent,
                    level.getGameTime());
        }
        return new CreateTrainScheduleServerCompat.CarriageStatus(train.id, trainName,
                serviceType, "", route.terminal(), carriageCount, route.upcomingStops(), -1L,
                Double.NaN, CarriageStatusPayload.Phase.NO_ROUTE, -1L, schedulePresent,
                level.getGameTime());
    }

    @Override
    public boolean updateMovingDisplay(ServerLevel level, ServerPlayer player,
            MovingCeilingDisplayUpdatePayload payload) {
        Entity candidate = level.getEntity(payload.entityId());
        if (!(candidate instanceof AbstractContraptionEntity entity)
                || entity.isRemoved() || entity.getContraption() == null) {
            return false;
        }
        Vec3 worldPosition = entity.toGlobalVector(Vec3.atCenterOf(payload.localPos()), 1.0F);
        if (player.distanceToSqr(worldPosition) > 256.0D) return false;
        Contraption contraption = entity.getContraption();
        StructureTemplate.StructureBlockInfo selected = contraption.getBlocks()
                .get(payload.localPos());
        if (selected == null) return false;
        boolean door = selected.state().is(dev.minescreen.MineScreen.DOOR_LCD_BLOCK.get())
                || selected.state().is(
                        dev.minescreen.MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get());
        boolean ceiling = selected.state().is(
                dev.minescreen.MineScreen.CEILING_DISPLAY_BLOCK.get());
        if ((!door && !ceiling) || payload.side() < 0 || payload.side() > 1
                || (door && payload.side() != 0)) {
            return false;
        }
        List<BlockPos> tiles = connectedDisplayTiles(contraption, payload.localPos(),
                selected.state(), door);
        if (tiles.isEmpty() || tiles.size() > 256 || UPDATE_TAGS == null) return false;
        Map<BlockPos, CompoundTag> updateTags = updateTags(contraption);
        if (updateTags == null) return false;
        for (BlockPos tile : tiles) {
            CompoundTag tag = currentTag(contraption, updateTags, tile);
            if (tag.hasUUID("owner") && !tag.getUUID("owner").equals(player.getUUID())
                    && !player.hasPermissions(2)) {
                return false;
            }
        }
        boolean linked = door || payload.linkedSides();
        for (BlockPos tile : tiles) {
            CompoundTag tag = currentTag(contraption, updateTags, tile);
            writeDisplaySettings(tag, payload.side(), linked, payload, player.getUUID());
            updateTags.put(tile.immutable(), tag);
        }
        var state = new MovingCeilingDisplayStatePayload(entity.getId(), tiles, payload.side(),
                linked, payload.mode(), payload.line(), payload.destination(),
                payload.nextStop(), payload.eta(), payload.status(), payload.notice(),
                payload.templateId(), payload.overlayTemplateId(), payload.turnaround(),
                player.getUUID());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, state);
        return true;
    }

    @Override
    public void tickServer(MinecraftServer server) {
        CreateTrainSegmentLearningService.tick(server);
    }

    @Override
    public boolean updateMovingTrafficDisplay(ServerLevel level, ServerPlayer player,
            MovingTrafficDisplayUpdatePayload payload) {
        Entity candidate = level.getEntity(payload.entityId());
        if (!(candidate instanceof AbstractContraptionEntity entity)
                || entity.isRemoved() || entity.getContraption() == null) return false;
        Vec3 worldPosition = entity.toGlobalVector(Vec3.atCenterOf(payload.localPos()), 1.0F);
        if (player.distanceToSqr(worldPosition) > 256.0D || UPDATE_TAGS == null) return false;
        Contraption contraption = entity.getContraption();
        StructureTemplate.StructureBlockInfo selected = contraption.getBlocks()
                .get(payload.localPos());
        if (selected == null || !selected.state().is(
                dev.minescreen.MineScreen.TRAFFIC_DISPLAY_BLOCK.get())) return false;
        List<BlockPos> tiles = connectedTrafficTiles(contraption, payload.localPos(),
                selected.state());
        if (tiles.isEmpty() || tiles.size() > 256) return false;
        Map<BlockPos, CompoundTag> updateTags = updateTags(contraption);
        if (updateTags == null) return false;
        for (BlockPos tile : tiles) {
            CompoundTag tag = currentTag(contraption, updateTags, tile);
            if (tag.hasUUID("owner") && !tag.getUUID("owner").equals(player.getUUID())
                    && !player.hasPermissions(2)) return false;
        }
        for (BlockPos tile : tiles) {
            CompoundTag tag = currentTag(contraption, updateTags, tile);
            writeTrafficSettings(tag, payload, player.getUUID());
            updateTags.put(tile.immutable(), tag);
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
        return true;
    }

    private static List<BlockPos> connectedTrafficTiles(Contraption contraption, BlockPos start,
            BlockState selected) {
        Direction facing = selected.getValue(dev.minescreen.TextDisplayBlock.FACING);
        Direction right = dev.minescreen.ScreenGeometry.rightDirection(facing);
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        open.add(start.immutable());
        while (!open.isEmpty() && visited.size() <= 256) {
            BlockPos current = open.removeFirst();
            if (visited.contains(current)) continue;
            StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(current);
            if (info == null || !info.state().is(
                    dev.minescreen.MineScreen.TRAFFIC_DISPLAY_BLOCK.get())
                    || info.state().getValue(dev.minescreen.TextDisplayBlock.FACING) != facing) {
                continue;
            }
            visited.add(current.immutable());
            open.add(current.relative(right));
            open.add(current.relative(right.getOpposite()));
            open.add(current.above());
            open.add(current.below());
        }
        return visited.stream().sorted(Comparator
                .comparingInt((BlockPos position) -> position.getY())
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ)).toList();
    }

    private static void writeTrafficSettings(CompoundTag tag,
            MovingTrafficDisplayUpdatePayload payload, java.util.UUID editor) {
        tag.putString("back_mode", payload.backMode().name());
        String prefix = payload.backSide() ? "back_" : "";
        tag.putString(prefix + "text", payload.destination());
        tag.putInt(prefix + "text_color", payload.textColor());
        tag.putInt(prefix + "background_color", payload.backgroundColor());
        tag.putString(prefix + "animation", payload.animation().name());
        tag.putFloat(prefix + "speed", payload.speed());
        tag.putInt(prefix + "font_size", payload.fontSize());
        tag.putString(prefix + "traffic_line", payload.line());
        tag.putString(prefix + "traffic_destination", payload.destination());
        tag.putString(prefix + "traffic_current", payload.currentStop());
        tag.putString(prefix + "traffic_next", payload.nextStop());
        tag.putString(prefix + "traffic_eta", payload.eta());
        tag.putString(prefix + "traffic_status", payload.status());
        tag.putString(prefix + "template_id", payload.templateId());
        tag.putString(prefix + "overlay_template_id", payload.overlayTemplateId());
        tag.putString("traffic_role", payload.trafficRole().name());
        tag.putString(prefix + "station_mode", payload.stationMode().name());
        tag.putString(prefix + "station_binding_name", payload.stationBindingName());
        tag.putString(prefix + "station_turnaround_name", payload.stationTurnaroundName());
        tag.putString(prefix + "station_train_type", payload.stationTrainType());
        tag.putString(prefix + "station_map_template", payload.stationMapTemplateId());
        if (!tag.hasUUID("owner")) tag.putUUID("owner", editor);
    }

    private static List<BlockPos> connectedDisplayTiles(Contraption contraption, BlockPos start,
            BlockState selected, boolean door) {
        Direction positive;
        if (door) {
            Direction facing = selected.getValue(BlockStateProperties.HORIZONTAL_FACING);
            positive = dev.minescreen.ScreenGeometry.rightDirection(facing);
        } else {
            Direction.Axis axis = selected.getValue(dev.minescreen.CeilingDisplayBlock.AXIS);
            positive = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        }
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        open.add(start.immutable());
        while (!open.isEmpty() && visited.size() <= 256) {
            BlockPos current = open.removeFirst();
            if (visited.contains(current)) continue;
            StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(current);
            if (info == null || !sameDisplay(selected, info.state(), door)) continue;
            visited.add(current.immutable());
            open.add(current.relative(positive));
            open.add(current.relative(positive.getOpposite()));
        }
        return visited.stream().sorted(Comparator
                .comparingInt((BlockPos position) -> position.getX())
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)).toList();
    }

    private static boolean sameDisplay(BlockState selected, BlockState candidate, boolean door) {
        if (door) {
            return candidate.getBlock() == selected.getBlock()
                    && candidate.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            == selected.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return candidate.is(dev.minescreen.MineScreen.CEILING_DISPLAY_BLOCK.get())
                && candidate.getValue(dev.minescreen.CeilingDisplayBlock.AXIS)
                        == selected.getValue(dev.minescreen.CeilingDisplayBlock.AXIS);
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, CompoundTag> updateTags(Contraption contraption) {
        try {
            return (Map<BlockPos, CompoundTag>) UPDATE_TAGS.get(contraption);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static CompoundTag currentTag(Contraption contraption,
            Map<BlockPos, CompoundTag> updateTags, BlockPos position) {
        CompoundTag updated = updateTags.get(position);
        if (updated != null) return updated.copy();
        StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(position);
        return info != null && info.nbt() != null ? info.nbt().copy() : new CompoundTag();
    }

    private static void writeDisplaySettings(CompoundTag tag, int side, boolean linked,
            MovingCeilingDisplayUpdatePayload payload, java.util.UUID editor) {
        tag.putBoolean("linked_sides", linked);
        writeSide(tag, side, payload);
        if (linked) writeSide(tag, 1 - side, payload);
        if (!tag.hasUUID("owner")) tag.putUUID("owner", editor);
    }

    private static void writeSide(CompoundTag tag, int side,
            MovingCeilingDisplayUpdatePayload payload) {
        String key = "side_" + side + "_";
        tag.putString(key + "mode", payload.mode().name());
        tag.putString(key + "line", payload.line());
        tag.putString(key + "destination", payload.destination());
        tag.putString(key + "next", payload.nextStop());
        tag.putString(key + "eta", payload.eta());
        tag.putString(key + "status", payload.status());
        tag.putString(key + "notice", payload.notice());
        tag.putString(key + "template", payload.templateId());
        tag.putString(key + "overlay_template", payload.overlayTemplateId());
        tag.putString(key + "turnaround", payload.turnaround());
    }

    private static Field findUpdateTagsField() {
        try {
            Field field = Contraption.class.getDeclaredField("updateTags");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static long plannedEta(Train train, double distance) {
        return TrainEtaEstimator.estimateTicks(distance, train.speed,
                Math.min(Math.abs(train.targetSpeed), Math.max(0.001D, train.maxSpeed())),
                train.acceleration(), 24_000L, UNKNOWN_ETA_TICKS);
    }

    private static boolean stoppedForSignal(Train train) {
        return train.navigation != null && train.navigation.waitingForSignal != null
                && Math.abs(train.speed) < 0.02D && Math.abs(train.targetSpeed) < 0.02D;
    }

    private static long cruiseSegmentEta(Train train, double distance) {
        double maximum = Math.max(0.001D, train.maxSpeed());
        double cruise = Math.min(Math.abs(train.throttle) * maximum,
                (maximum + Math.max(0.001D, train.maxTurnSpeed())) * 0.5D);
        if (!Double.isFinite(cruise) || cruise < 0.001D) cruise = Math.abs(train.targetSpeed);
        if (!Double.isFinite(cruise) || cruise < 0.001D) return UNKNOWN_ETA_TICKS;
        return Math.max(1L, Math.round(Math.max(0.0D, distance) / cruise) * 2L);
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
                // A condition group is a sequence. Include all later deterministic delays in the
                // same group; counting only the active condition made ETA shorten again whenever
                // a station used two consecutive wait conditions.
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

    private static boolean matchesType(Train train, String wanted) {
        if (wanted.isBlank()) return true;
        String name = train.name == null ? "" : train.name.getString();
        String icon = train.icon == null || train.icon.getId() == null ? ""
                : train.icon.getId().toString();
        return name.toLowerCase(Locale.ROOT).contains(wanted)
                || icon.toLowerCase(Locale.ROOT).contains(wanted);
    }

    private static String passengerServiceType(String name) {
        return TrainNameFormat.parse(name).serviceTypeOrLegacy();
    }

    private static RouteSummary routeSummary(Train train, String turnaroundStation,
            java.util.Collection<Train> allTrains) {
        GlobalStation current = train.getCurrentStation();
        String activeStation = current != null && current.name != null && !current.name.isBlank()
                ? current.name
                : train.navigation != null && train.navigation.destination != null
                        ? safe(train.navigation.destination.name) : "";
        RouteEndpoints endpoints = routeEndpoints(train, activeStation, turnaroundStation, false);
        // A loop has no passenger-facing terminus. Keep it blank and let displays use the next
        // station/direction; never put an internal sentinel into network or template text.
        String scheduledTerminal = endpoints.loop() ? "" : endpoints.destination();
        List<String> stops = legStopsForCarriage(train, activeStation, endpoints,
                scheduledTerminal, turnaroundStation, allTrains);
        if (stops.isEmpty()) {
            if (!activeStation.isBlank()) addExactStop(stops, activeStation);
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                try {
                    var predictions = new ArrayList<>(train.runtime.submitPredictions());
                    predictions.removeIf(java.util.Objects::isNull);
                    predictions.sort(Comparator.comparingInt(prediction -> prediction.ticks));
                    for (var prediction : predictions) {
                        addExactStop(stops, prediction.destination);
                        if (stops.size() >= 8) break;
                    }
                } catch (RuntimeException ignored) {
                    // Create may rebuild the prediction collection while the train changes entries.
                }
            }
        }
        if (!endpoints.loop() && scheduledTerminal.isBlank() && !stops.isEmpty()) {
            scheduledTerminal = stops.getLast().startsWith("~")
                    ? stops.getLast().substring(1).trim() : stops.getLast();
        }
        return new RouteSummary(scheduledTerminal, stops);
    }

    private static List<String> legStopsForCarriage(Train train, String activeStation,
            RouteEndpoints endpoints, String terminal, String turnaroundStation,
            java.util.Collection<Train> allTrains) {
        if (train.runtime == null || train.runtime.getSchedule() == null) {
            return new ArrayList<>();
        }
        try {
            var schedule = train.runtime.getSchedule();
            List<ScheduledStop> scheduledStops = collectScheduledStops(schedule);
            if (scheduledStops.isEmpty()) return new ArrayList<>();
            int cycleLen = effectiveCycleLength(scheduledStops, schedule.cyclic);
            int activeIdx = activeStopIndex(train, scheduledStops, activeStation,
                    schedule.entries.size(), schedule.cyclic) % cycleLen;
            if (endpoints.loop()) {
                List<String> stops = new ArrayList<>();
                addExactStop(stops, scheduledStops.get(activeIdx).name());
                for (int step = 1; step < cycleLen && stops.size() < 24; step++) {
                    int idx = (activeIdx + step) % cycleLen;
                    String stopName = scheduledStops.get(idx).name();
                    if (sameStation(stopName, scheduledStops.get(activeIdx).name())) break;
                    addExactStop(stops, stopName);
                }
                return expandIntermediateSkippedStops(train, stops, allTrains);
            }
            boolean[] apices = findTurnaroundApices(scheduledStops, cycleLen, schedule.cyclic,
                    turnaroundStation);
            boolean approachingApex = apices[activeIdx] && train.getCurrentStation() == null;
            int originIdx = activeIdx;
            if (!apices[activeIdx] || approachingApex) {
                for (int step = 1; step < cycleLen; step++) {
                    int cand = schedule.cyclic
                            ? Math.floorMod(activeIdx - step, cycleLen)
                            : activeIdx - step;
                    if (cand < 0) break;
                    if (apices[cand]) {
                        originIdx = cand;
                        break;
                    }
                }
            }
            int destIdx = activeIdx;
            if (!approachingApex) {
                for (int step = 1; step <= cycleLen; step++) {
                    int cand = schedule.cyclic
                            ? (activeIdx + step) % cycleLen
                            : activeIdx + step;
                    if (cand >= cycleLen) break;
                    if (apices[cand]) {
                        destIdx = cand;
                        break;
                    }
                }
            }
            List<String> stops = new ArrayList<>();
            addExactStop(stops, scheduledStops.get(originIdx).name());
            for (int step = 1; step <= cycleLen && stops.size() < 24; step++) {
                int idx = schedule.cyclic
                        ? (originIdx + step) % cycleLen
                        : originIdx + step;
                if (idx >= cycleLen) break;
                addExactStop(stops, scheduledStops.get(idx).name());
                if (idx == destIdx) break;
            }
            return expandIntermediateSkippedStops(train, stops, allTrains);
        } catch (RuntimeException ignored) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns passenger-facing route endpoints from the assigned Create schedule. A train's
     * navigation destination is only its next stop, so using it as the terminus made every
     * intermediate station appear to be the final destination.
     */
    private static RouteEndpoints routeEndpoints(Train train, String stationName) {
        return routeEndpoints(train, stationName, "", true);
    }

    private static RouteEndpoints routeEndpoints(Train train, String stationName,
            String turnaroundStation) {
        return routeEndpoints(train, stationName, turnaroundStation, true);
    }

    private static RouteEndpoints routeEndpoints(Train train, String stationName,
            String turnaroundStation, boolean platformBoard) {
        if (train.runtime == null || train.runtime.getSchedule() == null) {
            return RouteEndpoints.EMPTY;
        }
        try {
            var schedule = train.runtime.getSchedule();
            List<ScheduledStop> scheduledStops = collectScheduledStops(schedule);
            if (scheduledStops.isEmpty()) return RouteEndpoints.EMPTY;
            int cycleLen = effectiveCycleLength(scheduledStops, schedule.cyclic);
            int activeIndex = activeStopIndex(train, scheduledStops, stationName,
                    schedule.entries.size(), schedule.cyclic) % cycleLen;
            int boardIndex = resolveBoardStopIndex(scheduledStops, cycleLen, schedule.cyclic,
                    activeIndex, stationName);
            int effectiveIndex = (boardIndex >= 0 ? boardIndex : activeIndex) % cycleLen;
            if (TrainNameFormat.parse(train.name == null ? "" : train.name.getString()).loop()) {
                return loopEndpoints(scheduledStops, effectiveIndex, stationName);
            }

            String direction = effectiveIndex < 0 ? ""
                    : directionMarker(scheduledStops.get(effectiveIndex).name());
            if (effectiveIndex >= 0 && direction.isBlank()) {
                int directionalIndex = directionalNeighbour(scheduledStops, effectiveIndex,
                        train.currentStation != null, schedule.cyclic);
                if (directionalIndex >= 0) {
                    int dirCheckIdx = directionalIndex % cycleLen;
                    direction = directionMarker(scheduledStops.get(dirCheckIdx).name());
                    if (!direction.isBlank()) effectiveIndex = dirCheckIdx;
                }
            }
            if (effectiveIndex >= 0 && !direction.isBlank()) {
                int start = effectiveIndex;
                int end = effectiveIndex;
                int examined = 0;
                while (examined++ < cycleLen - 1) {
                    int previous = start - 1;
                    if (previous < 0) {
                        if (!schedule.cyclic) break;
                        previous = cycleLen - 1;
                    }
                    String marker = directionMarker(scheduledStops.get(previous).name());
                    if (marker.equals(direction)) start = previous;
                    else {
                        if (marker.isBlank()) start = previous;
                        break;
                    }
                }
                examined = 0;
                while (examined++ < cycleLen - 1) {
                    int next = end + 1;
                    if (next >= cycleLen) {
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

            boolean[] apices = findTurnaroundApices(scheduledStops, cycleLen, schedule.cyclic,
                    turnaroundStation);
            boolean approachingApexOnboard = !platformBoard
                    && apices[effectiveIndex]
                    && train.getCurrentStation() == null;
            int originIdx = effectiveIndex;
            if (!apices[effectiveIndex] || approachingApexOnboard) {
                for (int step = 1; step < cycleLen; step++) {
                    int cand = schedule.cyclic
                            ? Math.floorMod(effectiveIndex - step, cycleLen)
                            : effectiveIndex - step;
                    if (cand < 0) break;
                    if (apices[cand]) {
                        originIdx = cand;
                        break;
                    }
                }
            }
            int destIdx = effectiveIndex;
            if (!approachingApexOnboard) {
                for (int step = 1; step <= cycleLen; step++) {
                    int cand = schedule.cyclic
                            ? (effectiveIndex + step) % cycleLen
                            : effectiveIndex + step;
                    if (cand >= cycleLen) break;
                    if (apices[cand]) {
                        destIdx = cand;
                        break;
                    }
                }
            }
            return new RouteEndpoints(scheduledStops.get(originIdx).name(),
                    scheduledStops.get(destIdx).name(), false);
        } catch (RuntimeException ignored) {
            return RouteEndpoints.EMPTY;
        }
    }

    private static List<ScheduledStop> collectScheduledStops(
            com.simibubi.create.content.trains.schedule.Schedule schedule) {
        List<ScheduledStop> scheduledStops = new ArrayList<>();
        for (int entryIndex = 0; entryIndex < schedule.entries.size(); entryIndex++) {
            var entry = schedule.entries.get(entryIndex);
            if (entry != null && entry.instruction instanceof DestinationInstruction target) {
                addScheduledStop(scheduledStops, entryIndex, target.getFilter());
            }
        }
        return scheduledStops;
    }

    private static int effectiveCycleLength(List<ScheduledStop> stops, boolean cyclic) {
        if (cyclic && stops.size() > 2
                && sameStation(stops.getFirst().name(), stops.getLast().name())) {
            return stops.size() - 1;
        }
        return stops.size();
    }

    private static int resolveBoardStopIndex(List<ScheduledStop> stops, int cycleLen,
            boolean cyclic, int activeIndex, String stationName) {
        if (stationName == null || stationName.isBlank() || cycleLen <= 0) return -1;
        int startSearch = Math.max(0, activeIndex % cycleLen);
        for (int offset = 0; offset < cycleLen; offset++) {
            int candidate = startSearch + offset;
            if (candidate >= cycleLen) {
                if (!cyclic) break;
                candidate %= cycleLen;
            }
            if (destinationMatches(stops.get(candidate).name(), stationName)
                    || sameStation(stops.get(candidate).name(), stationName)) {
                return candidate;
            }
        }
        int named = namedStopIndex(stops, stationName);
        return named >= 0 ? named % cycleLen : -1;
    }

    /**
     * Identifies the turnaround apices (terminal stations) of an out-and-back schedule regardless
     * of which station entry index (0..N-1) the schedule was started at, and treats multi-platform
     * intermediate stops (e.g. {@code St2_1} and {@code St2_2}) as mirrors of the same station.
     */
    private static boolean[] findTurnaroundApices(List<ScheduledStop> stops, int cycleLen,
            boolean cyclic, String turnaroundStation) {
        boolean[] apices = new boolean[Math.max(1, cycleLen)];
        if (cycleLen <= 2) {
            for (int i = 0; i < cycleLen; i++) apices[i] = true;
            return apices;
        }
        if (!cyclic) {
            apices[0] = true;
            apices[cycleLen - 1] = true;
            int explicit = namedStopIndex(stops, turnaroundStation);
            if (explicit > 0 && explicit < cycleLen - 1) {
                apices[explicit] = true;
            } else if (sameBaseStation(stops.getFirst().name(), stops.get(cycleLen - 1).name())) {
                int bestPivot = -1;
                int bestScore = 0;
                for (int pivot = 1; pivot < cycleLen - 1; pivot++) {
                    int maxD = Math.min(pivot, cycleLen - 1 - pivot);
                    int score = 0;
                    for (int d = 1; d <= maxD; d++) {
                        if (sameBaseStation(stops.get(pivot - d).name(),
                                stops.get(pivot + d).name())) {
                            score++;
                        } else {
                            break;
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestPivot = pivot;
                    }
                }
                if (bestPivot > 0) apices[bestPivot] = true;
            }
            return apices;
        }

        int explicit = namedStopIndex(stops, turnaroundStation);
        if (explicit >= 0 && explicit < cycleLen) {
            apices[explicit] = true;
        }
        int maxDist = (cycleLen - 1) / 2;
        int apexCount = 0;
        int singleApex = -1;
        for (int i = 0; i < cycleLen; i++) {
            int score = 0;
            for (int d = 1; d <= maxDist; d++) {
                int prev = Math.floorMod(i - d, cycleLen);
                int next = (i + d) % cycleLen;
                if (sameBaseStation(stops.get(prev).name(), stops.get(next).name())) {
                    score++;
                } else {
                    break;
                }
            }
            if (score > 0) {
                apices[i] = true;
            }
        }
        for (int i = 0; i < cycleLen; i++) {
            if (apices[i]) {
                apexCount++;
                singleApex = i;
            }
        }
        if (apexCount == 1 && singleApex >= 0) {
            apices[(singleApex + cycleLen / 2) % cycleLen] = true;
        } else if (apexCount == 0) {
            boolean foundPair = false;
            for (int a = 0; a < cycleLen && !foundPair; a++) {
                for (int b = a + 1; b < cycleLen; b++) {
                    if (sameBaseStation(stops.get(a).name(), stops.get(b).name())) {
                        int span1 = b - a;
                        int span2 = cycleLen - span1;
                        apices[(a + span1 / 2) % cycleLen] = true;
                        apices[(b + span2 / 2) % cycleLen] = true;
                        foundPair = true;
                        break;
                    }
                }
            }
            if (!foundPair) {
                apices[0] = true;
                apices[cycleLen / 2] = true;
            }
        }
        return apices;
    }

    private static final java.util.regex.Pattern BASE_PLATFORM_SUFFIX_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?iu)^(.+?)(?:[\\s_-]+(?:platform|plat|p|站台)\\s*([0-9]{1,2}[a-z]?)"
                            + "|[\\s]+([0-9]{1,2})\\s*站台"
                            + "|[_-]([0-9]{1,2}[a-z]?))$");

    private static boolean sameBaseStation(String first, String second) {
        return extractBaseStation(first).equalsIgnoreCase(extractBaseStation(second));
    }

    private static String extractBaseStation(String stationName) {
        String stripped = stripDirection(stationName);
        if (stripped.isBlank()) return "";
        java.util.regex.Matcher matcher = BASE_PLATFORM_SUFFIX_PATTERN.matcher(stripped);
        if (matcher.matches()) {
            String base = matcher.group(1).trim();
            if (!base.isBlank()) return base;
        }
        return stripped;
    }

    private static void addScheduledStop(List<ScheduledStop> stops, int entryIndex,
            String station) {
        String value = safe(station).trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        if (stops.isEmpty() || !stops.getLast().name().equalsIgnoreCase(value)) {
            stops.add(new ScheduledStop(entryIndex, value));
        }
    }

    private static int namedStopIndex(List<ScheduledStop> stops, String stationName) {
        String requested = safe(stationName).trim();
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
        // A platform at C0 must announce the onward direction (for example "via C1, C4"),
        // not "via C0, C1". Skip every occurrence that resolves to this board's own station.
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

    /**
     * Maps Create's real schedule entry index to the corresponding passenger stop. This is the
     * critical distinction for out-and-back schedules: the same platform name can legitimately
     * occur once on the outward leg and once on the return leg.
     */
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

    /**
     * Returns the reversal stop for C1,C2,C3,C2,C1 and for Create's common cyclic shorthand
     * C1,C2,C3,C2 (where the cycle itself supplies the final C1).
     */
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
        return safe(value).replaceAll(
                "(?iu)\\s*(上行|下行|上り|下り|upbound|downbound)\\s*", "")
                .replace("[", "").replace("]", "")
                .replace("【", "").replace("】", "").trim();
    }

    private static List<String> stopsToTerminus(Train train, String boardStation,
            RouteEndpoints endpoints, String destination, String turnaroundStation,
            java.util.Collection<Train> allTrains) {
        if (train.runtime == null || train.runtime.getSchedule() == null) {
            return destination == null || destination.isBlank() ? List.of() : List.of(destination);
        }
        try {
            var schedule = train.runtime.getSchedule();
            List<ScheduledStop> scheduledStops = collectScheduledStops(schedule);
            if (scheduledStops.isEmpty()) {
                return destination == null || destination.isBlank()
                        ? List.of() : List.of(destination);
            }
            int cycleLen = effectiveCycleLength(scheduledStops, schedule.cyclic);
            int activeIndex = activeStopIndex(train, scheduledStops, boardStation,
                    schedule.entries.size(), schedule.cyclic) % cycleLen;
            int boardIndex = resolveBoardStopIndex(scheduledStops, cycleLen, schedule.cyclic,
                    activeIndex, boardStation);
            int startIndex = (boardIndex >= 0 ? boardIndex : activeIndex) % cycleLen;

            boolean[] apices = findTurnaroundApices(scheduledStops, cycleLen, schedule.cyclic,
                    turnaroundStation);
            int destIdx = startIndex;
            for (int step = 1; step <= cycleLen; step++) {
                int cand = schedule.cyclic
                        ? (startIndex + step) % cycleLen
                        : startIndex + step;
                if (cand >= cycleLen) break;
                if (apices[cand]) {
                    destIdx = cand;
                    break;
                }
            }

            List<String> stops = new ArrayList<>();
            addExactStop(stops, scheduledStops.get(startIndex).name());
            for (int step = 1; step <= cycleLen && stops.size() < 32; step++) {
                int idx = schedule.cyclic
                        ? (startIndex + step) % cycleLen
                        : startIndex + step;
                if (idx >= cycleLen) break;
                String stopName = scheduledStops.get(idx).name();
                if (endpoints.loop() && sameStation(stopName, boardStation)) {
                    break;
                }
                addExactStop(stops, stopName);
                if (!endpoints.loop() && idx == destIdx) {
                    break;
                }
            }
            return expandIntermediateSkippedStops(train, stops, allTrains);
        } catch (RuntimeException ignored) {
            return destination == null || destination.isBlank() ? List.of() : List.of(destination);
        }
    }

    /**
     * Inserts skipped intermediate stations (prefixed with {@code "~"}) between consecutive
     * scheduled stops by comparing against longer stopping sequences of other scheduled trains
     * on the same railway network (and verifying against the physical rail path in
     * {@code train.graph}), while ignoring unscheduled assembly/temporary Track Stations.
     */
    private static List<String> expandIntermediateSkippedStops(Train train,
            List<String> stoppingStops, java.util.Collection<Train> allTrains) {
        if (stoppingStops == null || stoppingStops.size() < 2) {
            return stoppingStops == null ? List.of() : stoppingStops;
        }
        Set<String> scheduledNetworkBases = collectScheduledBaseStations(allTrains);
        List<String> expanded = new ArrayList<>();
        GlobalStation prevStation = train != null && train.graph != null
                ? CreateStationPathEstimator.resolveNext(train.graph, stoppingStops.getFirst(), null)
                : null;
        expanded.add(stoppingStops.getFirst());

        for (int i = 0; i < stoppingStops.size() - 1 && expanded.size() < 32; i++) {
            String fromStop = stoppingStops.get(i);
            String toStop = stoppingStops.get(i + 1);
            GlobalStation nextStation = train != null && train.graph != null
                    ? CreateStationPathEstimator.resolveNext(train.graph, toStop, prevStation)
                    : null;

            List<String> skipped = new ArrayList<>();
            if (allTrains != null) {
                skipped.addAll(findSkippedStopsFromPeerTrains(fromStop, toStop, allTrains));
            }
            if (skipped.isEmpty() && train != null && train.graph != null
                    && prevStation != null && nextStation != null) {
                for (String graphStop : CreateStationPathEstimator.intermediateStations(
                        train.graph, prevStation, nextStation)) {
                    if (isScheduledPassengerStation(graphStop, scheduledNetworkBases)) {
                        skipped.add(graphStop);
                    }
                }
            }
            for (String skipName : skipped) {
                if (expanded.size() >= 31) break;
                if (isTemporaryAssemblyStationName(skipName)) continue;
                boolean alreadyStopping = false;
                for (String stop : stoppingStops) {
                    if (sameBaseStation(stop, skipName)) {
                        alreadyStopping = true;
                        break;
                    }
                }
                if (!alreadyStopping) {
                    addSkippedStop(expanded, skipName);
                }
            }
            addExactStop(expanded, toStop);
            if (nextStation != null) prevStation = nextStation;
        }
        return expanded;
    }

    private static Set<String> collectScheduledBaseStations(
            java.util.Collection<Train> allTrains) {
        Set<String> bases = new HashSet<>();
        if (allTrains == null) return bases;
        for (Train peer : allTrains) {
            if (peer == null || peer.invalid || peer.runtime == null
                    || peer.runtime.getSchedule() == null) {
                continue;
            }
            try {
                for (ScheduledStop stop : collectScheduledStops(peer.runtime.getSchedule())) {
                    if (isTemporaryAssemblyStationName(stop.name())) continue;
                    String base = extractBaseStation(stop.name()).toLowerCase(Locale.ROOT);
                    if (!base.isBlank()) bases.add(base);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return bases;
    }

    private static boolean isScheduledPassengerStation(String stationName,
            Set<String> scheduledNetworkBases) {
        if (isTemporaryAssemblyStationName(stationName)) return false;
        String base = extractBaseStation(stationName).toLowerCase(Locale.ROOT);
        if (base.isBlank()) return false;
        return scheduledNetworkBases.contains(base);
    }

    private static boolean isTemporaryAssemblyStationName(String stationName) {
        String clean = stripDirection(stationName).trim().toLowerCase(Locale.ROOT);
        if (clean.isBlank()) return true;
        return clean.equals("track station")
                || clean.startsWith("track station ")
                || clean.equals("列车站")
                || clean.equals("火车站")
                || clean.startsWith("temp")
                || clean.contains("temporary")
                || clean.contains("depot")
                || clean.contains("yard")
                || clean.contains("siding")
                || clean.contains("assemble")
                || clean.contains("车库")
                || clean.contains("车辆段")
                || clean.contains("编组");
    }

    private static List<String> findSkippedStopsFromPeerTrains(String fromStop, String toStop,
            java.util.Collection<Train> allTrains) {
        List<String> bestSubsequence = List.of();
        for (Train peer : allTrains) {
            if (peer == null || peer.invalid || peer.runtime == null
                    || peer.runtime.getSchedule() == null) {
                continue;
            }
            try {
                var schedule = peer.runtime.getSchedule();
                List<ScheduledStop> peerStops = collectScheduledStops(schedule);
                int cycleLen = effectiveCycleLength(peerStops, schedule.cyclic);
                if (cycleLen < 3) continue;
                for (int start = 0; start < cycleLen; start++) {
                    if (!sameBaseStation(peerStops.get(start).name(), fromStop)) continue;
                    List<String> between = new ArrayList<>();
                    for (int step = 1; step < cycleLen && step <= 16; step++) {
                        int idx = schedule.cyclic ? (start + step) % cycleLen : start + step;
                        if (idx >= cycleLen) break;
                        String candidate = peerStops.get(idx).name();
                        if (sameBaseStation(candidate, fromStop)) break;
                        if (sameBaseStation(candidate, toStop)) {
                            if (between.size() > bestSubsequence.size()) {
                                bestSubsequence = List.copyOf(between);
                            }
                            break;
                        }
                        between.add(candidate);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return bestSubsequence;
    }

    private static void addSkippedStop(List<String> stops, String station) {
        String value = safe(station).trim();
        if (value.startsWith("~")) value = value.substring(1).trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        for (String existing : stops) {
            String cleanExisting = existing.startsWith("~")
                    ? existing.substring(1).trim() : existing;
            if (sameBaseStation(cleanExisting, value)) return;
        }
        stops.add("~" + value);
    }

    private static int countSkippedStops(List<String> stops) {
        if (stops == null || stops.isEmpty()) return 0;
        int count = 0;
        for (String s : stops) {
            if (s != null && s.startsWith("~")) count++;
        }
        return count;
    }

    private static void addExactStop(List<String> stops, String station) {
        String value = safe(station).trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        if (stops.isEmpty() || !stops.get(stops.size() - 1).equalsIgnoreCase(value)) {
            stops.add(value);
        }
    }

    private record RouteSummary(String terminal, List<String> upcomingStops) {
        private RouteSummary {
            upcomingStops = List.copyOf(upcomingStops);
        }
    }

    private record RouteEndpoints(String origin, String destination, boolean loop) {
        private static final RouteEndpoints EMPTY = new RouteEndpoints("", "", false);
    }

    private record ScheduledStop(int entryIndex, String name) {}

    private static String directionMarker(String stationName) {
        String value = safe(stationName).toLowerCase(Locale.ROOT);
        if (value.contains("上行") || value.contains("上り") || value.contains("upbound")) {
            return "up";
        }
        if (value.contains("下行") || value.contains("下り") || value.contains("downbound")) {
            return "down";
        }
        return "";
    }

    private static long etaToStation(ServerLevel level, Train train, GlobalStation station) {
        if (station.id != null && station.id.equals(train.currentStation)) return 0L;
        GlobalStation destination = train.navigation == null ? null : train.navigation.destination;
        if (destination == null || station.id == null || !station.id.equals(destination.id)) {
            return -1L;
        }
        long planned = plannedEta(train, Math.max(0.0D,
                train.navigation.distanceToDestination));
        long persistent = CreateTrainSegmentLearningService.estimateCurrent(level, train);
        return persistent > 0L ? hybridCurrentLegTicks(train, persistent, planned) : planned;
    }

    private static long scheduledEta(ServerLevel level, Train train, String stationName) {
        if (train.runtime == null || train.runtime.getSchedule() == null
                || stationName == null || stationName.isBlank()) return -1L;
        long best = Long.MAX_VALUE;
        boolean matchingButUnknown = false;
        try {
            for (var prediction : train.runtime.submitPredictions()) {
                if (prediction != null && destinationMatches(prediction.destination, stationName)) {
                    // Create's prediction ticks are already cumulative from the train's current
                    // position. Select the earliest cumulative visit to this exact platform
                    // instead of whichever matching entry happens to iterate first.
                    if (prediction.ticks < 0) matchingButUnknown = true;
                    else best = Math.min(best, prediction.ticks);
                }
            }
        } catch (RuntimeException ignored) {
            // The runtime can rebuild its prediction list during the same server tick.
        }
        long estimated = estimateFullScheduleEta(level, train, stationName);
        if (estimated >= 0L && estimated < UNKNOWN_ETA_TICKS) return estimated;
        if (best != Long.MAX_VALUE) return best;
        return estimated >= 0L ? estimated
                : matchingButUnknown ? UNKNOWN_ETA_TICKS : -1L;
    }

    /**
     * Create records transit time per schedule entry only after that leg has been travelled.
     * Until then submitPredictions() returns -1 for every later stop, which used to make a board
     * appear only on the final 10–20 second leg. Build a cumulative timetable estimate instead:
     * remaining current leg + every intermediate dwell + learned/fallback future legs.
     */
    private static long estimateFullScheduleEta(ServerLevel level, Train train,
            String stationName) {
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
            long elapsed = remainingCurrentLegTicks(level, train, currentEntry, fallbackSegment);
            if (elapsed >= UNKNOWN_ETA_TICKS) return UNKNOWN_ETA_TICKS;
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
                if (offset == 0 && train.getCurrentStation() != null
                        && entryMatchesStation(train, entryIndex, train.getCurrentStation().name)) {
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
                long segmentTicks = futureSegmentTicks(level, train, nextIndex, stopAtEntry,
                        nextStop, fallbackSegment);
                if (segmentTicks >= UNKNOWN_ETA_TICKS) return UNKNOWN_ETA_TICKS;
                // Account for platform approach deceleration and departure launch acceleration
                // (~2 seconds per intermediate scheduled stop) so an express train bypassing
                // intermediate stations is properly predicted to overtake an all-stops train.
                long stopApproachPenalty = dwell > 0L ? 40L : 25L;
                elapsed = saturatingAdd(elapsed, saturatingAdd(segmentTicks, stopApproachPenalty));
                stopAtEntry = nextStop;
            }
        } catch (RuntimeException ignored) {
            // Create can replace the runtime schedule during this server tick.
        }
        return -1L;
    }

    private static boolean entryMatchesStation(Train train, int entryIndex, String stationName) {
        if (train.runtime == null || train.runtime.getSchedule() == null
                || stationName == null || stationName.isBlank()) return false;
        var entries = train.runtime.getSchedule().entries;
        if (entryIndex < 0 || entryIndex >= entries.size()) return false;
        var entry = entries.get(entryIndex);
        return entry != null && entry.instruction instanceof DestinationInstruction target
                && destinationMatches(target.getFilter(), stationName);
    }

    private static long remainingCurrentLegTicks(ServerLevel level, Train train, int entryIndex,
            long fallback) {
        GlobalStation present = train.getCurrentStation();
        if (present != null) {
            if (entryMatchesStation(train, entryIndex, present.name)) {
                return 0L;
            }
            // The station wait condition has finished and currentEntry has advanced to the next
            // stop while the train is still berthed at 'present' waiting for a clear path/signal.
            GlobalStation targetStop = scheduledStop(train, entryIndex, present, false);
            return futureSegmentTicks(level, train, entryIndex, present, targetStop, fallback);
        }
        long persistent = CreateTrainSegmentLearningService.estimateCurrent(level, train);
        long recorded = persistent > 0L ? persistent : recordedSegmentTicks(train, entryIndex);
        if (train.navigation != null && train.navigation.destination != null
                && train.navigation.distanceToDestination > 0.001D) {
            long planned = plannedEta(train,
                    Math.max(0.0D, train.navigation.distanceToDestination));
            long hybrid = hybridCurrentLegTicks(train, recorded, planned);
            if (hybrid >= 0L && hybrid < UNKNOWN_ETA_TICKS) return hybrid;
            // When stopped at a signal on the first un-learned run, estimate from remaining track
            // distance at normal cruise speed so the train still shows a valid ETA.
            double maximum = Math.max(0.001D, train.maxSpeed());
            double turn = Math.max(0.001D, train.maxTurnSpeed());
            double scheduledCruise = Math.min(maximum, (maximum + turn) * 0.5D);
            long nominalRemaining = TrainEtaEstimator.estimateTicks(
                    train.navigation.distanceToDestination, 0.0D, scheduledCruise,
                    train.acceleration(), 24_000L, UNKNOWN_ETA_TICKS);
            if (nominalRemaining > 0L && nominalRemaining < UNKNOWN_ETA_TICKS) {
                return nominalRemaining;
            }
        }
        long learned = recorded > 0L ? recorded : learnedSegmentTicks(train, entryIndex, fallback);
        if (train.navigation != null && train.navigation.destination != null
                && train.navigation.distanceStartedAt > 0.001D) {
            double fraction = Math.max(0.0D, Math.min(1.0D,
                    train.navigation.distanceToDestination
                            / train.navigation.distanceStartedAt));
            return Math.max(1L, Math.round(learned * fraction));
        }
        return learned;
    }

    /**
     * Uses three independent clocks for the active leg: current-physics integration, progress
     * through the last measured traversal, and elapsed time against that traversal. Their median
     * rejects the one clock most affected by a curve, transient target-speed change or a late
     * Create prediction refresh. The physics clock is calibrated against the measured whole leg,
     * so permanent route speed limits remain represented without carrying an entire old delay.
     */
    private static long hybridCurrentLegTicks(Train train, long recorded, long physics) {
        if (train.navigation == null) return physics;
        List<Long> candidates = new ArrayList<>(3);
        long calibratedPhysics = physics;
        if (recorded > 0L && physics >= 0L && physics < UNKNOWN_ETA_TICKS
                && train.navigation.distanceStartedAt > 0.001D) {
            double maximum = Math.max(0.001D, train.maxSpeed());
            double turn = Math.max(0.001D, train.maxTurnSpeed());
            double cruise = Math.min(maximum, (maximum + turn) * 0.5D);
            long nominalWhole = TrainEtaEstimator.estimateTicks(
                    train.navigation.distanceStartedAt, 0.0D, cruise, train.acceleration(),
                    24_000L, UNKNOWN_ETA_TICKS);
            if (nominalWhole > 0L && nominalWhole < UNKNOWN_ETA_TICKS) {
                double calibration = Math.max(0.70D,
                        Math.min(1.60D, recorded / (double) nominalWhole));
                calibratedPhysics = Math.max(1L, Math.round(physics * calibration));
            }
        }
        if (calibratedPhysics >= 0L && calibratedPhysics < UNKNOWN_ETA_TICKS) {
            candidates.add(calibratedPhysics);
        }
        if (recorded > 0L && train.navigation.distanceStartedAt > 0.001D) {
            double fraction = Math.max(0.0D, Math.min(1.0D,
                    train.navigation.distanceToDestination
                            / train.navigation.distanceStartedAt));
            long byDistance = Math.max(1L, Math.round(recorded * fraction));
            candidates.add(byDistance);
            long byElapsed = Math.max(1L, recorded - Math.max(0, train.runtime.ticksInTransit));
            // During a signal hold elapsed transit time keeps advancing while graph distance does
            // not. Do not allow that clock to pull the estimate artificially toward zero.
            candidates.add(stoppedForSignal(train) ? byDistance : byElapsed);
        }
        if (candidates.isEmpty()) return UNKNOWN_ETA_TICKS;
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
            if (planned < UNKNOWN_ETA_TICKS) return planned;
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

    private static long futureSegmentTicks(ServerLevel level, Train train, int destinationEntry,
            GlobalStation from, GlobalStation to, long fallback) {
        long persistent = CreateTrainSegmentLearningService.estimateSegment(level, train, from, to);
        if (persistent > 0L) return persistent;
        long learned = recordedSegmentTicks(train, destinationEntry);
        if (learned > 0L) return learned;
        double distance = CreateStationPathEstimator.distance(train.graph, from, to);
        if (!Double.isFinite(distance)) return Math.max(20L, fallback);
        double maximum = Math.max(0.001D, train.maxSpeed());
        double turn = Math.max(0.001D, train.maxTurnSpeed());
        double scheduledCruise = Math.min(maximum, (maximum + turn) * 0.5D);
        long estimated = TrainEtaEstimator.estimateTicks(distance, 0.0D, scheduledCruise,
                train.acceleration(), 24_000L, UNKNOWN_ETA_TICKS);
        return estimated >= UNKNOWN_ETA_TICKS ? Math.max(20L, fallback) : estimated;
    }

    private static GlobalStation scheduledStop(Train train, int entryIndex,
            GlobalStation previous, boolean currentEntry) {
        if (currentEntry) {
            GlobalStation present = train.getCurrentStation();
            if (present != null) {
                if (entryMatchesStation(train, entryIndex, present.name)) {
                    return present;
                }
                previous = present;
            } else if (train.navigation != null && train.navigation.destination != null) {
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
        // Create treats each inner list as alternatives and uses the first group that consists of
        // scheduled delays. Match its estimateStayDuration behaviour by summing that group.
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

    private static long saturatingAdd(long left, long right) {
        if (left >= UNKNOWN_ETA_TICKS || right >= UNKNOWN_ETA_TICKS
                || right > UNKNOWN_ETA_TICKS - left) return UNKNOWN_ETA_TICKS;
        return Math.max(0L, left) + Math.max(0L, right);
    }

    private static boolean destinationMatches(String filter, String stationName) {
        String wanted = safe(filter).trim();
        String station = safe(stationName).trim();
        if (wanted.isBlank() || station.isBlank()) return false;
        if (wanted.equalsIgnoreCase(station)) return true;
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < wanted.length(); index++) {
            char character = wanted.charAt(index);
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return station.matches("(?iu)" + regex.append('$'));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
