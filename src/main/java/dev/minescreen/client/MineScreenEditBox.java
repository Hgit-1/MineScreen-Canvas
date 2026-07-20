package dev.minescreen.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** EditBox with its high-contrast frame, text, selection and caret in the same widget pass. */
public final class MineScreenEditBox extends EditBox {
    public MineScreenEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        setBordered(false);
        setTextColor(0xFFEAF3FF);
        setTextColorUneditable(0xFF9AA9BA);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = isFocused() ? 0xFFFFD43B : isHovered() ? 0xFF71869D : 0xFF43546A;
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
        graphics.fill(getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1, 0xFF0D141D);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }
}
