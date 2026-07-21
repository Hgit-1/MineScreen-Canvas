package dev.minescreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenRegionLayout;
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
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        if (group.legacyAnchor()) {
            ScreenRenderSource source = ScreenContentManager.sourceFor(group);
            for (ScreenRenderSource.Pane pane : source.panes()) {
                drawPane(pose, pane, left + width * pane.left(), top + height * pane.top(),
                        left + width * pane.right(), top + height * pane.bottom(),
                        0.0F, 0.0F, 1.0F, 1.0F);
            }
        } else {
            net.minecraft.core.Direction rightDirection =
                    dev.minescreen.ScreenGeometry.rightDirection(group.facing());
            net.minecraft.core.Direction upDirection =
                    dev.minescreen.ScreenGeometry.upDirection(group.facing());
            int originRight = dev.minescreen.ScreenGeometry.coordinate(group.origin(), rightDirection);
            int originUp = dev.minescreen.ScreenGeometry.coordinate(group.origin(), upDirection);
            for (ScreenRegionLayout.Canvas canvas : ScreenRegionLayout.canvases(group, profile)) {
                ScreenRenderSource source = ScreenContentManager.sourceFor(group, canvas.regionId());
                for (ScreenRenderSource.Pane pane : source.panes()) {
                    for (net.minecraft.core.BlockPos tile : canvas.group().tiles()) {
                        if (profile.disabledTiles.contains(tile.asLong())) {
                            continue;
                        }
                        int globalColumn = dev.minescreen.ScreenGeometry.coordinate(tile, rightDirection)
                                - originRight;
                        int globalRow = dev.minescreen.ScreenGeometry.coordinate(tile, upDirection)
                                - originUp;
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
                        float topUnits = canvas.minRow() + (1.0F - localTop) * canvas.rows();
                        float bottomUnits = canvas.minRow() + (1.0F - localBottom) * canvas.rows();
                        float globalTop = 1.0F - topUnits / group.rows();
                        float globalBottom = 1.0F - bottomUnits / group.rows();
                        float u0 = (localLeft - pane.left()) / (pane.right() - pane.left());
                        float u1 = (localRight - pane.left()) / (pane.right() - pane.left());
                        float v0 = (localTop - pane.top()) / (pane.bottom() - pane.top());
                        float v1 = (localBottom - pane.top()) / (pane.bottom() - pane.top());
                        drawPane(pose, pane, left + width * globalLeft,
                                top + height * globalTop, left + width * globalRight,
                                top + height * globalBottom, u0, v0, u1, v1);
                    }
                }
            }
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        // Keep the immediate RenderSystem operation from leaking into EditBox/Button rendering.
        graphics.flush();
    }

    /** Draws a complete logical source, used by a computer's multi-plane panorama preview. */
    public static void drawSource(GuiGraphics graphics, ScreenRenderSource source, int left, int top,
            int width, int height) {
        graphics.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        for (ScreenRenderSource.Pane pane : source.panes()) {
            drawPane(pose, pane, left + width * pane.left(), top + height * pane.top(),
                    left + width * pane.right(), top + height * pane.bottom(),
                    0.0F, 0.0F, 1.0F, 1.0F);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        graphics.flush();
    }

    private static void drawPane(org.joml.Matrix4f pose, ScreenRenderSource.Pane pane,
            float left, float top, float right, float bottom,
            float u0, float v0, float u1, float v1) {
        pane.bind();
        if (pane.flipHorizontal()) {
            float swap = u0;
            u0 = 1.0F - u1;
            u1 = 1.0F - swap;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, left, bottom, 0.0F).setUv(u0, v1);
        builder.addVertex(pose, right, bottom, 0.0F).setUv(u1, v1);
        builder.addVertex(pose, right, top, 0.0F).setUv(u1, v0);
        builder.addVertex(pose, left, top, 0.0F).setUv(u0, v0);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }
}
