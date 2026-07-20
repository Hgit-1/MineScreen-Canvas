package dev.minescreen.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Optional client UI adapter API. A UI framework integration registers an implementation during
 * client setup and may decorate or replace MineScreen's built-in composited layer.
 */
public interface MineScreenUiProvider {
    /** Stable config identifier, for example {@code otyacraft_renewed}. */
    String id();

    /** Higher-priority available providers win when ui_provider is {@code auto}. */
    default int priority() {
        return 0;
    }

    default boolean available() {
        return true;
    }

    /** Call builtInLayer.run() to retain MineScreen controls, or render a complete replacement. */
    void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            Runnable builtInLayer);
}
