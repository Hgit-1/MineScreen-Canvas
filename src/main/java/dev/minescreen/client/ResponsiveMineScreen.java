package dev.minescreen.client;

import dev.minescreen.client.ui.MineScreenUiRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared rendering and pointer transform for complete, non-clipping MineScreen editor panels. */
abstract class ResponsiveMineScreen extends Screen {
    private ResponsiveLayout.Viewport viewport = ResponsiveLayout.fit(1, 1, 1, 1);

    protected ResponsiveMineScreen(Component title) {
        super(title);
    }

    protected final void configureResponsiveLayout(int desiredWidth, int desiredHeight) {
        viewport = ResponsiveLayout.fit(width, height, desiredWidth, desiredHeight);
    }

    protected final int layoutWidth() {
        return viewport.logicalWidth();
    }

    protected final int layoutHeight() {
        return viewport.logicalHeight();
    }

    protected final double logicalMouseX(double mouseX) {
        return viewport.logicalX(mouseX);
    }

    protected final double logicalMouseY(double mouseY) {
        return viewport.logicalY(mouseY);
    }

    protected final void renderResponsive(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, ResponsiveLayer layer) {
        int logicalMouseX = viewport.logicalX(mouseX);
        int logicalMouseY = viewport.logicalY(mouseY);
        MineScreenUiRegistry.render(this, graphics, mouseX, mouseY, partialTick, () -> {
            graphics.pose().pushPose();
            graphics.pose().scale(viewport.scale(), viewport.scale(), 1.0F);
            try {
                layer.render(logicalMouseX, logicalMouseY, partialTick);
            } finally {
                graphics.pose().popPose();
            }
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(logicalMouseX(mouseX), logicalMouseY(mouseY), button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(logicalMouseX(mouseX), logicalMouseY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
            double dragX, double dragY) {
        return super.mouseDragged(logicalMouseX(mouseX), logicalMouseY(mouseY), button,
                dragX / viewport.scale(), dragY / viewport.scale());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        return super.mouseScrolled(logicalMouseX(mouseX), logicalMouseY(mouseY), deltaX, deltaY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(logicalMouseX(mouseX), logicalMouseY(mouseY));
    }

    @FunctionalInterface
    protected interface ResponsiveLayer {
        void render(int mouseX, int mouseY, float partialTick);
    }
}
