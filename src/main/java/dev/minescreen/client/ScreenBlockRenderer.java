package dev.minescreen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;

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
        ScreenRenderSource source = ScreenContentManager.sourceFor(group);
        dev.minescreen.client.content.ClientScreenProfile profile =
                ScreenContentManager.profile(group.groupId());
        source.bind();
        RenderType renderType = source.renderType();
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        if (group.legacyAnchor()) {
            Vec3 origin = blockEntity.screenOrigin();
            float u0 = source.flipHorizontal() ? 1.0F : 0.0F;
            float u1 = source.flipHorizontal() ? 0.0F : 1.0F;
            quad(consumer, poseStack.last(), origin,
                    ScreenGeometry.right(blockEntity.facing()).scale(blockEntity.getScreenWidth()),
                    ScreenGeometry.up().scale(blockEntity.getScreenHeight()),
                    u0, u1, 0.0F, 1.0F, 255);
            return;
        }

        Vec3 groupOrigin = ScreenGeometry.origin(group.origin(), group.facing())
                .subtract(Vec3.atLowerCornerOf(blockEntity.getBlockPos()));
        Vec3 right = ScreenGeometry.right(group.facing());
        Vec3 up = ScreenGeometry.up();
        net.minecraft.core.Direction rightDirection = ScreenGeometry.rightDirection(group.facing());
        PoseStack.Pose pose = poseStack.last();
        int originHorizontal = horizontalCoordinate(group.origin(), rightDirection);
        // Render only physical tiles. Row/column come from world coordinates, so HashSet iteration
        // order and placement order cannot turn a valid tile into a black placeholder.
        java.util.List<net.minecraft.core.BlockPos> tiles = group.tiles().stream()
                .sorted(java.util.Comparator
                        .comparingInt((net.minecraft.core.BlockPos pos) -> pos.getY() - group.origin().getY())
                        .thenComparingInt(pos -> horizontalCoordinate(pos, rightDirection)
                                - originHorizontal))
                .toList();
        for (net.minecraft.core.BlockPos tile : tiles) {
            // The group snapshot is rebuilt on client ticks. Packet/chunk changes can happen
            // between that rebuild and this BER call, so validate every submitted quad against
            // the live world to prevent a removed tile from leaving a floating "ghost" image.
            if (!ScreenTileIndex.isLive(level, tile, group.facing())) {
                continue;
            }
            if (profile.disabledTiles.contains(tile.asLong())) {
                continue;
            }
            int column = horizontalCoordinate(tile, rightDirection) - originHorizontal;
            int row = tile.getY() - group.origin().getY();
            if (column < 0 || column >= group.columns() || row < 0 || row >= group.rows()) {
                continue;
            }
            Vec3 cellOrigin = groupOrigin.add(right.scale(column)).add(up.scale(row));
            float u0 = column / (float) group.columns();
            float u1 = (column + 1) / (float) group.columns();
            if (source.flipHorizontal()) {
                // Optional source-specific correction while preserving continuity between tiles.
                u0 = 1.0F - u0;
                u1 = 1.0F - u1;
            }
            float vBottom = 1.0F - row / (float) group.rows();
            float vTop = 1.0F - (row + 1) / (float) group.rows();
            quad(consumer, pose, cellOrigin, right, up, u0, u1, vTop, vBottom, 255);
        }
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

    private static int horizontalCoordinate(net.minecraft.core.BlockPos pos,
            net.minecraft.core.Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
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
