package dev.minescreen.client;

import dev.minescreen.TextDisplayAnimation;
import dev.minescreen.TextDisplayBlockEntity;
import dev.minescreen.DisplayBackMode;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import dev.minescreen.network.TextDisplayUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact editor whose panel, labels and widgets share MineScreen's composited UI layer. */
public final class TextDisplayEditorScreen extends ResponsiveMineScreen {
    private final BlockPos pos;
    private final TextDisplayBlockEntity initial;
    private final boolean backSide;
    private final boolean electric;
    private EditBox textBox;
    private EditBox textColorBox;
    private EditBox backgroundColorBox;
    private Button animationButton;
    private Button speedButton;
    private Button fontSizeButton;
    private Button backModeButton;
    private TextDisplayAnimation animation;
    private float speed;
    private int fontSize;
    private DisplayBackMode backMode;
    private Component status = Component.empty();
    private int statusColor = 0xFFB8C7D9;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;

    public TextDisplayEditorScreen(TextDisplayBlockEntity display) {
        this(display, false);
    }

    public TextDisplayEditorScreen(TextDisplayBlockEntity display, boolean backSide) {
        super(Component.translatable(display.isElectric()
                ? "screen.minescreen.electric_display.title"
                : "screen.minescreen.text_display.title"));
        pos = display.getBlockPos().immutable();
        initial = display;
        this.backSide = backSide;
        electric = display.isElectric();
        animation = display.animation(backSide);
        speed = display.speed(backSide);
        fontSize = display.fontSize(backSide);
        backMode = display.backMode();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        configureResponsiveLayout(540, 290);
        panelWidth = Math.min(520, layoutWidth() - 20);
        int panelHeight = 270;
        panelLeft = (layoutWidth() - panelWidth) / 2;
        panelTop = (layoutHeight() - panelHeight) / 2;
        int left = panelLeft + 16;
        int contentWidth = panelWidth - 32;

        textBox = new MineScreenEditBox(font, left, panelTop + 54, contentWidth, 20,
                Component.translatable("screen.minescreen.text_display.text"));
        textBox.setMaxLength(TextDisplayBlockEntity.MAX_TEXT_LENGTH);
        textBox.setValue(initial.text(backSide));
        addRenderableWidget(textBox);

        int half = (contentWidth - 8) / 2;
        textColorBox = new MineScreenEditBox(font, left, panelTop + 103, half, 20,
                Component.translatable("screen.minescreen.text_display.text_color"));
        textColorBox.setMaxLength(9);
        textColorBox.setValue(colorString(initial.textColor(backSide)));
        addRenderableWidget(textColorBox);
        backgroundColorBox = new MineScreenEditBox(font, left + half + 8, panelTop + 103, half,
                20, Component.translatable("screen.minescreen.text_display.background_color"));
        backgroundColorBox.setMaxLength(9);
        backgroundColorBox.setValue(colorString(initial.backgroundColor(backSide)));
        addRenderableWidget(backgroundColorBox);

        animationButton = addRenderableWidget(MineScreenButton.create(animationLabel(),
                button -> cycleAnimation(), left, panelTop + 133, half, 20));
        speedButton = addRenderableWidget(MineScreenButton.create(speedLabel(),
                button -> cycleSpeed(), left + half + 8, panelTop + 133, half, 20));
        fontSizeButton = addRenderableWidget(MineScreenButton.create(fontSizeLabel(),
                button -> cycleFontSize(), left, panelTop + 158, contentWidth, 20));
        backModeButton = addRenderableWidget(MineScreenButton.create(backModeLabel(),
                button -> cycleBackMode(), panelLeft + panelWidth - 158, panelTop + 7, 144, 20));
        backModeButton.visible = backModeButton.active = !backSide && !electric;
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.save"), button -> save(),
                left, panelTop + 226, half, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.cancel"),
                button -> onClose(), left + half + 8, panelTop + 226, half, 20));
        setInitialFocus(textBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderResponsive(graphics, mouseX, mouseY, partialTick,
                (logicalX, logicalY, tick) -> renderLayer(graphics, logicalX, logicalY, tick));
    }

    private void renderLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelHeight = 270;
        graphics.fillGradient(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
                0xFF1A202B, 0xFF0F141D);
        graphics.fill(panelLeft, panelTop, panelLeft + 4, panelTop + panelHeight,
                electric ? 0xFF62FFF1 : 0xFFFFD43B);
        graphics.fill(panelLeft + 4, panelTop + 34, panelLeft + panelWidth, panelTop + 35,
                0xFF394657);
        graphics.drawString(font, title, panelLeft + 16, panelTop + 13, 0xFFF4F7FB, false);
        if (backSide) {
            graphics.drawString(font, Component.translatable("screen.minescreen.back.editing"),
                    panelLeft + 170, panelTop + 13, 0xFFFFD43B, false);
        }
        graphics.drawString(font, Component.translatable("screen.minescreen.text_display.text"),
                panelLeft + 16, panelTop + 43, 0xFFD8E2EE, false);
        graphics.drawString(font,
                Component.translatable("screen.minescreen.text_display.text_color"),
                panelLeft + 16, panelTop + 91, 0xFF9EB0C4, false);
        graphics.drawString(font,
                Component.translatable("screen.minescreen.text_display.background_color"),
                panelLeft + 20 + (panelWidth - 40) / 2, panelTop + 91, 0xFF9EB0C4, false);

        int previewLeft = panelLeft + 16;
        int previewTop = panelTop + 184;
        int previewRight = panelLeft + panelWidth - 16;
        int background = parseColor(backgroundColorBox.getValue(), initial.backgroundColor(backSide));
        int foreground = parseColor(textColorBox.getValue(), initial.textColor(backSide));
        graphics.fill(previewLeft, previewTop, previewRight, previewTop + 30, background);
        if (electric) {
            for (int y = previewTop + 4; y < previewTop + 30; y += 5) {
                graphics.fill(previewLeft, y, previewRight, y + 1, foreground & 0x24FFFFFF);
            }
        }
        String preview = font.plainSubstrByWidth(textBox.getValue(), previewRight - previewLeft - 12);
        graphics.drawCenteredString(font, preview, (previewLeft + previewRight) / 2,
                previewTop + 10, foreground);
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, status, previewLeft, panelTop + 252, statusColor, false);
        }
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.flush();
    }

    private void cycleAnimation() {
        TextDisplayAnimation[] values = TextDisplayAnimation.values();
        animation = values[(animation.ordinal() + 1) % values.length];
        animationButton.setMessage(animationLabel());
    }

    private void cycleSpeed() {
        speed = speed < 0.75F ? 1.0F : speed < 1.5F ? 2.0F : speed < 3.0F ? 4.0F : 0.5F;
        speedButton.setMessage(speedLabel());
    }

    private void cycleFontSize() {
        fontSize = switch (fontSize) {
            case 25 -> 50;
            case 50 -> 75;
            case 75 -> 100;
            case 100 -> 125;
            case 125 -> 150;
            case 150 -> 200;
            default -> 25;
        };
        fontSizeButton.setMessage(fontSizeLabel());
    }

    private void cycleBackMode() {
        backMode = backMode.next();
        backModeButton.setMessage(backModeLabel());
    }

    private void save() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !TextDisplayBlockEntity.validText(textBox.getValue())) {
            error("screen.minescreen.text_display.error_text");
            return;
        }
        Integer foreground = parseColorStrict(textColorBox.getValue());
        Integer background = parseColorStrict(backgroundColorBox.getValue());
        if (foreground == null || background == null) {
            error("screen.minescreen.text_display.error_color");
            return;
        }
        PacketDistributor.sendToServer(new TextDisplayUpdatePayload(
                minecraft.level.dimension().location(), pos, textBox.getValue(), foreground,
                background, animation, speed, fontSize, backSide, backMode));
        onClose();
    }

    private Component animationLabel() {
        return Component.translatable("screen.minescreen.text_display.animation",
                Component.translatable("screen.minescreen.text_display.animation."
                        + animation.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private Component speedLabel() {
        return Component.translatable("screen.minescreen.text_display.speed", speed);
    }

    private Component fontSizeLabel() {
        return Component.translatable("screen.minescreen.text_display.font_size", fontSize);
    }

    private Component backModeLabel() {
        return Component.translatable("screen.minescreen.back.mode",
                Component.translatable("screen.minescreen.back.mode."
                        + backMode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private void error(String key) {
        status = Component.translatable(key);
        statusColor = 0xFFFF6B6B;
    }

    private static String colorString(int color) {
        return String.format("#%08X", color);
    }

    private static int parseColor(String value, int fallback) {
        Integer parsed = parseColorStrict(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer parseColorStrict(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() == 6) {
            normalized = "FF" + normalized;
        }
        if (normalized.length() != 8) {
            return null;
        }
        try {
            return (int) Long.parseLong(normalized, 16);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
