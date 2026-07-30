package dev.minescreen.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.minescreen.ScreenGeometry;
import dev.minescreen.DisplayBackMode;
import dev.minescreen.TextDisplayAnimation;
import dev.minescreen.TextDisplayBlock;
import dev.minescreen.TextDisplayBlockEntity;
import dev.minescreen.TextDisplayGroup;
import dev.minescreen.client.traffic.TrafficTemplateRepository;
import dev.minescreen.StationDisplayMode;
import dev.minescreen.TrafficDisplayRole;
import dev.minescreen.client.traffic.CreateTrainScheduleService;
import dev.minescreen.client.compat.MovingStructureCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

/** Renders one connected display group from its deterministic master block. */
public final class TextDisplayRenderer implements BlockEntityRenderer<TextDisplayBlockEntity> {
    private static final int TEXT_WIDTH_PER_TILE = 112;
    private static final int TEXT_HEIGHT_PER_TILE = 84;
    private static final int MAX_STATIC_LINES = 64;
    /** 100% means a readable sign glyph, not one raw Minecraft font pixel per canvas pixel. */
    private static final float SIGN_TEXT_SCALE_AT_100 = 5.0F;
    private final Font font;

    public TextDisplayRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public void render(TextDisplayBlockEntity display, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = display.getLevel();
        if (level == null || !level.isClientSide()) {
            return;
        }
        TextDisplayGroup group = TextDisplayGroupManager.groupAt(level, display.getBlockPos());
        if (group == null || !group.master().equals(display.getBlockPos())) {
            return;
        }
        renderSurface(display, group, level, false, partialTick, poseStack, buffers);
        if (display.supportsBackSide() && display.backMode() != DisplayBackMode.OFF) {
            renderSurface(display, group, level, true, partialTick, poseStack, buffers);
        }
    }

    private void renderSurface(TextDisplayBlockEntity display, TextDisplayGroup group,
            Level level, boolean backSide, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers) {
        Direction facing = group.facing();
        Vec3 frontRight = ScreenGeometry.right(facing);
        Vec3 up = ScreenGeometry.up(facing);
        Vec3 frontNormal = ScreenGeometry.normal(facing);
        Vec3 masterOrigin = ScreenGeometry.origin(display.getBlockPos(), facing)
                .subtract(Vec3.atLowerCornerOf(display.getBlockPos()));
        // An L-shaped component may have no tile at (minRight, minUp). Offset from the actual
        // master tile to the logical canvas origin so holes do not shift the entire text plane.
        Vec3 frontOrigin = masterOrigin.add(frontRight.scale(-group.column(display.getBlockPos())))
                .add(up.scale(-group.row(display.getBlockPos())));
        Vec3 right = backSide ? frontRight.scale(-1.0D) : frontRight;
        Vec3 normal = backSide ? frontNormal.scale(-1.0D) : frontNormal;
        Vec3 origin = backSide
                ? frontOrigin.add(frontRight.scale(group.columns())).add(frontNormal.scale(-0.19D))
                : frontOrigin;
        double elapsed = Math.max(0.0D,
                level.getGameTime() - display.animationStartGameTime(backSide) + partialTick);
        int background = display.backgroundColor(backSide);
        int foreground = display.textColor(backSide);
        boolean legacyTextMode = display.isTraffic()
                && display.stationMode(backSide) == StationDisplayMode.TEXT_SEQUENCE;
        TrafficTemplateRepository.Template trafficTemplate = display.isTraffic()
                ? TrafficTemplateRepository.resolve(legacyTextMode
                        ? TrafficTemplateRepository.Template.BUILTIN.id()
                        : display.templateId(backSide)) : null;
        String overlayTemplateId = display.isTraffic()
                ? display.overlayTemplateId(backSide) : "";
        if (legacyTextMode && overlayTemplateId.isBlank()) {
            overlayTemplateId = display.templateId(backSide);
        }
        TrafficTemplateRepository.Template overlayTemplate =
                overlayTemplateId.isBlank() ? null
                        : TrafficTemplateRepository.resolve(overlayTemplateId);
        if (trafficTemplate != null
                && !trafficTemplate.id().equals(TrafficTemplateRepository.Template.BUILTIN.id())) {
            background = trafficTemplate.backgroundColor();
            foreground = trafficTemplate.textColor();
        }
        if (display.animation(backSide) == TextDisplayAnimation.ALERT) {
            boolean warningPhase = ((long) Math.floor(elapsed * Math.max(0.1F, display.speed(backSide))
                    / 4.0D) & 1L) == 0L;
            background = warningPhase ? 0xFFE53935 : 0xFFFFB300;
            foreground = warningPhase ? 0xFFFFFFFF : 0xFF151515;
        }
        renderTileBackgrounds(poseStack, buffers, group, origin, right, up, normal, background,
                backSide);
        if (display.isElectric()) {
            renderElectricScanlines(poseStack, buffers, group, origin, right, up, normal,
                    foreground, backSide);
        }
        if (trafficTemplate != null && trafficTemplate.texture() != null) {
            // Station_Next no longer reserves a separate half-block banner. Imported artwork is a
            // normal full-canvas background, so every physical row remains usable by departures.
            renderTemplateTexture(poseStack, buffers, group, origin, right, up, normal,
                    trafficTemplate.texture(), backSide);
        }

        float configuredSize = display.fontSize(backSide) / 100.0F;
        float sizeFactor = display.isTraffic() ? configuredSize
                : configuredSize * SIGN_TEXT_SCALE_AT_100;
        int canvasWidth = TEXT_WIDTH_PER_TILE * group.columns();
        int canvasHeight = TEXT_HEIGHT_PER_TILE * group.rows();
        double innerLeft = 0.08D;
        double innerBottom = 0.10D;
        double innerWidth = Math.max(0.01D, group.columns() - 0.16D);
        double innerHeight = Math.max(0.01D, group.rows() - 0.24D);
        // Insets belong to the outside of the joined group, not to every physical tile. Fit and
        // center one logical canvas so a 3x2 board no longer leaves a large blank strip at its
        // right and bottom edges.
        double basePixelScale = Math.min(innerWidth / canvasWidth, innerHeight / canvasHeight);
        double contentWidth = canvasWidth * basePixelScale;
        double contentHeight = canvasHeight * basePixelScale;
        double leftOffset = innerLeft + (innerWidth - contentWidth) * 0.5D;
        double bottomOffset = innerBottom + (innerHeight - contentHeight) * 0.5D;
        // Keep all logical text/scene geometry clearly in front of the physical panel, optional
        // header image and electric scanlines. A 0.001-block separation produced distance- and
        // angle-dependent blue diagonal Z-fighting on moving Create contraptions.
        Vec3 topLeft = origin.add(right.scale(leftOffset))
                .add(up.scale(bottomOffset + contentHeight)).add(normal.scale(0.012D));
        Matrix4f basis = textBasis(topLeft, right, up, normal,
                (float) basePixelScale * sizeFactor);
        poseStack.pushPose();
        poseStack.mulPose(basis);
        if (display.isTraffic()) {
            renderTraffic(display, backSide, foreground, sizeFactor, canvasWidth, canvasHeight,
                    trafficTemplate, poseStack, buffers);
            if (overlayTemplate != null && overlayTemplate.textSequence().present()) {
                renderTextSequenceOverlay(display, overlayTemplate,
                        logicalWidth(canvasWidth, sizeFactor),
                        logicalHeight(canvasHeight, sizeFactor), poseStack, buffers);
            }
        } else {
            switch (display.animation(backSide)) {
                case STATIC -> renderStatic(display.text(backSide), foreground, sizeFactor, canvasWidth,
                        canvasHeight, poseStack, buffers);
                case MARQUEE -> renderMarquee(display.text(backSide), foreground, display.speed(backSide), elapsed,
                        sizeFactor, canvasWidth, canvasHeight, poseStack, buffers);
                case PULSE -> renderStatic(display.text(backSide), pulseColor(foreground, display.speed(backSide),
                        elapsed), sizeFactor, canvasWidth, canvasHeight, poseStack, buffers);
                case ALERT -> renderStatic(display.text(backSide), foreground, sizeFactor, canvasWidth,
                        canvasHeight, poseStack, buffers);
            }
        }
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(TextDisplayBlockEntity display) {
        Level level = display.getLevel();
        if (level == null || !level.isClientSide()) {
            return BlockEntityRenderer.super.getRenderBoundingBox(display);
        }
        TextDisplayGroup group = TextDisplayGroupManager.groupAt(level, display.getBlockPos());
        if (group == null || !group.master().equals(display.getBlockPos())) {
            return BlockEntityRenderer.super.getRenderBoundingBox(display);
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (BlockPos tile : group.tiles()) {
            minX = Math.min(minX, tile.getX());
            minY = Math.min(minY, tile.getY());
            minZ = Math.min(minZ, tile.getZ());
            maxX = Math.max(maxX, tile.getX() + 1.0D);
            maxY = Math.max(maxY, tile.getY() + 1.0D);
            maxZ = Math.max(maxZ, tile.getZ() + 1.0D);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.08D);
    }

    private void renderStatic(String text, int color, float sizeFactor, int canvasWidth,
            int canvasHeight, PoseStack poseStack, MultiBufferSource buffers) {
        int logicalWidth = Math.max(1, Math.round(canvasWidth / sizeFactor));
        int logicalHeight = Math.max(1, Math.round(canvasHeight / sizeFactor));
        List<FormattedCharSequence> wrapped = new ArrayList<>(font.split(Component.literal(text),
                logicalWidth));
        if (wrapped.isEmpty()) {
            return;
        }
        int maxLines = Math.min(MAX_STATIC_LINES,
                Math.max(1, logicalHeight / Math.max(1, font.lineHeight)));
        if (wrapped.size() > maxLines) {
            wrapped = new ArrayList<>(wrapped.subList(0, maxLines));
        }
        float startY = (logicalHeight - wrapped.size() * font.lineHeight) * 0.5F;
        for (int index = 0; index < wrapped.size(); index++) {
            FormattedCharSequence line = wrapped.get(index);
            float x = (logicalWidth - font.width(line)) * 0.5F;
            draw(line, x, startY + index * font.lineHeight, color, poseStack, buffers);
        }
    }

    private void renderMarquee(String text, int color, float speed, double elapsed,
            float sizeFactor, int canvasWidth, int canvasHeight, PoseStack poseStack,
            MultiBufferSource buffers) {
        if (text.isEmpty()) {
            return;
        }
        int logicalWidth = Math.max(1, Math.round(canvasWidth / sizeFactor));
        int logicalHeight = Math.max(1, Math.round(canvasHeight / sizeFactor));
        String cycle = text + "   •   ";
        int[] points = cycle.codePoints().toArray();
        int offset = Math.floorMod((int) Math.floor(elapsed * speed / 2.5D), points.length);
        StringBuilder rotated = new StringBuilder(cycle.length() * 2);
        for (int index = 0; index < points.length * 2; index++) {
            rotated.appendCodePoint(points[(offset + index) % points.length]);
        }
        String visible = font.plainSubstrByWidth(rotated.toString(), logicalWidth);
        FormattedCharSequence line = Component.literal(visible).getVisualOrderText();
        draw(line, 0.0F, (logicalHeight - font.lineHeight) * 0.5F, color, poseStack, buffers);
    }

    private void renderTraffic(TextDisplayBlockEntity display, boolean backSide, int color,
            float sizeFactor, int canvasWidth, int canvasHeight,
            TrafficTemplateRepository.Template template, PoseStack poseStack,
            MultiBufferSource buffers) {
        int movingWidth = logicalWidth(canvasWidth, sizeFactor);
        int movingHeight = logicalHeight(canvasHeight, sizeFactor);
        StationDisplayMode stationMode = display.stationMode(backSide);
        if (stationMode == StationDisplayMode.TEXT_SEQUENCE) {
            // Compatibility with the first experimental TXT build. The text document is now
            // rendered separately as an overlay, while the base board falls back to manual data.
            stationMode = StationDisplayMode.MANUAL;
        }
        MovingStructureCompat.CarriageTrainStatus movingTrain =
                MovingStructureCompat.carriageTrainStatus(display.getLevel(),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                display.templateId(backSide),
                                display.stationTurnaroundName(backSide)));
        if (movingTrain != null) {
            if (template != null && template.layout().equals("script_scene_v1")
                    && !template.scene().isEmpty()) {
                renderScriptScene(display, backSide, template, movingWidth, movingHeight,
                        movingTrain, poseStack, buffers);
            } else {
                renderMovingTrainBoard(display, backSide, movingTrain, movingWidth, movingHeight,
                        color, poseStack, buffers);
            }
            return;
        }
        if (display.trafficRole() == TrafficDisplayRole.ONBOARD) {
            renderOnboardWaiting(movingWidth, movingHeight, color, poseStack, buffers);
            return;
        }
        if (stationMode == StationDisplayMode.STATION_NEXT) {
            renderStationNext(display, backSide, color, logicalWidth(canvasWidth, sizeFactor),
                    logicalHeight(canvasHeight, sizeFactor), 42.0F / sizeFactor,
                    poseStack, buffers);
            return;
        }
        if (stationMode == StationDisplayMode.STATION_MAP) {
            TrafficTemplateRepository.Template mapTemplate = TrafficTemplateRepository.resolve(
                    display.stationMapTemplateId(backSide));
            renderStationMap(display, backSide, color, mapTemplate, logicalWidth(canvasWidth, sizeFactor),
                    logicalHeight(canvasHeight, sizeFactor), poseStack, buffers);
            return;
        }
        int logicalWidth = Math.max(1, Math.round(canvasWidth / sizeFactor));
        int logicalHeight = Math.max(1, Math.round(canvasHeight / sizeFactor));
        if (template != null && template.layout().equals("rmp_native_v1")
                && template.rmpDiagram() != null) {
            renderRmpDiagram(display, backSide, template, logicalWidth, logicalHeight, poseStack,
                    buffers);
            return;
        }
        if (template != null && template.layout().equals("script_scene_v1")
                && !template.scene().isEmpty()) {
            renderScriptScene(display, backSide, template, logicalWidth, logicalHeight, poseStack,
                    buffers);
            return;
        }
        String headline = display.trafficLine(backSide) + "  "
                + display.trafficDestination(backSide);
        String route = display.trafficCurrentStop(backSide).isBlank()
                ? display.trafficNextStop(backSide) : display.trafficCurrentStop(backSide)
                        + "  →  " + display.trafficNextStop(backSide);
        String eta = display.trafficEta(backSide).isBlank() ? "--" : display.trafficEta(backSide);
        List<FormattedCharSequence> lines = List.of(
                Component.literal(font.plainSubstrByWidth(headline, logicalWidth)).getVisualOrderText(),
                Component.literal(font.plainSubstrByWidth(route, logicalWidth)).getVisualOrderText(),
                Component.literal(font.plainSubstrByWidth(eta, logicalWidth)).getVisualOrderText());
        float[] scales = {1.25F, 0.85F, 1.0F};
        float[] offsets = {0.18F, 0.48F, 0.71F};
        for (int index = 0; index < lines.size(); index++) {
            FormattedCharSequence line = lines.get(index);
            float x = (logicalWidth - font.width(line) * scales[index]) * 0.5F;
            drawScaled(line, x, logicalHeight * offsets[index], color, scales[index], poseStack,
                    buffers);
        }
        String templateStatus = display.trafficStatus(backSide);
        if (templateStatus.isBlank() && template.metadata().arrival().enabled()) {
            templateStatus = template.metadata().arrival().notice();
        }
        if (!templateStatus.isBlank()) {
            FormattedCharSequence status = Component.literal(font.plainSubstrByWidth(
                    templateStatus, logicalWidth)).getVisualOrderText();
            draw(status, 4.0F, logicalHeight - font.lineHeight - 2.0F, color, poseStack, buffers);
        }
    }

    private void renderTextSequenceOverlay(TextDisplayBlockEntity display,
            TrafficTemplateRepository.Template overlay, int width, int height,
            PoseStack poseStack, MultiBufferSource buffers) {
        TrafficTemplateRepository.TextSequence sequence = overlay.textSequence();
        if (sequence == null || !sequence.present()) {
            return;
        }
        long total = sequence.frames().stream()
                .mapToLong(TrafficTemplateRepository.TextFrame::durationTicks).sum();
        if (total <= 0L) return;
        // The absolute world clock keeps every board using this document on the same page,
        // including boards rendered as Create moving structures and clients joining later.
        long elapsed = Math.max(0L, display.getLevel().getGameTime());
        long cursor = sequence.loop() ? Math.floorMod(elapsed, total)
                : Math.min(elapsed, total - 1L);
        TrafficTemplateRepository.TextFrame selected = sequence.frames().getLast();
        int selectedIndex = sequence.frames().size() - 1;
        long frameStart = 0L;
        for (int index = 0; index < sequence.frames().size(); index++) {
            TrafficTemplateRepository.TextFrame candidate = sequence.frames().get(index);
            long end = frameStart + candidate.durationTicks();
            if (cursor < end) {
                selected = candidate;
                selectedIndex = index;
                break;
            }
            frameStart = end;
        }
        long localTicks = Math.max(0L, cursor - frameStart);
        double progress = Math.min(1.0D,
                localTicks / (double) Math.max(1, selected.durationTicks()));
        boolean holdLast = !sequence.loop() && selectedIndex == sequence.frames().size() - 1;
        String text = selected.text();
        int alpha = 255;
        int slide = 0;
        switch (selected.transition()) {
            case "fade" -> {
                double opacity = Math.min(1.0D, progress / 0.16D);
                if (!holdLast) opacity = Math.min(opacity, Math.max(0.0D,
                        (1.0D - progress) / 0.16D));
                alpha = Math.max(4, (int) Math.round(255.0D * opacity));
            }
            case "slide_left" -> {
                if (progress < 0.18D) {
                    slide = (int) Math.round(width * (1.0D - progress / 0.18D));
                } else if (!holdLast && progress > 0.82D) {
                    slide = -(int) Math.round(width * ((progress - 0.82D) / 0.18D));
                }
            }
            case "typewriter" -> {
                int[] points = text.codePoints().toArray();
                int visible = Math.max(1, Math.min(points.length,
                        (int) Math.ceil(points.length * Math.min(1.0D, progress / 0.72D))));
                text = new String(points, 0, visible);
            }
            case "blink" -> alpha = (localTicks / 6L & 1L) == 0L ? 255 : 72;
            default -> {
            }
        }
        int animatedColor = (overlay.textColor() & 0x00FFFFFF) | (alpha << 24);
        int maximumWidth = Math.max(1, width - 12);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add(Component.empty().getVisualOrderText());
            } else {
                lines.addAll(font.split(Component.literal(paragraph), maximumWidth));
            }
        }
        int maximumLines = Math.min(3,
                Math.max(1, (height - 8) / Math.max(1, font.lineHeight)));
        if (lines.size() > maximumLines) lines = new ArrayList<>(lines.subList(0, maximumLines));
        int bandHeight = Math.min(height, lines.size() * font.lineHeight + 8);
        int bandTop = switch (sequence.position()) {
            case "top" -> 0;
            case "center" -> Math.max(0, (height - bandHeight) / 2);
            default -> Math.max(0, height - bandHeight);
        };
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.08D);
        quad2d(buffers.getBuffer(RenderType.textBackground()), poseStack.last().pose(),
                0, bandTop, width, bandTop + bandHeight, overlay.backgroundColor());
        float startY = bandTop + Math.max(3.0F,
                (bandHeight - lines.size() * font.lineHeight) * 0.5F);
        for (int index = 0; index < lines.size(); index++) {
            FormattedCharSequence line = lines.get(index);
            float x = switch (selected.align()) {
                case "left" -> 6.0F;
                case "right" -> width - 6.0F - font.width(line);
                default -> (width - font.width(line)) * 0.5F;
            };
            draw(line, x + slide, startY + index * font.lineHeight, animatedColor,
                    poseStack, buffers);
        }
        poseStack.popPose();
    }

    private void renderOnboardWaiting(int width, int height, int color, PoseStack poseStack,
            MultiBufferSource buffers) {
        String role = Component.translatable(
                "screen.minescreen.traffic.onboard_waiting_title").getString();
        String state = Component.translatable(
                "screen.minescreen.traffic.onboard_waiting_state").getString();
        drawFitted(role, 8, Math.max(6, height * 0.28F), Math.max(1, width - 16), color,
                1.35F, poseStack, buffers);
        drawFitted(state, 8, Math.max(22, height * 0.58F), Math.max(1, width - 16),
                0xFFAFC0D1, 0.78F, poseStack, buffers);
    }

    private void renderMovingTrainBoard(TextDisplayBlockEntity display, boolean backSide,
            MovingStructureCompat.CarriageTrainStatus train, int width, int height, int color,
            PoseStack poseStack, MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        String nextStation = TrafficTemplateRepository.mappedStationName(templateId,
                train.stationName());
        String destination = TrafficTemplateRepository.mappedStationName(templateId,
                train.terminalStation());
        if (internalLoopMarker(destination)) destination = "";
        String service = train.serviceType();
        String trainName = train.trainName();
        String carriage = train.carriageNumber() > 0
                ? Component.translatable("screen.minescreen.carriage_info.car_number",
                        train.carriageNumber()).getString() : "";
        String consist = train.carriageCount() > 0
                ? Component.translatable("screen.minescreen.carriage_info.consist",
                        train.carriageCount()).getString() : "";
        String destinationText = destination.isBlank() ? ""
                : Component.translatable("screen.minescreen.carriage_info.to",
                        destination).getString();
        String top = String.join("  ·  ", java.util.stream.Stream
                .of(trainName, carriage, consist, destinationText)
                .filter(value -> value != null && !value.isBlank()).toList());
        int serviceColor = serviceColor(service, color);
        drawFitted(service, 6, 6, Math.max(1, width / 4.0F), serviceColor, 1.0F,
                poseStack, buffers);
        int serviceWidth = service.isBlank() ? 0 : Math.min(width / 4, font.width(service) + 8);
        drawFitted(top, 8 + serviceWidth, 6,
                Math.max(1, width - serviceWidth - 14), color, 0.95F, poseStack, buffers);

        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        line2d(geometry, pose, 6, 22, width - 6, 22, 1.0D, 0xFF4B5968);

        String lead = switch (train.phase()) {
            case ARRIVED -> Component.translatable(
                    "screen.minescreen.carriage_info.now_at").getString();
            case APPROACHING -> Component.translatable(
                    "screen.minescreen.carriage_info.arriving").getString();
            case WAITING_SIGNAL -> Component.translatable(
                    "screen.minescreen.ceiling.waiting_signal").getString();
            case EN_ROUTE -> Component.translatable(
                    "screen.minescreen.carriage_info.next").getString();
            case NO_ROUTE -> Component.translatable(
                    "screen.minescreen.carriage_info.no_route").getString();
        };
        drawFitted(lead, 7, 29, Math.max(1, width * 0.22F), 0xFFAFC0D1, 0.82F,
                poseStack, buffers);
        drawFitted(nextStation, Math.max(54, width * 0.23F), 26,
                Math.max(1, width * 0.55F), color, 1.55F, poseStack, buffers);

        String eta = movingEta(train);
        String arrival = train.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED
                ? Component.translatable("screen.minescreen.ceiling.arrived").getString()
                : eta.isBlank() ? "--:--" : eta;
        int etaColor = train.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED
                && train.dwellKnown() && train.dwellTicks() <= 10L * 20L
                        ? 0xFFFF5C5C : 0xFFFFD166;
        drawFittedRight(arrival, width - 7, 28, Math.max(44, width * 0.20F), etaColor,
                1.05F, poseStack, buffers);
        if (train.phase() != MovingStructureCompat.ArrivalPhase.ARRIVED && train.etaKnown()) {
            String clock = expectedArrivalTime(display.getLevel(), train);
            drawFittedRight(Component.translatable(
                            "screen.minescreen.station.arrival_world_time", clock).getString(),
                    width - 7, 43, Math.max(55, width * 0.25F), 0xFFB8C6D4, 0.70F,
                    poseStack, buffers);
        }

        line2d(geometry, pose, 6, Math.max(50, height - 25), width - 6,
                Math.max(50, height - 25), 1.0D, 0xFF4B5968);
        List<String> stops = train.upcomingStops().stream()
                .map(stop -> TrafficTemplateRepository.mappedStationName(templateId, stop))
                .filter(stop -> !stop.isBlank()).limit(6).toList();
        String footer = stops.isEmpty() ? ""
                : Component.translatable("screen.minescreen.carriage_info.stops_at",
                        String.join(" · ", stops)).getString();
        drawFitted(footer, 7, Math.max(53, height - 20), width - 14,
                0xFFC7D3E2, 0.72F, poseStack, buffers);
    }

    private static String movingEta(MovingStructureCompat.CarriageTrainStatus train) {
        if (train.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            return train.dwellKnown() ? countdown(train.dwellTicks()) : "";
        }
        if (!train.etaKnown() || train.etaTicks() > 90L * 20L) return "";
        return countdown(train.etaTicks());
    }

    private static int logicalWidth(int canvasWidth, float sizeFactor) {
        return Math.max(1, Math.round(canvasWidth / sizeFactor));
    }

    private static int logicalHeight(int canvasHeight, float sizeFactor) {
        return Math.max(1, Math.round(canvasHeight / sizeFactor));
    }

    private void renderStationNext(TextDisplayBlockEntity display, boolean backSide, int color,
            int logicalWidth, int logicalHeight, float requestedHeaderHeight,
            PoseStack poseStack, MultiBufferSource buffers) {
        CreateTrainScheduleService.ResolvedSnapshot resolved =
                CreateTrainScheduleService.snapshotForDisplay(display.getLevel(),
                        display.getBlockPos(), display.templateId(backSide),
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                display.templateId(backSide),
                                display.stationTurnaroundName(backSide)), 4);
        CreateTrainScheduleService.Snapshot snapshot = resolved.snapshot();
        String stationName = snapshot.stationName().isBlank() ? display.trafficDestination(backSide)
                : snapshot.stationName();
        float headerHeight = Math.min(logicalHeight * 0.5F,
                Math.max(font.lineHeight + 5.0F, requestedHeaderHeight));
        VertexConsumer stationGeometry = buffers.getBuffer(RenderType.textBackground());
        Matrix4f stationPose = poseStack.last().pose();
        // A dedicated station-name masthead prevents the stop name from visually merging with
        // the first departure and gives large multi-block boards a stable reading hierarchy.
        // These rectangles must not overlap. The former full-width background sat at exactly the
        // same depth as the blue mast and rule; depth precision then produced the blue diagonal
        // hatching that changed with camera angle.
        quad2d(stationGeometry, stationPose, 3, 0, logicalWidth, headerHeight - 1,
                0xFF13212E);
        quad2d(stationGeometry, stationPose, 0, 0, 3, headerHeight - 1, 0xFF4AA8FF);
        quad2d(stationGeometry, stationPose, 0, headerHeight - 1, logicalWidth, headerHeight,
                0xFF647182);
        String now = worldClock(display.getLevel(), 0L);
        String nowLabel = logicalWidth >= 220
                ? Component.translatable("screen.minescreen.station.world_time", now).getString()
                : now;
        int nowWidth = font.width(nowLabel);
        boolean showStationLabel = logicalWidth >= 150
                && headerHeight >= font.lineHeight * 2.0F + 8.0F;
        if (showStationLabel) {
            drawFitted(Component.translatable("screen.minescreen.station.station_name")
                            .getString(),
                    7, 4, Math.max(1, logicalWidth - nowWidth - 20), 0xFF8FA2B5, 0.70F,
                    poseStack, buffers);
        }
        float stationScale = showStationLabel ? 1.15F : 1.0F;
        float stationY = Math.max(showStationLabel ? font.lineHeight * 0.70F + 6.0F : 3.0F,
                headerHeight - font.lineHeight * stationScale - 5.0F);
        drawFitted(stationName, 7, stationY,
                Math.max(1, logicalWidth - nowWidth - 14), color, 1.15F, poseStack, buffers);
        draw(Component.literal(nowLabel).getVisualOrderText(),
                logicalWidth - nowWidth - 5,
                5.0F, 0xFFDCE7F3,
                poseStack, buffers);
        List<CreateTrainScheduleService.Departure> visibleDepartures =
                snapshot.departures().stream()
                        .filter(departure -> departure.dwelling()
                                || departure.etaKnown()
                                && departure.etaTicks() <= 90L * 20L)
                        .limit(4).toList();
        int remainingHeight = Math.max(font.lineHeight * 2 + 3,
                Math.round(logicalHeight - headerHeight - 3.0F));
        int rowHeight = Math.max(font.lineHeight * 2 + 3,
                remainingHeight / Math.max(1, visibleDepartures.size()));
        int rowY = Math.round(headerHeight + 3.0F);
        if (snapshot.departures().isEmpty()) {
            if (snapshot.stationName().isBlank()) {
                drawScaled(Component.translatable("screen.minescreen.station.not_found")
                        .getVisualOrderText(), 6, rowY, color, 1.05F, poseStack, buffers);
                drawWrappedWorld(Component.translatable("screen.minescreen.station.bind_help"),
                        6, rowY + font.lineHeight + 6, logicalWidth - 12, 4, color,
                        poseStack, buffers);
                return;
            }
            drawScaled(Component.translatable("screen.minescreen.station.connected")
                    .getVisualOrderText(), 6, rowY, color, 1.05F, poseStack, buffers);
            String trainType = display.stationTrainType(backSide);
            Component reason = switch (snapshot.departureState(trainType)) {
                case NO_TRAINS -> Component.translatable("screen.minescreen.station.no_trains");
                case NO_ACTIVE_SCHEDULES -> Component.translatable(
                        "screen.minescreen.station.no_active_schedules");
                case FILTER_NO_MATCH -> Component.translatable(
                        "screen.minescreen.station.filter_no_match", trainType);
                case NO_STATION_PREDICTIONS, AVAILABLE -> Component.translatable(
                        "screen.minescreen.station.no_station_predictions");
            };
            drawWrappedWorld(reason, 6, rowY + font.lineHeight + 6, logicalWidth - 12, 3,
                    color, poseStack, buffers);
            drawWrappedWorld(Component.translatable("screen.minescreen.station.schedule_help"),
                    6, rowY + font.lineHeight * 4 + 8, logicalWidth - 12, 3, color,
                    poseStack, buffers);
            return;
        }
        if (visibleDepartures.isEmpty()) {
            drawWrappedWorld(Component.translatable(
                    "screen.minescreen.station.no_arrivals_in_window"),
                    6, rowY, logicalWidth - 12, 3, 0xFF9FB0C2, poseStack, buffers);
            return;
        }
        // Draw the title/row separators before text. Their z value remains behind every glyph.
        quad2d(stationGeometry, stationPose, 5, rowY - 2, logicalWidth - 5, rowY - 1,
                0xFF465363);
        for (int index = 1; index < visibleDepartures.size(); index++) {
            int separatorY = rowY + index * rowHeight - 2;
            quad2d(stationGeometry, stationPose, 5, separatorY, logicalWidth - 5,
                    separatorY + 1, 0xFF465363);
        }
        for (int index = 0; index < visibleDepartures.size(); index++) {
            CreateTrainScheduleService.Departure departure = visibleDepartures.get(index);
            int y = rowY + index * rowHeight;
            String arrow = departure.direction().equals("down") ? "↓ " : "↑ ";
            String type = departure.trainType();
            String identifier = trainIdentifier(departure.trainName(), type);
            String badge = arrow + (type.isBlank() ? identifier : type);
            int badgeColor = serviceColor(type.isBlank() ? departure.trainName() : type, color);
            String consist = departure.carriageCount() > 0
                    ? Component.translatable("screen.minescreen.carriage_info.consist",
                            departure.carriageCount()).getString() : "";
            if (!consist.isBlank()) identifier = identifier.isBlank()
                    ? consist : identifier + " · " + consist;
            int arrivalReserve = Math.max(50, Math.min(88, logicalWidth / 3));
            drawFitted(badge, 6, y, Math.max(1, logicalWidth / 3), badgeColor, 1.0F,
                    poseStack, buffers);
            int badgeWidth = Math.min(logicalWidth / 3, font.width(badge));
            if (!type.isBlank() && !identifier.isBlank()) {
                drawFitted(identifier, 10 + badgeWidth, y,
                        Math.max(1, logicalWidth - badgeWidth - arrivalReserve - 20),
                        color, 1.0F, poseStack, buffers);
            }

            String route = routeText(departure.origin(), departure.destination(),
                    departure.loop());
            drawFitted(route, 6, y + font.lineHeight + 1,
                    Math.max(1, logicalWidth - arrivalReserve - 14), 0xFFC9D5E3, 0.86F,
                    poseStack, buffers);
            String timing = departure.dwelling()
                    ? departure.dwellKnown()
                            ? Component.translatable("screen.minescreen.station.dwell",
                                    countdown(departure.dwellTicks())).getString()
                            : Component.translatable(
                                    "screen.minescreen.station.dwell_unknown").getString()
                    : departure.etaKnown()
                            ? countdown(departure.etaTicks()) : "--:--";
            int timingColor = departure.dwelling()
                    ? departure.dwellKnown() && departure.dwellTicks() <= 10L * 20L
                            ? 0xFFFF5C5C : 0xFFFFD43B
                    : departure.etaKnown() ? 0xFFFFD166 : 0xFF8290A0;
            int arrivalWidth = font.width(timing);
            draw(Component.literal(timing).getVisualOrderText(),
                    logicalWidth - arrivalWidth - 6, y + font.lineHeight + 1,
                    timingColor, poseStack, buffers);
            if (!departure.dwelling() && departure.etaKnown()) {
                String arrivalTime = Component.translatable(
                        "screen.minescreen.station.arrival_world_time",
                        worldClock(display.getLevel(), departure.etaTicks())).getString();
                drawFittedRight(arrivalTime, logicalWidth - 6, y,
                        arrivalReserve - 3, 0xFFB5C2D0, 0.72F, poseStack, buffers);
            }
        }
    }

    private static String countdown(long ticks) {
        long seconds = Math.max(0L, (ticks + 19L) / 20L);
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                seconds / 60L, seconds % 60L);
    }

    private static String routeText(String origin, String destination, boolean loop) {
        String from = origin == null || origin.isBlank() ? "?" : origin.trim();
        String to = destination == null || destination.isBlank() ? "?" : destination.trim();
        if (loop) {
            return Component.translatable("screen.minescreen.station.loop_via",
                    conciseStation(from), conciseStation(to)).getString();
        }
        return conciseStation(from) + " → " + conciseStation(to);
    }

    private static String conciseStation(String value) {
        return value.replaceAll("(?iu)\\s*(上行|下行|上り|下り|upbound|downbound)\\s*", "")
                .replace("[", "").replace("]", "").replace("【", "").replace("】", "")
                .trim();
    }

    /** Keeps the complete train name while avoiding a duplicated "普通 普通 C-1" label. */
    private static String trainIdentifier(String trainName, String trainType) {
        String name = trainName == null ? "" : trainName.trim();
        String type = trainType == null ? "" : trainType.trim();
        if (type.isBlank() || name.isBlank()) return name;
        if (name.regionMatches(true, 0, type, 0, Math.min(name.length(), type.length()))
                && name.length() >= type.length()) {
            String suffix = name.substring(type.length()).trim();
            while (!suffix.isEmpty() && "-:：·|/".indexOf(suffix.charAt(0)) >= 0) {
                suffix = suffix.substring(1).trim();
            }
            return suffix;
        }
        return name;
    }

    private static int serviceColor(String serviceType, int fallback) {
        String value = serviceType == null ? "" : serviceType.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("特快") || value.contains("特急")
                || value.contains("limited express")) return 0xFFFF5C5C;
        if (value.contains("快速") || value.contains("急行") || value.contains("rapid")
                || value.contains("express")) return 0xFFFFD43B;
        if (value.contains("普通") || value.contains("各停") || value.contains("local")) {
            return 0xFF55D66B;
        }
        return fallback;
    }

    private static String worldClock(Level level, long offsetTicks) {
        if (level == null) return "--:--";
        long shiftedTicks = Math.floorMod(level.getDayTime() + Math.max(0L, offsetTicks)
                + 6_000L, 24_000L);
        int totalMinutes = (int) (shiftedTicks * 1_440L / 24_000L);
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                totalMinutes / 60, totalMinutes % 60);
    }

    private void renderStationMap(TextDisplayBlockEntity display, boolean backSide, int color,
            TrafficTemplateRepository.Template mapTemplate, int logicalWidth, int logicalHeight,
            PoseStack poseStack, MultiBufferSource buffers) {
        if (mapTemplate != null && mapTemplate.rmpDiagram() != null) {
            CreateTrainScheduleService.ResolvedSnapshot resolved =
                    CreateTrainScheduleService.snapshotForDisplay(display.getLevel(),
                            display.getBlockPos(), mapTemplate.id(),
                            display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                            display.stationTrainType(backSide),
                            TrafficTemplateRepository.resolvedCreateStationName(
                                    display.templateId(backSide),
                                    display.stationTurnaroundName(backSide)), 1);
            CreateTrainScheduleService.Snapshot schedule = resolved.snapshot();
            String currentStation = schedule.stationName().isBlank()
                    ? display.trafficCurrentStop(backSide) : schedule.stationName();
            String destination = schedule.departures().isEmpty()
                    ? display.trafficDestination(backSide)
                    : schedule.departures().getFirst().destination();
            renderRmpDiagram(display, backSide, mapTemplate, logicalWidth, logicalHeight,
                    currentStation, destination, poseStack, buffers);
            return;
        }
        if (mapTemplate != null && !mapTemplate.scene().isEmpty()) {
            renderScriptScene(display, backSide, mapTemplate, logicalWidth, logicalHeight,
                    poseStack, buffers);
            return;
        }
        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        int lineColor = mapTemplate == null ? 0xFFFFA726 : mapTemplate.accentColor();
        double y = logicalHeight * 0.56D;
        line2d(geometry, pose, logicalWidth * 0.08D, y, logicalWidth * 0.92D, y, 2.5D, lineColor);
        String line = display.trafficLine(backSide);
        drawScaled(Component.literal(line).getVisualOrderText(), 4, 4, lineColor, 1.1F,
                poseStack, buffers);
        draw(Component.literal(display.trafficNextStop(backSide)).getVisualOrderText(),
                (float) (logicalWidth * 0.48D), (float) (y - font.lineHeight - 2), color,
                poseStack, buffers);
    }

    private void renderRmpDiagram(TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template, int logicalWidth, int logicalHeight,
            PoseStack poseStack, MultiBufferSource buffers) {
        renderRmpDiagram(display, backSide, template, logicalWidth, logicalHeight, "", "",
                poseStack, buffers);
    }

    private void renderRmpDiagram(TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template, int logicalWidth, int logicalHeight,
            String currentStation, String destination, PoseStack poseStack,
            MultiBufferSource buffers) {
        TrafficTemplateRepository.RmpDiagram diagram = template.rmpDiagram();
        double spanX = Math.max(1.0D, diagram.maxX() - diagram.minX());
        double spanY = Math.max(1.0D, diagram.maxY() - diagram.minY());
        double margin = Math.max(6.0D, Math.min(logicalWidth, logicalHeight) * 0.06D);
        double header = Math.min(18.0D, logicalHeight * 0.16D);
        double availableWidth = Math.max(1.0D, logicalWidth - margin * 2.0D);
        double availableHeight = Math.max(1.0D, logicalHeight - margin * 2.0D - header);
        double scale = Math.min(availableWidth / spanX, availableHeight / spanY);
        double fittedWidth = spanX * scale;
        double fittedHeight = spanY * scale;
        double offsetX = (logicalWidth - fittedWidth) * 0.5D - diagram.minX() * scale;
        double offsetY = header + (availableHeight - fittedHeight) * 0.5D + margin
                - diagram.minY() * scale;

        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        for (TrafficTemplateRepository.RmpEdge edge : diagram.edges()) {
            TrafficTemplateRepository.RmpNode from = diagram.nodes().get(edge.from());
            TrafficTemplateRepository.RmpNode to = diagram.nodes().get(edge.to());
            line2d(geometry, pose, from.x() * scale + offsetX, from.y() * scale + offsetY,
                    to.x() * scale + offsetX, to.y() * scale + offsetY,
                    Math.max(1.1D, Math.min(3.5D, scale * 0.08D)), edge.color());
        }
        int labelStride = Math.max(1, (diagram.nodes().size() + 63) / 64);
        for (int index = 0; index < diagram.nodes().size(); index++) {
            TrafficTemplateRepository.RmpNode node = diagram.nodes().get(index);
            double x = node.x() * scale + offsetX;
            double y = node.y() * scale + offsetY;
            boolean current = stationNameMatches(node, currentStation);
            boolean target = !current && stationNameMatches(node, destination);
            double radius = Math.max(1.4D, Math.min(3.4D, scale * 0.11D))
                    * (current ? 1.65D : target ? 1.35D : 1.0D);
            int nodeColor = current ? 0xFFFFD43B : target ? 0xFF62E6A7
                    : template.accentColor();
            quad2d(geometry, pose, x - radius, y - radius, x + radius, y + radius,
                    nodeColor);
            if (index % labelStride == 0 && !node.name().isBlank()) {
                String label = font.plainSubstrByWidth(node.name(), Math.max(20, logicalWidth / 5));
                draw(Component.literal(label).getVisualOrderText(), (float) (x + radius + 1.5D),
                        (float) (y - font.lineHeight * 0.5D), template.textColor(), poseStack, buffers);
            }
        }
        String headerText = display.trafficLine(backSide);
        String headerDestination = destination.isBlank()
                ? display.trafficDestination(backSide) : destination;
        if (!headerDestination.isBlank()) {
            headerText += "  →  " + headerDestination;
        }
        FormattedCharSequence headerLine = Component.literal(font.plainSubstrByWidth(headerText,
                Math.max(1, logicalWidth - 8))).getVisualOrderText();
        drawScaled(headerLine, 4.0F, 2.0F, template.textColor(), 1.1F, poseStack, buffers);
        String templateStatus = display.trafficStatus(backSide);
        if (templateStatus.isBlank() && template.metadata().arrival().enabled()) {
            templateStatus = template.metadata().arrival().notice();
        }
        if (!templateStatus.isBlank()) {
            FormattedCharSequence status = Component.literal(font.plainSubstrByWidth(
                    templateStatus, Math.max(1, logicalWidth / 2)))
                    .getVisualOrderText();
            draw(status, 4.0F, logicalHeight - font.lineHeight - 2.0F,
                    template.textColor(), poseStack, buffers);
        }
    }

    private static boolean stationNameMatches(TrafficTemplateRepository.RmpNode node,
            String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        String normalized = normalizeStationName(wanted);
        return normalized.equals(normalizeStationName(node.id()))
                || normalized.equals(normalizeStationName(node.name()))
                || normalized.equals(normalizeStationName(node.secondaryName()));
    }

    private static String normalizeStationName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace("station", "").replace("駅", "")
                .replaceAll("[\\s._-]+", "").trim();
    }

    private void renderScriptScene(TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template, int logicalWidth, int logicalHeight,
            PoseStack poseStack, MultiBufferSource buffers) {
        renderScriptScene(display, backSide, template, logicalWidth, logicalHeight, null,
                poseStack, buffers);
    }

    private void renderScriptScene(TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template, int logicalWidth, int logicalHeight,
            MovingStructureCompat.CarriageTrainStatus train,
            PoseStack poseStack, MultiBufferSource buffers) {
        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        int order = 0;
        for (TrafficTemplateRepository.SceneElement element : template.scene()) {
            // A generated scene is painter-ordered: a full-canvas background is commonly
            // followed by header panels, badges, route lines and finally text. Rendering every
            // primitive at exactly the same depth made those overlapping quads fight in the depth
            // buffer, producing the random stripes, missing rectangles and broken glyphs seen in
            // game. Move each declaration a small deterministic distance toward the viewer while
            // retaining normal world depth testing against blocks in front of the display.
            poseStack.pushPose();
            poseStack.translate(0.0D, 0.0D, sceneLayer(order++));
            Matrix4f pose = poseStack.last().pose();
            double x = element.x() * logicalWidth;
            double y = element.y() * logicalHeight;
            switch (element.type()) {
                case "rect" -> quad2d(geometry, pose, x, y,
                        x + element.width() * logicalWidth,
                        y + element.height() * logicalHeight, element.color());
                case "ellipse" -> ellipse2d(geometry, pose,
                        x + element.width() * logicalWidth * 0.5D,
                        y + element.height() * logicalHeight * 0.5D,
                        element.width() * logicalWidth * 0.5D,
                        element.height() * logicalHeight * 0.5D, element.color());
                case "line" -> line2d(geometry, pose, x, y,
                        element.x2() * logicalWidth, element.y2() * logicalHeight,
                        Math.max(0.75D, element.size()), element.color());
                case "text" -> {
                    String value = expand(element.text(), display, backSide, template, train);
                    double available = element.maxWidth() > 0.0D ? element.maxWidth()
                            : element.align().equals("right") ? element.x()
                            : element.align().equals("center")
                                    ? Math.min(element.x(), 1.0D - element.x()) * 2.0D
                                    : 1.0D - element.x();
                    float maximumPixels = Math.max(1.0F, (float) (available * logicalWidth));
                    var component = Component.literal(value);
                    if (element.bold()) component.withStyle(ChatFormatting.BOLD);
                    FormattedCharSequence line = component.getVisualOrderText();
                    float textScale = element.vertical() ? element.size()
                            : Math.min(element.size(),
                                    maximumPixels / Math.max(1.0F, font.width(line)));
                    float minimumScale = Math.min(element.size(), 0.34F);
                    if (!element.vertical() && textScale < minimumScale) {
                        int localWidth = Math.max(1,
                                (int) Math.floor(maximumPixels / minimumScale));
                        component = Component.literal(font.plainSubstrByWidth(value, localWidth));
                        if (element.bold()) component.withStyle(ChatFormatting.BOLD);
                        line = component.getVisualOrderText();
                        textScale = minimumScale;
                    }
                    float localX = 0.0F;
                    if (element.align().equals("center")) localX = -font.width(line) * 0.5F;
                    else if (element.align().equals("right")) localX = -font.width(line);
                    poseStack.pushPose();
                    poseStack.translate(x, y, 0.006D);
                    if (element.rotation() != 0.0F) {
                        poseStack.mulPose(Axis.ZP.rotationDegrees(element.rotation()));
                    }
                    poseStack.scale(textScale, textScale, 1.0F);
                    if (element.vertical()) {
                        drawVertical(value, element.align(), element.color(), element.bold(),
                                poseStack, buffers);
                    } else {
                        font.drawInBatch(line, localX, 0.0F, element.color(), false,
                                poseStack.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                                LightTexture.FULL_BRIGHT);
                    }
                    poseStack.popPose();
                }
                default -> {
                }
            }
            poseStack.popPose();
        }
    }

    /** Pixel-space depth used only inside the already transformed logical display canvas. */
    private static float sceneLayer(int order) {
        return Math.min(1.0F, 0.01F + Math.max(0, order) * 0.015F);
    }

    private static String expand(String text, TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template) {
        return expand(text, display, backSide, template, null);
    }

    private static String expand(String text, TextDisplayBlockEntity display, boolean backSide,
            TrafficTemplateRepository.Template template,
            MovingStructureCompat.CarriageTrainStatus train) {
        String line = display.trafficLine(backSide);
        String destination = display.trafficDestination(backSide);
        String current = display.trafficCurrentStop(backSide);
        String next = display.trafficNextStop(backSide);
        String eta = display.trafficEta(backSide);
        String status = display.trafficStatus(backSide);
        String trainName = "";
        String serviceType = "";
        String carriageNumber = "";
        String carriageCount = "";
        String upcoming = "";
        if (train != null) {
            trainName = train.trainName();
            serviceType = train.serviceType();
            destination = TrafficTemplateRepository.mappedStationName(template.id(),
                    train.terminalStation());
            if (internalLoopMarker(destination)) destination = "";
            next = TrafficTemplateRepository.mappedStationName(template.id(),
                    train.stationName());
            eta = movingEta(train);
            status = train.phase().name();
            carriageNumber = train.carriageNumber() <= 0 ? ""
                    : Integer.toString(train.carriageNumber());
            carriageCount = train.carriageCount() <= 0 ? ""
                    : Integer.toString(train.carriageCount());
            upcoming = train.upcomingStops().stream().map(stop ->
                    TrafficTemplateRepository.mappedStationName(template.id(), stop))
                    .filter(stop -> !stop.isBlank()).collect(java.util.stream.Collectors
                            .joining(" · "));
        }
        String expanded = text.replace("${line}", line)
                .replace("${destination}", destination)
                .replace("${current}", current)
                .replace("${next}", next)
                .replace("${eta}", eta)
                .replace("${status}", status)
                .replace("${train_name}", trainName)
                .replace("${service_type}", serviceType)
                .replace("${carriage_number}", carriageNumber)
                .replace("${carriage_count}", carriageCount)
                .replace("${upcoming_stops}", upcoming)
                .replace("${direction}", directionLabel(destination, next))
                .replace("${arrival_time}", train == null ? "--:--"
                        : expectedArrivalTime(display.getLevel(), train));
        if (expanded.contains("${create_station}") || expanded.contains("${station_binding}")) {
            var match = TrafficTemplateRepository.stationMatch(template.id(), display.getLevel());
            expanded = expanded.replace("${create_station}",
                    match == null ? "" : match.createStationName())
                    .replace("${station_binding}", match == null ? "" : match.logicalStationId());
        }
        if (expanded.contains("${game_time}")) {
            expanded = expanded.replace("${game_time}", gameTime(display.getLevel()));
        }
        return TrafficTemplateRepository.completePlaceholders(template, expanded);
    }

    private static String gameTime(Level level) {
        if (level == null) return "--:--";
        long shiftedTicks = Math.floorMod(level.getDayTime() + 6_000L, 24_000L);
        int totalMinutes = (int) (shiftedTicks * 1_440L / 24_000L);
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        return twoDigits(hours) + ":" + twoDigits(minutes);
    }

    private static String expectedArrivalTime(Level level,
            MovingStructureCompat.CarriageTrainStatus train) {
        if (train == null) return "--:--";
        if (train.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            return "";
        }
        return train.etaKnown() ? worldClock(level, train.etaTicks()) : "--:--";
    }

    private static String directionLabel(String destination, String next) {
        String loop = Component.translatable("screen.minescreen.station.loop").getString();
        String target = destination == null ? "" : destination.trim();
        if (target.isBlank() || internalLoopMarker(target)
                || target.equalsIgnoreCase(loop)) target = next == null ? "" : next.trim();
        return target.isBlank() ? ""
                : Component.translatable("screen.minescreen.traffic.direction_value", target)
                        .getString();
    }

    private static boolean internalLoopMarker(String value) {
        return value != null && value.trim().equalsIgnoreCase("@minescreen:loop");
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static void line2d(VertexConsumer consumer, Matrix4f pose, double x1, double y1,
            double x2, double y2, double width, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-5D) return;
        double px = -dy / length * width * 0.5D;
        double py = dx / length * width * 0.5D;
        vertex2d(consumer, pose, x2 + px, y2 + py, color);
        vertex2d(consumer, pose, x2 - px, y2 - py, color);
        vertex2d(consumer, pose, x1 - px, y1 - py, color);
        vertex2d(consumer, pose, x1 + px, y1 + py, color);
    }

    private static void quad2d(VertexConsumer consumer, Matrix4f pose, double x1, double y1,
            double x2, double y2, int color) {
        vertex2d(consumer, pose, x1, y2, color);
        vertex2d(consumer, pose, x2, y2, color);
        vertex2d(consumer, pose, x2, y1, color);
        vertex2d(consumer, pose, x1, y1, color);
    }

    private static void ellipse2d(VertexConsumer consumer, Matrix4f pose, double centerX,
            double centerY, double radiusX, double radiusY, int color) {
        if (radiusX <= 0.0D || radiusY <= 0.0D) return;
        int segments = 24;
        for (int index = 0; index < segments; index++) {
            double angle1 = Math.PI * 2.0D * index / segments;
            double angle2 = Math.PI * 2.0D * (index + 1) / segments;
            vertex2d(consumer, pose, centerX, centerY, color);
            vertex2d(consumer, pose, centerX + Math.cos(angle2) * radiusX,
                    centerY + Math.sin(angle2) * radiusY, color);
            vertex2d(consumer, pose, centerX + Math.cos(angle1) * radiusX,
                    centerY + Math.sin(angle1) * radiusY, color);
            vertex2d(consumer, pose, centerX, centerY, color);
        }
    }

    private static void vertex2d(VertexConsumer consumer, Matrix4f pose, double x, double y,
            int color) {
        consumer.addVertex(pose, (float) x, (float) y, 0.001F).setColor(color)
                .setLight(LightTexture.FULL_BRIGHT);
    }

    private void draw(FormattedCharSequence line, float x, float y, int color,
            PoseStack poseStack, MultiBufferSource buffers) {
        drawScaled(line, x, y, color, 1.0F, poseStack, buffers);
    }

    private void drawFitted(String value, float x, float y, float maximumWidth, int color,
            float preferredScale, PoseStack poseStack, MultiBufferSource buffers) {
        String safe = value == null ? "" : value;
        if (safe.isBlank() || maximumWidth <= 0.0F) return;
        FormattedCharSequence sequence = Component.literal(safe).getVisualOrderText();
        float scale = Math.min(preferredScale,
                maximumWidth / Math.max(1.0F, font.width(sequence)));
        drawScaled(sequence, x, y, color, Math.max(0.45F, scale), poseStack, buffers);
    }

    private void drawFittedRight(String value, float right, float y, float maximumWidth, int color,
            float preferredScale, PoseStack poseStack, MultiBufferSource buffers) {
        String safe = value == null ? "" : value;
        if (safe.isBlank() || maximumWidth <= 0.0F) return;
        float scale = Math.min(preferredScale,
                maximumWidth / Math.max(1.0F, font.width(safe)));
        drawScaled(Component.literal(safe).getVisualOrderText(),
                right - font.width(safe) * scale, y, color, scale, poseStack, buffers);
    }

    private void drawWrappedWorld(Component text, float x, float y, int maximumWidth,
            int maximumLines, int color, PoseStack poseStack, MultiBufferSource buffers) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, maximumWidth));
        int count = Math.min(Math.max(1, maximumLines), lines.size());
        for (int index = 0; index < count; index++) {
            draw(lines.get(index), x, y + index * font.lineHeight, color, poseStack, buffers);
        }
    }

    private void drawVertical(String value, String align, int color, boolean bold,
            PoseStack poseStack, MultiBufferSource buffers) {
        int[] codePoints = value.codePoints().limit(32).toArray();
        for (int index = 0; index < codePoints.length; index++) {
            var component = Component.literal(new String(Character.toChars(codePoints[index])));
            if (bold) component.withStyle(ChatFormatting.BOLD);
            FormattedCharSequence glyph = component.getVisualOrderText();
            float x = align.equals("center") ? -font.width(glyph) * 0.5F
                    : align.equals("right") ? -font.width(glyph) : 0.0F;
            font.drawInBatch(glyph, x, index * font.lineHeight, color, false,
                    poseStack.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                    LightTexture.FULL_BRIGHT);
        }
    }

    private void drawScaled(FormattedCharSequence line, float x, float y, int color, float scale,
            PoseStack poseStack, MultiBufferSource buffers) {
        poseStack.pushPose();
        poseStack.translate(x, y, 0.0F);
        poseStack.scale(scale, scale, 1.0F);
        font.drawInBatch(line, 0.0F, 0.0F, color, false, poseStack.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static int pulseColor(int color, float speed, double elapsed) {
        double brightness = 0.52D + 0.48D * (0.5D + 0.5D
                * Math.sin(elapsed * Math.max(0.1D, speed) * 0.22D));
        int red = (int) Math.round(FastColor.ARGB32.red(color) * brightness);
        int green = (int) Math.round(FastColor.ARGB32.green(color) * brightness);
        int blue = (int) Math.round(FastColor.ARGB32.blue(color) * brightness);
        return FastColor.ARGB32.color(FastColor.ARGB32.alpha(color), red, green, blue);
    }

    private static Matrix4f textBasis(Vec3 topLeft, Vec3 right, Vec3 up, Vec3 normal,
            float scale) {
        return new Matrix4f().zero()
                .m00((float) right.x * scale).m01((float) right.y * scale)
                .m02((float) right.z * scale)
                .m10((float) -up.x * scale).m11((float) -up.y * scale)
                .m12((float) -up.z * scale)
                .m20((float) normal.x * scale).m21((float) normal.y * scale)
                .m22((float) normal.z * scale)
                .m30((float) topLeft.x).m31((float) topLeft.y).m32((float) topLeft.z)
                .m33(1.0F);
    }

    private static void renderTileBackgrounds(PoseStack poseStack, MultiBufferSource buffers,
            TextDisplayGroup group, Vec3 origin, Vec3 right, Vec3 up, Vec3 normal, int color,
            boolean backSide) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        for (BlockPos tile : group.tiles()) {
            int column = backSide ? group.columns() - 1 - group.column(tile) : group.column(tile);
            occupied.add(cellKey(column, group.row(tile)));
        }
        for (BlockPos tile : group.tiles()) {
            int column = backSide ? group.columns() - 1 - group.column(tile) : group.column(tile);
            Vec3 tileOrigin = origin.add(right.scale(column))
                    .add(up.scale(group.row(tile)));
            int row = group.row(tile);
            boolean joinedLeft = occupied.contains(cellKey(column - 1, row));
            boolean joinedRight = occupied.contains(cellKey(column + 1, row));
            boolean joinedDown = occupied.contains(cellKey(column, row - 1));
            boolean joinedUp = occupied.contains(cellKey(column, row + 1));
            double leftInset = joinedLeft ? -0.001D : 0.06D;
            double rightEdge = joinedRight ? 1.001D : 0.94D;
            double bottomInset = joinedDown ? -0.001D : 0.10D;
            double topEdge = joinedUp ? 1.001D : 0.86D;
            Vec3 lowerLeft = tileOrigin.add(right.scale(leftInset)).add(up.scale(bottomInset))
                    .add(normal.scale(0.004D));
            Vec3 lowerRight = lowerLeft.add(right.scale(rightEdge - leftInset));
            Vec3 upperRight = lowerRight.add(up.scale(topEdge - bottomInset));
            Vec3 upperLeft = lowerLeft.add(up.scale(topEdge - bottomInset));
            vertex(consumer, pose, lowerLeft, color);
            vertex(consumer, pose, lowerRight, color);
            vertex(consumer, pose, upperRight, color);
            vertex(consumer, pose, upperLeft, color);
        }
    }

    private static long cellKey(int column, int row) {
        return ((long) column << 32) ^ (row & 0xFFFFFFFFL);
    }

    private static void renderElectricScanlines(PoseStack poseStack, MultiBufferSource buffers,
            TextDisplayGroup group, Vec3 origin, Vec3 right, Vec3 up, Vec3 normal, int color,
            boolean backSide) {
        int gridColor = FastColor.ARGB32.color(40, FastColor.ARGB32.red(color),
                FastColor.ARGB32.green(color), FastColor.ARGB32.blue(color));
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        java.util.Map<Integer, java.util.SortedSet<Integer>> rows = new java.util.TreeMap<>();
        for (BlockPos tile : group.tiles()) {
            int column = backSide ? group.columns() - 1 - group.column(tile) : group.column(tile);
            int row = group.row(tile);
            rows.computeIfAbsent(row, ignored -> new java.util.TreeSet<>()).add(column);
        }
        for (var rowEntry : rows.entrySet()) {
            Integer runStart = null;
            int previous = Integer.MIN_VALUE;
            List<int[]> runs = new ArrayList<>();
            for (int column : rowEntry.getValue()) {
                if (runStart == null) runStart = column;
                else if (column != previous + 1) {
                    runs.add(new int[] {runStart, previous});
                    runStart = column;
                }
                previous = column;
            }
            if (runStart != null) runs.add(new int[] {runStart, previous});
            for (int[] run : runs) {
                Vec3 runOrigin = origin.add(right.scale(run[0])).add(up.scale(rowEntry.getKey()))
                        .add(normal.scale(0.0055D));
                double width = run[1] - run[0] + 0.88D;
                for (int stripe = 1; stripe <= 4; stripe++) {
                    double y = 0.10D + stripe * 0.152D;
                    Vec3 lowerLeft = runOrigin.add(right.scale(0.06D)).add(up.scale(y));
                    Vec3 lowerRight = lowerLeft.add(right.scale(width));
                Vec3 upperRight = lowerRight.add(up.scale(0.012D));
                Vec3 upperLeft = lowerLeft.add(up.scale(0.012D));
                vertex(consumer, pose, lowerLeft, gridColor);
                vertex(consumer, pose, lowerRight, gridColor);
                vertex(consumer, pose, upperRight, gridColor);
                vertex(consumer, pose, upperLeft, gridColor);
                }
            }
        }
    }

    private static void renderTemplateTexture(PoseStack poseStack, MultiBufferSource buffers,
            TextDisplayGroup group, Vec3 origin, Vec3 right, Vec3 up, Vec3 normal,
            net.minecraft.resources.ResourceLocation texture, boolean backSide) {
        VertexConsumer consumer = buffers.getBuffer(ScreenRenderType.screen(texture));
        Matrix4f pose = poseStack.last().pose();
        for (BlockPos tile : group.tiles()) {
            int column = backSide ? group.columns() - 1 - group.column(tile) : group.column(tile);
            int row = group.row(tile);
            float u0 = column / (float) group.columns();
            float u1 = (column + 1.0F) / group.columns();
            float vBottom = 1.0F - row / (float) group.rows();
            float vTop = 1.0F - (row + 1.0F) / group.rows();
            Vec3 lowerLeft = origin.add(right.scale(column - 0.001D))
                    .add(up.scale(row - 0.001D)).add(normal.scale(0.005D));
            Vec3 lowerRight = lowerLeft.add(right.scale(1.002D));
            Vec3 upperRight = lowerRight.add(up.scale(1.002D));
            Vec3 upperLeft = lowerLeft.add(up.scale(1.002D));
            textureVertex(consumer, pose, lowerLeft, u0, vBottom);
            textureVertex(consumer, pose, lowerRight, u1, vBottom);
            textureVertex(consumer, pose, upperRight, u1, vTop);
            textureVertex(consumer, pose, upperLeft, u0, vTop);
        }
    }

    private static void textureVertex(VertexConsumer consumer, Matrix4f pose, Vec3 point,
            float u, float v) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(255, 255, 255, 255).setUv(u, v);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Vec3 point, int color) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(color).setLight(LightTexture.FULL_BRIGHT);
    }
}
