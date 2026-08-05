package dev.minescreen.client;

import java.nio.file.Path;

import org.lwjgl.util.tinyfd.TinyFileDialogs;

import dev.minescreen.MineScreenClientConfig;
import dev.minescreen.client.compat.Availability;
import dev.minescreen.client.compat.CapabilityResult;
import dev.minescreen.client.compat.CompatibilityManager;
import dev.minescreen.client.ui.CustomUiArtwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Guided front-end for the client-only compatibility settings and capability probe. */
public final class CompatibilityScreen extends ResponsiveMineScreen {
    private final Screen parent;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private Component notice = Component.empty();
    private int noticeColor = 0xFF91A4B8;

    public CompatibilityScreen(Screen parent) {
        super(Component.translatable("screen.minescreen.compatibility.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        configureResponsiveLayout(700, 370);
        panelWidth = Math.min(684, layoutWidth() - 16);
        panelHeight = Math.min(354, layoutHeight() - 12);
        panelLeft = (layoutWidth() - panelWidth) / 2;
        panelTop = (layoutHeight() - panelHeight) / 2;

        int left = panelLeft + 18;
        int right = panelLeft + panelWidth - 18;
        int buttonWidth = 150;
        int bottom = panelTop + panelHeight - 30;

        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.compatibility.select_browser"),
                button -> choose(ProgramKind.BROWSER), right - buttonWidth, panelTop + 94,
                buttonWidth, 20));
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.compatibility.select_ffmpeg"),
                button -> choose(ProgramKind.FFMPEG), right - buttonWidth, panelTop + 158,
                buttonWidth, 20));
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.compatibility.select_ffprobe"),
                button -> choose(ProgramKind.FFPROBE), right - buttonWidth, panelTop + 182,
                buttonWidth, 20));
        addRenderableWidget(MineScreenButton.create(modeLabel(), button -> toggleMode(),
                left, bottom, 176, 20));
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.compatibility.refresh"),
                button -> refresh(), left + 182, bottom, 104, 20));
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.compatibility.copy_diagnostics"),
                button -> copyDiagnostics(), left + 292, bottom, 142, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.back"),
                button -> onClose(), right - 100, bottom, 100, 20));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderResponsive(graphics, mouseX, mouseY, partialTick,
                (logicalX, logicalY, tick) -> renderLayer(graphics, logicalX, logicalY, tick));
    }

    private void renderLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
                0xFF1A202B, 0xFF0F141D);
        graphics.fill(panelLeft, panelTop, panelLeft + 4, panelTop + panelHeight, 0xFFFFD43B);
        graphics.fill(panelLeft + 4, panelTop + 34, panelLeft + panelWidth, panelTop + 35,
                0xFF394657);
        graphics.drawString(font, title, panelLeft + 18, panelTop + 13, 0xFFF4F7FB, false);

        var platform = CompatibilityManager.platform();
        Component platformLine = Component.translatable("screen.minescreen.compatibility.platform",
                platform.os(), platform.osVersion(), platform.architecture(), platform.runtime());
        graphics.drawString(font, platformLine, panelLeft + 18, panelTop + 45,
                0xFF9FB1C5, false);
        graphics.drawString(font,
                Component.translatable("screen.minescreen.compatibility.explanation"),
                panelLeft + 18, panelTop + 62, 0xFFCED9E6, false);

        renderCapability(graphics, panelTop + 88,
                Component.translatable("screen.minescreen.compatibility.web"),
                CompatibilityManager.selectedBrowser(),
                CompatibilityManager.externalBrowser().orElse(null));
        renderCapability(graphics, panelTop + 152,
                Component.translatable("screen.minescreen.compatibility.video"),
                CompatibilityManager.selectedVideo(),
                CompatibilityManager.externalFfmpeg().orElse(null));
        renderCapability(graphics, panelTop + 222,
                Component.translatable("screen.minescreen.compatibility.vnc"),
                CompatibilityManager.probe(dev.minescreen.client.compat.Capability.VNC_LOSSLESS),
                null);

        if (!notice.getString().isBlank()) {
            graphics.drawString(font, notice, panelLeft + 18, panelTop + panelHeight - 49,
                    noticeColor, false);
        }
        CustomUiArtwork.drawPanel(graphics, panelLeft, panelTop, panelWidth, panelHeight);
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.flush();
    }

    private void renderCapability(GuiGraphics graphics, int y, Component label,
            CapabilityResult result, Path selectedPath) {
        int stateColor = switch (result.availability()) {
            case AVAILABLE -> 0xFF6FE59A;
            case COMPATIBILITY -> 0xFFFFD35E;
            case UNAVAILABLE -> 0xFFFF7777;
        };
        graphics.fill(panelLeft + 18, y, panelLeft + panelWidth - 18, y + 1, 0xFF344252);
        graphics.drawString(font, label, panelLeft + 18, y + 8, 0xFFF2F6FB, false);
        graphics.drawString(font, availabilityLabel(result.availability()), panelLeft + 112,
                y + 8, stateColor, false);
        graphics.drawString(font,
                Component.translatable("screen.minescreen.compatibility.backend",
                        backendLabel(result)),
                panelLeft + 18, y + 24, 0xFF9FB1C5, false);
        String detail = selectedPath == null ? result.reason() : selectedPath.toString();
        if (detail != null && !detail.isBlank()) {
            graphics.drawString(font, elide(detail, 68), panelLeft + 18, y + 39,
                    0xFF7F91A5, false);
        }
    }

    private void choose(ProgramKind kind) {
        String configured = switch (kind) {
            case BROWSER -> MineScreenClientConfig.EXTERNAL_BROWSER_PATH.get();
            case FFMPEG -> MineScreenClientConfig.EXTERNAL_FFMPEG_PATH.get();
            case FFPROBE -> MineScreenClientConfig.EXTERNAL_FFPROBE_PATH.get();
        };
        String selected = TinyFileDialogs.tinyfd_openFileDialog(
                Component.translatable(kind.titleKey).getString(), configured, null, null, false);
        if (selected == null || selected.isBlank()) return;
        try {
            Path executable = Path.of(selected).toAbsolutePath().normalize();
            switch (kind) {
                case BROWSER -> MineScreenClientConfig.setExternalBrowser(executable);
                case FFMPEG -> MineScreenClientConfig.setExternalFfmpeg(executable);
                case FFPROBE -> MineScreenClientConfig.setExternalFfprobe(executable);
            }
            CompatibilityManager.refresh();
            notice = Component.translatable("screen.minescreen.compatibility.saved");
            noticeColor = 0xFF6FE59A;
        } catch (RuntimeException failure) {
            notice = Component.translatable("screen.minescreen.compatibility.save_failed",
                    failure.getMessage() == null ? failure.getClass().getSimpleName()
                            : failure.getMessage());
            noticeColor = 0xFFFF7777;
        }
    }

    private void refresh() {
        CompatibilityManager.refresh();
        notice = Component.translatable("screen.minescreen.compatibility.refreshed");
        noticeColor = 0xFF6FE59A;
    }

    private void toggleMode() {
        var next = MineScreenClientConfig.COMPATIBILITY_MODE.get()
                == MineScreenClientConfig.CompatibilityMode.AUTO
                ? MineScreenClientConfig.CompatibilityMode.CORE_ONLY
                : MineScreenClientConfig.CompatibilityMode.AUTO;
        MineScreenClientConfig.COMPATIBILITY_MODE.set(next);
        MineScreenClientConfig.COMPATIBILITY_MODE.save();
        CompatibilityManager.refresh();
        clearWidgets();
        init();
    }

    private void copyDiagnostics() {
        minecraft.keyboardHandler.setClipboard(CompatibilityManager.diagnostics());
        notice = Component.translatable("screen.minescreen.compatibility.copied");
        noticeColor = 0xFF6FE59A;
    }

    private Component modeLabel() {
        return Component.translatable("screen.minescreen.compatibility.mode",
                MineScreenClientConfig.COMPATIBILITY_MODE.get().getTranslatedName());
    }

    private static Component availabilityLabel(Availability availability) {
        return Component.translatable("screen.minescreen.compatibility.availability."
                + availability.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component backendLabel(CapabilityResult result) {
        return Component.translatable("screen.minescreen.compatibility.backend."
                + result.backend().name().toLowerCase(java.util.Locale.ROOT));
    }

    private String elide(String value, int maxWidth) {
        if (font.width(value) <= maxWidth * 6) return value;
        return font.plainSubstrByWidth(value, Math.max(30, maxWidth * 6 - font.width("…"))) + "…";
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private enum ProgramKind {
        BROWSER("screen.minescreen.compatibility.select_browser"),
        FFMPEG("screen.minescreen.compatibility.select_ffmpeg"),
        FFPROBE("screen.minescreen.compatibility.select_ffprobe");

        private final String titleKey;

        ProgramKind(String titleKey) {
            this.titleKey = titleKey;
        }
    }
}
