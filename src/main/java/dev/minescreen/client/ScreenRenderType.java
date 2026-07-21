package dev.minescreen.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Texture-backed quad pipeline. The cache keeps one RenderType/texture binding per source. */
public final class ScreenRenderType {
    private static final Map<ResourceLocation, RenderType> CACHE = new HashMap<>();
    private static final Map<Integer, RenderType> ID_CACHE = new HashMap<>();

    private ScreenRenderType() {
    }

    public static RenderType screen(ResourceLocation texture) {
        return CACHE.computeIfAbsent(texture, ScreenRenderType::create);
    }

    public static RenderType screen(int textureId) {
        return ID_CACHE.computeIfAbsent(textureId, ScreenRenderType::createForId);
    }

    public static void release(int textureId) {
        if (textureId > 0) {
            ID_CACHE.remove(textureId);
        }
    }

    /** Drops a short-lived DynamicTexture RenderType when a WEB status surface closes. */
    public static void release(ResourceLocation texture) {
        if (texture != null) {
            CACHE.remove(texture);
        }
    }

    private static RenderType create(ResourceLocation texture) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(
                        net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                .setOverlayState(RenderStateShard.NO_OVERLAY)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(false);
        return RenderType.create("minescreen_screen", DefaultVertexFormat.POSITION_TEX_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false, state);
    }

    private static RenderType createForId(int textureId) {
        RenderStateShard.EmptyTextureStateShard texture = new RenderStateShard.EmptyTextureStateShard(
                () -> com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, textureId), () -> {
                });
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(
                        net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader))
                .setTextureState(texture)
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                .setOverlayState(RenderStateShard.NO_OVERLAY)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(false);
        return RenderType.create("minescreen_screen_id_" + textureId, DefaultVertexFormat.POSITION_TEX_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false, state);
    }
}
