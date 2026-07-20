package dev.minescreen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.minescreen.ComputerBlock;
import dev.minescreen.ComputerBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
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
        ScreenGroup group = linkedGroup(computer, level);
        if (group == null) {
            return;
        }
        ScreenContentManager.requestHostKeepAlive(group.groupId());
        ScreenRenderSource source = ScreenContentManager.sourceFor(group);
        source.bind();
        VertexConsumer consumer = buffers.getBuffer(source.renderType());
        Direction facing = computer.getBlockState().getValue(ComputerBlock.FACING);
        Vec3 face = ScreenGeometry.origin(computer.getBlockPos(), facing)
                .subtract(Vec3.atLowerCornerOf(computer.getBlockPos()));
        Vec3 right = ScreenGeometry.right(facing);
        Vec3 up = ScreenGeometry.up();
        // The custom controller's display is inset two pixels from the block boundary and framed
        // by its raised monitor housing. Keep the live preview inside that physical glass area.
        Vec3 origin = face.add(ScreenGeometry.normal(facing).scale(-0.118D))
                .add(right.scale(0.14D)).add(up.scale(0.38D));
        float u0 = source.flipHorizontal() ? 1.0F : 0.0F;
        float u1 = source.flipHorizontal() ? 0.0F : 1.0F;
        PoseStack.Pose pose = poseStack.last();
        vertex(consumer, pose, origin, u0, 1.0F);
        vertex(consumer, pose, origin.add(right.scale(0.72D)), u1, 1.0F);
        vertex(consumer, pose, origin.add(right.scale(0.72D)).add(up.scale(0.44D)), u1, 0.0F);
        vertex(consumer, pose, origin.add(up.scale(0.44D)), u0, 0.0F);
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
