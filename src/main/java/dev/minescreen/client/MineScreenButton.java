package dev.minescreen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** High-contrast panel button whose frame and text are emitted in one widget render call. */
public final class MineScreenButton extends Button {
    private MineScreenButton(int x, int y, int width, int height, Component message,
            OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static MineScreenButton create(Component message, OnPress onPress,
            int x, int y, int width, int height) {
        return new MineScreenButton(x, y, width, height, message, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        int border = active ? isHovered() ? 0xFFFFD43B : 0xFF52667D : 0xFF435063;
        int background = active ? isHovered() ? 0xFF31475C : 0xFF263646 : 0xFF1D2733;
        graphics.fill(left, top, right, bottom, border);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, background);
        int color = active ? 0xFFF4F8FC : 0xFF9AA9BA;
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                left + getWidth() / 2,
                top + (getHeight() - Minecraft.getInstance().font.lineHeight) / 2, color);
    }
}
