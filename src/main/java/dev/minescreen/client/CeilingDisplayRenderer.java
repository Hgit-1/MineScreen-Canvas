package dev.minescreen.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.minescreen.CeilingDisplayBlockEntity;
import dev.minescreen.CeilingDisplayGroup;
import dev.minescreen.CeilingDisplayGroupResolver;
import dev.minescreen.CeilingDisplayMode;
import dev.minescreen.DoorLcdGroup;
import dev.minescreen.DoorLcdGroupResolver;
import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.compat.MovingStructureCompat;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.traffic.TrafficTemplateRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

/** Two downward-facing display faces on an equilateral-triangular ceiling prism. */
public final class CeilingDisplayRenderer implements BlockEntityRenderer<CeilingDisplayBlockEntity> {
    private static final double CEILING_Y = 0.94D;
    private static final double RIDGE_Y = CEILING_Y - Math.sqrt(3.0D) * 0.5D;
    private final Font font;
    private final java.util.Map<CeilingDisplayBlockEntity, StationAnimation[]> stationAnimations =
            new java.util.WeakHashMap<>();

    public CeilingDisplayRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public void render(CeilingDisplayBlockEntity display, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (display.isRemoved() || display.getLevel() == null) return;
        if (display.getBlockState().is(MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get())) {
            renderCarriageInfoDisplay(display, poseStack, buffers);
            return;
        }
        if (display.getBlockState().is(MineScreen.DOOR_LCD_BLOCK.get())) {
            renderDoorLcd(display, partialTick, poseStack, buffers);
            return;
        }
        CeilingDisplayGroup group = CeilingDisplayGroupResolver.resolve(display.getLevel(),
                display.getBlockPos());
        if (group == null || !group.master().equals(display.getBlockPos())) return;
        ScreenGroup sideA = group.sideGroup(0);
        ScreenGroup sideB = group.sideGroup(1);
        if (display.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
            ScreenContentManager.registerAuxiliaryGroup(sideA);
            ScreenContentManager.registerAuxiliaryGroup(sideB);
        } else {
            ScreenContentManager.registerMovingGroup(display.getLevel(), sideA, partialTick);
            ScreenContentManager.registerMovingGroup(display.getLevel(), sideB, partialTick);
        }

        Vec3 axis = group.axis() == net.minecraft.core.Direction.Axis.X
                ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
        Vec3 cross = group.axis() == net.minecraft.core.Direction.Axis.X
                ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 ridgeStart = cross.scale(0.5D).add(up.scale(RIDGE_Y));
        Vec3 ridgeEnd = ridgeStart.add(axis.scale(group.length()));
        Vec3 ceilingAStart = up.scale(CEILING_Y);
        Vec3 ceilingAEnd = ceilingAStart.add(axis.scale(group.length()));
        Vec3 ceilingBStart = cross.add(up.scale(CEILING_Y));
        Vec3 ceilingBEnd = ceilingBStart.add(axis.scale(group.length()));

        // The non-display ceiling face is against the carriage roof. Only the two faces descending
        // to the central ridge are drawn, so the prism hangs down instead of opening upward.
        renderChassisFace(poseStack, buffers, ceilingAStart, ceilingAEnd, ridgeEnd, ridgeStart);
        renderChassisFace(poseStack, buffers, ridgeStart, ridgeEnd, ceilingBEnd, ceilingBStart);
        renderSide(display, group, 0, sideA, ridgeStart, ceilingAStart, axis, cross.scale(-1),
                poseStack, buffers);
        renderSide(display, group, 1, sideB, ridgeEnd, ceilingBEnd, axis.scale(-1), cross,
                poseStack, buffers);
    }

    private void renderDoorLcd(CeilingDisplayBlockEntity display, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers) {
        DoorLcdGroup group = DoorLcdGroupResolver.resolve(display.getLevel(), display.getBlockPos());
        if (group == null || !group.master().equals(display.getBlockPos())) return;
        ScreenGroup screenGroup = group.screenGroup();
        if (display.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
            ScreenContentManager.registerAuxiliaryGroup(screenGroup);
        } else {
            ScreenContentManager.registerMovingGroup(display.getLevel(), screenGroup, partialTick);
        }
        Vec3 right = ScreenGeometry.right(group.facing());
        Vec3 outward = ScreenGeometry.normal(group.facing());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 origin = ScreenGeometry.origin(group.master(), group.facing())
                .subtract(Vec3.atLowerCornerOf(group.master()));
        // Anchor the upper edge at the mounting face and let the lower edge recede into the
        // carriage. In side profile this is a down-left 45-degree plane. The former arrangement
        // put the lower edge outward, making the visible normal point upward rather than toward
        // passengers standing below the display.
        Vec3 topStart = origin.add(up.scale(0.86D));
        Vec3 bottomStart = origin.add(outward.scale(-0.72D)).add(up.scale(0.14D));
        Vec3 normal = outward.subtract(up).normalize();

        int settingsSide = 0;
        if (display.mode(settingsSide) == CeilingDisplayMode.TRAFFIC) {
            renderDoorTraffic(display, settingsSide, group.length(), bottomStart, topStart, right,
                    normal, poseStack, buffers);
            return;
        }
        ScreenRenderSource source = ScreenContentManager.sourceForMoving(screenGroup);
        for (ScreenRenderSource.Pane pane : source.panes()) {
            pane.bind();
            VertexConsumer consumer = buffers.getBuffer(pane.renderType());
            Vec3 bottomLeft = doorFacePoint(bottomStart, topStart, right, group.length(),
                    pane.left(), pane.bottom(), normal);
            Vec3 bottomRight = doorFacePoint(bottomStart, topStart, right, group.length(),
                    pane.right(), pane.bottom(), normal);
            Vec3 topRight = doorFacePoint(bottomStart, topStart, right, group.length(),
                    pane.right(), pane.top(), normal);
            Vec3 topLeft = doorFacePoint(bottomStart, topStart, right, group.length(),
                    pane.left(), pane.top(), normal);
            float u0 = pane.flipHorizontal() ? 1.0F : 0.0F;
            float u1 = pane.flipHorizontal() ? 0.0F : 1.0F;
            texturedQuad(consumer, poseStack.last().pose(), bottomLeft, bottomRight, topRight,
                    topLeft, u0, u1, 0.0F, 1.0F);
        }
    }

    private void renderCarriageInfoDisplay(CeilingDisplayBlockEntity display,
            PoseStack poseStack, MultiBufferSource buffers) {
        DoorLcdGroup group = DoorLcdGroupResolver.resolve(display.getLevel(),
                display.getBlockPos());
        if (group == null || !group.master().equals(display.getBlockPos())) return;
        Vec3 right = ScreenGeometry.right(group.facing()).normalize();
        Vec3 outward = ScreenGeometry.normal(group.facing()).normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 origin = ScreenGeometry.origin(group.master(), group.facing())
                .subtract(Vec3.atLowerCornerOf(group.master()));
        Vec3 bottom = origin.add(up.scale(0.12D));
        Vec3 top = origin.add(up.scale(0.88D));
        renderChassisFace(poseStack, buffers,
                doorFacePoint(bottom, top, right, group.length(), 0, 0, outward),
                doorFacePoint(bottom, top, right, group.length(), 1, 0, outward),
                doorFacePoint(bottom, top, right, group.length(), 1, 1, outward),
                doorFacePoint(bottom, top, right, group.length(), 0, 1, outward));

        Vec3 panelBottom = origin.add(up.scale(0.17D));
        Vec3 panelTop = origin.add(up.scale(0.83D));
        solidQuad(buffers.getBuffer(RenderType.textBackground()), poseStack.last().pose(),
                doorFacePoint(panelBottom, panelTop, right, group.length(), 0.015F, 0, outward)
                        .add(outward.normalize().scale(0.004D)),
                doorFacePoint(panelBottom, panelTop, right, group.length(), 0.985F, 0, outward)
                        .add(outward.normalize().scale(0.004D)),
                doorFacePoint(panelBottom, panelTop, right, group.length(), 0.985F, 1, outward)
                        .add(outward.normalize().scale(0.004D)),
                doorFacePoint(panelBottom, panelTop, right, group.length(), 0.015F, 1, outward)
                        .add(outward.normalize().scale(0.004D)),
                0xFF05080D);

        int logicalWidth = Math.max(144, 144 * group.length());
        Vec3 down = new Vec3(0, -1, 0);
        Vec3 topLeft = origin.add(right.scale(0.04D)).add(up.scale(0.80D))
                .add(outward.normalize().scale(0.015D));
        Matrix4f transform = basis(topLeft, right, down, outward,
                (group.length() - 0.08D) / logicalWidth, 0.60D / 64.0D);
        poseStack.pushPose();
        poseStack.mulPose(transform);
        renderInfoStrip(trafficValues(display, 0), logicalWidth, display.getLevel(),
                poseStack, buffers);
        renderOverlay(display, 0, logicalWidth, 64, poseStack, buffers);
        poseStack.popPose();
    }

    private void renderSide(CeilingDisplayBlockEntity display, CeilingDisplayGroup group, int side,
            ScreenGroup screenGroup, Vec3 bottomStart, Vec3 ridgeStart, Vec3 horizontal,
            Vec3 outward, PoseStack poseStack, MultiBufferSource buffers) {
        int settingsSide = display.linkedSides() ? 0 : side;
        if (display.mode(settingsSide) == CeilingDisplayMode.TRAFFIC) {
            renderTraffic(display, settingsSide, group.length(), bottomStart, ridgeStart, horizontal,
                    outward, poseStack, buffers);
            return;
        }
        ScreenRenderSource source;
        if (display.linkedSides() && side == 1) {
            source = ScreenContentManager.sourceForMoving(group.sideGroup(0));
        } else {
            source = ScreenContentManager.sourceForMoving(screenGroup);
        }
        for (ScreenRenderSource.Pane pane : source.panes()) {
            pane.bind();
            VertexConsumer consumer = buffers.getBuffer(pane.renderType());
            Vec3 bottomLeft = facePoint(bottomStart, ridgeStart, horizontal, group.length(),
                    pane.left(), pane.bottom(), outward);
            Vec3 bottomRight = facePoint(bottomStart, ridgeStart, horizontal, group.length(),
                    pane.right(), pane.bottom(), outward);
            Vec3 topRight = facePoint(bottomStart, ridgeStart, horizontal, group.length(),
                    pane.right(), pane.top(), outward);
            Vec3 topLeft = facePoint(bottomStart, ridgeStart, horizontal, group.length(),
                    pane.left(), pane.top(), outward);
            float u0 = pane.flipHorizontal() ? 1.0F : 0.0F;
            float u1 = pane.flipHorizontal() ? 0.0F : 1.0F;
            texturedQuad(consumer, poseStack.last().pose(), bottomLeft, bottomRight, topRight,
                    topLeft, u0, u1, 0.0F, 1.0F);
        }
    }

    private void renderTraffic(CeilingDisplayBlockEntity display, int side, int length,
            Vec3 bottomStart, Vec3 ridgeStart, Vec3 horizontal, Vec3 outward, PoseStack poseStack,
            MultiBufferSource buffers) {
        TrafficTemplateRepository.Template template = TrafficTemplateRepository.resolve(
                display.templateId(side));
        if (display.overlayTemplateId(side).isBlank() && template.textSequence().present()) {
            template = TrafficTemplateRepository.Template.BUILTIN;
        }
        TrafficValues values = trafficValues(display, side);
        VertexConsumer background = buffers.getBuffer(RenderType.textBackground());
        solidQuad(background, poseStack.last().pose(),
                facePoint(bottomStart, ridgeStart, horizontal, length, 0, 1, outward),
                facePoint(bottomStart, ridgeStart, horizontal, length, 1, 1, outward),
                facePoint(bottomStart, ridgeStart, horizontal, length, 1, 0, outward),
                facePoint(bottomStart, ridgeStart, horizontal, length, 0, 0, outward),
                template.backgroundColor());
        if (template.texture() != null) {
            VertexConsumer texture = buffers.getBuffer(ScreenRenderType.screen(template.texture()));
            Vec3 textureLift = outward.normalize().scale(0.004D);
            texturedQuad(texture, poseStack.last().pose(),
                    facePoint(bottomStart, ridgeStart, horizontal, length, 0, 1, outward)
                            .add(textureLift),
                    facePoint(bottomStart, ridgeStart, horizontal, length, 1, 1, outward)
                            .add(textureLift),
                    facePoint(bottomStart, ridgeStart, horizontal, length, 1, 0, outward)
                            .add(textureLift),
                    facePoint(bottomStart, ridgeStart, horizontal, length, 0, 0, outward)
                            .add(textureLift),
                    0, 1, 0, 1);
        }
        Vec3 down = bottomStart.subtract(ridgeStart).normalize();
        double slopeLength = bottomStart.distanceTo(ridgeStart);
        Vec3 topLeft = ridgeStart.add(horizontal.normalize().scale(0.06D))
                .add(down.scale(0.06D)).add(outward.normalize().scale(0.015D));
        int logicalWidth = Math.max(112, 112 * length);
        Matrix4f basis = basis(topLeft, horizontal.normalize(), down, outward.normalize(),
                (length - 0.12D) / logicalWidth, (slopeLength - 0.12D) / 84.0D);
        poseStack.pushPose();
        poseStack.mulPose(basis);
        renderTrafficContent(display, side, template, values, logicalWidth, 84, poseStack, buffers);
        renderOverlay(display, side, logicalWidth, 84, poseStack, buffers);
        poseStack.popPose();
    }

    private void renderDoorTraffic(CeilingDisplayBlockEntity display, int side, int length,
            Vec3 bottomStart, Vec3 topStart, Vec3 horizontal, Vec3 outward, PoseStack poseStack,
            MultiBufferSource buffers) {
        TrafficTemplateRepository.Template template = TrafficTemplateRepository.resolve(
                display.templateId(side));
        if (display.overlayTemplateId(side).isBlank() && template.textSequence().present()) {
            template = TrafficTemplateRepository.Template.BUILTIN;
        }
        solidQuad(buffers.getBuffer(RenderType.textBackground()), poseStack.last().pose(),
                doorFacePoint(bottomStart, topStart, horizontal, length, 0, 0, outward),
                doorFacePoint(bottomStart, topStart, horizontal, length, 1, 0, outward),
                doorFacePoint(bottomStart, topStart, horizontal, length, 1, 1, outward),
                doorFacePoint(bottomStart, topStart, horizontal, length, 0, 1, outward),
                template.backgroundColor());
        if (template.texture() != null) {
            Vec3 textureLift = outward.normalize().scale(0.004D);
            texturedQuad(buffers.getBuffer(ScreenRenderType.screen(template.texture())),
                    poseStack.last().pose(),
                    doorFacePoint(bottomStart, topStart, horizontal, length, 0, 0, outward)
                            .add(textureLift),
                    doorFacePoint(bottomStart, topStart, horizontal, length, 1, 0, outward)
                            .add(textureLift),
                    doorFacePoint(bottomStart, topStart, horizontal, length, 1, 1, outward)
                            .add(textureLift),
                    doorFacePoint(bottomStart, topStart, horizontal, length, 0, 1, outward)
                            .add(textureLift),
                    0, 1, 0, 1);
        }
        Vec3 down = bottomStart.subtract(topStart).normalize();
        double slopeLength = bottomStart.distanceTo(topStart);
        Vec3 topLeft = topStart.add(horizontal.normalize().scale(0.06D))
                .add(down.scale(0.06D)).add(outward.normalize().scale(0.015D));
        int logicalWidth = Math.max(112, 112 * length);
        Matrix4f transform = basis(topLeft, horizontal.normalize(), down, outward,
                (length - 0.12D) / logicalWidth, (slopeLength - 0.12D) / 84.0D);
        poseStack.pushPose();
        poseStack.mulPose(transform);
        renderTrafficContent(display, side, template, trafficValues(display, side), logicalWidth,
                84, poseStack, buffers);
        renderOverlay(display, side, logicalWidth, 84, poseStack, buffers);
        poseStack.popPose();
    }

    private void renderTrafficContent(CeilingDisplayBlockEntity display, int side,
            TrafficTemplateRepository.Template template, TrafficValues values, int logicalWidth,
            int logicalHeight, PoseStack poseStack, MultiBufferSource buffers) {
        if (template.layout().equals("rmp_native_v1") && template.rmpDiagram() != null) {
            renderRmpTraffic(display, side, template, values, logicalWidth, logicalHeight,
                    poseStack, buffers);
        } else if (template.layout().equals("script_scene_v1") && !template.scene().isEmpty()) {
            renderScriptTraffic(display, side, template, values, logicalWidth, logicalHeight,
                    poseStack, buffers);
        } else {
            renderPassengerTraffic(values, logicalWidth, logicalHeight, template.textColor(),
                    template.accentColor(), poseStack, buffers);
        }
    }

    private void renderOverlay(CeilingDisplayBlockEntity display, int side, int width, int height,
            PoseStack poseStack, MultiBufferSource buffers) {
        String overlayId = display.overlayTemplateId(side);
        TrafficTemplateRepository.Template overlay;
        if (overlayId.isBlank()) {
            overlay = TrafficTemplateRepository.resolve(display.templateId(side));
            if (!overlay.textSequence().present()) return;
        } else {
            overlay = TrafficTemplateRepository.resolve(overlayId);
        }
        if (!overlay.textSequence().present()) return;
        renderTextSequence(display.getLevel(), overlay, width, height, poseStack, buffers);
    }

    /**
     * Dynamic TXT documents use the synchronized world game time as their clock. The imported
     * template contains only bounded UTF-8 text and timing metadata; local file paths are never
     * consulted by the renderer or sent to the server.
     */
    private void renderTextSequence(Level level, TrafficTemplateRepository.Template overlay,
            int width, int height, PoseStack poseStack, MultiBufferSource buffers) {
        TrafficTemplateRepository.TextSequence sequence = overlay.textSequence();
        long totalTicks = sequence.frames().stream()
                .mapToLong(TrafficTemplateRepository.TextFrame::durationTicks).sum();
        if (totalTicks <= 0L) return;
        long elapsed = Math.max(0L, level.getGameTime());
        long cursor = sequence.loop() ? Math.floorMod(elapsed, totalTicks)
                : Math.min(elapsed, totalTicks - 1L);
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
        float slide = 0.0F;
        switch (selected.transition()) {
            case "fade" -> {
                double opacity = Math.min(1.0D, progress / 0.16D);
                if (!holdLast) {
                    opacity = Math.min(opacity, Math.max(0.0D, (1.0D - progress) / 0.16D));
                }
                alpha = Math.max(4, (int) Math.round(255.0D * opacity));
            }
            case "slide_left" -> {
                if (progress < 0.18D) {
                    slide = (float) (width * (1.0D - progress / 0.18D));
                } else if (!holdLast && progress > 0.82D) {
                    slide = (float) (-width * ((progress - 0.82D) / 0.18D));
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
        if (lines.size() > maximumLines) lines = lines.subList(0, maximumLines);
        int bandHeight = Math.min(height, lines.size() * font.lineHeight + 8);
        int bandTop = switch (sequence.position()) {
            case "top" -> 0;
            case "center" -> Math.max(0, (height - bandHeight) / 2);
            default -> Math.max(0, height - bandHeight);
        };
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.08D);
        logicalQuad(buffers.getBuffer(RenderType.textBackground()), poseStack.last().pose(),
                0, bandTop, width, bandTop + bandHeight, overlay.backgroundColor());
        float y = bandTop
                + Math.max(3.0F, (bandHeight - lines.size() * font.lineHeight) * 0.5F);
        for (FormattedCharSequence line : lines) {
            float x = switch (selected.align()) {
                case "left" -> 6.0F;
                case "right" -> width - 6.0F - font.width(line);
                default -> (width - font.width(line)) * 0.5F;
            };
            font.drawInBatch(line, x + slide, y, animatedColor, false, poseStack.last().pose(),
                    buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            y += font.lineHeight;
        }
        poseStack.popPose();
    }

    private void renderRmpTraffic(CeilingDisplayBlockEntity display, int side,
            TrafficTemplateRepository.Template template, TrafficValues values, int width,
            int height, PoseStack poseStack, MultiBufferSource buffers) {
        TrafficTemplateRepository.RmpDiagram diagram = template.rmpDiagram();
        double spanX = Math.max(1.0D, diagram.maxX() - diagram.minX());
        double spanY = Math.max(1.0D, diagram.maxY() - diagram.minY());
        double margin = 5.0D;
        double header = 13.0D;
        double scale = Math.min(Math.max(1.0D, width - margin * 2.0D) / spanX,
                Math.max(1.0D, height - margin * 2.0D - header) / spanY);
        double mapWidth = spanX * scale;
        double mapHeight = spanY * scale;
        double offsetX = (width - mapWidth) * 0.5D - diagram.minX() * scale;
        double offsetY = header + (height - header - mapHeight) * 0.5D - diagram.minY() * scale;
        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        for (TrafficTemplateRepository.RmpEdge edge : diagram.edges()) {
            TrafficTemplateRepository.RmpNode from = diagram.nodes().get(edge.from());
            TrafficTemplateRepository.RmpNode to = diagram.nodes().get(edge.to());
            logicalLine(geometry, pose, from.x() * scale + offsetX,
                    from.y() * scale + offsetY, to.x() * scale + offsetX,
                    to.y() * scale + offsetY, Math.max(1.0D, Math.min(2.5D, scale * 0.07D)),
                    edge.color());
        }
        int labelStride = Math.max(1, (diagram.nodes().size() + 31) / 32);
        for (int index = 0; index < diagram.nodes().size(); index++) {
            TrafficTemplateRepository.RmpNode node = diagram.nodes().get(index);
            double x = node.x() * scale + offsetX;
            double y = node.y() * scale + offsetY;
            double radius = Math.max(1.1D, Math.min(2.6D, scale * 0.09D));
            logicalQuad(geometry, pose, x - radius, y - radius, x + radius, y + radius,
                    template.accentColor());
            if (index % labelStride == 0 && !node.name().isBlank()) {
                String name = font.plainSubstrByWidth(node.name(), Math.max(18, width / 6));
                font.drawInBatch(Component.literal(name).getVisualOrderText(), (float) (x + radius + 1),
                        (float) (y - font.lineHeight * 0.5F), template.textColor(), false, pose,
                        buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
        }
        drawLine(values.line() + "  " + values.destination(), width, 1,
                template.textColor(), poseStack, buffers);
    }

    private void renderScriptTraffic(CeilingDisplayBlockEntity display, int side,
            TrafficTemplateRepository.Template template, TrafficValues values, int width,
            int height, PoseStack poseStack, MultiBufferSource buffers) {
        VertexConsumer geometry = buffers.getBuffer(RenderType.textBackground());
        int order = 0;
        for (TrafficTemplateRepository.SceneElement element : template.scene()) {
            poseStack.pushPose();
            poseStack.translate(0.0D, 0.0D, sceneLayer(order++));
            Matrix4f pose = poseStack.last().pose();
            double x = element.x() * width;
            double y = element.y() * height;
            switch (element.type()) {
                case "rect" -> logicalQuad(geometry, pose, x, y,
                        x + element.width() * width, y + element.height() * height, element.color());
                case "ellipse" -> logicalEllipse(geometry, pose,
                        x + element.width() * width * 0.5D,
                        y + element.height() * height * 0.5D,
                        element.width() * width * 0.5D,
                        element.height() * height * 0.5D, element.color());
                case "line" -> logicalLine(geometry, pose, x, y, element.x2() * width,
                        element.y2() * height, Math.max(0.75D, element.size()), element.color());
                case "text" -> {
                    String value = expand(element.text(), display, side, template, values);
                    if ((element.text().contains("${next}")
                            || element.text().contains("${status}"))
                            && values.transition() < 1.0F) {
                        x += (1.0F - values.transition()) * width * 0.08D;
                    }
                    double available = element.maxWidth() > 0.0D ? element.maxWidth()
                            : element.align().equals("right") ? element.x()
                            : element.align().equals("center")
                                    ? Math.min(element.x(), 1.0D - element.x()) * 2.0D
                                    : 1.0D - element.x();
                    float maximumPixels = Math.max(1.0F, (float) (available * width));
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
                        font.drawInBatch(line, localX, 0, element.color(), false,
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

    private TrafficValues trafficValues(CeilingDisplayBlockEntity display, int side) {
        MovingStructureCompat.CarriageTrainStatus train = MovingStructureCompat
                .carriageTrainStatus(display.getLevel(),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                display.templateId(side), display.turnaround(side)));
        String templateId = display.templateId(side);
        if (train == null) {
            return new TrafficValues(display.line(side), "", display.destination(side),
                    display.nextStop(side), display.eta(side), display.status(side),
                    display.notice(side), 0, 0, java.util.List.of(),
                    -1L, -1L, MovingStructureCompat.ArrivalPhase.NO_ROUTE, false, 1.0F);
        }
        if (train.stationName().isBlank()
                || train.phase() == MovingStructureCompat.ArrivalPhase.NO_ROUTE) {
            String line = train.trainName().isBlank() ? display.line(side) : train.trainName();
            String status = Component.translatable(train.schedulePresent()
                    ? "screen.minescreen.ceiling.awaiting_route"
                    : "screen.minescreen.ceiling.no_running_schedule").getString();
            String terminal = terminal(display, side,
                    TrafficTemplateRepository.mappedStationName(templateId,
                            train.terminalStation()));
            return new TrafficValues(line, train.serviceType(), terminal, status,
                    "",
                    status, display.notice(side), train.carriageCount(),
                    train.carriageNumber(),
                    mappedStops(templateId, train.upcomingStops()),
                    train.etaTicks(), train.dwellTicks(),
                    MovingStructureCompat.ArrivalPhase.NO_ROUTE, true,
                    stationTransition(display, side, status));
        }
        String line = train.trainName().isBlank() ? display.line(side) : train.trainName();
        String eta = formatEta(train);
        String status = switch (train.phase()) {
            case APPROACHING -> Component.translatable(
                    "screen.minescreen.ceiling.prepare_to_alight").getString();
            case ARRIVED -> train.dwellKnown()
                    ? Component.translatable("screen.minescreen.ceiling.dwell_remaining",
                            formatClock(train.dwellTicks(), false)).getString()
                    : Component.translatable(
                            "screen.minescreen.ceiling.arrived_doors").getString();
            case EN_ROUTE -> Component.translatable(
                    "screen.minescreen.ceiling.next_station").getString();
            case WAITING_SIGNAL -> Component.translatable(
                    "screen.minescreen.ceiling.waiting_signal").getString();
            case NO_ROUTE -> display.status(side);
        };
        String terminal = terminal(display, side,
                TrafficTemplateRepository.mappedStationName(templateId,
                        train.terminalStation()));
        String stationName = TrafficTemplateRepository.mappedStationName(templateId,
                train.stationName());
        return new TrafficValues(line, train.serviceType(), terminal, stationName, eta,
                status, display.notice(side), train.carriageCount(),
                train.carriageNumber(),
                mappedStops(templateId, train.upcomingStops()),
                train.etaTicks(), train.dwellTicks(), train.phase(), true,
                stationTransition(display, side, stationName));
    }

    private float stationTransition(CeilingDisplayBlockEntity display, int side,
            String stationName) {
        long now = display.getLevel() == null ? 0L : display.getLevel().getGameTime();
        StationAnimation[] states = stationAnimations.computeIfAbsent(display,
                ignored -> new StationAnimation[] {new StationAnimation(), new StationAnimation()});
        StationAnimation state = states[side == 1 ? 1 : 0];
        String value = stationName == null ? "" : stationName;
        if (!state.station.equals(value)) {
            state.station = value;
            state.changedAt = now;
        }
        return Math.min(1.0F, Math.max(0.0F, (now - state.changedAt) / 12.0F));
    }

    private static java.util.List<String> mappedStops(String templateId,
            java.util.List<String> stops) {
        if (stops == null || stops.isEmpty()) return java.util.List.of();
        return stops.stream().map(stop -> TrafficTemplateRepository.mappedStationName(
                templateId, stop)).toList();
    }

    /** A value entered in the editor is an explicit override; the Create schedule is the fallback. */
    private static String terminal(CeilingDisplayBlockEntity display, int side,
            String automaticTerminal) {
        String manual = display.destination(side) == null ? "" : display.destination(side).trim();
        if (!manual.isBlank() && !manual.equalsIgnoreCase("Destination")) return manual;
        if ("@minescreen:loop".equals(automaticTerminal)) {
            return Component.translatable("screen.minescreen.station.loop").getString();
        }
        return automaticTerminal == null ? "" : automaticTerminal;
    }

    private static String formatEta(MovingStructureCompat.CarriageTrainStatus train) {
        if (train.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            return train.dwellKnown() ? formatClock(train.dwellTicks(), false)
                    : Component.translatable("screen.minescreen.ceiling.arrived").getString();
        }
        // The countdown is a real 90-second arrival window. Do not clamp a five-minute ETA to
        // 01:30, otherwise the board appears to start counting from the previous station.
        if (!train.etaKnown() || train.etaTicks() > 90L * 20L) return "";
        return formatClock(train.etaTicks(), false);
    }

    private static String formatClock(long ticks, boolean clampArrivalEstimate) {
        long seconds = Math.max(0L, (ticks + 19L) / 20L);
        if (clampArrivalEstimate) seconds = Math.min(90L, seconds);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60L,
                seconds % 60L);
    }

    private void renderPassengerTraffic(TrafficValues values, int width, int height, int color,
            int accentColor, PoseStack poseStack, MultiBufferSource buffers) {
        if (values.dynamic() && (values.phase() == MovingStructureCompat.ArrivalPhase.APPROACHING
                || values.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED)) {
            drawTrainHeading(values, width, 5, color, poseStack, buffers);
            float slide = (1.0F - values.transition()) * Math.min(24.0F, width * 0.16F);
            poseStack.pushPose();
            poseStack.translate(slide, 0, 0.02F);
            drawLine((values.phase() == MovingStructureCompat.ArrivalPhase.APPROACHING
                    ? Component.translatable("screen.minescreen.ceiling.arriving_at",
                            values.nextStop())
                    : Component.translatable("screen.minescreen.ceiling.arrived_at",
                            values.nextStop())).getString(), width, 29, accentColor,
                    poseStack, buffers);
            poseStack.popPose();
            String footer = values.phase() == MovingStructureCompat.ArrivalPhase.APPROACHING
                    ? values.eta() + "  " + values.status() : values.status();
            if (!values.notice().isBlank()) footer = footer + "  ·  " + values.notice();
            int footerColor = values.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED
                    && values.dwellTicks() >= 0L
                    ? values.dwellTicks() <= 10L * 20L ? 0xFFFF5C5C : 0xFFFFD43B
                    : color;
            drawLine(footer, width, 58, footerColor, poseStack, buffers);
            return;
        }
        drawTrainHeading(values, width, 8, color, poseStack, buffers);
        String next = values.dynamic()
                ? Component.translatable("screen.minescreen.ceiling.next_station_value",
                        values.nextStop()).getString()
                : values.nextStop();
        float slide = (1.0F - values.transition()) * Math.min(24.0F, width * 0.16F);
        poseStack.pushPose();
        poseStack.translate(slide, 0, 0.02F);
        drawLine(next, width, 35, color, poseStack, buffers);
        poseStack.popPose();
        String footer = values.notice().isBlank()
                ? values.eta().isBlank() ? values.status() : values.eta()
                : values.eta() + "  ·  " + values.notice();
        drawLine(footer, width, 61, color, poseStack, buffers);
    }

    private void renderInfoStrip(TrafficValues values, int width, Level level, PoseStack poseStack,
            MultiBufferSource buffers) {
        VertexConsumer background = buffers.getBuffer(RenderType.textBackground());
        logicalQuad(background, poseStack.last().pose(), 0, 17, width, 19, 0xFF4D8DFF);

        String consist = values.carriageCount() > 0
                ? Component.translatable("screen.minescreen.carriage_info.consist",
                        values.carriageCount()).getString() : "--";
        String carriage = values.carriageNumber() > 0
                ? Component.translatable("screen.minescreen.carriage_info.car_number",
                        values.carriageNumber()).getString() : "";
        String service = values.serviceType();
        String destination = values.destination().isBlank() ? ""
                : Component.translatable("screen.minescreen.carriage_info.to",
                        values.destination()).getString();
        String arrivalClock = values.etaTicks() >= 0L
                && values.etaTicks() < Long.MAX_VALUE / 8L
                ? worldClock(level, values.etaTicks()) : "--:--";
        String clocks = values.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED
                ? worldClock(level, 0L)
                : worldClock(level, 0L) + " → " + arrivalClock;
        int clockWidth = font.width(clocks);
        int serviceWidth = service.isBlank() ? 0 : Math.min(62, font.width(service) + 6);
        if (!service.isBlank()) {
            drawFitted(service, 4, 4, serviceWidth, serviceColor(service, 0xFFF2F6FF), 0.82F,
                    poseStack, buffers);
        }
        String topLine = String.join("  |  ", java.util.stream.Stream
                .of(carriage, consist, values.line(), destination)
                .filter(value -> value != null && !value.isBlank()).toList());
        int topX = service.isBlank() ? 4 : 4 + serviceWidth;
        drawFitted(topLine, topX, 4, width - topX - clockWidth - 8, 0xFFF2F6FF, 0.82F,
                poseStack, buffers);
        drawFitted(clocks, width - clockWidth - 4, 4, clockWidth, 0xFFFFD166, 0.82F,
                poseStack, buffers);

        String lead = switch (values.phase()) {
            case APPROACHING -> Component.translatable(
                    "screen.minescreen.carriage_info.arriving").getString();
            case ARRIVED -> Component.translatable(
                    "screen.minescreen.carriage_info.now_at").getString();
            case WAITING_SIGNAL -> Component.translatable(
                    "screen.minescreen.ceiling.waiting_signal").getString();
            case EN_ROUTE -> Component.translatable(
                    "screen.minescreen.carriage_info.next").getString();
            case NO_ROUTE -> Component.translatable(
                    "screen.minescreen.carriage_info.no_route").getString();
        };
        drawFitted(lead, 5, 24, Math.min(68, width * 0.28F), 0xFFE7EEF8, 0.78F,
                poseStack, buffers);
        String station = values.nextStop().isBlank() ? values.status() : values.nextStop();
        float stationX = Math.min(72, width * 0.30F)
                + (1.0F - values.transition()) * Math.min(24.0F, width * 0.12F);
        drawFitted(station, stationX, 23,
                width - Math.min(76, width * 0.30F), 0xFFFF665E, 1.55F,
                poseStack, buffers);

        String footer;
        int footerColor = 0xFFC7D3E2;
        if (values.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            footer = values.notice().isBlank() ? values.status()
                    : values.status() + "  ·  " + values.notice();
            if (values.dwellTicks() >= 0L) {
                footerColor = values.dwellTicks() <= 10L * 20L
                        ? 0xFFFF5C5C : 0xFFFFD43B;
            }
        } else if (!values.notice().isBlank()) {
            // A manually configured passenger notice is safety/service information and therefore
            // takes priority over the optional stopping-pattern footer instead of disappearing
            // for half of every animation cycle.
            footer = values.notice();
            footerColor = 0xFFFFD166;
        } else if (!values.upcomingStops().isEmpty()) {
            footer = Component.translatable("screen.minescreen.carriage_info.stops_at",
                    String.join(" · ", values.upcomingStops())).getString();
        } else {
            footer = values.eta().isBlank() ? values.status()
                    : values.eta() + "  " + values.status();
        }
        drawFitted(footer, 5, 51, width - 10, footerColor, 0.70F, poseStack, buffers);
    }

    private static float sceneLayer(int order) {
        return Math.min(1.0F, 0.01F + Math.max(0, order) * 0.015F);
    }

    private record TrafficValues(String line, String serviceType, String destination,
            String nextStop, String eta, String status, String notice, int carriageCount,
            int carriageNumber, java.util.List<String> upcomingStops, long etaTicks,
            long dwellTicks,
            MovingStructureCompat.ArrivalPhase phase, boolean dynamic, float transition) {
    }

    private static final class StationAnimation {
        private String station = "";
        private long changedAt;
    }

    private static String expand(String text, CeilingDisplayBlockEntity display, int side,
            TrafficTemplateRepository.Template template, TrafficValues values) {
        String expanded = text.replace("${line}", values.line())
                .replace("${destination}", values.destination())
                .replace("${current}", "")
                .replace("${next}", values.nextStop())
                .replace("${eta}", values.eta())
                .replace("${status}", values.status())
                .replace("${notice}", values.notice())
                .replace("${train_name}", values.line())
                .replace("${service_type}", values.serviceType())
                .replace("${carriage_count}", Integer.toString(values.carriageCount()))
                .replace("${carriage_number}", Integer.toString(values.carriageNumber()))
                .replace("${upcoming_stops}", String.join(" · ", values.upcomingStops()))
                .replace("${direction}", directionLabel(values))
                .replace("${arrival_time}", expectedArrivalTime(display.getLevel(), values));
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
        return worldClock(level, 0L);
    }

    private static String expectedArrivalTime(Level level, TrafficValues values) {
        if (values.phase() == MovingStructureCompat.ArrivalPhase.ARRIVED) {
            return "";
        }
        return values.etaTicks() >= 0L && values.etaTicks() < Long.MAX_VALUE / 8L
                ? worldClock(level, values.etaTicks()) : "--:--";
    }

    private static String directionLabel(TrafficValues values) {
        String loop = Component.translatable("screen.minescreen.station.loop").getString();
        String target = values.destination() == null ? "" : values.destination().trim();
        if (target.isBlank() || target.equalsIgnoreCase("@minescreen:loop")
                || target.equalsIgnoreCase(loop)) {
            target = values.nextStop() == null ? "" : values.nextStop().trim();
        }
        return target.isBlank() ? ""
                : Component.translatable("screen.minescreen.traffic.direction_value", target)
                        .getString();
    }

    private static String worldClock(Level level, long offsetTicks) {
        if (level == null) return "--:--";
        long shiftedTicks = Math.floorMod(level.getDayTime() + Math.max(0L, offsetTicks)
                + 6_000L, 24_000L);
        int totalMinutes = (int) (shiftedTicks * 1_440L / 24_000L);
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        return twoDigits(hours) + ":" + twoDigits(minutes);
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
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

    private void drawTrainHeading(TrafficValues values, int width, float y, int fallbackColor,
            PoseStack poseStack, MultiBufferSource buffers) {
        String service = values.serviceType();
        if (service.isBlank()) {
            drawLine(values.line(), width, y, fallbackColor, poseStack, buffers);
            return;
        }
        String identifier = trainIdentifier(values.line(), service);
        String consist = values.carriageCount() > 0
                ? Component.translatable("screen.minescreen.carriage_info.consist",
                        values.carriageCount()).getString() : "";
        String carriage = values.carriageNumber() > 0
                ? Component.translatable("screen.minescreen.carriage_info.car_number",
                        values.carriageNumber()).getString() : "";
        String destination = values.destination().isBlank() ? ""
                : Component.translatable("screen.minescreen.carriage_info.to",
                        values.destination()).getString();
        String complete = String.join("  ·  ", java.util.stream.Stream
                .of(identifier.isBlank() ? service : service + "  " + identifier,
                        carriage, consist, destination)
                .filter(value -> value != null && !value.isBlank()).toList());
        String clipped = font.plainSubstrByWidth(complete, Math.max(1, width - 8));
        int serviceLength = Math.min(service.length(), clipped.length());
        String shownService = clipped.substring(0, serviceLength);
        String shownIdentifier = clipped.substring(serviceLength);
        float totalWidth = font.width(clipped);
        float x = (width - totalWidth) * 0.5F;
        font.drawInBatch(Component.literal(shownService).getVisualOrderText(), x, y,
                serviceColor(service, fallbackColor), false, poseStack.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        if (!shownIdentifier.isEmpty()) {
            font.drawInBatch(Component.literal(shownIdentifier).getVisualOrderText(),
                    x + font.width(shownService), y, fallbackColor, false,
                    poseStack.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                    LightTexture.FULL_BRIGHT);
        }
    }

    private static String trainIdentifier(String trainName, String serviceType) {
        String name = trainName == null ? "" : trainName.trim();
        String service = serviceType == null ? "" : serviceType.trim();
        if (!service.isBlank() && name.length() >= service.length()
                && name.regionMatches(true, 0, service, 0, service.length())) {
            return name.substring(service.length()).trim();
        }
        return name;
    }

    private static void logicalLine(VertexConsumer consumer, Matrix4f pose, double x1, double y1,
            double x2, double y2, double width, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-5D) return;
        double px = -dy / length * width * 0.5D;
        double py = dx / length * width * 0.5D;
        logicalVertex(consumer, pose, x2 + px, y2 + py, color);
        logicalVertex(consumer, pose, x2 - px, y2 - py, color);
        logicalVertex(consumer, pose, x1 - px, y1 - py, color);
        logicalVertex(consumer, pose, x1 + px, y1 + py, color);
    }

    private static void logicalQuad(VertexConsumer consumer, Matrix4f pose, double x1, double y1,
            double x2, double y2, int color) {
        logicalVertex(consumer, pose, x1, y2, color);
        logicalVertex(consumer, pose, x2, y2, color);
        logicalVertex(consumer, pose, x2, y1, color);
        logicalVertex(consumer, pose, x1, y1, color);
    }

    private static void logicalEllipse(VertexConsumer consumer, Matrix4f pose, double centerX,
            double centerY, double radiusX, double radiusY, int color) {
        if (radiusX <= 0.0D || radiusY <= 0.0D) return;
        int segments = 24;
        for (int index = 0; index < segments; index++) {
            double angle1 = Math.PI * 2.0D * index / segments;
            double angle2 = Math.PI * 2.0D * (index + 1) / segments;
            logicalVertex(consumer, pose, centerX, centerY, color);
            logicalVertex(consumer, pose, centerX + Math.cos(angle2) * radiusX,
                    centerY + Math.sin(angle2) * radiusY, color);
            logicalVertex(consumer, pose, centerX + Math.cos(angle1) * radiusX,
                    centerY + Math.sin(angle1) * radiusY, color);
            logicalVertex(consumer, pose, centerX, centerY, color);
        }
    }

    private static void logicalVertex(VertexConsumer consumer, Matrix4f pose, double x, double y,
            int color) {
        consumer.addVertex(pose, (float) x, (float) y, 0.001F).setColor(color)
                .setLight(LightTexture.FULL_BRIGHT);
    }

    private void drawLine(String text, int width, float y, int color, PoseStack poseStack,
            MultiBufferSource buffers) {
        String clipped = font.plainSubstrByWidth(text == null ? "" : text, width - 8);
        FormattedCharSequence line = Component.literal(clipped).getVisualOrderText();
        float x = (width - font.width(line)) * 0.5F;
        font.drawInBatch(line, x, y, color, false, poseStack.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
    }

    private void drawFitted(String text, float x, float y, float maximumWidth, int color,
            float scale, PoseStack poseStack, MultiBufferSource buffers) {
        if (text == null || text.isBlank() || maximumWidth <= 1.0F || scale <= 0.0F) return;
        String clipped = font.plainSubstrByWidth(text,
                Math.max(1, (int) Math.floor(maximumWidth / scale)));
        poseStack.pushPose();
        poseStack.translate(x, y, 0.02F);
        poseStack.scale(scale, scale, 1.0F);
        font.drawInBatch(Component.literal(clipped).getVisualOrderText(), 0, 0, color, false,
                poseStack.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                LightTexture.FULL_BRIGHT);
        poseStack.popPose();
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

    private static Vec3 facePoint(Vec3 bottom, Vec3 ridge, Vec3 horizontal, int length,
            float u, float v, Vec3 outward) {
        return bottom.add(horizontal.normalize().scale(u * length))
                .add(ridge.subtract(bottom).scale(1.0F - v))
                .add(outward.normalize().scale(0.005D));
    }

    private static Vec3 doorFacePoint(Vec3 bottom, Vec3 top, Vec3 horizontal, int length,
            float u, float v, Vec3 outward) {
        return bottom.add(horizontal.normalize().scale(u * length))
                .add(top.subtract(bottom).scale(v))
                .add(outward.normalize().scale(0.005D));
    }

    private static Matrix4f basis(Vec3 origin, Vec3 x, Vec3 y, Vec3 z, double sx, double sy) {
        return new Matrix4f().zero()
                .m00((float) (x.x * sx)).m01((float) (x.y * sx)).m02((float) (x.z * sx))
                .m10((float) (y.x * sy)).m11((float) (y.y * sy)).m12((float) (y.z * sy))
                .m20((float) (z.x * sx)).m21((float) (z.y * sx)).m22((float) (z.z * sx))
                .m30((float) origin.x).m31((float) origin.y).m32((float) origin.z).m33(1);
    }

    private static void renderChassisFace(PoseStack poseStack, MultiBufferSource buffers,
            Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        solidQuad(buffers.getBuffer(RenderType.textBackground()),
                poseStack.last().pose(), a, b, c, d, 0xFF111820);
    }

    private static void solidQuad(VertexConsumer consumer, Matrix4f pose, Vec3 a, Vec3 b, Vec3 c,
            Vec3 d, int color) {
        for (Vec3 point : new Vec3[]{a, b, c, d}) {
            consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                    .setColor(color).setLight(LightTexture.FULL_BRIGHT);
        }
    }

    private static void texturedQuad(VertexConsumer consumer, Matrix4f pose, Vec3 bottomLeft,
            Vec3 bottomRight, Vec3 topRight, Vec3 topLeft, float u0, float u1, float vTop,
            float vBottom) {
        textureVertex(consumer, pose, bottomLeft, u0, vBottom);
        textureVertex(consumer, pose, bottomRight, u1, vBottom);
        textureVertex(consumer, pose, topRight, u1, vTop);
        textureVertex(consumer, pose, topLeft, u0, vTop);
    }

    private static void textureVertex(VertexConsumer consumer, Matrix4f pose, Vec3 point,
            float u, float v) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(255, 255, 255, 255).setUv(u, v);
    }

    @Override
    public AABB getRenderBoundingBox(CeilingDisplayBlockEntity display) {
        CeilingDisplayGroup group = display.getLevel() == null ? null
                : CeilingDisplayGroupResolver.resolve(display.getLevel(), display.getBlockPos());
        if (group != null) return group.bounds().inflate(0.1D);
        DoorLcdGroup doorGroup = display.getLevel() == null ? null
                : DoorLcdGroupResolver.resolve(display.getLevel(), display.getBlockPos());
        return doorGroup == null ? BlockEntityRenderer.super.getRenderBoundingBox(display)
                : doorGroup.bounds().inflate(0.1D);
    }
}
