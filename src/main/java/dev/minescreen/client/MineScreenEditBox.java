package dev.minescreen.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** EditBox with its high-contrast frame, text, selection and caret in the same widget pass. */
public final class MineScreenEditBox extends EditBox {
    /** MineScreen sources include long Windows paths and signed URLs; vanilla defaults to 32. */
    public static final int DEFAULT_MAX_LENGTH = 65_535;

    public MineScreenEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        // Set this before any screen calls setValue(). Individual password/id fields lower the
        // limit before assigning their value. This prevents EditBox's vanilla 32-character default
        // from destructively truncating an already-saved path while a screen is being initialized.
        setMaxLength(DEFAULT_MAX_LENGTH);
        // Keep EditBox's native 4px horizontal padding and vertical centering. The earlier
        // borderless implementation removed both and pinned text/caret to the upper-left edge.
        setBordered(true);
        setTextColor(0xFFEAF3FF);
        setTextColorUneditable(0xFF9AA9BA);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = isFocused() ? 0xFFFFD43B : isHovered() ? 0xFF71869D : 0xFF43546A;
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        // Recolor only the outermost pixel after the native field has drawn. Background, text,
        // selection and caret remain one EditBox pass and keep their correct shared geometry.
        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top + 1, left + 1, bottom - 1, border);
        graphics.fill(right - 1, top + 1, right, bottom - 1, border);
    }
}
