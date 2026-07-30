package dev.minescreen.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.minescreen.ScreenGeometry;
import dev.minescreen.TrainLightPanelBlock;
import dev.minescreen.TrainLightPanelBlockEntity;
import dev.minescreen.client.compat.MovingStructureCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Pure-white full-bright diagnostic face, independent of sampled world/contraption light. */
public final class TrainLightPanelRenderer
        implements BlockEntityRenderer<TrainLightPanelBlockEntity> {
    public TrainLightPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TrainLightPanelBlockEntity panel, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (panel.isRemoved()) return;
        Direction facing = panel.getBlockState().getValue(TrainLightPanelBlock.FACING);
        if (panel.getLevel() != null && !(panel.getLevel() instanceof ClientLevel)) {
            MovingStructureCompat.illuminateTestPanel(panel.getLevel(), panel.getBlockPos(), facing);
        }
        Vec3 right = ScreenGeometry.right(facing);
        Vec3 up = ScreenGeometry.up(facing);
        Vec3 normal = ScreenGeometry.normal(facing);
        Vec3 origin = ScreenGeometry.origin(panel.getBlockPos(), facing)
                .subtract(Vec3.atLowerCornerOf(panel.getBlockPos()))
                .add(right.scale(1.0D / 16.0D)).add(up.scale(1.0D / 16.0D))
                .add(normal.scale(0.003D));
        Vec3 lowerRight = origin.add(right.scale(14.0D / 16.0D));
        Vec3 upperRight = lowerRight.add(up.scale(14.0D / 16.0D));
        Vec3 upperLeft = origin.add(up.scale(14.0D / 16.0D));
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
        Matrix4f pose = poseStack.last().pose();
        vertex(consumer, pose, origin);
        vertex(consumer, pose, lowerRight);
        vertex(consumer, pose, upperRight);
        vertex(consumer, pose, upperLeft);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Vec3 point) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(255, 255, 255, 255).setLight(LightTexture.FULL_BRIGHT);
    }
}
