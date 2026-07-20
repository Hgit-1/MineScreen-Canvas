package dev.minescreen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Browser-like tab with an active top edge, favicon marker and dedicated close hit area. */
public final class BrowserTabButton extends AbstractButton {
    private final Runnable selectAction;
    private final Runnable closeAction;
    private boolean selected;

    public BrowserTabButton(int x, int y, int width, int height, Runnable selectAction,
            Runnable closeAction) {
        super(x, y, width, height, Component.literal("New Tab"));
        this.selectAction = selectAction;
        this.closeAction = closeAction;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public void onPress() {
        selectAction.run();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (mouseX >= getX() + getWidth() - 16) {
            closeAction.run();
        } else {
            selectAction.run();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        int background = selected ? 0xFF293746 : isHovered() ? 0xFF222E3B : 0xFF17212C;
        int edge = selected ? 0xFFFFD43B : 0xFF405064;
        // Two-pixel clipped corners read as a compact browser-tab silhouette without relying on
        // vanilla button sprites or a separate text/background layer.
        graphics.fill(left + 2, top, right - 2, bottom, background);
        graphics.fill(left, top + 2, right, bottom, background);
        graphics.fill(left + 2, top, right - 2, top + (selected ? 2 : 1), edge);
        graphics.fill(left, bottom - 1, right, bottom, selected ? background : 0xFF0C1219);

        int iconColor = selected ? 0xFF4DD6D1 : 0xFF718398;
        graphics.fill(left + 6, top + 6, left + 13, top + 13, 0xFF0B1118);
        graphics.fill(left + 8, top + 8, left + 12, top + 12, iconColor);

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int textLeft = left + 17;
        int textRight = Math.max(textLeft, right - 18);
        graphics.enableScissor(textLeft, top, textRight, bottom);
        graphics.drawString(font, getMessage(), textLeft,
                top + (getHeight() - font.lineHeight) / 2,
                active ? 0xFFF1F5FA : 0xFF738193, false);
        graphics.disableScissor();

        if (selected || isHovered()) {
            int closeColor = mouseX >= right - 16 ? 0xFFFF7777 : 0xFFB9C5D2;
            graphics.drawString(font, "×", right - 12,
                    top + (getHeight() - font.lineHeight) / 2, closeColor, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
