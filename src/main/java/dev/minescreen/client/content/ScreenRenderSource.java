package dev.minescreen.client.content;

import net.minecraft.client.renderer.RenderType;

/** Render-thread texture binding, matching vertex pipeline, and source-specific UV orientation. */
public record ScreenRenderSource(RenderType renderType, Runnable prepare, boolean flipHorizontal) {
    public ScreenRenderSource(RenderType renderType, Runnable prepare) {
        this(renderType, prepare, false);
    }

    public void bind() {
        prepare.run();
    }
}
