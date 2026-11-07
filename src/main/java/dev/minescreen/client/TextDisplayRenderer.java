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
            if (trafficTemplate.id().equals("melbourne_metro")
                    && (display.trafficRole() == TrafficDisplayRole.ONBOARD
                            || !(level instanceof net.minecraft.client.multiplayer.ClientLevel))) {
                background = 0xFF070B10;
                foreground = 0xFFF4F6F8;
            }
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

    @Override
    public int getViewDistance() {
        return dev.minescreen.MineScreenConfig.MAX_RENDER_DISTANCE.get();
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
        String selectedTemplate = display.templateId(backSide) == null ? ""
                : display.templateId(backSide).trim().toLowerCase(java.util.Locale.ROOT);
        MovingStructureCompat.CarriageTrainStatus movingTrain =
                MovingStructureCompat.carriageTrainStatus(display.getLevel(),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                display.templateId(backSide),
                                display.stationTurnaroundName(backSide)));
        if (movingTrain != null) {
            if (selectedTemplate.equals("melbourne_metro")
                    || selectedTemplate.startsWith("melbourne_cabin")) {
                renderMelbourneCabin(display, backSide, movingTrain, selectedTemplate,
                        movingWidth, movingHeight, poseStack, buffers);
            } else if (template != null && template.layout().equals("script_scene_v1")
                    && !template.scene().isEmpty()) {
                renderScriptScene(display, backSide, template, movingWidth, movingHeight,
                        movingTrain, poseStack, buffers);
            } else {
                renderMovingTrainBoard(display, backSide, movingTrain, movingWidth, movingHeight,
                        color, poseStack, buffers);
            }
            return;
        }
        if (selectedTemplate.startsWith("melbourne_cabin")
                || (display.trafficRole() == TrafficDisplayRole.ONBOARD
                        && selectedTemplate.equals("melbourne_metro"))) {
            renderMelbourneCabinStationaryPreview(display, backSide, selectedTemplate,
                    movingWidth, movingHeight, poseStack, buffers);
            return;
        }
        if (display.trafficRole() == TrafficDisplayRole.ONBOARD) {
            renderOnboardWaiting(movingWidth, movingHeight, color, poseStack, buffers);
            return;
        }
        if (stationMode == StationDisplayMode.STATION_NEXT) {
            int lw = logicalWidth(canvasWidth, sizeFactor);
            int lh = logicalHeight(canvasHeight, sizeFactor);
            switch (selectedTemplate) {
                case "melbourne_metro" -> renderStationMelbourneMetro(display, backSide,
                        lw, lh, poseStack, buffers);
                case "melbourne_concourse" -> renderMelbourneConcourseCards(display, backSide,
                        lw, lh, poseStack, buffers);
                case "melbourne_concourse_split" -> renderMelbourneConcourseSplit(display, backSide,
                        lw, lh, poseStack, buffers);
                case "melbourne_wayfinding" -> renderMelbourneWayfinding(display, backSide,
                        lw, lh, poseStack, buffers);
                default -> renderStationNext(display, backSide, color,
                        lw, lh, 42.0F / sizeFactor, poseStack, buffers);
            }
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

        line2d(buffers.getBuffer(RenderType.textBackground()), pose, 6,
                Math.max(50, height - 25), width - 6,
                Math.max(50, height - 25), 1.0D, 0xFF4B5968);
        List<String> stops = train.upcomingStops().stream()
                .filter(stop -> !isSkippedStopEntry(stop))
                .map(stop -> TrafficTemplateRepository.mappedStationName(
                        templateId, stripSkippedPrefix(stop)))
                .filter(stop -> !stop.isBlank()).limit(6).toList();
        String footer = stops.isEmpty() ? ""
                : Component.translatable("screen.minescreen.carriage_info.stops_at",
                        String.join(" · ", stops)).getString();
        drawFitted(footer, 7, Math.max(53, height - 20), width - 14,
                0xFFC7D3E2, 0.72F, poseStack, buffers);
    }

    private void renderMelbourneCabin(TextDisplayBlockEntity display, boolean backSide,
            MovingStructureCompat.CarriageTrainStatus train, String selectedTemplate,
            int width, int height, PoseStack poseStack, MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        String nextStation = conciseStation(TrafficTemplateRepository.mappedStationName(
                templateId, train.stationName()));
        String destination = conciseStation(TrafficTemplateRepository.mappedStationName(
                templateId, train.terminalStation()));
        if (internalLoopMarker(destination)) destination = "";
        List<String> stops = new ArrayList<>();
        for (String stop : train.upcomingStops()) {
            boolean skipped = isSkippedStopEntry(stop);
            String mapped = conciseStation(TrafficTemplateRepository.mappedStationName(
                    templateId, stripSkippedPrefix(stop)));
            if (!mapped.isBlank()) stops.add(skipped ? "~" + mapped : mapped);
        }
        if (stops.isEmpty()) {
            if (!nextStation.isBlank()) stops.add(nextStation);
            if (!destination.isBlank() && !destination.equalsIgnoreCase(nextStation)) {
                stops.add(destination);
            }
        }
        if (destination.isBlank() && !stops.isEmpty()) {
            destination = stripSkippedPrefix(stops.getLast());
        }
        int accent = melbournePlatformColor(display, backSide, 0, 0xFF279FD5);
        Direction facing = display.getBlockState().hasProperty(TextDisplayBlock.FACING)
                ? display.getBlockState().getValue(TextDisplayBlock.FACING) : Direction.NORTH;
        Vec3 screenRight = backSide
                ? ScreenGeometry.right(facing).scale(-1.0D)
                : ScreenGeometry.right(facing);
        int doorSide = MovingStructureCompat.carriageDoorSide(display.getLevel(), screenRight,
                train.stationName());
        doorSide = resolveManualDoorSide(display.trafficStatus(backSide),
                display.trafficEta(backSide), doorSide);

        boolean showArrivalBanner = shouldShowMelbourneCabinArrivalBanner(selectedTemplate,
                train.phase(), train.etaTicks(), train.distance(), display.getLevel());
        if (showArrivalBanner) {
            renderMelbourneCabinNext(nextStation, destination, train.phase(), accent,
                    doorSide, stops, train.serviceType(), train.trainName(),
                    width, height, poseStack, buffers);
        } else {
            renderMelbourneCabinMap(stops, nextStation, destination, accent,
                    train.serviceType(), train.trainName(),
                    width, height, poseStack, buffers);
        }
    }

    private static int resolveManualDoorSide(String status, String eta, int detectedSide) {
        for (String val : new String[] { status, eta }) {
            if (val == null || val.isBlank()) continue;
            String lower = val.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("left") || lower.contains("左")) return -1;
            if (lower.contains("right") || lower.contains("右")) return 1;
        }
        return detectedSide;
    }

    public static boolean shouldShowMelbourneCabinArrivalBanner(String selectedTemplate,
            MovingStructureCompat.ArrivalPhase phase, long etaTicks, double distance,
            Level level) {
        if ("melbourne_cabin_next".equals(selectedTemplate)) return true;
        if ("melbourne_cabin_map".equals(selectedTemplate)) return false;
        if (phase == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            return true;
        }
        if (phase == MovingStructureCompat.ArrivalPhase.APPROACHING) {
            boolean finalBrakingApproach = (Double.isFinite(distance) && distance <= 12.0D)
                    || (etaTicks >= 0L && etaTicks <= 4L * 20L);
            if (finalBrakingApproach) {
                return true;
            }
        }
        // While cruising between stations, show the Route Map for ~9 seconds and briefly flash
        // the "Next station" broadcast for ~3 seconds so passengers see both automatically.
        if (level != null && (etaTicks < 0L || etaTicks > 6L * 20L)) {
            long cycleTick = Math.floorMod(level.getGameTime(), 240L);
            return cycleTick >= 180L;
        }
        return false;
    }

    private void renderMelbourneCabinStationaryPreview(TextDisplayBlockEntity display,
            boolean backSide, String selectedTemplate, int width, int height,
            PoseStack poseStack, MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        CreateTrainScheduleService.ResolvedSnapshot resolved =
                CreateTrainScheduleService.snapshotForDisplay(display.getLevel(),
                        display.getBlockPos(), templateId,
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                templateId, display.stationTurnaroundName(backSide)), 1);
        CreateTrainScheduleService.Snapshot snapshot = resolved.snapshot();
        String nextStation = display.trafficNextStop(backSide);
        String destination = display.trafficDestination(backSide);
        List<String> stops = new ArrayList<>();
        MovingStructureCompat.ArrivalPhase phase = MovingStructureCompat.ArrivalPhase.EN_ROUTE;
        long etaTicks = -1L;
        String serviceType = "";
        String trainName = "";
        if (!snapshot.departures().isEmpty()) {
            CreateTrainScheduleService.Departure dep = snapshot.departures().getFirst();
            serviceType = dep.trainType();
            trainName = dep.trainName();
            for (String stop : dep.upcomingStops()) {
                boolean skipped = isSkippedStopEntry(stop);
                String mapped = conciseStation(
                        TrafficTemplateRepository.mappedStationName(templateId,
                                stripSkippedPrefix(stop)));
                if (!mapped.isBlank()) stops.add(skipped ? "~" + mapped : mapped);
            }
            if (!stops.isEmpty()) nextStation = stripSkippedPrefix(stops.getFirst());
            if (!dep.destination().isBlank()) {
                destination = conciseStation(TrafficTemplateRepository.mappedStationName(
                        templateId, dep.destination()));
            }
            etaTicks = dep.etaTicks();
            if (dep.dwelling()) {
                phase = MovingStructureCompat.ArrivalPhase.ARRIVED;
            } else if (dep.etaKnown() && dep.etaSeconds() <= 5) {
                phase = MovingStructureCompat.ArrivalPhase.APPROACHING;
            }
        }
        if (stops.isEmpty()) {
            String cur = display.trafficCurrentStop(backSide);
            if (cur != null && !cur.isBlank()) stops.add(conciseStation(cur));
            if (nextStation != null && !nextStation.isBlank()
                    && (stops.isEmpty() || !stops.getLast().equalsIgnoreCase(nextStation))) {
                stops.add(conciseStation(nextStation));
            }
            if (destination != null && !destination.isBlank()
                    && (stops.isEmpty() || !stops.getLast().equalsIgnoreCase(destination))) {
                stops.add(conciseStation(destination));
            }
        }
        int accent = melbournePlatformColor(display, backSide, 0, 0xFF279FD5);
        int doorSide = resolveManualDoorSide(display.trafficStatus(backSide),
                display.trafficEta(backSide), 1);

        boolean showArrivalBanner = shouldShowMelbourneCabinArrivalBanner(selectedTemplate,
                phase, etaTicks, Double.NaN, display.getLevel());
        if (showArrivalBanner) {
            renderMelbourneCabinNext(nextStation, destination, phase, accent,
                    doorSide, stops, serviceType, trainName, width, height, poseStack, buffers);
        } else {
            renderMelbourneCabinMap(stops, nextStation, destination, accent,
                    serviceType, trainName, width, height, poseStack, buffers);
        }
    }

    /**
     * Melbourne Cabin View A (Image 1):
     * Dark cabin banner with a white top callout bubble ("Now arriving at" / "Next station" +
     * downward pointer), large colored station pill box, and "Doors opening this side" + door
     * icon + left/right arrow on the right.
     */
    private void renderMelbourneCabinNext(String nextStation, String destination,
            MovingStructureCompat.ArrivalPhase phase, int accentColor, int doorSide,
            List<String> stops, String serviceType, String trainName,
            int width, int height, PoseStack poseStack, MultiBufferSource buffers) {
        final int white = 0xFFF4F6F8;
        final int darkText = 0xFF0C141C;
        final int ruleColor = 0xFF4A5664;

        float margin = Math.max(5.0F, width * 0.035F);
        float dividerX = width * 0.68F;
        String stationText = (nextStation == null || nextStation.isBlank())
                ? (destination == null || destination.isBlank() ? "Awaiting Route" : destination)
                : nextStation;

        String callout = switch (phase) {
            case ARRIVED -> "Now at";
            case APPROACHING -> "Now arriving at";
            case WAITING_SIGNAL -> "Held at signal";
            case EN_ROUTE, NO_ROUTE -> "Next station";
        };

        // 1. Callout bubble + Station pill dimensions
        float leftCenter = dividerX * 0.50F;
        float calloutScale = height >= 120 ? 0.90F : 0.66F;
        float calloutTextW = font.width(callout) * calloutScale;
        float bubbleW = Math.min(dividerX - margin * 2.0F, calloutTextW + 10.0F);
        float bubbleH = Math.max(9.5F, font.lineHeight * calloutScale + 4.0F);
        float bubbleX = Math.max(margin, leftCenter - bubbleW * 0.5F);
        float bubbleY = Math.max(6.0F, height * 0.16F);

        float pillScale = height >= 120 ? 1.65F : 1.18F;
        float maxPillW = Math.max(24.0F, dividerX - margin * 2.0F - 4.0F);
        float rawStationW = font.width(stationText) * pillScale;
        float pillW = Math.min(maxPillW, Math.max(bubbleW + 14.0F, rawStationW + 14.0F));
        float pillH = Math.max(16.0F, Math.min(height * 0.42F, font.lineHeight * pillScale + 8.0F));
        float pillX = Math.max(margin, leftCenter - pillW * 0.5F);
        float pillY = bubbleY + bubbleH + 3.2F;

        VertexConsumer geom = buffers.getBuffer(RenderType.textBackground());
        Matrix4f basePose = poseStack.last().pose();
        // White speech bubble + downward pointer triangle
        quad2d(geom, basePose, bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH, white);
        quad2d(geom, basePose, leftCenter - 2.4F, bubbleY + bubbleH,
                leftCenter + 2.4F, bubbleY + bubbleH + 1.3F, white);
        quad2d(geom, basePose, leftCenter - 1.1F, bubbleY + bubbleH + 1.3F,
                leftCenter + 1.1F, bubbleY + bubbleH + 2.4F, white);

        // Large colored station highlight box
        quad2d(geom, basePose, pillX, pillY, pillX + pillW, pillY + pillH, accentColor);

        // Vertical separator rule between station box and door indicator
        quad2d(geom, basePose, dividerX - 0.5F, height * 0.16F, dividerX + 0.5F,
                height * 0.84F, ruleColor);

        // Right-side Train Doors icon ([][]) + Left (<-) or Right (->) Arrow
        float rightX = dividerX + Math.max(4.0F, width * 0.025F);
        float rightW = Math.max(14.0F, width - rightX - margin);
        float iconY = pillY + pillH - Math.max(11.0F, pillH * 0.56F);
        float doorH = Math.max(9.5F, Math.min(18.0F, pillH * 0.52F));
        float leafW = Math.max(4.0F, doorH * 0.42F);
        float arrowSpan = Math.min(doorH * 0.85F, Math.max(5.0F, rightW - leafW * 2.0F - 7.0F));
        float arrowMidY = iconY + doorH * 0.5F;
        boolean opensLeft = doorSide < 0;
        float doorX;
        if (opensLeft) {
            float arrowX0 = rightX + 1.0F;
            float arrowX1 = arrowX0 + arrowSpan;
            doorX = arrowX1 + 5.0F;
            if (arrowX1 > arrowX0 + 3.0F) {
                quad2d(geom, basePose, arrowX0, arrowMidY - 0.9F, arrowX1, arrowMidY + 0.9F, white);
                line2d(geom, basePose, arrowX0, arrowMidY,
                        arrowX0 + 3.8F, arrowMidY - 3.6F, 1.5D, white);
                line2d(geom, basePose, arrowX0, arrowMidY,
                        arrowX0 + 3.8F, arrowMidY + 3.6F, 1.5D, white);
            }
        } else {
            doorX = rightX + 1.0F;
            float arrowX0 = doorX + leafW * 2.0F + 5.0F;
            float arrowX1 = Math.min(width - margin, arrowX0 + arrowSpan);
            if (arrowX1 > arrowX0 + 3.0F) {
                quad2d(geom, basePose, arrowX0, arrowMidY - 0.9F, arrowX1, arrowMidY + 0.9F, white);
                line2d(geom, basePose, arrowX1 - 3.8F, arrowMidY - 3.6F,
                        arrowX1, arrowMidY, 1.5D, white);
                line2d(geom, basePose, arrowX1 - 3.8F, arrowMidY + 3.6F,
                        arrowX1, arrowMidY, 1.5D, white);
            }
        }
        // Left & right door leaves with non-overlapping dark upper window cutouts
        fillWithHole(geom, basePose, doorX, iconY, doorX + leafW, iconY + doorH,
                doorX + 1.0F, iconY + 1.4F, doorX + leafW - 1.0F, iconY + doorH * 0.48F, white);
        quad2d(geom, basePose, doorX + 1.0F, iconY + 1.4F,
                doorX + leafW - 1.0F, iconY + doorH * 0.48F, 0xFF070B10);
        fillWithHole(geom, basePose, doorX + leafW + 1.2F, iconY,
                doorX + leafW * 2.0F + 1.2F, iconY + doorH,
                doorX + leafW + 2.2F, iconY + 1.4F,
                doorX + leafW * 2.0F + 0.2F, iconY + doorH * 0.48F, white);
        quad2d(geom, basePose, doorX + leafW + 2.2F, iconY + 1.4F,
                doorX + leafW * 2.0F + 0.2F, iconY + doorH * 0.48F, 0xFF070B10);

        // 2. Text layers
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.25D);
        drawFitted(callout, bubbleX + (bubbleW - Math.min(bubbleW - 4.0F, calloutTextW)) * 0.5F,
                bubbleY + (bubbleH - font.lineHeight * calloutScale) * 0.5F,
                bubbleW - 4.0F, darkText, calloutScale, poseStack, buffers);

        float fittedPillScale = Math.min(pillScale,
                (pillW - 8.0F) / Math.max(1.0F, font.width(stationText)));
        float sTextW = font.width(stationText) * fittedPillScale;
        drawScaled(Component.literal(stationText).getVisualOrderText(),
                pillX + (pillW - sTextW) * 0.5F,
                pillY + (pillH - font.lineHeight * fittedPillScale) * 0.5F,
                darkText, fittedPillScale, poseStack, buffers);

        float sideTextScale = height >= 120 ? 0.68F : 0.50F;
        String rightLine1 = (phase == MovingStructureCompat.ArrivalPhase.APPROACHING
                || phase == MovingStructureCompat.ArrivalPhase.ARRIVED)
                ? "Doors opening"
                : (destination != null && !destination.isBlank() ? "To " + destination : "Doors opening");
        String rightLine2 = (phase == MovingStructureCompat.ArrivalPhase.APPROACHING
                || phase == MovingStructureCompat.ArrivalPhase.ARRIVED)
                ? "this side"
                : melbourneStoppingPatternLabel(stops, serviceType, trainName, false, true);
        float rTextY1 = bubbleY + 1.0F;
        float rTextY2 = rTextY1 + font.lineHeight * sideTextScale + 1.5F;
        drawFitted(rightLine1, rightX, rTextY1, rightW, white, sideTextScale, poseStack, buffers);
        drawFitted(rightLine2, rightX, rTextY2, rightW, white, sideTextScale, poseStack, buffers);
        poseStack.popPose();
    }

    /**
     * Melbourne Cabin View B (Image 2):
     * Horizontal colored route line with diagonal (-28 deg) station labels above each stop,
     * a circular ring + angled colored highlight pill for the next station, and
     * "To <Terminal>   Stopping all stations" along the bottom bar.
     */
    private void renderMelbourneCabinMap(List<String> allStops, String nextStation,
            String destination, int accentColor, String serviceType, String trainName,
            int width, int height, PoseStack poseStack, MultiBufferSource buffers) {
        final int white = 0xFFF4F6F8;
        final int passedColor = 0xFF8E9FAD;
        final int darkText = 0xFF0A121A;
        final int ruleColor = 0xFF687685;

        float margin = Math.max(6.0F, width * 0.04F);
        float lineY = height * 0.55F;
        float footerRuleY = height * 0.72F;

        List<String> stops = new ArrayList<>();
        if (allStops != null) {
            for (String s : allStops) {
                if (!isSkippedStopEntry(s)) {
                    String clean = stripSkippedPrefix(s);
                    if (!clean.isBlank()) stops.add(clean);
                }
            }
        }
        if (stops.isEmpty()) {
            if (nextStation != null && !nextStation.isBlank()) stops.add(nextStation);
            if (destination != null && !destination.isBlank()
                    && (stops.isEmpty() || !stops.getLast().equalsIgnoreCase(destination))) {
                stops.add(destination);
            }
        }
        if (stops.isEmpty()) {
            stops.add("Station 1");
            stops.add("Station 2");
        }

        int activeIdx = 0;
        if (nextStation != null && !nextStation.isBlank()) {
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).equalsIgnoreCase(nextStation)) {
                    activeIdx = i;
                    break;
                }
            }
        }

        int maxVisible = width >= 300 ? 10 : width >= 200 ? 7 : 5;
        if (stops.size() > maxVisible) {
            int start = Math.max(0, Math.min(activeIdx - 1, stops.size() - maxVisible));
            stops = new ArrayList<>(stops.subList(start, start + maxVisible));
            activeIdx = Math.max(0, activeIdx - start);
        }

        // 1. Horizontal route bar + footer separator rule
        float lineStartX = margin + 4.0F;
        float lineEndX = width - margin - 10.0F;
        VertexConsumer geom = buffers.getBuffer(RenderType.textBackground());
        Matrix4f basePose = poseStack.last().pose();
        quad2d(geom, basePose, lineStartX, lineY - 1.5F, lineEndX, lineY + 1.5F, accentColor);
        quad2d(geom, basePose, margin, footerRuleY - 0.5F, width - margin, footerRuleY + 0.3F,
                ruleColor);

        int n = stops.size();
        float spanX = Math.max(10.0F, lineEndX - lineStartX - 8.0F);
        float stepX = n <= 1 ? 0.0F : spanX / (n - 1);

        // 2. Station ticks and active station ring node
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.20D);
        VertexConsumer ringGeom = buffers.getBuffer(RenderType.textBackground());
        Matrix4f ringPose = poseStack.last().pose();
        for (int i = 0; i < n; i++) {
            float nodeX = n == 1 ? (lineStartX + lineEndX) * 0.5F : (lineStartX + 4.0F + i * stepX);
            if (i == activeIdx) {
                fillWithHole(ringGeom, ringPose, nodeX - 2.4F, lineY - 2.4F,
                        nodeX + 2.4F, lineY + 2.4F,
                        nodeX - 1.8F, lineY - 1.8F, nodeX + 1.8F, lineY + 1.8F, darkText);
                fillWithHole(ringGeom, ringPose, nodeX - 1.8F, lineY - 1.8F,
                        nodeX + 1.8F, lineY + 1.8F,
                        nodeX - 0.8F, lineY - 0.8F, nodeX + 0.8F, lineY + 0.8F, white);
                quad2d(ringGeom, ringPose, nodeX - 0.8F, lineY - 0.8F,
                        nodeX + 0.8F, lineY + 0.8F, darkText);
            } else {
                quad2d(ringGeom, ringPose, nodeX - 0.9F, lineY - 2.4F, nodeX + 0.9F, lineY + 2.4F,
                        i < activeIdx ? passedColor : white);
            }
        }
        poseStack.popPose();

        // 3. Diagonal (-28 deg) station labels + colored pill box on active station
        float labelScale = height >= 120 ? 0.72F : 0.54F;
        float maxLabelLen = Math.max(28.0F, lineY * 1.45F);
        for (int i = 0; i < n; i++) {
            float nodeX = n == 1 ? (lineStartX + lineEndX) * 0.5F : (lineStartX + 4.0F + i * stepX);
            String name = stops.get(i);
            float scale = Math.min(labelScale, maxLabelLen / Math.max(1.0F, font.width(name)));
            float textW = font.width(name) * scale;
            float textH = font.lineHeight * scale;

            poseStack.pushPose();
            poseStack.translate(nodeX, lineY - 4.2F, 0.30D);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-28.0F));
            if (i == activeIdx) {
                VertexConsumer pillGeom = buffers.getBuffer(RenderType.textBackground());
                quad2d(pillGeom, poseStack.last().pose(), -1.5F, -textH - 1.0F,
                        textW + 3.0F, 1.2F, accentColor);
                poseStack.pushPose();
                poseStack.translate(0.5D, -textH, 0.25D);
                poseStack.scale(scale, scale, 1.0F);
                font.drawInBatch(Component.literal(name).getVisualOrderText(), 0.0F, 0.0F,
                        darkText, false, poseStack.last().pose(), buffers,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
                poseStack.popPose();
            } else {
                poseStack.translate(0.0D, -textH, 0.20D);
                poseStack.scale(scale, scale, 1.0F);
                font.drawInBatch(Component.literal(name).getVisualOrderText(), 0.0F, 0.0F,
                        i < activeIdx ? passedColor : white, false, poseStack.last().pose(),
                        buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
            poseStack.popPose();
        }

        // 4. Bottom Footer: "To <Terminal>     Stopping all stations" / "Limited express" / "Express"
        String term = (destination == null || destination.isBlank())
                ? stops.getLast() : destination;
        String patternLabel = melbourneStoppingPatternLabel(allStops, serviceType, trainName,
                false, false);
        if (patternLabel.equalsIgnoreCase("Stops all stations")) {
            patternLabel = "Stopping all stations";
        }
        String footerText = "To " + term + "     " + patternLabel;
        float footerScale = height >= 120 ? 0.92F : 0.68F;
        float footerY = footerRuleY + (height - footerRuleY - font.lineHeight * footerScale) * 0.5F;
        drawFitted(footerText, margin, footerY, width - margin * 2.0F, white, footerScale,
                poseStack, buffers);
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
        int minRowHeight = font.lineHeight * 2 + 3;
        int remainingHeight = Math.max(minRowHeight,
                Math.round(logicalHeight - headerHeight - 3.0F));
        int maxVisibleRows = Math.min(4, Math.max(1, remainingHeight / minRowHeight));
        List<CreateTrainScheduleService.Departure> visibleDepartures =
                snapshot.departures().stream()
                        .filter(departure -> departure.dwelling()
                                || departure.etaKnown()
                                && departure.etaTicks() <= 90L * 20L)
                        .limit(maxVisibleRows).toList();
        int rowHeight = Math.max(minRowHeight,
                remainingHeight / Math.max(1, visibleDepartures.size()));
        int rowY = Math.round(headerHeight + 3.0F);
        int maxBottomY = logicalHeight - 1;
        if (snapshot.departures().isEmpty()) {
            float bodyScale = logicalHeight < 110 ? 0.85F : 1.0F;
            float nextY = rowY + Math.round(font.lineHeight * 1.05F) + 3.0F;
            if (snapshot.stationName().isBlank()) {
                drawScaled(Component.translatable("screen.minescreen.station.not_found")
                        .getVisualOrderText(), 6, rowY, color, 1.05F, poseStack, buffers);
                drawWrappedWorld(Component.translatable("screen.minescreen.station.bind_help"),
                        6, nextY, logicalWidth - 12, 4, maxBottomY, bodyScale, color,
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
            nextY = drawWrappedWorld(reason, 6, nextY, logicalWidth - 12, 3,
                    maxBottomY, bodyScale, color, poseStack, buffers);
            drawWrappedWorld(Component.translatable("screen.minescreen.station.schedule_help"),
                    6, nextY + 2.0F, logicalWidth - 12, 3, maxBottomY, bodyScale,
                    0xFFB5C2D0, poseStack, buffers);
            return;
        }
        if (visibleDepartures.isEmpty()) {
            drawWrappedWorld(Component.translatable(
                    "screen.minescreen.station.no_arrivals_in_window"),
                    6, rowY, logicalWidth - 12, 3, maxBottomY, 1.0F, 0xFF9FB0C2,
                    poseStack, buffers);
            return;
        }
        // Draw the title/row separators before text. Their z value remains behind every glyph.
        // Re-acquire the textBackground buffer because earlier font.drawInBatch calls flush
        // non-fixed buffers in MultiBufferSource.BufferSource.
        stationGeometry = buffers.getBuffer(RenderType.textBackground());
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

            int routeMaxWidth = Math.max(1, logicalWidth - arrivalReserve - 14);
            String route = routeText(display.templateId(backSide), departure.origin(),
                    departure.destination(), departure.loop(), departure.upcomingStops());
            int rowBottomY = Math.min(maxBottomY, y + rowHeight - 2);
            if (rowHeight >= font.lineHeight * 3 + 2
                    && font.width(route) * 0.86F > routeMaxWidth) {
                drawWrappedWorld(Component.literal(route), 6, y + font.lineHeight + 1,
                        routeMaxWidth, 2, rowBottomY, 0.80F, 0xFFC9D5E3, poseStack, buffers);
            } else {
                drawFitted(route, 6, y + font.lineHeight + 1,
                        routeMaxWidth, 0xFFC9D5E3, 0.86F,
                        poseStack, buffers);
            }
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

    private void renderStationMelbourneMetro(TextDisplayBlockEntity display, boolean backSide,
            int logicalWidth, int logicalHeight, PoseStack poseStack,
            MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        CreateTrainScheduleService.ResolvedSnapshot resolved =
                CreateTrainScheduleService.snapshotForDisplay(display.getLevel(),
                        display.getBlockPos(), templateId,
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                templateId, display.stationTurnaroundName(backSide)), 4);
        CreateTrainScheduleService.Snapshot snapshot = resolved.snapshot();
        String stationName = snapshot.stationName().isBlank()
                ? display.trafficDestination(backSide) : snapshot.stationName();
        List<CreateTrainScheduleService.Departure> visibleDepartures =
                snapshot.departures().stream()
                        .filter(departure -> departure.dwelling()
                                || departure.etaKnown()
                                || departure.etaTicks() >= 0L)
                        .limit(4).toList();

        final int metroGreen = melbournePlatformColor(display, backSide, 0, 0xFF008542);
        final int darkText = 0xFF101B24;
        final int subText = 0xFF36424E;
        final int badgeBg = 0xFF232B33;
        final int ruleColor = 0xFF5A6774;
        final int bottomRuleColor = 0xFFB8C2CC;

        float margin = Math.max(4.0F, logicalWidth * 0.032F);
        float topBarH = Math.max(2.5F, logicalHeight * 0.042F);
        float dividerY = Math.max(20.0F, logicalHeight * 0.24F);
        float bottomTop = Math.min(logicalHeight - 15.0F, logicalHeight * 0.77F);
        float badgeW = Math.max(28.0F, Math.min(64.0F, logicalWidth * 0.20F));
        float badgeH = Math.max(9.0F, (dividerY - topBarH) * 0.50F);
        float badgeX = logicalWidth - margin - badgeW;
        float badgeY = topBarH + 2.0F;

        boolean hasPlatformBadge =
                CreateTrainScheduleService.hasExplicitPlatformSuffix(stationName);
        String platformNumber = hasPlatformBadge
                ? CreateTrainScheduleService.extractPlatformLabel(stationName, 1) : "";
        float platSize = hasPlatformBadge ? Math.max(9.5F, badgeH) : 0.0F;
        float platX = hasPlatformBadge ? badgeX - platSize - 2.5F : badgeX;

        float clockW = Math.max(34.0F, Math.min(76.0F, logicalWidth * 0.25F));
        float clockX = logicalWidth - margin - clockW;
        float clockY = bottomTop + 2.0F;
        float clockH = Math.max(10.0F, logicalHeight - clockY - 2.0F);

        // 1. Base chrome geometry (top green line bar, ETA badge box, divider rules, clock box)
        VertexConsumer chrome = buffers.getBuffer(RenderType.textBackground());
        Matrix4f basePose = poseStack.last().pose();
        quad2d(chrome, basePose, 0, 0, logicalWidth, topBarH, metroGreen);
        if (hasPlatformBadge) {
            quad2d(chrome, basePose, platX, badgeY, platX + platSize, badgeY + platSize,
                    metroGreen);
        }
        quad2d(chrome, basePose, badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeBg);
        quad2d(chrome, basePose, margin, dividerY - 0.8F, logicalWidth - margin, dividerY,
                ruleColor);
        quad2d(chrome, basePose, margin, bottomTop - 0.8F, logicalWidth - margin, bottomTop,
                bottomRuleColor);
        fillWithHole(chrome, basePose, clockX, clockY, clockX + clockW, clockY + clockH,
                clockX + 0.8F, clockY + 0.8F, clockX + clockW - 0.8F, clockY + clockH - 0.8F,
                0xFF8C97A3);
        quad2d(chrome, basePose,
                clockX + 0.8F, clockY + 0.8F, clockX + clockW - 0.8F, clockY + clockH - 0.8F,
                0xFFEBEEF2);

        if (hasPlatformBadge) {
            float pScale = Math.min(logicalHeight >= 140 ? 1.15F : 0.82F,
                    (platSize - 3.0F) / Math.max(1.0F, font.width(platformNumber)));
            float pTextW = font.width(platformNumber) * pScale;
            float pTextY = badgeY + (platSize - font.lineHeight * pScale) * 0.5F;
            drawScaled(Component.literal(platformNumber).getVisualOrderText(),
                    platX + (platSize - pTextW) * 0.5F, pTextY, 0xFFFFFFFF, pScale,
                    poseStack, buffers);
        }

        // Live world clock in bottom-right box
        String nowClock = worldClock(display.getLevel(), 0L);
        float clockScale = Math.min(logicalHeight >= 140 ? 1.05F : 0.72F,
                (clockW - 6.0F) / Math.max(1.0F, font.width(nowClock)));
        float clockTextW = font.width(nowClock) * clockScale;
        float clockTextY = clockY + (clockH - font.lineHeight * clockScale) * 0.5F;
        drawScaled(Component.literal(nowClock).getVisualOrderText(),
                clockX + (clockW - clockTextW) * 0.5F, clockTextY, darkText, clockScale,
                poseStack, buffers);

        if (visibleDepartures.isEmpty()) {
            String titleText = stationName.isBlank()
                    ? Component.translatable("screen.minescreen.station.not_found").getString()
                    : conciseStation(TrafficTemplateRepository.mappedStationName(
                            templateId, stationName));
            float titleScale = logicalHeight >= 140 ? 1.35F : 0.96F;
            drawFitted(titleText, margin, topBarH + 2.0F,
                    Math.max(1.0F, platX - margin - 6.0F), darkText, titleScale,
                    poseStack, buffers);
            drawFitted("-- min", badgeX + 3.0F,
                    badgeY + (badgeH - font.lineHeight * 0.75F) * 0.5F,
                    badgeW - 6.0F, 0xFFFFFFFF, 0.75F, poseStack, buffers);
            String subLine = snapshot.stationName().isBlank()
                    ? Component.translatable("screen.minescreen.station.bind_help").getString()
                    : Component.translatable("screen.minescreen.station.connected").getString();
            float subScale = logicalHeight >= 140 ? 0.78F : 0.60F;
            drawFitted(subLine, margin, dividerY - font.lineHeight * subScale - 2.0F,
                    logicalWidth - margin * 2.0F, subText, subScale, poseStack, buffers);

            Component middleMsg = snapshot.departures().isEmpty()
                    ? switch (snapshot.departureState(display.stationTrainType(backSide))) {
                        case NO_TRAINS -> Component.translatable(
                                "screen.minescreen.station.no_trains");
                        case NO_ACTIVE_SCHEDULES -> Component.translatable(
                                "screen.minescreen.station.no_active_schedules");
                        case FILTER_NO_MATCH -> Component.translatable(
                                "screen.minescreen.station.filter_no_match",
                                display.stationTrainType(backSide));
                        case NO_STATION_PREDICTIONS, AVAILABLE -> Component.translatable(
                                "screen.minescreen.station.no_station_predictions");
                    }
                    : Component.translatable("screen.minescreen.station.no_arrivals_in_window");
            drawWrappedWorld(middleMsg, margin + 2.0F, dividerY + 5.0F,
                    Math.round(logicalWidth - margin * 2.0F - 4.0F), 3,
                    Math.round(bottomTop - 2.0F), logicalHeight >= 140 ? 0.85F : 0.68F,
                    darkText, poseStack, buffers);
            return;
        }

        // 2. Primary Departure Header
        CreateTrainScheduleService.Departure first = visibleDepartures.getFirst();
        String firstArrivalClock = worldClock(display.getLevel(),
                first.dwelling() ? 0L : first.etaTicks());
        String firstDest = first.destination().isBlank() ? stationName : first.destination();
        firstDest = conciseStation(TrafficTemplateRepository.mappedStationName(
                templateId, firstDest));
        String rawLineField = display.trafficLine(backSide) == null ? ""
                : display.trafficLine(backSide).trim();
        String configuredLine = cleanLineText(rawLineField);
        boolean useTrainNameMode = configuredLine.equalsIgnoreCase("@train")
                || configuredLine.equalsIgnoreCase("train")
                || configuredLine.equalsIgnoreCase("train name")
                || configuredLine.equals("列车名")
                || configuredLine.equals("车次");
        boolean useDestMode = configuredLine.equalsIgnoreCase("@dest")
                || configuredLine.equalsIgnoreCase("dest")
                || configuredLine.equalsIgnoreCase("destination")
                || configuredLine.equals("终点站");
        boolean hasCustomScreenLine = !configuredLine.isBlank()
                && !configuredLine.equalsIgnoreCase("Line 1")
                && !useTrainNameMode
                && !useDestMode;
        String customTrainName = first.trainName() == null ? "" : first.trainName().trim();
        boolean hasCustomTrainName = !customTrainName.isBlank()
                && !customTrainName.equalsIgnoreCase("Unnamed Train")
                && !customTrainName.equals("未命名列车");
        String headerTitle = hasCustomScreenLine ? configuredLine
                : useTrainNameMode && hasCustomTrainName ? customTrainName
                : firstDest;
        String headerHeadline = firstArrivalClock + "  " + headerTitle;
        float titleScale = logicalHeight >= 140 ? 1.42F : 0.98F;
        drawFitted(headerHeadline, margin, topBarH + 1.8F,
                Math.max(1.0F, platX - margin - 6.0F), darkText, titleScale,
                poseStack, buffers);

        String etaLabel = melbourneEtaText(first);
        float etaScale = Math.min(logicalHeight >= 140 ? 1.05F : 0.74F,
                (badgeW - 6.0F) / Math.max(1.0F, font.width(etaLabel)));
        float etaTextW = font.width(etaLabel) * etaScale;
        float etaTextY = badgeY + (badgeH - font.lineHeight * etaScale) * 0.5F;
        drawScaled(Component.literal(etaLabel).getVisualOrderText(),
                badgeX + (badgeW - etaTextW) * 0.5F, etaTextY, 0xFFFFFFFF, etaScale,
                poseStack, buffers);

        String customNotice = cleanLineText(display.trafficStatus(backSide));
        String stoppingPattern = !customNotice.isBlank()
                && !customNotice.equalsIgnoreCase("On time")
                ? customNotice
                : melbourneStoppingPatternLabel(first.upcomingStops(), first.trainType(),
                        first.trainName(), first.loop(), false);
        float subScale = logicalHeight >= 140 ? 0.78F : 0.60F;
        drawFitted(stoppingPattern, margin, dividerY - font.lineHeight * subScale - 1.8F,
                logicalWidth - margin * 2.0F, subText, subScale, poseStack, buffers);

        // 3. Middle Multi-Column Stopping Pattern Grid (larger text & wraps into 2nd column)
        List<String> stops = new ArrayList<>();
        List<Boolean> skippedFlags = new ArrayList<>();
        for (String stop : first.upcomingStops()) {
            boolean skipped = isSkippedStopEntry(stop);
            String rawStop = stripSkippedPrefix(stop);
            String mapped = conciseStation(
                    TrafficTemplateRepository.mappedStationName(templateId, rawStop));
            if (!mapped.isBlank()) {
                stops.add(mapped);
                skippedFlags.add(skipped);
            }
        }
        if (stops.isEmpty()) {
            String from = conciseStation(TrafficTemplateRepository.mappedStationName(
                    templateId, stationName));
            if (!from.isBlank()) {
                stops.add(from);
                skippedFlags.add(false);
            }
            if (!firstDest.isBlank() && !firstDest.equalsIgnoreCase(from)) {
                stops.add(firstDest);
                skippedFlags.add(false);
            }
        }

        int numCols = logicalWidth >= 300 ? 4 : logicalWidth >= 200 ? 3 : 2;
        if (stops.size() <= 8) numCols = Math.min(numCols, 2);
        float gridTop = dividerY;
        float gridBottom = bottomTop - 2.0F;
        float gridH = Math.max(14.0F, gridBottom - gridTop);

        int maxRowsPerCol;
        float stopScale;
        if (logicalHeight < 120) {
            // 1-block-tall screen (e.g. 1x2 landscape): cap at 4 rows per column with larger text
            // so stations 5..8 wrap onto the 2nd column and stay clearly legible from afar.
            maxRowsPerCol = 4;
            stopScale = stops.size() <= 4 ? 0.78F : 0.72F;
        } else {
            // 2+-block-tall screen (e.g. 1x2 portrait or 2x2): wrap after 6-8 rows
            maxRowsPerCol = stops.size() <= 10 ? 5 : stops.size() <= 14 ? 7 : 8;
            stopScale = stops.size() <= 10 ? 0.92F : 0.82F;
        }
        float stopLineH = font.lineHeight * stopScale;
        float rowStep = Math.min(stopLineH + 2.8F,
                (gridH - 3.5F) / Math.max(1, maxRowsPerCol));
        float colWidth = (logicalWidth - margin * 2.0F) / numCols;
        int totalVisibleStops = Math.min(stops.size(), numCols * maxRowsPerCol);
        int activeCols = Math.max(1, (totalVisibleStops + maxRowsPerCol - 1) / maxRowsPerCol);

        // Pass 3a: Draw vertical column lines, column separator rules, and station ticks
        VertexConsumer gridGeom = buffers.getBuffer(RenderType.textBackground());
        for (int col = 0; col < activeCols; col++) {
            int colStart = col * maxRowsPerCol;
            int colEnd = Math.min(totalVisibleStops, colStart + maxRowsPerCol);
            int countInCol = colEnd - colStart;
            if (countInCol <= 0) continue;
            if (col > 0) {
                float sepX = margin + col * colWidth - 2.2F;
                quad2d(gridGeom, basePose, sepX - 0.35F, gridTop + 2.0F, sepX + 0.35F,
                        gridBottom - 1.0F, bottomRuleColor);
            }
            float lineX = margin + col * colWidth + 2.8F;
            float firstCenterY = gridTop + 2.6F + stopLineH * 0.5F;
            float lastCenterY = gridTop + 2.6F + (countInCol - 1) * rowStep + stopLineH * 0.5F;
            float lineTopY = col == 0 ? gridTop : Math.max(gridTop + 1.5F, firstCenterY - 2.2F);
            float lineBottomY = col < activeCols - 1
                    ? Math.min(gridBottom - 1.5F, lastCenterY + 2.2F)
                    : lastCenterY;
            quad2d(gridGeom, basePose, lineX - 0.65F, lineTopY, lineX + 0.65F, lineBottomY,
                    metroGreen);
            // Continuation dashes at bottom of wrapped column & top of next column
            if (col < activeCols - 1 && lineBottomY + 1.8F < gridBottom) {
                quad2d(gridGeom, basePose, lineX - 0.65F, lineBottomY + 0.8F,
                        lineX + 0.65F, lineBottomY + 1.5F, metroGreen);
            }
            if (col > 0 && lineTopY - 1.5F > gridTop) {
                quad2d(gridGeom, basePose, lineX - 0.65F, lineTopY - 1.5F,
                        lineX + 0.65F, lineTopY - 0.8F, metroGreen);
            }
            int row = 0;
            while (row < countInCol) {
                int stopIdx = colStart + row;
                float stopY = gridTop + 2.6F + row * rowStep;
                float centerY = stopY + stopLineH * 0.5F;
                boolean isFirst = stopIdx == 0;
                boolean isLast = stopIdx == totalVisibleStops - 1;
                boolean isSkipped = stopIdx < skippedFlags.size() && skippedFlags.get(stopIdx)
                        && !isFirst && !isLast;
                if (isSkipped) {
                    int runEnd = row;
                    while (runEnd + 1 < countInCol) {
                        int nextStopIdx = colStart + runEnd + 1;
                        if (nextStopIdx < skippedFlags.size() && skippedFlags.get(nextStopIdx)
                                && nextStopIdx < totalVisibleStops - 1) {
                            runEnd++;
                        } else {
                            break;
                        }
                    }
                    float runStartY = gridTop + 2.6F + row * rowStep + stopLineH * 0.5F;
                    float runEndY = gridTop + 2.6F + runEnd * rowStep + stopLineH * 0.5F;
                    float arrowTipY = (runStartY + runEndY) * 0.5F + 1.2F;
                    // Downward express chevron arrow (v) on the vertical route line
                    line2d(gridGeom, basePose, lineX - 2.2F, arrowTipY - 2.4F,
                            lineX, arrowTipY, 1.15D, metroGreen);
                    line2d(gridGeom, basePose, lineX + 2.2F, arrowTipY - 2.4F,
                            lineX, arrowTipY, 1.15D, metroGreen);
                    row = runEnd + 1;
                    continue;
                }
                if (isFirst || isLast) {
                    fillWithHole(gridGeom, basePose, lineX - 1.6F, centerY - 1.2F,
                            lineX + 1.6F, centerY + 1.2F,
                            lineX - 0.9F, centerY - 0.6F,
                            lineX + 0.9F, centerY + 0.6F, darkText);
                    quad2d(gridGeom, basePose, lineX - 0.9F, centerY - 0.6F,
                            lineX + 0.9F, centerY + 0.6F, 0xFFFFFFFF);
                } else {
                    quad2d(gridGeom, basePose, lineX, centerY - 0.5F,
                            lineX + 2.0F, centerY + 0.5F, metroGreen);
                }
                row++;
            }
        }

        // First stop accent highlight pill on elevated Z layer
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.15D);
        VertexConsumer overlayGeom = buffers.getBuffer(RenderType.textBackground());
        Matrix4f overlayPose = poseStack.last().pose();
        if (totalVisibleStops > 0) {
            float firstLineX = margin + 2.8F;
            float firstStopY = gridTop + 2.6F;
            float pillW = Math.min(colWidth - 7.0F,
                    font.width(stops.getFirst()) * stopScale + 4.8F);
            quad2d(overlayGeom, overlayPose, firstLineX + 2.4F, firstStopY - 0.7F,
                    firstLineX + 2.4F + pillW, firstStopY + stopLineH + 0.7F, metroGreen);
        }
        poseStack.popPose();

        // Pass 3b: Draw station names in columns (stopping in dark text, skipped in light grey)
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.30D);
        for (int col = 0; col < activeCols; col++) {
            int colStart = col * maxRowsPerCol;
            int colEnd = Math.min(totalVisibleStops, colStart + maxRowsPerCol);
            float lineX = margin + col * colWidth + 2.8F;
            float textMaxW = Math.max(1.0F, colWidth - 8.5F);
            for (int row = 0; row < colEnd - colStart; row++) {
                int stopIdx = colStart + row;
                float stopY = gridTop + 2.6F + row * rowStep;
                String name = stops.get(stopIdx);
                boolean isSkipped = stopIdx > 0 && stopIdx < totalVisibleStops - 1
                        && stopIdx < skippedFlags.size() && skippedFlags.get(stopIdx);
                int nameColor = stopIdx == 0 ? 0xFFFFFFFF : isSkipped ? 0xFF8C97A3 : darkText;
                drawFitted(name, lineX + 4.0F, stopY, textMaxW, nameColor, stopScale,
                        poseStack, buffers);
            }
        }
        poseStack.popPose();

        // 4. Bottom Following Departures List (Left of Clock Box)
        float bottomLeftW = Math.max(20.0F, clockX - margin - 4.0F);
        float bottomAreaH = Math.max(10.0F, logicalHeight - bottomTop - 1.5F);
        int extraDepartures = Math.max(0, Math.min(3, visibleDepartures.size() - 1));
        int slotRows = Math.max(2, extraDepartures);
        float bottomRowH = bottomAreaH / slotRows;
        float rowBadgeW = Math.max(20.0F, Math.min(42.0F, bottomLeftW * 0.20F));
        float rowBadgeX = margin + bottomLeftW - rowBadgeW;

        VertexConsumer bottomGeom = buffers.getBuffer(RenderType.textBackground());
        int barRows = Math.max(1, extraDepartures);
        for (int idx = 0; idx < barRows; idx++) {
            float rY = bottomTop + 1.0F + idx * bottomRowH;
            float rH = Math.max(3.5F, bottomRowH - 1.2F);
            quad2d(bottomGeom, basePose, margin, rY + 0.4F, margin + 1.5F, rY + rH - 0.4F,
                    metroGreen);
            if (idx + 1 < barRows) {
                quad2d(bottomGeom, basePose, margin, rY + bottomRowH - 0.5F,
                        margin + bottomLeftW, rY + bottomRowH, bottomRuleColor);
            }
            if (visibleDepartures.size() > idx + 1) {
                quad2d(bottomGeom, basePose, rowBadgeX, rY + 0.4F,
                        rowBadgeX + rowBadgeW, rY + rH - 0.4F, badgeBg);
            }
        }

        float bottomTextScale = slotRows >= 3
                ? (logicalHeight >= 140 ? 0.60F : 0.44F)
                : (logicalHeight >= 140 ? 0.72F : 0.54F);
        if (visibleDepartures.size() > 1) {
            for (int idx = 1; idx < Math.min(4, visibleDepartures.size()); idx++) {
                CreateTrainScheduleService.Departure dep = visibleDepartures.get(idx);
                float rY = bottomTop + 1.0F + (idx - 1) * bottomRowH;
                float rH = Math.max(3.5F, bottomRowH - 1.2F);
                float textY = rY + (rH - font.lineHeight * bottomTextScale) * 0.5F;
                String depTime = worldClock(display.getLevel(),
                        dep.dwelling() ? 0L : dep.etaTicks());
                String depDest = dep.destination().isBlank() ? stationName : dep.destination();
                depDest = conciseStation(TrafficTemplateRepository.mappedStationName(
                        templateId, depDest));
                String depTrain = dep.trainName() == null ? "" : dep.trainName().trim();
                boolean hasDepTrainName = !depTrain.isBlank()
                        && !depTrain.equalsIgnoreCase("Unnamed Train")
                        && !depTrain.equals("未命名列车");
                String depTitle = hasCustomScreenLine ? configuredLine
                        : useDestMode ? depDest
                        : hasDepTrainName ? depTrain : depDest;
                String depPattern = melbourneStoppingPatternLabel(dep.upcomingStops(),
                        dep.trainType(), dep.trainName(), dep.loop(), true);
                String depLine = depTime + "  " + depTitle + "   " + depPattern;
                drawFitted(depLine, margin + 3.5F, textY,
                        Math.max(1.0F, rowBadgeX - margin - 6.0F), darkText, bottomTextScale,
                        poseStack, buffers);
                String depEta = melbourneEtaText(dep);
                drawFitted(depEta, rowBadgeX + 2.5F, textY, rowBadgeW - 5.0F, 0xFFFFFFFF,
                        bottomTextScale, poseStack, buffers);
            }
        } else {
            float rY = bottomTop + 1.0F;
            float rH = Math.max(3.5F, bottomRowH - 1.2F);
            float textY = rY + (rH - font.lineHeight * bottomTextScale) * 0.5F;
            String serviceInfo = hasCustomScreenLine ? configuredLine
                    : useDestMode ? firstDest
                    : hasCustomTrainName ? customTrainName : firstDest;
            if (first.carriageCount() > 0) {
                serviceInfo += "  ·  " + first.carriageCount() + " Cars";
            }
            String firstPattern = melbourneStoppingPatternLabel(first.upcomingStops(),
                    first.trainType(), first.trainName(), first.loop(), false);
            drawFitted(serviceInfo + "   " + firstPattern, margin + 3.5F, textY,
                    bottomLeftW - 6.0F, darkText, bottomTextScale, poseStack, buffers);
        }
    }

    private record ConcourseDepartureItem(String platformLabel, int platformColor,
            String platformStationName, CreateTrainScheduleService.Departure departure) {
    }

    private static final java.util.regex.Pattern INLINE_HEX_COLOR =
            java.util.regex.Pattern.compile("(?i)#([0-9a-f]{6,8})\\b");

    public static String cleanLineText(String value) {
        if (value == null || value.isBlank()) return "";
        String withoutColors = INLINE_HEX_COLOR.matcher(value).replaceAll("");
        String[] parts = withoutColors.split("[,;/]+");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) nonEmpty.add(trimmed);
        }
        return String.join(", ", nonEmpty);
    }

    private static int melbournePlatformColor(TextDisplayBlockEntity display, boolean backSide,
            int platformIndex, int defaultPrimary) {
        // 1. Check comma-separated per-platform entries in Tab 1/2 "线路" (Line) or "状态" (Status)
        for (String fieldVal : new String[] {
                display.trafficLine(backSide), display.trafficStatus(backSide) }) {
            if (fieldVal == null || fieldVal.isBlank()) continue;
            String[] parts = fieldVal.split("[,;/]+");
            String token = platformIndex < parts.length ? parts[platformIndex].trim()
                    : parts.length == 1 ? parts[0].trim() : "";
            if (!token.isEmpty()) {
                java.util.regex.Matcher m = INLINE_HEX_COLOR.matcher(token);
                if (m.find()) {
                    String hex = m.group(1);
                    if (hex.length() == 8) hex = hex.substring(2);
                    try {
                        return 0xFF000000 | Integer.parseInt(hex, 16);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        // 2. Check Tab 3 "文字颜色" (for Platform 1) and "背景颜色" (for Platform 2)
        int configuredText = display.textColor(backSide);
        int configuredBg = display.backgroundColor(backSide);
        if (platformIndex == 0 && (configuredText & 0x00FFFFFF) != 0x00FFFFFF) {
            return 0xFF000000 | (configuredText & 0x00FFFFFF);
        }
        if (platformIndex == 1 && (configuredBg & 0x00FFFFFF) != 0x00101820
                && (configuredBg & 0x00FFFFFF) != 0x00F3F5F7) {
            return 0xFF000000 | (configuredBg & 0x00FFFFFF);
        }
        return melbournePlatformColor(platformIndex, configuredText, defaultPrimary);
    }

    private static String melbournePlatformCustomTitle(TextDisplayBlockEntity display,
            boolean backSide, int platformIndex) {
        String lineField = display.trafficLine(backSide);
        if (lineField == null || lineField.isBlank()
                || lineField.trim().equalsIgnoreCase("Line 1")) {
            return "";
        }
        String[] parts = lineField.split("[,;/]+");
        String token = platformIndex < parts.length ? parts[platformIndex].trim()
                : parts.length == 1 ? parts[0].trim() : "";
        String cleaned = INLINE_HEX_COLOR.matcher(token).replaceAll("").trim();
        return cleaned;
    }

    private static int melbournePlatformColor(int platformIndex, int configuredColor,
            int defaultPrimary) {
        if (platformIndex == 0 && (configuredColor & 0x00FFFFFF) != 0x00FFFFFF) {
            return 0xFF000000 | (configuredColor & 0x00FFFFFF);
        }
        return switch (platformIndex % 6) {
            case 0 -> (configuredColor & 0x00FFFFFF) != 0x00FFFFFF
                    ? (0xFF000000 | (configuredColor & 0x00FFFFFF)) : defaultPrimary;
            case 1 -> 0xFF152C6B; // Burnley / Glen Waverley Dark Blue
            case 2 -> 0xFFFFB81C; // Sunbury / Craigieburn Yellow
            case 3 -> 0xFF008542; // Frankston Green
            case 4 -> 0xFFD22630; // Clifton Hill / Mernda Red
            default -> 0xFFF178AF; // Sandringham Pink
        };
    }

    /**
     * Melbourne Concourse Style 1 ("melbourne_concourse"):
     * Main-hall vertical/landscape departure cards showing Time, Destination, Stopping Pattern,
     * and a large Colored Platform Number Square + Dark ETA pill on the right of each card.
     */
    private void renderMelbourneConcourseCards(TextDisplayBlockEntity display, boolean backSide,
            int logicalWidth, int logicalHeight, PoseStack poseStack,
            MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        List<CreateTrainScheduleService.PlatformSnapshot> platforms =
                CreateTrainScheduleService.concoursePlatforms(display.getLevel(),
                        display.getBlockPos(), templateId,
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                templateId, display.stationTurnaroundName(backSide)),
                        false, 4);

        int primaryAccent = melbournePlatformColor(display, backSide, 0, 0xFF279FD5);
        final int darkText = 0xFF101B24;
        final int subText = 0xFF36424E;
        final int badgeBg = 0xFF232B33;
        final int ruleColor = 0xFFB8C2CC;

        float margin = Math.max(4.0F, logicalWidth * 0.035F);
        float topBarH = Math.max(2.5F, logicalHeight * 0.038F);
        float headerBottomY = Math.max(14.0F, logicalHeight * 0.15F);
        float footerTopY = Math.min(logicalHeight - 13.0F, logicalHeight * 0.82F);

        List<ConcourseDepartureItem> allItems = new ArrayList<>();
        for (int pIdx = 0; pIdx < platforms.size(); pIdx++) {
            CreateTrainScheduleService.PlatformSnapshot plat = platforms.get(pIdx);
            int pColor = platforms.size() == 1 ? primaryAccent
                    : melbournePlatformColor(display, backSide, pIdx, 0xFF279FD5);
            for (CreateTrainScheduleService.Departure dep : plat.snapshot().departures()) {
                if (dep.dwelling() || dep.etaKnown() || dep.etaTicks() >= 0L) {
                    allItems.add(new ConcourseDepartureItem(plat.platformLabel(), pColor,
                            plat.stationName(), dep));
                }
            }
        }
        allItems.sort(java.util.Comparator.comparingLong(
                item -> item.departure().dwelling() ? -1L : item.departure().etaTicks()));

        String customHeaderTitle = melbournePlatformCustomTitle(display, backSide, 0);
        String baseTitle = !customHeaderTitle.isBlank() ? customHeaderTitle
                : !platforms.isEmpty() ? platforms.getFirst().baseStationName()
                : display.trafficCurrentStop(backSide);
        if (baseTitle == null || baseTitle.isBlank()) baseTitle = "Concourse Departures";

        // 1. Top bar, header divider, footer divider, and live clock box
        VertexConsumer chrome = buffers.getBuffer(RenderType.textBackground());
        Matrix4f basePose = poseStack.last().pose();
        quad2d(chrome, basePose, 0, 0, logicalWidth, topBarH, primaryAccent);
        quad2d(chrome, basePose, margin, headerBottomY - 0.8F, logicalWidth - margin,
                headerBottomY, 0xFF5A6774);
        quad2d(chrome, basePose, margin, footerTopY - 0.8F, logicalWidth - margin,
                footerTopY, ruleColor);

        float headerScale = Math.min(logicalHeight >= 140 ? 0.92F : 0.68F,
                (headerBottomY - topBarH - 2.5F) / font.lineHeight);
        drawFitted(baseTitle, margin,
                topBarH + (headerBottomY - topBarH - font.lineHeight * headerScale) * 0.5F,
                logicalWidth - margin * 2.0F, darkText, headerScale, poseStack, buffers);

        // Bottom clock box
        float clockW = Math.max(34.0F, Math.min(72.0F, logicalWidth * 0.32F));
        float clockH = Math.max(9.0F, logicalHeight - footerTopY - 3.0F);
        float clockX = logicalWidth - margin - clockW;
        float clockY = footerTopY + 1.5F;
        VertexConsumer clockGeom = buffers.getBuffer(RenderType.textBackground());
        fillWithHole(clockGeom, basePose, clockX, clockY, clockX + clockW, clockY + clockH,
                clockX + 0.7F, clockY + 0.7F, clockX + clockW - 0.7F, clockY + clockH - 0.7F,
                0xFF8C97A3);
        quad2d(clockGeom, basePose,
                clockX + 0.7F, clockY + 0.7F, clockX + clockW - 0.7F, clockY + clockH - 0.7F,
                0xFFEBEEF2);
        String nowClock = worldClock(display.getLevel(), 0L);
        float clockScale = Math.min(logicalHeight >= 140 ? 0.90F : 0.66F,
                (clockW - 5.0F) / Math.max(1.0F, font.width(nowClock)));
        drawScaled(Component.literal(nowClock).getVisualOrderText(),
                clockX + (clockW - font.width(nowClock) * clockScale) * 0.5F,
                clockY + (clockH - font.lineHeight * clockScale) * 0.5F,
                darkText, clockScale, poseStack, buffers);

        if (allItems.isEmpty()) {
            drawFitted("No scheduled departures in window", margin, headerBottomY + 8.0F,
                    logicalWidth - margin * 2.0F, subText,
                    logicalHeight >= 140 ? 0.85F : 0.65F, poseStack, buffers);
            return;
        }

        // 2. Main Departure Cards (up to 3 cards in middle section)
        int maxCards = logicalHeight >= 130 ? 3 : 2;
        int cardCount = Math.min(maxCards, allItems.size());
        float cardsAreaH = Math.max(20.0F, footerTopY - headerBottomY);
        float cardH = cardsAreaH / maxCards;

        for (int idx = 0; idx < cardCount; idx++) {
            ConcourseDepartureItem item = allItems.get(idx);
            CreateTrainScheduleService.Departure dep = item.departure();
            float cTop = headerBottomY + idx * cardH;
            float cBottom = cTop + cardH;

            VertexConsumer cardGeom = buffers.getBuffer(RenderType.textBackground());
            if (idx > 0) {
                quad2d(cardGeom, basePose, margin, cTop - 0.6F, logicalWidth - margin, cTop,
                        ruleColor);
            }

            // Right-side Platform Square + Dark ETA pill underneath
            float rightColW = Math.max(22.0F, Math.min(44.0F, logicalWidth * 0.22F));
            float rightX = logicalWidth - margin - rightColW;
            float etaPillH = Math.max(5.8F, cardH * 0.27F);
            float platBoxH = Math.max(8.5F, Math.min(rightColW, cardH - etaPillH - 3.5F));
            float platBoxW = Math.min(rightColW, platBoxH * 1.15F);
            float platBoxX = rightX + (rightColW - platBoxW) * 0.5F;
            float platBoxY = cTop + 1.8F;
            float etaPillY = platBoxY + platBoxH + 1.0F;

            quad2d(cardGeom, basePose, platBoxX, platBoxY, platBoxX + platBoxW,
                    platBoxY + platBoxH, item.platformColor());
            quad2d(cardGeom, basePose, rightX, etaPillY, rightX + rightColW,
                    etaPillY + etaPillH, badgeBg);

            // Platform number text
            float pScale = Math.min(logicalHeight >= 140 ? 1.10F : 0.78F,
                    (platBoxH - 2.0F) / font.lineHeight);
            float pTextW = font.width(item.platformLabel()) * pScale;
            drawScaled(Component.literal(item.platformLabel()).getVisualOrderText(),
                    platBoxX + (platBoxW - pTextW) * 0.5F,
                    platBoxY + (platBoxH - font.lineHeight * pScale) * 0.5F,
                    0xFFFFFFFF, pScale, poseStack, buffers);

            // ETA text
            String etaStr = melbourneEtaText(dep);
            float eScale = Math.min(logicalHeight >= 140 ? 0.68F : 0.48F,
                    (rightColW - 3.0F) / Math.max(1.0F, font.width(etaStr)));
            float eTextW = font.width(etaStr) * eScale;
            drawScaled(Component.literal(etaStr).getVisualOrderText(),
                    rightX + (rightColW - eTextW) * 0.5F,
                    etaPillY + (etaPillH - font.lineHeight * eScale) * 0.5F,
                    0xFFFFFFFF, eScale, poseStack, buffers);

            // Left-side Time, Destination, and Stopping Pattern
            float textMaxW = Math.max(10.0F, rightX - margin - 5.0F);
            String depClock = worldClock(display.getLevel(),
                    dep.dwelling() ? 0L : dep.etaTicks());
            String dest = dep.destination().isBlank() ? item.platformStationName()
                    : dep.destination();
            dest = conciseStation(TrafficTemplateRepository.mappedStationName(templateId, dest));
            float timeScale = logicalHeight >= 140 ? 0.72F : 0.52F;
            float destScale = logicalHeight >= 140 ? 1.05F : 0.76F;
            float patScale = logicalHeight >= 140 ? 0.58F : 0.44F;

            drawFitted(depClock, margin, cTop + 1.8F, textMaxW, darkText, timeScale,
                    poseStack, buffers);
            float destY = cTop + 2.0F + font.lineHeight * timeScale + 0.8F;
            drawFitted(dest, margin, destY, textMaxW, darkText, destScale,
                    poseStack, buffers);
            float patY = destY + font.lineHeight * destScale + 0.8F;
            if (patY + font.lineHeight * patScale <= cBottom) {
                String pattern = melbourneStoppingPatternLabel(dep.upcomingStops(),
                        dep.trainType(), dep.trainName(), dep.loop(), false);
                drawFitted(pattern, margin, patY, textMaxW, subText, patScale,
                        poseStack, buffers);
            }
        }

        // 3. Footer summary left of clock
        float footerLeftW = Math.max(12.0F, clockX - margin - 4.0F);
        float fScale = logicalHeight >= 140 ? 0.62F : 0.48F;
        if (allItems.size() > cardCount) {
            ConcourseDepartureItem nextItem = allItems.get(cardCount);
            String fClock = worldClock(display.getLevel(),
                    nextItem.departure().dwelling() ? 0L : nextItem.departure().etaTicks());
            String fDest = conciseStation(TrafficTemplateRepository.mappedStationName(
                    templateId, nextItem.departure().destination()));
            String fLine = fClock + " " + fDest + " (Plat " + nextItem.platformLabel() + ")  "
                    + melbourneEtaText(nextItem.departure());
            drawFitted(fLine, margin,
                    clockY + (clockH - font.lineHeight * fScale) * 0.5F,
                    footerLeftW, darkText, fScale, poseStack, buffers);
        } else {
            String summary = platforms.size() + (platforms.size() == 1 ? " Platform" : " Platforms")
                    + " Active";
            drawFitted(summary, margin,
                    clockY + (clockH - font.lineHeight * fScale) * 0.5F,
                    footerLeftW, subText, fScale, poseStack, buffers);
        }
    }

    /**
     * Melbourne Concourse Style 2 ("melbourne_concourse_split"):
     * Splits the display into one section per platform (like the right-side screens in Melbourne
     * Central concourse), showing each platform's line bar, next departure, large colored Platform
     * Number square + dark ETA pill, and following departures for that platform.
     */
    private void renderMelbourneConcourseSplit(TextDisplayBlockEntity display, boolean backSide,
            int logicalWidth, int logicalHeight, PoseStack poseStack,
            MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        List<CreateTrainScheduleService.PlatformSnapshot> platforms =
                CreateTrainScheduleService.concoursePlatforms(display.getLevel(),
                        display.getBlockPos(), templateId,
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                templateId, display.stationTurnaroundName(backSide)),
                        true, 4);

        int configuredColor = display.textColor(backSide);
        final int darkText = 0xFF101B24;
        final int subText = 0xFF36424E;
        final int badgeBg = 0xFF232B33;
        final int ruleColor = 0xFFB8C2CC;

        if (platforms.isEmpty()) {
            drawFitted(Component.translatable("screen.minescreen.station.not_found").getString(),
                    6.0F, 8.0F, logicalWidth - 12.0F, darkText, 0.9F, poseStack, buffers);
            return;
        }

        int count = Math.min(4, platforms.size());
        boolean twoByTwo = count > 2 && logicalWidth >= 180;
        int cols = twoByTwo ? 2 : 1;
        int rows = (count + cols - 1) / cols;
        float cellW = (float) logicalWidth / cols;
        float cellH = (float) logicalHeight / rows;

        for (int idx = 0; idx < count; idx++) {
            CreateTrainScheduleService.PlatformSnapshot plat = platforms.get(idx);
            int col = idx % cols;
            int row = idx / cols;
            float x0 = col * cellW;
            float y0 = row * cellH;
            float pad = Math.max(3.5F, cellW * 0.04F);
            int accent = melbournePlatformColor(display, backSide, idx, 0xFFD22630);

            List<CreateTrainScheduleService.Departure> deps = plat.snapshot().departures().stream()
                    .filter(d -> d.dwelling() || d.etaKnown() || d.etaTicks() >= 0L)
                    .limit(4).toList();

            float topBarH = Math.max(2.2F, cellH * 0.065F);
            float groupHeaderH = Math.max(7.5F, cellH * 0.18F);
            float mainBottomY = y0 + cellH * 0.68F;

            VertexConsumer geom = buffers.getBuffer(RenderType.textBackground());
            Matrix4f pose = poseStack.last().pose();
            quad2d(geom, pose, x0, y0, x0 + cellW, y0 + topBarH, accent);
            if (col > 0) {
                quad2d(geom, pose, x0 - 0.6F, y0, x0 + 0.6F, y0 + cellH, 0xFF5A6774);
            }
            if (row > 0) {
                quad2d(geom, pose, x0, y0 - 0.8F, x0 + cellW, y0 + 0.8F, 0xFF36424E);
            }
            quad2d(geom, pose, x0 + pad, y0 + topBarH + groupHeaderH - 0.6F,
                    x0 + cellW - pad, y0 + topBarH + groupHeaderH, ruleColor);
            quad2d(geom, pose, x0 + pad, mainBottomY - 0.6F,
                    x0 + cellW - pad, mainBottomY, ruleColor);

            // Section header: Line / Platform title
            String firstDest = deps.isEmpty() ? ""
                    : conciseStation(TrafficTemplateRepository.mappedStationName(
                            templateId, deps.getFirst().destination()));
            String customPlatTitle = melbournePlatformCustomTitle(display, backSide, idx);
            String titleLead = !customPlatTitle.isBlank() ? customPlatTitle
                    : !firstDest.isBlank() ? firstDest : conciseStation(plat.stationName());
            String sectionTitle = titleLead + "  ·  Platform " + plat.platformLabel();
            float hdrScale = Math.min(cellH >= 75 ? 0.72F : 0.52F,
                    (groupHeaderH - 2.0F) / font.lineHeight);
            drawFitted(sectionTitle, x0 + pad,
                    y0 + topBarH + (groupHeaderH - font.lineHeight * hdrScale) * 0.5F,
                    cellW - pad * 2.0F, darkText, hdrScale, poseStack, buffers);

            // Right-side Platform Number Square + Dark ETA pill
            float mainTopY = y0 + topBarH + groupHeaderH + 1.2F;
            float mainH = Math.max(12.0F, mainBottomY - mainTopY - 1.2F);
            float rightW = Math.max(20.0F, Math.min(40.0F, cellW * 0.22F));
            float rightX = x0 + cellW - pad - rightW;
            float etaH = Math.max(5.2F, mainH * 0.28F);
            float pBoxH = Math.max(7.5F, Math.min(rightW, mainH - etaH - 2.0F));
            float pBoxW = Math.min(rightW, pBoxH * 1.15F);
            float pBoxX = rightX + (rightW - pBoxW) * 0.5F;
            float pBoxY = mainTopY + 0.5F;
            float etaY = pBoxY + pBoxH + 0.8F;

            VertexConsumer boxGeom = buffers.getBuffer(RenderType.textBackground());
            quad2d(boxGeom, pose, pBoxX, pBoxY, pBoxX + pBoxW, pBoxY + pBoxH, accent);
            quad2d(boxGeom, pose, rightX, etaY, rightX + rightW, etaY + etaH, badgeBg);

            float pScale = Math.min(cellH >= 75 ? 0.96F : 0.68F,
                    (pBoxH - 1.5F) / font.lineHeight);
            drawScaled(Component.literal(plat.platformLabel()).getVisualOrderText(),
                    pBoxX + (pBoxW - font.width(plat.platformLabel()) * pScale) * 0.5F,
                    pBoxY + (pBoxH - font.lineHeight * pScale) * 0.5F,
                    0xFFFFFFFF, pScale, poseStack, buffers);

            String etaStr = deps.isEmpty() ? "-- min" : melbourneEtaText(deps.getFirst());
            float eScale = Math.min(cellH >= 75 ? 0.62F : 0.45F,
                    (rightW - 2.5F) / Math.max(1.0F, font.width(etaStr)));
            drawScaled(Component.literal(etaStr).getVisualOrderText(),
                    rightX + (rightW - font.width(etaStr) * eScale) * 0.5F,
                    etaY + (etaH - font.lineHeight * eScale) * 0.5F,
                    0xFFFFFFFF, eScale, poseStack, buffers);

            float leftMaxW = Math.max(10.0F, rightX - (x0 + pad) - 4.0F);
            if (deps.isEmpty()) {
                drawFitted("No departures", x0 + pad, mainTopY + 2.0F, leftMaxW,
                        subText, cellH >= 75 ? 0.68F : 0.50F, poseStack, buffers);
            } else {
                CreateTrainScheduleService.Departure first = deps.getFirst();
                String clock = worldClock(display.getLevel(),
                        first.dwelling() ? 0L : first.etaTicks());
                String dest = firstDest.isBlank() ? plat.stationName() : firstDest;
                float tScale = cellH >= 75 ? 0.64F : 0.48F;
                float dScale = cellH >= 75 ? 0.96F : 0.68F;
                drawFitted(clock, x0 + pad, mainTopY + 0.5F, leftMaxW, darkText, tScale,
                        poseStack, buffers);
                float dY = mainTopY + 1.0F + font.lineHeight * tScale;
                drawFitted(dest, x0 + pad, dY, leftMaxW, darkText, dScale,
                        poseStack, buffers);
                float patY = dY + font.lineHeight * dScale + 0.5F;
                float patScale = cellH >= 75 ? 0.52F : 0.40F;
                if (patY + font.lineHeight * patScale <= mainBottomY) {
                    String patLabel = melbourneStoppingPatternLabel(first.upcomingStops(),
                            first.trainType(), first.trainName(), first.loop(), true);
                    drawFitted(patLabel, x0 + pad, patY, leftMaxW, subText, patScale,
                            poseStack, buffers);
                }
            }

            // Sub-rows at bottom of platform cell for 2nd, 3rd (& 4th) trains
            float subAreaH = Math.max(6.0F, (y0 + cellH) - mainBottomY - 1.0F);
            int subCount = Math.max(0, Math.min(2, deps.size() - 1));
            float subRowH = subAreaH * 0.5F;
            float subScale = cellH >= 75 ? 0.54F : 0.42F;
            for (int sIdx = 1; sIdx <= subCount; sIdx++) {
                CreateTrainScheduleService.Departure subDep = deps.get(sIdx);
                float sY = mainBottomY + 0.8F + (sIdx - 1) * subRowH;
                float sH = Math.max(3.2F, subRowH - 0.8F);
                VertexConsumer subGeom = buffers.getBuffer(RenderType.textBackground());
                if (sIdx > 1) {
                    quad2d(subGeom, pose, x0 + pad, sY - 0.4F, x0 + cellW - pad, sY, ruleColor);
                }
                // Mini colored bar on left + mini platform square + dark ETA pill on right
                quad2d(subGeom, pose, x0 + pad, sY + 0.3F, x0 + pad + 1.2F, sY + sH - 0.3F, accent);
                float miniEtaW = Math.max(16.0F, rightW * 0.78F);
                float miniEtaX = x0 + cellW - pad - miniEtaW;
                float miniPlatW = Math.max(5.5F, sH);
                float miniPlatX = miniEtaX - miniPlatW - 1.2F;
                quad2d(subGeom, pose, miniPlatX, sY + 0.3F, miniPlatX + miniPlatW,
                        sY + sH - 0.3F, accent);
                quad2d(subGeom, pose, miniEtaX, sY + 0.3F, miniEtaX + miniEtaW,
                        sY + sH - 0.3F, badgeBg);

                float mpScale = Math.min(subScale, (sH - 1.0F) / font.lineHeight);
                drawScaled(Component.literal(plat.platformLabel()).getVisualOrderText(),
                        miniPlatX + (miniPlatW - font.width(plat.platformLabel()) * mpScale) * 0.5F,
                        sY + (sH - font.lineHeight * mpScale) * 0.5F,
                        0xFFFFFFFF, mpScale, poseStack, buffers);

                String sClock = worldClock(display.getLevel(),
                        subDep.dwelling() ? 0L : subDep.etaTicks());
                String sDest = conciseStation(TrafficTemplateRepository.mappedStationName(
                        templateId, subDep.destination()));
                String sEta = melbourneEtaText(subDep);
                drawFitted(sClock + "  " + sDest, x0 + pad + 2.6F,
                        sY + (sH - font.lineHeight * subScale) * 0.5F,
                        Math.max(8.0F, miniPlatX - (x0 + pad) - 4.5F), darkText, subScale,
                        poseStack, buffers);
                float seScale = Math.min(subScale * 0.92F,
                        (miniEtaW - 2.0F) / Math.max(1.0F, font.width(sEta)));
                drawScaled(Component.literal(sEta).getVisualOrderText(),
                        miniEtaX + (miniEtaW - font.width(sEta) * seScale) * 0.5F,
                        sY + (sH - font.lineHeight * seScale) * 0.5F,
                        0xFFFFFFFF, seScale, poseStack, buffers);
            }
        }
    }

    /**
     * Melbourne Concourse Style 3 ("melbourne_wayfinding"):
     * Station & Platform Directory Poster (like the center blue "Melbourne Central Station /
     * Platforms 1-4" screens in the main hall photo).
     */
    private void renderMelbourneWayfinding(TextDisplayBlockEntity display, boolean backSide,
            int logicalWidth, int logicalHeight, PoseStack poseStack,
            MultiBufferSource buffers) {
        String templateId = display.templateId(backSide);
        List<CreateTrainScheduleService.PlatformSnapshot> platforms =
                CreateTrainScheduleService.concoursePlatforms(display.getLevel(),
                        display.getBlockPos(), templateId,
                        display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                        display.stationTrainType(backSide),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                templateId, display.stationTurnaroundName(backSide)),
                        true, 2);

        int configuredColor = display.textColor(backSide);
        final int bannerBlue = (configuredColor & 0x00FFFFFF) != 0x00FFFFFF
                ? (0xFF000000 | (configuredColor & 0x00FFFFFF))
                : 0xFF0072CE;
        final int darkText = 0xFF101B24;
        final int subText = 0xFF36424E;
        final int ruleColor = 0xFFB8C2CC;

        float margin = Math.max(5.0F, logicalWidth * 0.045F);
        float bannerH = logicalHeight * 0.45F;

        // 1. Top Blue Banner + Non-overlapping Train Pictogram Icon (prevents depth Z-fighting)
        VertexConsumer chrome = buffers.getBuffer(RenderType.textBackground());
        Matrix4f basePose = poseStack.last().pose();
        float iconSize = Math.max(11.0F, Math.min(24.0F, bannerH * 0.36F));
        float iconX = (logicalWidth - iconSize) * 0.5F;
        float iconY = Math.max(2.5F, bannerH * 0.08F);

        fillWithHole(chrome, basePose, 0, 0, logicalWidth, bannerH,
                iconX, iconY, iconX + iconSize, iconY + iconSize, bannerBlue);
        drawMelbourneTrainIcon(chrome, basePose, iconX, iconY, iconSize, bannerBlue, 0xFFFFFFFF);

        // Station Name in Banner (never render raw hex color codes in the station banner)
        String customDest = cleanLineText(display.trafficDestination(backSide));
        boolean hasCustomStationBanner = !customDest.isBlank()
                && !customDest.equalsIgnoreCase("Destination")
                && !customDest.equalsIgnoreCase("终点")
                && !customDest.equalsIgnoreCase("目的地");
        String stationTitle = hasCustomStationBanner ? customDest
                : !platforms.isEmpty() ? platforms.getFirst().baseStationName() + " Station"
                : !display.trafficCurrentStop(backSide).isBlank()
                        ? display.trafficCurrentStop(backSide) + " Station"
                        : "Station Concourse";
        float titleScale = logicalHeight >= 140 ? 1.18F : 0.86F;
        float titleY = iconY + iconSize + Math.max(2.5F, (bannerH - (iconY + iconSize)
                - font.lineHeight * titleScale) * 0.45F);
        float titleW = Math.min(logicalWidth - margin * 2.0F, font.width(stationTitle) * titleScale);
        drawFitted(stationTitle, (logicalWidth - titleW) * 0.5F, titleY,
                logicalWidth - margin * 2.0F, 0xFFFFFFFF, titleScale, poseStack, buffers);

        // 2. Bottom White Platform Directory
        String platRange = platforms.isEmpty() ? "Platforms"
                : platforms.size() == 1 ? "Platform " + platforms.getFirst().platformLabel() + "  →"
                : "Platforms " + platforms.getFirst().platformLabel() + "–"
                        + platforms.getLast().platformLabel() + "  →";
        float dirHeaderY = bannerH + 3.0F;
        float dirScale = logicalHeight >= 140 ? 0.88F : 0.66F;
        drawFitted(platRange, margin, dirHeaderY, logicalWidth - margin * 2.0F,
                darkText, dirScale, poseStack, buffers);

        float listTopY = dirHeaderY + font.lineHeight * dirScale + 3.0F;
        quad2d(buffers.getBuffer(RenderType.textBackground()), basePose,
                margin, listTopY - 1.0F, logicalWidth - margin, listTopY - 0.2F, 0xFF5A6774);

        if (platforms.isEmpty()) {
            drawFitted("No nearby platforms found", margin, listTopY + 4.0F,
                    logicalWidth - margin * 2.0F, subText, 0.65F, poseStack, buffers);
            return;
        }

        int visiblePlats = Math.min(4, platforms.size());
        float listH = Math.max(12.0F, logicalHeight - listTopY - 2.0F);
        float rowH = listH / visiblePlats;
        float rowScale = logicalHeight >= 140 ? 0.74F : 0.56F;

        for (int idx = 0; idx < visiblePlats; idx++) {
            CreateTrainScheduleService.PlatformSnapshot plat = platforms.get(idx);
            float rY = listTopY + idx * rowH;
            int pColor = melbournePlatformColor(display, backSide, idx, 0xFF008542);

            VertexConsumer rowGeom = buffers.getBuffer(RenderType.textBackground());
            if (idx > 0) {
                quad2d(rowGeom, basePose, margin, rY - 0.5F, logicalWidth - margin, rY, ruleColor);
            }
            // Colored platform badge on left + vertical accent bar on right
            float badgeS = Math.max(7.0F, Math.min(14.0F, rowH - 2.4F));
            float badgeY = rY + (rowH - badgeS) * 0.5F;
            quad2d(rowGeom, basePose, margin, badgeY, margin + badgeS, badgeY + badgeS, pColor);
            quad2d(rowGeom, basePose, logicalWidth - margin - 2.0F, badgeY,
                    logicalWidth - margin, badgeY + badgeS, pColor);

            float pNumScale = Math.min(rowScale, (badgeS - 1.5F) / font.lineHeight);
            drawScaled(Component.literal(plat.platformLabel()).getVisualOrderText(),
                    margin + (badgeS - font.width(plat.platformLabel()) * pNumScale) * 0.5F,
                    badgeY + (badgeS - font.lineHeight * pNumScale) * 0.5F,
                    0xFFFFFFFF, pNumScale, poseStack, buffers);

            List<CreateTrainScheduleService.Departure> deps = plat.snapshot().departures();
            String customPlatLine = melbournePlatformCustomTitle(display, backSide, idx);
            String lineInfo;
            if (!deps.isEmpty()) {
                CreateTrainScheduleService.Departure first = deps.getFirst();
                String dest = conciseStation(TrafficTemplateRepository.mappedStationName(
                        templateId, first.destination()));
                lineInfo = !customPlatLine.isBlank()
                        ? customPlatLine + "  ·  To " + dest + " (" + melbourneEtaText(first) + ")"
                        : "To " + dest + "  (" + melbourneEtaText(first) + ")";
            } else {
                lineInfo = !customPlatLine.isBlank()
                        ? customPlatLine
                        : conciseStation(plat.stationName());
            }
            float textY = rY + (rowH - font.lineHeight * rowScale) * 0.5F;
            drawFitted(lineInfo, margin + badgeS + 4.0F, textY,
                    logicalWidth - margin * 2.0F - badgeS - 8.0F, darkText, rowScale,
                    poseStack, buffers);
        }
    }

    private static String melbourneEtaText(CreateTrainScheduleService.Departure departure) {
        if (departure.dwelling()) {
            return "NOW";
        }
        if (!departure.etaKnown()) {
            return "-- min";
        }
        int seconds = departure.etaSeconds();
        if (seconds <= 5) {
            return "NOW";
        }
        if (seconds < 60) {
            return seconds + " sec";
        }
        return ((seconds + 29) / 60) + " min";
    }

    private static String countdown(long ticks) {
        long seconds = Math.max(0L, (ticks + 19L) / 20L);
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                seconds / 60L, seconds % 60L);
    }

    public static boolean isSkippedStopEntry(String stop) {
        return stop != null && stop.trim().startsWith("~");
    }

    public static String stripSkippedPrefix(String stop) {
        if (stop == null) return "";
        String trimmed = stop.trim();
        while (trimmed.startsWith("~")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    /**
     * Classifies a train's stopping pattern as {@code "Stops all stations"} (or
     * {@code "All stations"} when {@code compact == true}), {@code "Limited express"}, or
     * {@code "Express"} based on skipped intermediate stations and/or explicit service type tags.
     */
    public static String melbourneStoppingPatternLabel(List<String> upcomingStops,
            String trainType, String trainName, boolean loop, boolean compact) {
        String typeCheck = ((trainType == null ? "" : trainType) + " "
                + (trainName == null ? "" : trainName)).toLowerCase(java.util.Locale.ROOT);
        if (typeCheck.contains("limited express") || typeCheck.contains("区间快速")
                || typeCheck.contains("區間快速") || typeCheck.contains("准急")
                || typeCheck.contains("準急") || typeCheck.contains("通勤快速")) {
            return "Limited express";
        }
        int totalStops = 0;
        int skippedStops = 0;
        if (upcomingStops != null) {
            for (String stop : upcomingStops) {
                if (stop == null || stop.isBlank()) continue;
                totalStops++;
                if (isSkippedStopEntry(stop)) skippedStops++;
            }
        }
        int intermediateTotal = Math.max(0, totalStops - 2);
        int intermediateStopping = Math.max(0, intermediateTotal - skippedStops);
        if (typeCheck.contains("express") || typeCheck.contains("rapid")
                || typeCheck.contains("特快") || typeCheck.contains("特急")
                || typeCheck.contains("急行") || typeCheck.contains("快速")
                || typeCheck.contains("直达") || typeCheck.contains("直達")) {
            if (skippedStops > 0 && intermediateStopping >= 2) {
                return "Limited express";
            }
            return "Express";
        }
        if (skippedStops > 0) {
            if (intermediateStopping == 0
                    || (skippedStops >= 2 && skippedStops >= intermediateStopping * 2)) {
                return "Express";
            }
            return "Limited express";
        }
        if (loop) {
            return compact ? "All stations" : "Stops all stations via Loop";
        }
        return compact ? "All stations" : "Stops all stations";
    }

    private static String routeText(String templateId, String origin, String destination,
            boolean loop, List<String> upcomingStops) {
        if (upcomingStops != null && !upcomingStops.isEmpty()) {
            List<String> formattedStops = upcomingStops.stream()
                    .filter(stop -> !isSkippedStopEntry(stop))
                    .map(stop -> conciseStation(
                            TrafficTemplateRepository.mappedStationName(
                                    templateId, stripSkippedPrefix(stop))))
                    .filter(stop -> !stop.isBlank())
                    .toList();
            if (!formattedStops.isEmpty()) {
                return String.join(" → ", formattedStops);
            }
        }
        String from = origin == null || origin.isBlank() ? "?" : origin.trim();
        String to = destination == null || destination.isBlank() ? "?" : destination.trim();
        if (loop) {
            return Component.translatable("screen.minescreen.station.loop_via",
                    conciseStation(from), conciseStation(to)).getString();
        }
        return conciseStation(from) + " → " + conciseStation(to);
    }

    private static String conciseStation(String value) {
        return stripSkippedPrefix(value)
                .replaceAll("(?iu)\\s*(上行|下行|上り|下り|upbound|downbound)\\s*", "")
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
            quad2d(buffers.getBuffer(RenderType.textBackground()), pose,
                    x - radius, y - radius, x + radius, y + radius,
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
                case "rect" -> quad2d(buffers.getBuffer(RenderType.textBackground()), pose, x, y,
                        x + element.width() * logicalWidth,
                        y + element.height() * logicalHeight, element.color());
                case "ellipse" -> ellipse2d(buffers.getBuffer(RenderType.textBackground()), pose,
                        x + element.width() * logicalWidth * 0.5D,
                        y + element.height() * logicalHeight * 0.5D,
                        element.width() * logicalWidth * 0.5D,
                        element.height() * logicalHeight * 0.5D, element.color());
                case "line" -> line2d(buffers.getBuffer(RenderType.textBackground()), pose, x, y,
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
        String arrivalTime = "--:--";
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
            upcoming = train.upcomingStops().stream()
                    .filter(stop -> !isSkippedStopEntry(stop))
                    .map(stop -> TrafficTemplateRepository.mappedStationName(
                            template.id(), stripSkippedPrefix(stop)))
                    .filter(stop -> !stop.isBlank()).collect(java.util.stream.Collectors
                            .joining(" · "));
            arrivalTime = expectedArrivalTime(display.getLevel(), train);
        } else if (display.getLevel() != null) {
            CreateTrainScheduleService.ResolvedSnapshot resolved =
                    CreateTrainScheduleService.snapshotForDisplay(display.getLevel(),
                            display.getBlockPos(), display.templateId(backSide),
                            display.stationBindingName(backSide), display.trafficCurrentStop(backSide),
                            display.stationTrainType(backSide),
                            TrafficTemplateRepository.resolvedCreateStationName(
                                    display.templateId(backSide),
                                    display.stationTurnaroundName(backSide)), 4);
            CreateTrainScheduleService.Snapshot snapshot = resolved.snapshot();
            if (!snapshot.stationName().isBlank()) {
                current = TrafficTemplateRepository.mappedStationName(template.id(),
                        snapshot.stationName());
            }
            if (!snapshot.departures().isEmpty()) {
                CreateTrainScheduleService.Departure first = snapshot.departures().getFirst();
                trainName = first.trainName();
                serviceType = first.trainType();
                if (!trainName.isBlank()) line = trainName;
                if (!first.destination().isBlank()) {
                    destination = TrafficTemplateRepository.mappedStationName(template.id(),
                            first.destination());
                }
                carriageCount = first.carriageCount() <= 0 ? ""
                        : Integer.toString(first.carriageCount());
                eta = first.dwelling()
                        ? first.dwellKnown() ? countdown(first.dwellTicks())
                                : Component.translatable("screen.minescreen.station.dwell_unknown")
                                        .getString()
                        : first.etaKnown() ? countdown(first.etaTicks()) : "--:--";
                arrivalTime = first.dwelling() ? worldClock(display.getLevel(), 0L)
                        : first.etaKnown() ? worldClock(display.getLevel(), first.etaTicks())
                                : "--:--";
                List<String> mappedStops = first.upcomingStops().stream()
                        .filter(stop -> !isSkippedStopEntry(stop))
                        .map(stop -> conciseStation(
                                TrafficTemplateRepository.mappedStationName(
                                        template.id(), stripSkippedPrefix(stop))))
                        .filter(stop -> !stop.isBlank())
                        .toList();
                if (!mappedStops.isEmpty()) {
                    next = mappedStops.size() > 1 ? mappedStops.get(1) : mappedStops.getFirst();
                    upcoming = String.join(" · ", mappedStops);
                }
            }
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
                .replace("${arrival_time}", arrivalTime);
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

    /**
     * Fills {@code [x0, y0, x1, y1]} with four non-overlapping strips around the inner hole
     * {@code [hx0, hy0, hx1, hy1]} so inner details never share depth values with their frame.
     */
    private static void fillWithHole(VertexConsumer consumer, Matrix4f pose,
            double x0, double y0, double x1, double y1,
            double hx0, double hy0, double hx1, double hy1, int color) {
        double cx0 = Math.max(x0, Math.min(x1, hx0));
        double cy0 = Math.max(y0, Math.min(y1, hy0));
        double cx1 = Math.max(cx0, Math.min(x1, hx1));
        double cy1 = Math.max(cy0, Math.min(y1, hy1));
        if (cy0 > y0) quad2d(consumer, pose, x0, y0, x1, cy0, color);
        if (cy1 < y1) quad2d(consumer, pose, x0, cy1, x1, y1, color);
        if (cx0 > x0 && cy1 > cy0) quad2d(consumer, pose, x0, cy0, cx0, cy1, color);
        if (cx1 < x1 && cy1 > cy0) quad2d(consumer, pose, cx1, cy0, x1, cy1, color);
    }

    /**
     * Draws a crisp front-facing Melbourne Metro train pictogram inside {@code [x, y, x+s, y+s]}
     * by partitioning the square into strictly disjoint rectangles (zero overlapping quads).
     */
    private static void drawMelbourneTrainIcon(VertexConsumer vc, Matrix4f pose,
            float x, float y, float s, int trainColor, int badgeColor) {
        float x00 = x;
        float x18 = x + s * 0.18F;
        float x24 = x + s * 0.24F;
        float x26 = x + s * 0.26F;
        float x36 = x + s * 0.36F;
        float x40 = x + s * 0.40F;
        float x60 = x + s * 0.60F;
        float x64 = x + s * 0.64F;
        float x74 = x + s * 0.74F;
        float x76 = x + s * 0.76F;
        float x82 = x + s * 0.82F;
        float x100 = x + s;

        float y00 = y;
        float y06 = y + s * 0.06F;
        float y14 = y + s * 0.14F;
        float y24 = y + s * 0.24F;
        float y50 = y + s * 0.50F;
        float y58 = y + s * 0.58F;
        float y70 = y + s * 0.70F;
        float y78 = y + s * 0.78F;
        float y90 = y + s * 0.90F;
        float y100 = y + s;

        // 1. Top margin + overhead pantograph bar [y00 .. y14]
        quad2d(vc, pose, x00, y00, x36, y14, badgeColor);
        quad2d(vc, pose, x36, y00, x64, y06, badgeColor);
        quad2d(vc, pose, x36, y06, x64, y14, trainColor);
        quad2d(vc, pose, x64, y00, x100, y14, badgeColor);

        // 2. Left & right badge side margins [y14 .. y78]
        quad2d(vc, pose, x00, y14, x18, y78, badgeColor);
        quad2d(vc, pose, x82, y14, x100, y78, badgeColor);

        // 3. Train cab body [x18 .. x82, y14 .. y78]
        // Roof header
        quad2d(vc, pose, x18, y14, x82, y24, trainColor);
        // Windshield band (pillars + wide white front window)
        quad2d(vc, pose, x18, y24, x26, y50, trainColor);
        quad2d(vc, pose, x26, y24, x74, y50, badgeColor);
        quad2d(vc, pose, x74, y24, x82, y50, trainColor);
        // Mid nose band
        quad2d(vc, pose, x18, y50, x82, y58, trainColor);
        // Headlight band (left & right white headlights + center grille)
        quad2d(vc, pose, x18, y58, x26, y70, trainColor);
        quad2d(vc, pose, x26, y58, x40, y70, badgeColor);
        quad2d(vc, pose, x40, y58, x60, y70, trainColor);
        quad2d(vc, pose, x60, y58, x74, y70, badgeColor);
        quad2d(vc, pose, x74, y58, x82, y70, trainColor);
        // Lower bumper skirt
        quad2d(vc, pose, x18, y70, x82, y78, trainColor);

        // 4. Lower wheel/rail legs & bottom margin [y78 .. y100]
        quad2d(vc, pose, x00, y78, x24, y90, badgeColor);
        quad2d(vc, pose, x24, y78, x36, y90, trainColor);
        quad2d(vc, pose, x36, y78, x64, y90, badgeColor);
        quad2d(vc, pose, x64, y78, x76, y90, trainColor);
        quad2d(vc, pose, x76, y78, x100, y90, badgeColor);
        quad2d(vc, pose, x00, y90, x100, y100, badgeColor);
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

    private float drawWrappedWorld(Component text, float x, float y, int maximumWidth,
            int maximumLines, int maxBottomY, float scale, int color, PoseStack poseStack,
            MultiBufferSource buffers) {
        float safeScale = Math.max(0.45F, scale);
        int splitWidth = Math.max(1, Math.round(maximumWidth / safeScale));
        List<FormattedCharSequence> lines = font.split(text, splitWidth);
        int count = Math.min(Math.max(1, maximumLines), lines.size());
        float lineHeightScaled = font.lineHeight * safeScale;
        float lineStep = Math.max(1.0F, lineHeightScaled + 1.0F);
        float currentY = y;
        for (int index = 0; index < count; index++) {
            if (currentY + lineHeightScaled > maxBottomY) {
                break;
            }
            drawScaled(lines.get(index), x, currentY, color, safeScale, poseStack, buffers);
            currentY += lineStep;
        }
        return currentY;
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
