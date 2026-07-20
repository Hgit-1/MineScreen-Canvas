package dev.minescreen.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;

/**
 * Renders the complete MineScreen panel into one transparent texture, then composites it once.
 * ModernUI therefore receives media, widget frames, glyphs and EditBox carets as one final layer
 * and cannot apply a later blur to only the text portion.
 */
final class UiLayerCompositor {
    private static TextureTarget target;
    private static boolean composing;

    private UiLayerCompositor() {
    }

    static void compose(GuiGraphics graphics, Runnable layer) {
        if (composing || !RenderSystem.isOnRenderThread()) {
            layer.run();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main.width < 1 || main.height < 1) {
            layer.run();
            return;
        }
        ensureTarget(main.width, main.height);
        graphics.flush();
        composing = true;
        RuntimeException failure = null;
        try {
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            layer.run();
            graphics.flush();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            main.bindWrite(true);
            composing = false;
        }
        if (failure != null) {
            throw failure;
        }
        composite(graphics, target.getColorTextureId());
    }

    private static void ensureTarget(int width, int height) {
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_NEAREST);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_NEAREST);
        }
    }

    private static void composite(GuiGraphics graphics, int textureId) {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);
        float width = graphics.guiWidth();
        float height = graphics.guiHeight();
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, 0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(pose, width, height, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(pose, width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        builder.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
