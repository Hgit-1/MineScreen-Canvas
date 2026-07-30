package dev.minescreen.client.compat.create;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;

import dev.minescreen.ScreenGeometry;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.compat.ClientCarriageTrainState;
import dev.minescreen.client.compat.MovingStructureCompat;
import dev.minescreen.TrainNameFormat;
import dev.minescreen.TrainEtaEstimator;
import dev.minescreen.PassengerArrivalLogic;
import dev.minescreen.network.MovingCeilingDisplayStatePayload;
import dev.minescreen.network.MovingTrafficDisplayUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

/** Create 6.0.x implementation isolated behind {@link MovingStructureCompat}. */
public final class CreateMovingStructureBridge implements MovingStructureCompat.Bridge {
    private final Map<Level, WeakReference<AbstractContraptionEntity>> byVirtualLevel =
            new WeakHashMap<>();
    private final Map<Level, Set<PanelKey>> illuminatedPanels = new WeakHashMap<>();

    @Override
    public MovingStructureCompat.Placement locate(Level virtualLevel, AABB localBounds,
            float partialTick) {
        AbstractContraptionEntity entity = entityFor(virtualLevel);
        if (entity == null) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[] {localBounds.minX, localBounds.maxX}) {
            for (double y : new double[] {localBounds.minY, localBounds.maxY}) {
                for (double z : new double[] {localBounds.minZ, localBounds.maxZ}) {
                    Vec3 point = entity.toGlobalVector(new Vec3(x, y, z), partialTick);
                    minX = Math.min(minX, point.x);
                    minY = Math.min(minY, point.y);
                    minZ = Math.min(minZ, point.z);
                    maxX = Math.max(maxX, point.x);
                    maxY = Math.max(maxY, point.y);
                    maxZ = Math.max(maxZ, point.z);
                }
            }
        }
        Vec3 center = entity.toGlobalVector(localBounds.getCenter(), partialTick);
        return new MovingStructureCompat.Placement(entity.level().dimension(),
                new AABB(minX, minY, minZ, maxX, maxY, maxZ), center);
    }

    @Override
    public Vec3 toWorld(Level virtualLevel, Vec3 localPosition, float partialTick) {
        AbstractContraptionEntity entity = entityFor(virtualLevel);
        return entity == null ? null : entity.toGlobalVector(localPosition, partialTick);
    }

    @Override
    public Vec3 toLocal(Level virtualLevel, Vec3 worldPosition, float partialTick) {
        AbstractContraptionEntity entity = entityFor(virtualLevel);
        return entity == null ? null : entity.toLocalVector(worldPosition, partialTick);
    }

    @Override
    public void illuminateTestPanel(Level virtualLevel, BlockPos localPosition, Direction facing) {
        if (virtualLevel == null || entityFor(virtualLevel) == null
                || localPosition == null || facing == null) return;
        Set<PanelKey> panels = illuminatedPanels.computeIfAbsent(virtualLevel,
                ignored -> new HashSet<>());
        PanelKey key = new PanelKey(localPosition.immutable(), facing);
        BlockPos primaryRelay = localPosition.relative(facing);
        if (!panels.add(key) && virtualLevel.getBlockState(primaryRelay).is(Blocks.LIGHT)) return;

        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        // Create's virtual light engine does not contain the full real-world air volume. Sparse,
        // invisible relay sources make the interior falloff resemble a stationary level-15 block:
        // bright near the panel, then progressively softer toward the end of the carriage.
        injectRelay(virtualLevel, primaryRelay, 15);
        injectCross(virtualLevel, localPosition.relative(facing, 3), right, up, 12);
        injectCross(virtualLevel, localPosition.relative(facing, 5), right, up, 9);
        runVirtualLightEngine(virtualLevel);
    }

    @Override
    public MovingStructureCompat.CarriageTrainStatus carriageTrainStatus(Level virtualLevel,
            String turnaroundStation) {
        AbstractContraptionEntity entity = entityFor(virtualLevel);
        if (!(entity instanceof CarriageContraptionEntity carriageEntity)
                || carriageEntity.getCarriage() == null) return null;
        Train train = carriageEntity.getCarriage().train;
        if (train == null) return null;
        TrainNameFormat parsedName = TrainNameFormat.parse(
                train.name == null ? "" : train.name.getString());
        String trainName = parsedName.displayName();
        String serviceType = parsedName.serviceTypeOrLegacy();
        int carriageCount = train.carriages == null ? 0 : train.carriages.size();
        int carriageNumber = Math.max(1, Math.min(carriageCount,
                carriageEntity.carriageIndex + 1));
        List<String> upcomingStops = upcomingStops(train);
        String terminal = parsedName.loop() ? "" : upcomingStops.isEmpty() ? ""
                : upcomingStops.get(upcomingStops.size() - 1);
        boolean schedulePresent = train.runtime != null && train.runtime.getSchedule() != null
                && !train.runtime.paused;
        MovingStructureCompat.CarriageTrainStatus fallback;
        GlobalStation current = train.getCurrentStation();
        if (current != null) {
            fallback = new MovingStructureCompat.CarriageTrainStatus(trainName, serviceType,
                    current.name == null ? "" : current.name, terminal, carriageCount,
                    carriageNumber,
                    upcomingStops, 0L, 0.0D, MovingStructureCompat.ArrivalPhase.ARRIVED,
                    -1L, schedulePresent);
            return ClientCarriageTrainState.getOrRequest(train.id, turnaroundStation, fallback);
        }
        GlobalStation destination = train.navigation == null ? null
                : train.navigation.destination;
        if (destination != null) {
            double distance = Math.max(0.0D, train.navigation.distanceToDestination);
            boolean signalHold = train.navigation.waitingForSignal != null
                    && Math.abs(train.speed) < 0.02D
                    && Math.abs(train.targetSpeed) < 0.02D;
            long eta = signalHold ? -1L : dynamicEtaTicks(train, distance);
            // Prefer a useful passenger-facing time window over a very late distance threshold.
            // The five-block check remains only for trains whose current speed cannot produce ETA.
            long noticeTicks = MineScreenConfig.CARRIAGE_ARRIVAL_NOTICE_SECONDS.get() * 20L;
            MovingStructureCompat.ArrivalPhase phase = switch (PassengerArrivalLogic.select(
                    false, signalHold, eta, noticeTicks, Long.MAX_VALUE / 4L)) {
                case EN_ROUTE -> eta < 0L && distance < 5.0D
                        ? MovingStructureCompat.ArrivalPhase.APPROACHING
                        : MovingStructureCompat.ArrivalPhase.EN_ROUTE;
                case WAITING_SIGNAL -> MovingStructureCompat.ArrivalPhase.WAITING_SIGNAL;
                case APPROACHING -> MovingStructureCompat.ArrivalPhase.APPROACHING;
                case ARRIVED -> MovingStructureCompat.ArrivalPhase.ARRIVED;
            };
            fallback = new MovingStructureCompat.CarriageTrainStatus(trainName, serviceType,
                    destination.name == null ? "" : destination.name, terminal, carriageCount,
                    carriageNumber,
                    upcomingStops, eta, distance, phase, -1L, schedulePresent);
            return ClientCarriageTrainState.getOrRequest(train.id, turnaroundStation, fallback);
        }
        fallback = new MovingStructureCompat.CarriageTrainStatus(trainName, serviceType, "",
                terminal, carriageCount, carriageNumber, upcomingStops, -1L, Double.NaN,
                MovingStructureCompat.ArrivalPhase.NO_ROUTE, -1L, schedulePresent);
        return ClientCarriageTrainState.getOrRequest(train.id, turnaroundStation, fallback);
    }

    private static long dynamicEtaTicks(Train train, double distance) {
        return TrainEtaEstimator.estimateTicks(distance, train.speed,
                Math.min(Math.abs(train.targetSpeed), Math.max(0.001D, train.maxSpeed())),
                train.acceleration(), 24_000L, -1L);
    }

    private static String passengerServiceType(String trainName) {
        return TrainNameFormat.parse(trainName).serviceTypeOrLegacy();
    }

    private static List<String> upcomingStops(Train train) {
        List<String> result = new ArrayList<>();
        if (train.navigation != null && train.navigation.destination != null) {
            addExactStop(result, train.navigation.destination.name);
        }
        if (train.runtime != null && train.runtime.getSchedule() != null) {
            try {
                var predictions = new ArrayList<>(train.runtime.submitPredictions());
                predictions.removeIf(java.util.Objects::isNull);
                predictions.sort(java.util.Comparator.comparingInt(
                        prediction -> prediction.ticks));
                for (var prediction : predictions) {
                    addExactStop(result, prediction.destination);
                    if (result.size() >= 8) break;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(result);
    }

    private static void addExactStop(List<String> result, String station) {
        String value = station == null ? "" : station.trim();
        if (value.isBlank() || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) return;
        if (result.isEmpty() || !result.get(result.size() - 1).equalsIgnoreCase(value)) {
            result.add(value);
        }
    }

    @Override
    public MovingStructureCompat.MovingDisplayHit findDisplayHit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return null;
        var ray = ContraptionHandlerClient.getRayInputs(minecraft.player);
        Vec3 start = ray.getFirst();
        Vec3 end = ray.getSecond();
        MovingStructureCompat.MovingDisplayHit nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (!(candidate instanceof AbstractContraptionEntity contraptionEntity)
                    || contraptionEntity.isRemoved()
                    || contraptionEntity.getContraption() == null) {
                continue;
            }
            BlockHitResult hit = ContraptionHandlerClient.rayTraceContraption(start, end,
                    contraptionEntity);
            if (hit == null || !isDisplayBlock(contraptionEntity.getContraption()
                    .getBlocks().get(hit.getBlockPos()))) {
                hit = fallbackDisplayHit(contraptionEntity, start, end);
            }
            if (hit == null) continue;
            BlockPos position = hit.getBlockPos();
            Level virtualLevel = contraptionEntity.getContraption()
                    .getOrCreateClientContraptionLazy().getRenderLevel();
            Object blockEntity = contraptionEntity.getContraption()
                    .getBlockEntityClientSide(position);
            if (!(blockEntity instanceof dev.minescreen.CeilingDisplayBlockEntity)
                    && !(blockEntity instanceof dev.minescreen.TextDisplayBlockEntity)) {
                blockEntity = virtualLevel.getBlockEntity(position);
            }
            if (!(blockEntity instanceof dev.minescreen.CeilingDisplayBlockEntity)
                    && !(blockEntity instanceof dev.minescreen.TextDisplayBlockEntity)) {
                continue;
            }
            Vec3 worldHit = contraptionEntity.toGlobalVector(hit.getLocation(), 1.0F);
            double distance = start.distanceToSqr(worldHit);
            if (distance >= nearestDistance) continue;
            nearestDistance = distance;
            nearest = new MovingStructureCompat.MovingDisplayHit(virtualLevel,
                    contraptionEntity.getId(), position.immutable(), hit.getDirection(),
                    hit.getLocation(), (net.minecraft.world.level.block.entity.BlockEntity)
                            blockEntity);
        }
        return nearest;
    }

    /**
     * Create's normal contraption ray trace follows vanilla collision boxes. The sloped/very thin
     * LCD face can sit outside that shape while a train is moving, so use a tightly inflated local
     * block box as an interaction-only fallback. This does not change collision or rendering.
     */
    private static BlockHitResult fallbackDisplayHit(AbstractContraptionEntity entity, Vec3 start,
            Vec3 end) {
        Vec3 localStart = entity.toLocalVector(start, 1.0F);
        Vec3 localEnd = entity.toLocalVector(end, 1.0F);
        Vec3 nearest = null;
        BlockPos nearestPos = null;
        BlockState nearestState = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var entry : entity.getContraption().getBlocks().entrySet()) {
            if (!isDisplayBlock(entry.getValue())) continue;
            java.util.Optional<Vec3> clipped = new AABB(entry.getKey()).inflate(0.12D)
                    .clip(localStart, localEnd);
            if (clipped.isEmpty()) continue;
            double candidateDistance = localStart.distanceToSqr(clipped.get());
            if (candidateDistance >= distance) continue;
            distance = candidateDistance;
            nearest = clipped.get();
            nearestPos = entry.getKey();
            nearestState = entry.getValue().state();
        }
        if (nearest == null || nearestPos == null || nearestState == null) return null;
        Direction face = nearestState.hasProperty(
                net.minecraft.world.level.block.state.properties.BlockStateProperties
                        .HORIZONTAL_FACING)
                ? nearestState.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties
                                .HORIZONTAL_FACING)
                : Direction.UP;
        return new BlockHitResult(nearest, face, nearestPos, false);
    }

    private static boolean isDisplayBlock(
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
                    .StructureBlockInfo info) {
        if (info == null) return false;
        BlockState state = info.state();
        return state.is(dev.minescreen.MineScreen.CEILING_DISPLAY_BLOCK.get())
                || state.is(dev.minescreen.MineScreen.DOOR_LCD_BLOCK.get())
                || state.is(dev.minescreen.MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get())
                || state.is(dev.minescreen.MineScreen.TRAFFIC_DISPLAY_BLOCK.get());
    }

    @Override
    public void applyDisplayState(MovingCeilingDisplayStatePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(payload.entityId())
                instanceof AbstractContraptionEntity entity)
                || entity.getContraption() == null) {
            return;
        }
        for (BlockPos position : payload.localTiles()) {
            if (entity.getContraption().getBlockEntityClientSide(position)
                    instanceof dev.minescreen.CeilingDisplayBlockEntity display) {
                display.applyServerUpdate(payload.side(), payload.linkedSides(), payload.mode(),
                        payload.line(), payload.destination(), payload.nextStop(), payload.eta(),
                        payload.status(), payload.notice(), payload.templateId(),
                        payload.overlayTemplateId(), payload.turnaround(), payload.editor());
            }
        }
    }

    @Override
    public void applyTrafficDisplayState(MovingTrafficDisplayUpdatePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(payload.entityId())
                instanceof AbstractContraptionEntity entity)
                || entity.getContraption() == null) return;
        Level virtualLevel = entity.getContraption().getOrCreateClientContraptionLazy()
                .getRenderLevel();
        dev.minescreen.TextDisplayGroup group =
                dev.minescreen.TextDisplayGroupResolver.resolve(virtualLevel,
                        payload.localPos());
        java.util.Set<BlockPos> tiles = group == null
                ? java.util.Set.of(payload.localPos()) : group.tiles();
        for (BlockPos tile : tiles) {
            Object blockEntity = entity.getContraption().getBlockEntityClientSide(tile);
            if (!(blockEntity instanceof dev.minescreen.TextDisplayBlockEntity)) {
                blockEntity = virtualLevel.getBlockEntity(tile);
            }
            if (blockEntity instanceof dev.minescreen.TextDisplayBlockEntity display) {
                display.applyTrafficServerUpdate(payload.backSide(), payload.backMode(),
                        payload.line(), payload.destination(), payload.currentStop(),
                        payload.nextStop(), payload.eta(), payload.status(), payload.templateId(),
                        payload.overlayTemplateId(), payload.trafficRole(),
                        payload.stationMode(), payload.stationBindingName(),
                        payload.stationTurnaroundName(), payload.stationTrainType(),
                        payload.stationMapTemplateId(), payload.textColor(),
                        payload.backgroundColor(), payload.animation(), payload.speed(),
                        payload.fontSize(), Minecraft.getInstance().player == null
                                ? null : Minecraft.getInstance().player.getUUID());
            }
        }
    }

    private static void injectCross(Level level, BlockPos center, Direction right,
            Direction up, int light) {
        injectRelay(level, center, light);
        injectRelay(level, center.relative(right), Math.max(1, light - 1));
        injectRelay(level, center.relative(right.getOpposite()), Math.max(1, light - 1));
        injectRelay(level, center.relative(up), Math.max(1, light - 1));
        injectRelay(level, center.relative(up.getOpposite()), Math.max(1, light - 1));
    }

    private static void injectRelay(Level level, BlockPos position, int light) {
        if (!level.getBlockState(position).isAir()) return;
        BlockState relay = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL,
                Math.max(0, Math.min(15, light)));
        // The modification lives only in this contraption's render-only Level. The callback marks
        // Create's structure/light buffers dirty; no server block or real chunk is touched.
        level.setBlock(position, relay, 0, 512);
    }

    private static void runVirtualLightEngine(Level level) {
        try {
            // Keep Flywheel/VirtualRenderWorld out of MineScreen's linked type graph. Create is an
            // optional dependency and its VisualizationLevel API is not present on plain clients.
            level.getClass().getMethod("runLightEngine").invoke(level);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private AbstractContraptionEntity entityFor(Level virtualLevel) {
        if (virtualLevel == null) {
            return null;
        }
        WeakReference<AbstractContraptionEntity> reference = byVirtualLevel.get(virtualLevel);
        AbstractContraptionEntity cached = reference == null ? null : reference.get();
        if (owns(cached, virtualLevel)) {
            return cached;
        }
        ClientLevel realLevel = Minecraft.getInstance().level;
        if (realLevel == null) {
            byVirtualLevel.remove(virtualLevel);
            return null;
        }
        for (Entity candidate : realLevel.entitiesForRendering()) {
            if (candidate instanceof AbstractContraptionEntity contraptionEntity
                    && owns(contraptionEntity, virtualLevel)) {
                byVirtualLevel.put(virtualLevel, new WeakReference<>(contraptionEntity));
                return contraptionEntity;
            }
        }
        byVirtualLevel.remove(virtualLevel);
        return null;
    }

    private static boolean owns(AbstractContraptionEntity entity, Level virtualLevel) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        Contraption contraption = entity.getContraption();
        return contraption != null
                && contraption.getOrCreateClientContraptionLazy().getRenderLevel() == virtualLevel;
    }

    private record PanelKey(BlockPos position, Direction facing) {
    }
}
