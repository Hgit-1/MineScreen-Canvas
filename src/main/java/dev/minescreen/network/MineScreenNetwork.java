package dev.minescreen.network;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Optional stage-5 metadata channel. All registered payloads are state-only, never frame data. */
public final class MineScreenNetwork {
    /** Bumped when traffic_display_update gained the persisted PLATFORM/ONBOARD role. */
    public static final String PROTOCOL_VERSION = "13";
    private static final Set<String> CONTENT_TYPES = Set.of("IDLE", "VIDEO", "WEB", "VNC");
    private static final Map<NavigationKey, Long> LAST_WEB_NAVIGATION = new HashMap<>();

    private MineScreenNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(ScreenStateUpdatePayload.TYPE, ScreenStateUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleStateUpdate);
        registrar.playToClient(ScreenStatePayload.TYPE, ScreenStatePayload.STREAM_CODEC,
                MineScreenNetwork::handleState);
        registrar.playToServer(ScreenControlRequestPayload.TYPE, ScreenControlRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleControl);
        registrar.playToServer(ScreenStateRequestPayload.TYPE, ScreenStateRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleStateRequest);
        registrar.playToServer(WebNavigationPayload.TYPE, WebNavigationPayload.STREAM_CODEC,
                MineScreenNetwork::handleWebNavigation);
        registrar.playToServer(WebPeerAnnouncePayload.TYPE, WebPeerAnnouncePayload.STREAM_CODEC,
                MineScreenNetwork::handleWebPeerAnnounce);
        registrar.playToClient(WebPeerDirectoryPayload.TYPE, WebPeerDirectoryPayload.STREAM_CODEC,
                MineScreenNetwork::handleWebPeerDirectory);
        registrar.playToServer(TextDisplayUpdatePayload.TYPE, TextDisplayUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleTextDisplayUpdate);
        registrar.playToServer(TrafficDisplayUpdatePayload.TYPE, TrafficDisplayUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleTrafficDisplayUpdate);
        registrar.playToServer(CeilingDisplayUpdatePayload.TYPE, CeilingDisplayUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleCeilingDisplayUpdate);
        registrar.playToServer(MovingCeilingDisplayUpdatePayload.TYPE,
                MovingCeilingDisplayUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleMovingCeilingDisplayUpdate);
        registrar.playToClient(MovingCeilingDisplayStatePayload.TYPE,
                MovingCeilingDisplayStatePayload.STREAM_CODEC,
                MineScreenNetwork::handleMovingCeilingDisplayState);
        registrar.playBidirectional(MovingTrafficDisplayUpdatePayload.TYPE,
                MovingTrafficDisplayUpdatePayload.STREAM_CODEC,
                MineScreenNetwork::handleMovingTrafficDisplayUpdate);
        registrar.playBidirectional(TrafficTemplateAnnouncePayload.TYPE,
                TrafficTemplateAnnouncePayload.STREAM_CODEC,
                MineScreenNetwork::handleTrafficTemplateAnnounce);
        registrar.playBidirectional(TrafficTemplateRequestPayload.TYPE,
                TrafficTemplateRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleTrafficTemplateRequest);
        registrar.playBidirectional(TrafficTemplateChunkPayload.TYPE,
                TrafficTemplateChunkPayload.STREAM_CODEC,
                MineScreenNetwork::handleTrafficTemplateChunk);
        registrar.playToServer(TrafficTemplateCatalogRequestPayload.TYPE,
                TrafficTemplateCatalogRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleTrafficTemplateCatalogRequest);
        registrar.playToServer(TrainScheduleRequestPayload.TYPE,
                TrainScheduleRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleTrainScheduleRequest);
        registrar.playToClient(TrainSchedulePayload.TYPE, TrainSchedulePayload.STREAM_CODEC,
                MineScreenNetwork::handleTrainSchedule);
        registrar.playToServer(CarriageStatusRequestPayload.TYPE,
                CarriageStatusRequestPayload.STREAM_CODEC,
                MineScreenNetwork::handleCarriageStatusRequest);
        registrar.playToClient(CarriageStatusPayload.TYPE, CarriageStatusPayload.STREAM_CODEC,
                MineScreenNetwork::handleCarriageStatus);
    }

    private static void handleCarriageStatusRequest(CarriageStatusRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var status = dev.minescreen.compat.CreateTrainScheduleServerCompat.carriageStatus(
                player.serverLevel(), payload.trainId(), player.position(),
                payload.turnaroundStation());
        if (!status.trainId().equals(payload.trainId())) return;
        context.reply(new CarriageStatusPayload(status.trainId(), payload.turnaroundStation(),
                status.trainName(),
                status.serviceType(), status.stationName(), status.terminalStation(),
                status.carriageCount(), status.upcomingStops(), status.etaTicks(),
                status.distance(), status.phase(), status.dwellTicks(),
                status.schedulePresent(), status.gameTime()));
    }

    private static void handleCarriageStatus(CarriageStatusPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.compat.ClientCarriageTrainState.accept(payload);
        }
    }

    private static void handleTrainScheduleRequest(TrainScheduleRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || payload.trainType() == null || payload.trainType().length() > 96
                || payload.turnaroundStation() == null
                || payload.turnaroundStation().length() > 128
                || player.distanceToSqr(payload.stationPos().getX() + 0.5D,
                        payload.stationPos().getY() + 0.5D,
                        payload.stationPos().getZ() + 0.5D) > 65_536.0D) {
            return;
        }
        var snapshot = dev.minescreen.compat.CreateTrainScheduleServerCompat.snapshot(level,
                payload.stationPos(), payload.trainType(), payload.turnaroundStation(),
                payload.maximum());
        var departures = snapshot.departures().stream().limit(8).map(value ->
                new TrainSchedulePayload.Entry(value.trainId(), value.trainName(), value.trainType(),
                        value.origin(), value.destination(), value.direction(),
                        value.loop(), value.carriageCount(), value.etaTicks(),
                        value.dwellTicks(), value.waitingForSignal())).toList();
        context.reply(new TrainSchedulePayload(payload.stationPos(), payload.trainType(),
                payload.turnaroundStation(), snapshot.stationName(), snapshot.gameTime(),
                snapshot.totalTrains(),
                snapshot.activeScheduleTrains(), snapshot.filterMatchedTrains(), departures));
    }

    private static void handleTrainSchedule(TrainSchedulePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.traffic.ClientTrainScheduleState.accept(payload);
        }
    }

    private static void handleTrafficTemplateAnnounce(TrafficTemplateAnnouncePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrafficTemplateSyncServer.announce(player, payload);
        } else if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.traffic.TrafficTemplateSyncClient.acceptAnnouncement(payload);
        }
    }

    private static void handleTrafficTemplateRequest(TrafficTemplateRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrafficTemplateSyncServer.request(player, payload);
        } else if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.traffic.TrafficTemplateSyncClient.acceptRequest(payload);
        }
    }

    private static void handleTrafficTemplateChunk(TrafficTemplateChunkPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrafficTemplateSyncServer.chunk(player, payload);
        } else if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.traffic.TrafficTemplateSyncClient.acceptChunk(payload);
        }
    }

    private static void handleTrafficTemplateCatalogRequest(
            TrafficTemplateCatalogRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrafficTemplateSyncServer.catalog(player);
        }
    }

    private static void handleCeilingDisplayUpdate(CeilingDisplayUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || player.distanceToSqr(payload.pos().getX() + 0.5D, payload.pos().getY() + 0.5D,
                        payload.pos().getZ() + 0.5D) > 256.0D
                || payload.side() < 0 || payload.side() > 1
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.line())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.destination())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.nextStop())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.eta())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.status())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.notice())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.templateId())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.overlayTemplateId())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.turnaround())) {
            return;
        }
        boolean doorLcd = level.getBlockState(payload.pos())
                .is(dev.minescreen.MineScreen.DOOR_LCD_BLOCK.get())
                || level.getBlockState(payload.pos())
                        .is(dev.minescreen.MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get());
        java.util.Set<net.minecraft.core.BlockPos> tiles;
        if (doorLcd) {
            dev.minescreen.DoorLcdGroup group = dev.minescreen.DoorLcdGroupResolver.resolve(
                    level, payload.pos());
            if (group == null || payload.side() != 0) return;
            tiles = group.tiles();
        } else {
            dev.minescreen.CeilingDisplayGroup group =
                    dev.minescreen.CeilingDisplayGroupResolver.resolve(level, payload.pos());
            if (group == null) return;
            tiles = group.tiles();
        }
        for (net.minecraft.core.BlockPos tile : tiles) {
            if (level.getBlockEntity(tile) instanceof dev.minescreen.CeilingDisplayBlockEntity member
                    && !player.hasPermissions(2) && !member.mayEdit(player.getUUID())) return;
        }
        for (net.minecraft.core.BlockPos tile : tiles) {
            if (level.getBlockEntity(tile) instanceof dev.minescreen.CeilingDisplayBlockEntity member) {
                member.applyServerUpdate(payload.side(), doorLcd || payload.linkedSides(), payload.mode(),
                        payload.line(), payload.destination(), payload.nextStop(), payload.eta(),
                        payload.status(), payload.notice(), payload.templateId(),
                        payload.overlayTemplateId(), payload.turnaround(), player.getUUID());
            }
        }
    }

    private static void handleMovingCeilingDisplayUpdate(
            MovingCeilingDisplayUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || payload.entityId() < 0 || payload.side() < 0 || payload.side() > 1
                || payload.mode() == null
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.line())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.destination())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.nextStop())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.eta())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.status())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.notice())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.templateId())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.overlayTemplateId())
                || !dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.turnaround())) {
            return;
        }
        dev.minescreen.compat.CreateTrainScheduleServerCompat.updateMovingDisplay(
                player.serverLevel(), player, payload);
    }

    private static void handleMovingCeilingDisplayState(
            MovingCeilingDisplayStatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.compat.MovingStructureCompat.applyDisplayState(payload);
        }
    }

    private static void handleMovingTrafficDisplayUpdate(
            MovingTrafficDisplayUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            if (payload.entityId() < 0 || !validMovingTrafficPayload(payload)) return;
            dev.minescreen.compat.CreateTrainScheduleServerCompat.updateMovingTrafficDisplay(
                    player.serverLevel(), player, payload);
        } else if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.compat.MovingStructureCompat.applyTrafficDisplayState(payload);
        }
    }

    private static boolean validMovingTrafficPayload(MovingTrafficDisplayUpdatePayload payload) {
        return dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.line())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.destination())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.currentStop())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.nextStop())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.eta())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.status())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.templateId())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.overlayTemplateId())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.stationBindingName())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.stationTrainType())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.stationTurnaroundName())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.stationMapTemplateId())
                && payload.animation() != null && payload.backMode() != null
                // A mounted contraption display is always onboard. Reject contradictory client
                // state instead of letting platform bindings leak into a moving train.
                && payload.trafficRole() == dev.minescreen.TrafficDisplayRole.ONBOARD
                && payload.stationMode() != null && Float.isFinite(payload.speed())
                && payload.speed() >= dev.minescreen.TextDisplayBlockEntity.MIN_SPEED
                && payload.speed() <= dev.minescreen.TextDisplayBlockEntity.MAX_SPEED
                && payload.fontSize() >= dev.minescreen.TextDisplayBlockEntity.MIN_FONT_SIZE
                && payload.fontSize() <= dev.minescreen.TextDisplayBlockEntity.MAX_FONT_SIZE;
    }

    private static void handleTextDisplayUpdate(TextDisplayUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || player.distanceToSqr(payload.pos().getX() + 0.5D,
                        payload.pos().getY() + 0.5D, payload.pos().getZ() + 0.5D) > 256.0D
                || !dev.minescreen.TextDisplayBlockEntity.validText(payload.text())
                || !Float.isFinite(payload.speed())
                || payload.speed() < dev.minescreen.TextDisplayBlockEntity.MIN_SPEED
                || payload.speed() > dev.minescreen.TextDisplayBlockEntity.MAX_SPEED
                || payload.fontSize() < dev.minescreen.TextDisplayBlockEntity.MIN_FONT_SIZE
                || payload.fontSize() > dev.minescreen.TextDisplayBlockEntity.MAX_FONT_SIZE
                || !(level.getBlockEntity(payload.pos())
                        instanceof dev.minescreen.TextDisplayBlockEntity display)
                || display.isTraffic()
                || (!display.supportsBackSide()
                        && (payload.backSide()
                                || payload.backMode() != dev.minescreen.DisplayBackMode.OFF))
                || (!display.mayEdit(player.getUUID()) && !player.hasPermissions(2))) {
            return;
        }
        dev.minescreen.TextDisplayGroup group = dev.minescreen.TextDisplayGroupResolver.resolve(
                level, payload.pos());
        if (group == null || group.traffic() || !canEditGroup(level, group, player)) {
            return;
        }
        for (net.minecraft.core.BlockPos tile : group.tiles()) {
            if (level.getBlockEntity(tile) instanceof dev.minescreen.TextDisplayBlockEntity member) {
                member.applyServerUpdate(payload.backSide(), payload.backMode(), payload.text(),
                        payload.textColor(), payload.backgroundColor(), payload.animation(),
                        payload.speed(), payload.fontSize(), player.getUUID());
            }
        }
    }

    private static void handleTrafficDisplayUpdate(TrafficDisplayUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || player.distanceToSqr(payload.pos().getX() + 0.5D, payload.pos().getY() + 0.5D,
                        payload.pos().getZ() + 0.5D) > 256.0D
                || !validTrafficPayload(payload)
                || !(level.getBlockEntity(payload.pos())
                        instanceof dev.minescreen.TextDisplayBlockEntity display)
                || !display.isTraffic()) {
            return;
        }
        dev.minescreen.TextDisplayGroup group = dev.minescreen.TextDisplayGroupResolver.resolve(
                level, payload.pos());
        if (group == null || !group.traffic() || !canEditGroup(level, group, player)) {
            return;
        }
        for (net.minecraft.core.BlockPos tile : group.tiles()) {
            if (level.getBlockEntity(tile) instanceof dev.minescreen.TextDisplayBlockEntity member) {
                member.applyTrafficServerUpdate(payload.backSide(), payload.backMode(), payload.line(), payload.destination(),
                        payload.currentStop(), payload.nextStop(), payload.eta(), payload.status(),
                        payload.templateId(), payload.overlayTemplateId(), payload.trafficRole(),
                        payload.stationMode(),
                        payload.stationBindingName(),
                        payload.stationTurnaroundName(), payload.stationTrainType(),
                        payload.stationMapTemplateId(), payload.textColor(), payload.backgroundColor(),
                        payload.animation(), payload.speed(), payload.fontSize(), player.getUUID());
            }
        }
    }

    private static boolean validTrafficPayload(TrafficDisplayUpdatePayload payload) {
        return dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.line())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.destination())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.currentStop())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.nextStop())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.eta())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.status())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.templateId())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.overlayTemplateId())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.stationBindingName())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(
                        payload.stationTurnaroundName())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.stationTrainType())
                && dev.minescreen.TextDisplayBlockEntity.validTrafficField(payload.stationMapTemplateId())
                && payload.trafficRole() != null && payload.stationMode() != null
                && Float.isFinite(payload.speed())
                && payload.speed() >= dev.minescreen.TextDisplayBlockEntity.MIN_SPEED
                && payload.speed() <= dev.minescreen.TextDisplayBlockEntity.MAX_SPEED
                && payload.fontSize() >= dev.minescreen.TextDisplayBlockEntity.MIN_FONT_SIZE
                && payload.fontSize() <= dev.minescreen.TextDisplayBlockEntity.MAX_FONT_SIZE;
    }

    private static boolean canEditGroup(ServerLevel level, dev.minescreen.TextDisplayGroup group,
            ServerPlayer player) {
        if (player.hasPermissions(2)) {
            return true;
        }
        for (net.minecraft.core.BlockPos tile : group.tiles()) {
            if (level.getBlockEntity(tile) instanceof dev.minescreen.TextDisplayBlockEntity display
                    && !display.mayEdit(player.getUUID())) {
                return false;
            }
        }
        return true;
    }

    private static void handleStateUpdate(ScreenStateUpdatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        dev.minescreen.ScreenGroup actualGroup = ServerScreenGroupResolver.resolve(level, payload.master());
        if (!level.dimension().location().equals(payload.dimension())
                || player.distanceToSqr(payload.master().getX() + 0.5D, payload.master().getY() + 0.5D,
                        payload.master().getZ() + 0.5D) > 256.0D
                || actualGroup == null || !actualGroup.groupId().equals(payload.screenId())
                || !actualGroup.master().equals(payload.master())
                || actualGroup.columns() != payload.columns() || actualGroup.rows() != payload.rows()
                || payload.columns() > 128 || payload.rows() > 128
                || !CONTENT_TYPES.contains(payload.contentType())
                || !validSourceReference(payload.contentType(), payload.sourceReference(),
                        player.getServer())) {
            return;
        }
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        if (state == null) {
            state = new ServerScreenStateData.State(payload.screenId(), payload.dimension(), payload.master(),
                    payload.columns(), payload.rows(), payload.contentType(), payload.sourceReference(),
                    Math.max(0L, payload.positionMs()), level.getGameTime(), payload.paused(), payload.loop(),
                    clampVolume(payload.volume()), payload.access(), player.getUUID());
        } else {
            if (!state.owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                return;
            }
            state.master = payload.master().immutable();
            state.columns = payload.columns();
            state.rows = payload.rows();
            state.contentType = payload.contentType();
            state.sourceReference = payload.sourceReference();
            state.positionMs = Math.max(0L, payload.positionMs());
            state.updatedGameTime = level.getGameTime();
            state.paused = payload.paused();
            state.loop = payload.loop();
            state.volume = clampVolume(payload.volume());
            state.access = payload.access();
        }
        data.put(state);
        sendStateNear(level, state);
    }

    private static void handleWebNavigation(WebNavigationPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || !"WEB".equals(state.contentType)
                || !level.dimension().location().equals(payload.dimension())
                || !state.master.equals(payload.master())
                || actualGroup == null || !actualGroup.groupId().equals(state.screenId)
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                        state.master.getZ() + 0.5D) > 256.0D
                || !mayNavigate(player, state)
                || !validSourceReference("WEB", payload.url(), player.getServer())) {
            return;
        }
        long gameTime = level.getGameTime();
        NavigationKey key = new NavigationKey(player.getUUID(), state.screenId);
        Long previousTick = LAST_WEB_NAVIGATION.get(key);
        // Server-side abuse guard. The client already waits for a URL to remain stable, but an
        // untrusted client cannot force a broadcast more than once per four server ticks.
        if (previousTick != null && gameTime - previousTick < 4L) {
            return;
        }
        LAST_WEB_NAVIGATION.put(key, gameTime);
        if (payload.url().equals(state.sourceReference)) {
            return;
        }
        state.sourceReference = payload.url();
        data.put(state);
        sendStateNear(level, state);
    }

    private static void handleWebPeerAnnounce(WebPeerAnnouncePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData.State state = ServerScreenStateData.get(level).get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || !"WEB".equals(state.contentType)
                || !level.dimension().location().equals(payload.dimension())
                || !state.master.equals(payload.master())
                || actualGroup == null || !actualGroup.groupId().equals(payload.screenId())
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                        state.master.getZ() + 0.5D) > synchronizationRadius() * synchronizationRadius()) {
            return;
        }
        WebPeerRegistry.announce(player, payload, state);
    }

    private static void handleWebPeerDirectory(WebPeerDirectoryPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.web.WebPeerService.acceptDirectory(payload);
        }
    }

    private static void handleControl(ScreenControlRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ServerScreenStateData data = ServerScreenStateData.get(level);
        ServerScreenStateData.State state = data.get(payload.screenId());
        dev.minescreen.ScreenGroup actualGroup = state == null ? null
                : ServerScreenGroupResolver.resolve(level, state.master);
        if (state == null || actualGroup == null || !actualGroup.groupId().equals(state.screenId)
                || player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                state.master.getZ() + 0.5D) > 256.0D) {
            return;
        }
        // Protocol-compatible retirement of the old exclusive lease. New clients never request a
        // lease, and any request from an older client clears stale ownership instead of locking out
        // other players. Each client sends input directly to its own browser/RFB connection.
        if (state.controller != null) {
            state.controller = null;
            data.setDirty();
        }
        sendStateNear(level, state);
    }

    private static void handleStateRequest(ScreenStateRequestPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        double radius = synchronizationRadius();
        for (ServerScreenStateData.State state : ServerScreenStateData.get(level).states()) {
            if (player.distanceToSqr(state.master.getX() + 0.5D, state.master.getY() + 0.5D,
                    state.master.getZ() + 0.5D) <= radius * radius) {
                context.reply(state.payload(level.getGameTime()));
            }
        }
    }

    private static void handleState(ScreenStatePayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            dev.minescreen.client.network.ClientNetworkState.accept(payload);
        }
    }

    private static boolean validSourceReference(String contentType, String value,
            net.minecraft.server.MinecraftServer server) {
        if (value == null || value.length() > 2048 || value.indexOf('\0') >= 0) {
            return false;
        }
        if (contentType.equals("IDLE")) {
            return value.isEmpty();
        }
        if (contentType.equals("VIDEO")) {
            // A video reference is a media id, never a client filesystem path.
            return value.isEmpty() || value.matches("[A-Za-z0-9._-]{1,128}");
        }
        try {
            URI uri = URI.create(value.contains("://") ? value : "vnc://" + value);
            if (uri.getUserInfo() != null || uri.getHost() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (contentType.equals("WEB")) {
                return scheme.equals("https") || scheme.equals("http")
                        && SourceReferencePolicy.permitsHttp(MineScreenConfig.ALLOW_HTTP.get(),
                                MineScreenConfig.UNRESTRICTED_SINGLEPLAYER.get(),
                                server != null && server.isSingleplayer(),
                                server != null && server.isPublished());
            }
            return contentType.equals("VNC") && scheme.equals("vnc");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static float clampVolume(float volume) {
        return Float.isFinite(volume) ? Math.max(0.0F, Math.min(1.0F, volume)) : 1.0F;
    }

    static void sendStateNear(ServerLevel level, ServerScreenStateData.State state) {
        PacketDistributor.sendToPlayersNear(level, null, state.master.getX() + 0.5D,
                state.master.getY() + 0.5D, state.master.getZ() + 0.5D,
                synchronizationRadius(), state.payload(level.getGameTime()));
    }

    static void releasePlayer(java.util.UUID playerId) {
        LAST_WEB_NAVIGATION.keySet().removeIf(key -> key.player().equals(playerId));
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            WebPeerRegistry.removePlayer(server, playerId);
        }
    }

    private static double synchronizationRadius() {
        return Math.max(32.0D, MineScreenConfig.MAX_RENDER_DISTANCE.get() + 16.0D);
    }

    private static boolean mayNavigate(ServerPlayer player, ServerScreenStateData.State state) {
        if (state.owner.equals(player.getUUID()) || player.hasPermissions(2)) {
            return true;
        }
        return state.access == ScreenAccess.ANYONE;
    }

    private record NavigationKey(java.util.UUID player, java.util.UUID screen) {
    }
}
