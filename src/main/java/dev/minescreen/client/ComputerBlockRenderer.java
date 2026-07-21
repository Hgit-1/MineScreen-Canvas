package dev.minescreen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.minescreen.ComputerBlock;
import dev.minescreen.ComputerBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenRegionLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Powered host face that continues rendering a reduced live preview after its GUI closes. */
public final class ComputerBlockRenderer implements BlockEntityRenderer<ComputerBlockEntity> {
    public ComputerBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ComputerBlockEntity computer, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!computer.powered() || !(computer.getLevel() instanceof ClientLevel level)) {
            return;
        }
        ScreenHostNetworkManager.HostNetwork network =
                ScreenHostNetworkManager.networkAt(level, computer.getBlockPos());
        ScreenGroup group = network == null ? linkedGroup(computer, level) : network.rootGroup();
        if (group == null) {
            return;
        }
        if (network != null && network.panoramic()) {
            ScreenContentManager.PanoramaRender panorama = ScreenContentManager.panoramaFor(network);
            if (panorama != null) {
                ScreenContentManager.requestHostKeepAlive(network.canvas().groupId());
                renderFullSource(computer, poseStack, buffers, panorama.source());
                return;
            }
        }
        if (network != null) {
            for (ScreenGroup member : network.groups()) {
                ScreenContentManager.requestHostKeepAlive(member.groupId());
                ScreenContentManager.sourceFor(member);
            }
        } else {
            ScreenContentManager.requestHostKeepAlive(group.groupId());
        }
        Direction facing = computer.getBlockState().getValue(ComputerBlock.FACING);
        Vec3 face = ScreenGeometry.origin(computer.getBlockPos(), facing)
                .subtract(Vec3.atLowerCornerOf(computer.getBlockPos()));
        Vec3 right = ScreenGeometry.right(facing);
        Vec3 up = ScreenGeometry.up(facing);
        // The custom controller's display is inset two pixels from the block boundary and framed
        // by its raised monitor housing. Keep the live preview inside that physical glass area.
        Vec3 origin = face.add(ScreenGeometry.normal(facing).scale(-0.118D))
                .add(right.scale(0.14D)).add(up.scale(0.38D));
        PoseStack.Pose pose = poseStack.last();
        if (group.legacyAnchor()) {
            ScreenRenderSource source = ScreenContentManager.sourceFor(group);
            for (ScreenRenderSource.Pane pane : source.panes()) {
                pane.bind();
                VertexConsumer consumer = buffers.getBuffer(pane.renderType());
                Vec3 paneOrigin = origin.add(right.scale(0.72D * pane.left()))
                        .add(up.scale(0.44D * (1.0F - pane.bottom())));
                Vec3 paneRight = right.scale(0.72D * (pane.right() - pane.left()));
                Vec3 paneUp = up.scale(0.44D * (pane.bottom() - pane.top()));
                float u0 = pane.flipHorizontal() ? 1.0F : 0.0F;
                float u1 = pane.flipHorizontal() ? 0.0F : 1.0F;
                vertex(consumer, pose, paneOrigin, u0, 1.0F);
                vertex(consumer, pose, paneOrigin.add(paneRight), u1, 1.0F);
                vertex(consumer, pose, paneOrigin.add(paneRight).add(paneUp), u1, 0.0F);
                vertex(consumer, pose, paneOrigin.add(paneUp), u0, 0.0F);
            }
            return;
        }
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        net.minecraft.core.Direction screenRight = ScreenGeometry.rightDirection(group.facing());
        net.minecraft.core.Direction screenUp = ScreenGeometry.upDirection(group.facing());
        int originRight = ScreenGeometry.coordinate(group.origin(), screenRight);
        int originUp = ScreenGeometry.coordinate(group.origin(), screenUp);
        for (ScreenRegionLayout.Canvas canvas : ScreenRegionLayout.canvases(group, profile)) {
            ScreenRenderSource source = ScreenContentManager.sourceFor(group, canvas.regionId());
            for (ScreenRenderSource.Pane pane : source.panes()) {
                for (net.minecraft.core.BlockPos tile : canvas.group().tiles()) {
                    if (profile.disabledTiles.contains(tile.asLong())) {
                        continue;
                    }
                    int globalColumn = ScreenGeometry.coordinate(tile, screenRight) - originRight;
                    int globalRow = ScreenGeometry.coordinate(tile, screenUp) - originUp;
                    int column = globalColumn - canvas.minColumn();
                    int row = globalRow - canvas.minRow();
                    float tileLeft = column / (float) canvas.columns();
                    float tileRight = (column + 1) / (float) canvas.columns();
                    float tileTop = 1.0F - (row + 1) / (float) canvas.rows();
                    float tileBottom = 1.0F - row / (float) canvas.rows();
                    float localLeft = Math.max(tileLeft, pane.left());
                    float localRight = Math.min(tileRight, pane.right());
                    float localTop = Math.max(tileTop, pane.top());
                    float localBottom = Math.min(tileBottom, pane.bottom());
                    if (localLeft >= localRight || localTop >= localBottom) {
                        continue;
                    }
                    float globalLeft = (canvas.minColumn() + localLeft * canvas.columns())
                            / group.columns();
                    float globalRight = (canvas.minColumn() + localRight * canvas.columns())
                            / group.columns();
                    float globalTop = 1.0F - (canvas.minRow()
                            + (1.0F - localTop) * canvas.rows()) / group.rows();
                    float globalBottom = 1.0F - (canvas.minRow()
                            + (1.0F - localBottom) * canvas.rows()) / group.rows();
                    float u0 = (localLeft - pane.left()) / (pane.right() - pane.left());
                    float u1 = (localRight - pane.left()) / (pane.right() - pane.left());
                    if (pane.flipHorizontal()) {
                        float swap = u0;
                        u0 = 1.0F - u1;
                        u1 = 1.0F - swap;
                    }
                    float v0 = (localTop - pane.top()) / (pane.bottom() - pane.top());
                    float v1 = (localBottom - pane.top()) / (pane.bottom() - pane.top());
                    pane.bind();
                    VertexConsumer consumer = buffers.getBuffer(pane.renderType());
                    Vec3 partOrigin = origin.add(right.scale(0.72D * globalLeft))
                            .add(up.scale(0.44D * (1.0F - globalBottom)));
                    Vec3 partRight = right.scale(0.72D * (globalRight - globalLeft));
                    Vec3 partUp = up.scale(0.44D * (globalBottom - globalTop));
                    vertex(consumer, pose, partOrigin, u0, v1);
                    vertex(consumer, pose, partOrigin.add(partRight), u1, v1);
                    vertex(consumer, pose, partOrigin.add(partRight).add(partUp), u1, v0);
                    vertex(consumer, pose, partOrigin.add(partUp), u0, v0);
                }
            }
        }
    }

    private static void renderFullSource(ComputerBlockEntity computer, PoseStack poseStack,
            MultiBufferSource buffers, ScreenRenderSource source) {
        Direction facing = computer.getBlockState().getValue(ComputerBlock.FACING);
        Vec3 face = ScreenGeometry.origin(computer.getBlockPos(), facing)
                .subtract(Vec3.atLowerCornerOf(computer.getBlockPos()));
        Vec3 right = ScreenGeometry.right(facing);
        Vec3 up = ScreenGeometry.up(facing);
        Vec3 origin = face.add(ScreenGeometry.normal(facing).scale(-0.118D))
                .add(right.scale(0.14D)).add(up.scale(0.38D));
        PoseStack.Pose pose = poseStack.last();
        for (ScreenRenderSource.Pane pane : source.panes()) {
            pane.bind();
            VertexConsumer consumer = buffers.getBuffer(pane.renderType());
            Vec3 paneOrigin = origin.add(right.scale(0.72D * pane.left()))
                    .add(up.scale(0.44D * (1.0F - pane.bottom())));
            Vec3 paneRight = right.scale(0.72D * (pane.right() - pane.left()));
            Vec3 paneUp = up.scale(0.44D * (pane.bottom() - pane.top()));
            float u0 = pane.flipHorizontal() ? 1.0F : 0.0F;
            float u1 = pane.flipHorizontal() ? 0.0F : 1.0F;
            vertex(consumer, pose, paneOrigin, u0, 1.0F);
            vertex(consumer, pose, paneOrigin.add(paneRight), u1, 1.0F);
            vertex(consumer, pose, paneOrigin.add(paneRight).add(paneUp), u1, 0.0F);
            vertex(consumer, pose, paneOrigin.add(paneUp), u0, 0.0F);
        }
    }

    private static ScreenGroup linkedGroup(ComputerBlockEntity computer, ClientLevel level) {
        long gameTime = level.getGameTime();
        ScreenGroup cached = computer.clientLinkedGroupId() == null ? null
                : ScreenGroupManager.group(computer.clientLinkedGroupId());
        if (cached == null || gameTime >= computer.clientNextLinkResolveTick()) {
            cached = ScreenLinkResolver.findGroup(level, computer.getBlockPos());
            computer.setClientLink(cached == null ? null : cached.groupId(), gameTime + 20L);
        }
        return cached;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 point,
            float u, float v) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(255, 255, 255, 255).setUv(u, v);
    }
}
