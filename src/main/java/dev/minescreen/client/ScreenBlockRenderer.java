package dev.minescreen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenRegionLayout;
import dev.minescreen.client.content.ScreenRotation;

/**
 * Renders the entire configured plane from one anchor block. The texture is registered once and
 * the vertex data is submitted each frame without allocating a texture or touching OpenGL upload
 * APIs. Video/VNC sessions update their existing DynamicTexture in place.
 */
public final class ScreenBlockRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    public ScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!(blockEntity.getLevel() instanceof ClientLevel level)
                || !ScreenTileIndex.isLive(level, blockEntity)) {
            return;
        }
        ScreenGroup group = ScreenGroupManager.groupAt(blockEntity);
        if (group == null || !group.master().equals(blockEntity.getBlockPos())) {
            return;
        }
        dev.minescreen.client.content.ClientScreenProfile profile =
                ScreenContentManager.profile(group.groupId());
        ScreenContentManager.PanoramaRender panorama = ScreenContentManager.panoramaFor(group);
        if (panorama != null) {
            if (group.legacyAnchor()) {
                renderLegacyPanorama(blockEntity, panorama, poseStack.last(), bufferSource);
            } else {
                renderPanorama(level, blockEntity, group, profile, panorama, poseStack.last(),
                        bufferSource);
            }
            return;
        }
        if (group.legacyAnchor()) {
            ScreenRenderSource source = ScreenContentManager.sourceFor(group);
            Vec3 origin = blockEntity.screenOrigin();
            Vec3 right = ScreenGeometry.right(blockEntity.facing());
            Vec3 up = ScreenGeometry.up(blockEntity.facing());
            for (ScreenRenderSource.Pane pane : source.panes()) {
                pane.bind();
                VertexConsumer consumer = bufferSource.getBuffer(pane.renderType());
                Vec3 paneOrigin = origin.add(right.scale(pane.left() * blockEntity.getScreenWidth()))
                        .add(up.scale((1.0F - pane.bottom()) * blockEntity.getScreenHeight()));
                float u0 = pane.flipHorizontal() ? 1.0F : 0.0F;
                float u1 = pane.flipHorizontal() ? 0.0F : 1.0F;
                quad(consumer, poseStack.last(), paneOrigin,
                        right.scale((pane.right() - pane.left()) * blockEntity.getScreenWidth()),
                        up.scale((pane.bottom() - pane.top()) * blockEntity.getScreenHeight()),
                        u0, u1, 0.0F, 1.0F, 255);
            }
            return;
        }

        Vec3 groupOrigin = ScreenGeometry.origin(group.origin(), group.facing())
                .subtract(Vec3.atLowerCornerOf(blockEntity.getBlockPos()));
        Vec3 right = ScreenGeometry.right(group.facing());
        Vec3 up = ScreenGeometry.up(group.facing());
        net.minecraft.core.Direction rightDirection = ScreenGeometry.rightDirection(group.facing());
        net.minecraft.core.Direction upDirection = ScreenGeometry.upDirection(group.facing());
        PoseStack.Pose pose = poseStack.last();
        int originHorizontal = ScreenGeometry.coordinate(group.origin(), rightDirection);
        int originVertical = ScreenGeometry.coordinate(group.origin(), upDirection);
        int rotation = ScreenHostNetworkManager.rotationFor(group);
        for (ScreenRegionLayout.Canvas canvas : ScreenRegionLayout.canvases(group, profile)) {
            ScreenRenderSource source = ScreenContentManager.sourceFor(group, canvas.regionId());
            java.util.List<net.minecraft.core.BlockPos> tiles = canvas.group().tiles().stream()
                    .sorted(java.util.Comparator
                            .comparingInt((net.minecraft.core.BlockPos pos) ->
                                    ScreenGeometry.coordinate(pos, upDirection) - originVertical)
                            .thenComparingInt(pos -> ScreenGeometry.coordinate(pos, rightDirection)
                                    - originHorizontal))
                    .toList();
            if (rotation != 0) {
                renderRotatedCanvas(level, groupOrigin, right, up, group, profile, canvas, source,
                        tiles, rightDirection, upDirection, originHorizontal, originVertical,
                        rotation, pose, bufferSource);
                continue;
            }
            for (ScreenRenderSource.Pane pane : source.panes()) {
                pane.bind();
                VertexConsumer consumer = bufferSource.getBuffer(pane.renderType());
                for (net.minecraft.core.BlockPos tile : tiles) {
                // Validate each submitted tile against live world state. Missing locations in an
                // irregular or cable-linked bounding rectangle submit no geometry at all.
                if (!ScreenTileIndex.isLive(level, tile, group.facing())
                        || profile.disabledTiles.contains(tile.asLong())) {
                    continue;
                }
                int globalColumn = ScreenGeometry.coordinate(tile, rightDirection) - originHorizontal;
                int globalRow = ScreenGeometry.coordinate(tile, upDirection) - originVertical;
                int column = globalColumn - canvas.minColumn();
                int row = globalRow - canvas.minRow();
                if (column < 0 || column >= canvas.columns()
                        || row < 0 || row >= canvas.rows()) {
                    continue;
                }
                float tileLeft = column / (float) canvas.columns();
                float tileRight = (column + 1) / (float) canvas.columns();
                float tileBottom = 1.0F - row / (float) canvas.rows();
                float tileTop = 1.0F - (row + 1) / (float) canvas.rows();
                float left = Math.max(tileLeft, pane.left());
                float rightEdge = Math.min(tileRight, pane.right());
                float top = Math.max(tileTop, pane.top());
                float bottom = Math.min(tileBottom, pane.bottom());
                if (left >= rightEdge || top >= bottom) {
                    continue;
                }
                Vec3 paneOrigin = groupOrigin.add(right.scale(canvas.minColumn()
                        + left * canvas.columns())).add(up.scale(canvas.minRow()
                                + (1.0F - bottom) * canvas.rows()));
                float u0 = (left - pane.left()) / (pane.right() - pane.left());
                float u1 = (rightEdge - pane.left()) / (pane.right() - pane.left());
                if (pane.flipHorizontal()) {
                    u0 = 1.0F - u0;
                    u1 = 1.0F - u1;
                }
                float vTop = (top - pane.top()) / (pane.bottom() - pane.top());
                float vBottom = (bottom - pane.top()) / (pane.bottom() - pane.top());
                quad(consumer, pose, paneOrigin,
                        right.scale((rightEdge - left) * canvas.columns()),
                        up.scale((bottom - top) * canvas.rows()),
                        u0, u1, vTop, vBottom, 255);
                }
            }
        }
    }

    private static void renderRotatedCanvas(ClientLevel level, Vec3 groupOrigin, Vec3 rightVector,
            Vec3 upVector, ScreenGroup group,
            dev.minescreen.client.content.ClientScreenProfile profile,
            ScreenRegionLayout.Canvas canvas, ScreenRenderSource source,
            java.util.List<net.minecraft.core.BlockPos> tiles,
            net.minecraft.core.Direction rightDirection,
            net.minecraft.core.Direction upDirection, int originHorizontal, int originVertical,
            int rotation, PoseStack.Pose pose, MultiBufferSource buffers) {
        for (ScreenRenderSource.Pane pane : source.panes()) {
            pane.bind();
            VertexConsumer consumer = buffers.getBuffer(pane.renderType());
            for (net.minecraft.core.BlockPos tile : tiles) {
                if (!ScreenTileIndex.isLive(level, tile, group.facing())
                        || profile.disabledTiles.contains(tile.asLong())) {
                    continue;
                }
                int column = ScreenGeometry.coordinate(tile, rightDirection) - originHorizontal
                        - canvas.minColumn();
                int row = ScreenGeometry.coordinate(tile, upDirection) - originVertical
                        - canvas.minRow();
                if (column < 0 || column >= canvas.columns() || row < 0 || row >= canvas.rows()) {
                    continue;
                }
                float physicalLeft = column / (float) canvas.columns();
                float physicalRight = (column + 1) / (float) canvas.columns();
                float physicalTop = 1.0F - (row + 1) / (float) canvas.rows();
                float physicalBottom = 1.0F - row / (float) canvas.rows();
                float contentLeft = cornerMinU(physicalLeft, physicalRight, physicalTop,
                        physicalBottom, rotation);
                float contentRight = cornerMaxU(physicalLeft, physicalRight, physicalTop,
                        physicalBottom, rotation);
                float contentTop = cornerMinV(physicalLeft, physicalRight, physicalTop,
                        physicalBottom, rotation);
                float contentBottom = cornerMaxV(physicalLeft, physicalRight, physicalTop,
                        physicalBottom, rotation);
                float left = Math.max(contentLeft, pane.left());
                float right = Math.min(contentRight, pane.right());
                float top = Math.max(contentTop, pane.top());
                float bottom = Math.min(contentBottom, pane.bottom());
                if (left >= right || top >= bottom) {
                    continue;
                }
                float u0 = (left - pane.left()) / (pane.right() - pane.left());
                float u1 = (right - pane.left()) / (pane.right() - pane.left());
                if (pane.flipHorizontal()) {
                    float swap = u0;
                    u0 = 1.0F - u1;
                    u1 = 1.0F - swap;
                }
                float vTop = (top - pane.top()) / (pane.bottom() - pane.top());
                float vBottom = (bottom - pane.top()) / (pane.bottom() - pane.top());
                Vec3 bottomLeft = rotatedCanvasPoint(groupOrigin, rightVector, upVector, canvas,
                        left, bottom, rotation);
                Vec3 bottomRight = rotatedCanvasPoint(groupOrigin, rightVector, upVector, canvas,
                        right, bottom, rotation);
                Vec3 topRight = rotatedCanvasPoint(groupOrigin, rightVector, upVector, canvas,
                        right, top, rotation);
                Vec3 topLeft = rotatedCanvasPoint(groupOrigin, rightVector, upVector, canvas,
                        left, top, rotation);
                arbitraryQuad(consumer, pose, bottomLeft, bottomRight, topRight, topLeft,
                        u0, u1, vTop, vBottom, 255);
            }
        }
    }

    private static Vec3 rotatedCanvasPoint(Vec3 origin, Vec3 right, Vec3 up,
            ScreenRegionLayout.Canvas canvas, float contentU, float contentV, int rotation) {
        float physicalU = ScreenRotation.physicalU(contentU, contentV, rotation);
        float physicalV = ScreenRotation.physicalV(contentU, contentV, rotation);
        return origin.add(right.scale(canvas.minColumn() + physicalU * canvas.columns()))
                .add(up.scale(canvas.minRow() + (1.0F - physicalV) * canvas.rows()));
    }

    private static float cornerMinU(float left, float right, float top, float bottom, int rotation) {
        return Math.min(Math.min(ScreenRotation.contentU(left, top, rotation),
                ScreenRotation.contentU(right, top, rotation)), Math.min(
                        ScreenRotation.contentU(right, bottom, rotation),
                        ScreenRotation.contentU(left, bottom, rotation)));
    }

    private static float cornerMaxU(float left, float right, float top, float bottom, int rotation) {
        return Math.max(Math.max(ScreenRotation.contentU(left, top, rotation),
                ScreenRotation.contentU(right, top, rotation)), Math.max(
                        ScreenRotation.contentU(right, bottom, rotation),
                        ScreenRotation.contentU(left, bottom, rotation)));
    }

    private static float cornerMinV(float left, float right, float top, float bottom, int rotation) {
        return Math.min(Math.min(ScreenRotation.contentV(left, top, rotation),
                ScreenRotation.contentV(right, top, rotation)), Math.min(
                        ScreenRotation.contentV(right, bottom, rotation),
                        ScreenRotation.contentV(left, bottom, rotation)));
    }

    private static float cornerMaxV(float left, float right, float top, float bottom, int rotation) {
        return Math.max(Math.max(ScreenRotation.contentV(left, top, rotation),
                ScreenRotation.contentV(right, top, rotation)), Math.max(
                        ScreenRotation.contentV(right, bottom, rotation),
                        ScreenRotation.contentV(left, bottom, rotation)));
    }

    private static void renderLegacyPanorama(ScreenBlockEntity blockEntity,
            ScreenContentManager.PanoramaRender panorama, PoseStack.Pose pose,
            MultiBufferSource buffers) {
        Vec3 origin = blockEntity.screenOrigin();
        Vec3 right = blockEntity.screenRight();
        Vec3 up = blockEntity.screenUp();
        ScreenHostNetworkManager.Surface surface = panorama.surface();
        for (ScreenRenderSource.Pane pane : panorama.source().panes()) {
            float globalLeft = Math.max(surface.left(), pane.left());
            float globalRight = Math.min(surface.right(), pane.right());
            float globalTop = Math.max(surface.top(), pane.top());
            float globalBottom = Math.min(surface.bottom(), pane.bottom());
            if (globalLeft >= globalRight || globalTop >= globalBottom) {
                continue;
            }
            float u0 = (globalLeft - pane.left()) / (pane.right() - pane.left());
            float u1 = (globalRight - pane.left()) / (pane.right() - pane.left());
            if (pane.flipHorizontal()) {
                u0 = 1.0F - u0;
                u1 = 1.0F - u1;
            }
            float vTop = (globalTop - pane.top()) / (pane.bottom() - pane.top());
            float vBottom = (globalBottom - pane.top()) / (pane.bottom() - pane.top());
            Vec3 bottomLeft = legacyPoint(origin, right, up, blockEntity, surface,
                    globalLeft, globalBottom);
            Vec3 bottomRight = legacyPoint(origin, right, up, blockEntity, surface,
                    globalRight, globalBottom);
            Vec3 topRight = legacyPoint(origin, right, up, blockEntity, surface,
                    globalRight, globalTop);
            Vec3 topLeft = legacyPoint(origin, right, up, blockEntity, surface,
                    globalLeft, globalTop);
            pane.bind();
            arbitraryQuad(buffers.getBuffer(pane.renderType()), pose, bottomLeft, bottomRight,
                    topRight, topLeft, u0, u1, vTop, vBottom, 255);
        }
    }

    private static Vec3 legacyPoint(Vec3 origin, Vec3 right, Vec3 up,
            ScreenBlockEntity blockEntity, ScreenHostNetworkManager.Surface surface,
            float canvasU, float canvasV) {
        float physicalU = surface.physicalU(canvasU, canvasV);
        float physicalV = surface.physicalV(canvasU, canvasV);
        return origin.add(right.scale(physicalU * blockEntity.getScreenWidth()))
                .add(up.scale((1.0F - physicalV) * blockEntity.getScreenHeight()));
    }

    /** Maps one physical plane onto its unit-preserving slice of the shared host content canvas. */
    private static void renderPanorama(ClientLevel level, ScreenBlockEntity blockEntity,
            ScreenGroup group, dev.minescreen.client.content.ClientScreenProfile profile,
            ScreenContentManager.PanoramaRender panorama, PoseStack.Pose pose,
            MultiBufferSource buffers) {
        Vec3 groupOrigin = ScreenGeometry.origin(group.origin(), group.facing())
                .subtract(Vec3.atLowerCornerOf(blockEntity.getBlockPos()));
        Vec3 rightVector = ScreenGeometry.right(group.facing());
        Vec3 upVector = ScreenGeometry.up(group.facing());
        net.minecraft.core.Direction right = ScreenGeometry.rightDirection(group.facing());
        net.minecraft.core.Direction up = ScreenGeometry.upDirection(group.facing());
        int originRight = ScreenGeometry.coordinate(group.origin(), right);
        int originUp = ScreenGeometry.coordinate(group.origin(), up);
        ScreenHostNetworkManager.Surface surface = panorama.surface();
        for (ScreenRenderSource.Pane pane : panorama.source().panes()) {
            for (net.minecraft.core.BlockPos tile : group.tiles()) {
                if (!ScreenTileIndex.isLive(level, tile, group.facing())
                        || profile.disabledTiles.contains(tile.asLong())) {
                    continue;
                }
                int column = ScreenGeometry.coordinate(tile, right) - originRight;
                int row = ScreenGeometry.coordinate(tile, up) - originUp;
                float localLeft = column / (float) group.columns();
                float localRight = (column + 1) / (float) group.columns();
                float localBottom = 1.0F - row / (float) group.rows();
                float localTop = 1.0F - (row + 1) / (float) group.rows();
                float tileGlobalLeft = Math.min(Math.min(
                        surface.canvasU(localLeft, localTop),
                        surface.canvasU(localRight, localTop)), Math.min(
                                surface.canvasU(localRight, localBottom),
                                surface.canvasU(localLeft, localBottom)));
                float tileGlobalRight = Math.max(Math.max(
                        surface.canvasU(localLeft, localTop),
                        surface.canvasU(localRight, localTop)), Math.max(
                                surface.canvasU(localRight, localBottom),
                                surface.canvasU(localLeft, localBottom)));
                float tileGlobalTop = Math.min(Math.min(
                        surface.canvasV(localLeft, localTop),
                        surface.canvasV(localRight, localTop)), Math.min(
                                surface.canvasV(localRight, localBottom),
                                surface.canvasV(localLeft, localBottom)));
                float tileGlobalBottom = Math.max(Math.max(
                        surface.canvasV(localLeft, localTop),
                        surface.canvasV(localRight, localTop)), Math.max(
                                surface.canvasV(localRight, localBottom),
                                surface.canvasV(localLeft, localBottom)));
                float globalLeft = Math.max(tileGlobalLeft, pane.left());
                float globalRight = Math.min(tileGlobalRight, pane.right());
                float globalTop = Math.max(tileGlobalTop, pane.top());
                float globalBottom = Math.min(tileGlobalBottom, pane.bottom());
                if (globalLeft >= globalRight || globalTop >= globalBottom) {
                    continue;
                }
                float u0 = (globalLeft - pane.left()) / (pane.right() - pane.left());
                float u1 = (globalRight - pane.left()) / (pane.right() - pane.left());
                if (pane.flipHorizontal()) {
                    u0 = 1.0F - u0;
                    u1 = 1.0F - u1;
                }
                float vTop = (globalTop - pane.top()) / (pane.bottom() - pane.top());
                float vBottom = (globalBottom - pane.top()) / (pane.bottom() - pane.top());
                Vec3 bottomLeft = panoramaPoint(groupOrigin, rightVector, upVector, group, surface,
                        globalLeft, globalBottom);
                Vec3 bottomRight = panoramaPoint(groupOrigin, rightVector, upVector, group, surface,
                        globalRight, globalBottom);
                Vec3 topRight = panoramaPoint(groupOrigin, rightVector, upVector, group, surface,
                        globalRight, globalTop);
                Vec3 topLeft = panoramaPoint(groupOrigin, rightVector, upVector, group, surface,
                        globalLeft, globalTop);
                pane.bind();
                VertexConsumer consumer = buffers.getBuffer(pane.renderType());
                arbitraryQuad(consumer, pose, bottomLeft, bottomRight, topRight, topLeft,
                        u0, u1, vTop, vBottom, 255);
            }
        }
    }

    private static Vec3 panoramaPoint(Vec3 origin, Vec3 right, Vec3 up, ScreenGroup group,
            ScreenHostNetworkManager.Surface surface, float canvasU, float canvasV) {
        float physicalU = surface.physicalU(canvasU, canvasV);
        float physicalV = surface.physicalV(canvasU, canvasV);
        return origin.add(right.scale(physicalU * group.columns()))
                .add(up.scale((1.0F - physicalV) * group.rows()));
    }

    private static void arbitraryQuad(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, Vec3 topLeft,
            float u0, float u1, float vTop, float vBottom, int color) {
        vertex(consumer, pose, bottomLeft, u0, vBottom, color);
        vertex(consumer, pose, bottomRight, u1, vBottom, color);
        vertex(consumer, pose, topRight, u1, vTop, color);
        vertex(consumer, pose, topLeft, u0, vTop, color);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 origin,
            Vec3 right, Vec3 up, float u0, float u1, float vTop, float vBottom, int color) {
        vertex(consumer, pose, origin, u0, vBottom, color);
        vertex(consumer, pose, origin.add(right), u1, vBottom, color);
        vertex(consumer, pose, origin.add(right).add(up), u1, vTop, color);
        vertex(consumer, pose, origin.add(up), u0, vTop, color);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 position,
            float u, float v, int color) {
        consumer.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setColor(color, color, color, 255)
                .setUv(u, v);
    }

    @Override
    public AABB getRenderBoundingBox(ScreenBlockEntity blockEntity) {
        ScreenGroup group = ScreenGroupManager.groupAt(blockEntity);
        if (group != null && group.master().equals(blockEntity.getBlockPos())) {
            return group.bounds();
        }
        Vec3 origin = Vec3.atLowerCornerOf(blockEntity.getBlockPos()).add(blockEntity.screenOrigin());
        Vec3 opposite = origin.add(blockEntity.screenRight().scale(blockEntity.getScreenWidth()))
                .add(blockEntity.screenUp().scale(blockEntity.getScreenHeight()));
        return new AABB(origin, opposite).inflate(0.05D);
    }

    @Override
    public int getViewDistance() {
        return dev.minescreen.MineScreenConfig.MAX_RENDER_DISTANCE.get();
    }
}
