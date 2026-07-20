package dev.minescreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** 1.21.1 immediate GUI quad using Tesselator.begin, never the removed getBuilder chain. */
public final class ScreenPreviewRenderer {
    private ScreenPreviewRenderer() {
    }

    public static void draw(GuiGraphics graphics, ScreenGroup group, int left, int top,
            int width, int height) {
        // Flush GuiGraphics' buffered panel primitives before issuing the immediate preview quad;
        // otherwise a later flush can draw the panel over the preview despite sharing GUI Z=0.
        graphics.flush();
        ScreenRenderSource source = ScreenContentManager.sourceFor(group);
        source.bind();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        float uLeft = source.flipHorizontal() ? 1.0F : 0.0F;
        float uRight = source.flipHorizontal() ? 0.0F : 1.0F;
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, left, top + height, 0.0F).setUv(uLeft, 1.0F);
        builder.addVertex(pose, left + width, top + height, 0.0F).setUv(uRight, 1.0F);
        builder.addVertex(pose, left + width, top, 0.0F).setUv(uRight, 0.0F);
        builder.addVertex(pose, left, top, 0.0F).setUv(uLeft, 0.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        // Keep the immediate RenderSystem operation from leaking into EditBox/Button rendering.
        graphics.flush();
    }
}
