package dev.minescreen.client.content;

import java.util.List;

import net.minecraft.client.renderer.RenderType;

/**
 * One or more texture panes mapped into a normalized logical screen canvas.
 * Each pane's prepare callback must leave its texture bound on shader unit 0. World RenderTypes
 * also bind it later, but immediate computer/GUI previews deliberately bypass RenderType setup.
 */
public final class ScreenRenderSource {
    private final List<Pane> panes;

    public ScreenRenderSource(RenderType renderType, Runnable prepare) {
        this(renderType, prepare, false);
    }

    public ScreenRenderSource(RenderType renderType, Runnable prepare, boolean flipHorizontal) {
        this(List.of(new Pane(0.0F, 0.0F, 1.0F, 1.0F,
                renderType, prepare, flipHorizontal)));
    }

    public ScreenRenderSource(List<Pane> panes) {
        if (panes == null || panes.isEmpty()) {
            throw new IllegalArgumentException("Screen render source needs at least one pane");
        }
        this.panes = List.copyOf(panes);
    }

    public List<Pane> panes() {
        return panes;
    }

    /** Compatibility accessors for single-pane callers. */
    public RenderType renderType() {
        return panes.getFirst().renderType();
    }

    public boolean flipHorizontal() {
        return panes.getFirst().flipHorizontal();
    }

    public void bind() {
        panes.getFirst().bind();
    }

    public record Pane(float left, float top, float right, float bottom,
            RenderType renderType, Runnable prepare, boolean flipHorizontal) {
        public Pane {
            if (!(left >= 0.0F && top >= 0.0F && right <= 1.0F && bottom <= 1.0F
                    && left < right && top < bottom)) {
                throw new IllegalArgumentException("Invalid normalized pane bounds");
            }
        }

        public void bind() {
            prepare.run();
        }
    }
}
