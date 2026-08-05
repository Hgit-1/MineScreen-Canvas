package dev.minescreen.client;

import java.nio.file.Path;

import dev.minescreen.client.compat.FfmpegRuntimeManager;
import dev.minescreen.client.compat.McefDownloadProgressBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** High-contrast combined runtime installer inspired by MCEF's familiar startup screen. */
public final class RuntimeDownloadScreen extends Screen {
    private final Screen parent;
    private final Screen mcefDownloader;
    private FfmpegRuntimeManager.State widgetState;
    private int completeTicks;
    private Component notice = Component.empty();

    RuntimeDownloadScreen(Screen parent, Screen mcefDownloader) {
        super(Component.translatable("screen.minescreen.runtime_download.title"));
        this.parent = parent;
        this.mcefDownloader = mcefDownloader;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        widgetState = FfmpegRuntimeManager.snapshot().state();
        if (widgetState == FfmpegRuntimeManager.State.FAILED) {
            int y = height / 2 + 112;
            addRenderableWidget(MineScreenButton.create(
                    Component.translatable("screen.minescreen.runtime_download.retry"),
                    button -> FfmpegRuntimeManager.retry(), width / 2 - 246, y, 116, 20));
            addRenderableWidget(MineScreenButton.create(
                    Component.translatable("screen.minescreen.runtime_download.import"),
                    button -> chooseArchive(), width / 2 - 124, y, 116, 20));
            addRenderableWidget(MineScreenButton.create(
                    Component.translatable("screen.minescreen.runtime_download.copy_url"),
                    button -> copyManualUrl(), width / 2 - 2, y, 116, 20));
            addRenderableWidget(MineScreenButton.create(
                    Component.translatable("screen.minescreen.runtime_download.continue"),
                    button -> finish(), width / 2 + 120, y, 126, 20));
        }
    }

    @Override
    public void tick() {
        super.tick();
        FfmpegRuntimeManager.State state = FfmpegRuntimeManager.snapshot().state();
        if (state != widgetState) rebuildButtons();
        var mcef = McefDownloadProgressBridge.snapshot();
        boolean mcefFinished = !mcef.present() || mcef.done() || mcef.failed();
        boolean ffmpegFinished = state == FfmpegRuntimeManager.State.READY
                || state == FfmpegRuntimeManager.State.IDLE;
        if (ffmpegFinished && mcefFinished) {
            if (++completeTicks >= 15) finish();
        } else {
            completeTicks = 0;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xB018202B);
        int center = width / 2;
        int top = Math.max(38, height / 2 - 132);
        graphics.drawCenteredString(font, title, center, top, 0xFFFFC95A);
        graphics.drawCenteredString(font,
                Component.translatable("screen.minescreen.runtime_download.security"),
                center, top + 18, 0xFFDDE7F2);

        var mcef = McefDownloadProgressBridge.snapshot();
        int row = top + 50;
        if (mcef.present()) {
            drawProgress(graphics, center, row,
                    Component.translatable("screen.minescreen.runtime_download.mcef"),
                    cleanTask(mcef.task()), mcef.progress(), mcef.failed());
            row += 76;
        }
        var ffmpeg = FfmpegRuntimeManager.snapshot();
        String ffmpegDetail = switch (ffmpeg.state()) {
            case FAILED -> ffmpeg.error();
            case READY -> Component.translatable("screen.minescreen.runtime_download.ready")
                    .getString();
            default -> ffmpeg.detail();
        };
        drawProgress(graphics, center, row,
                Component.translatable("screen.minescreen.runtime_download.ffmpeg"),
                ffmpegDetail, ffmpeg.progress(), ffmpeg.state() == FfmpegRuntimeManager.State.FAILED);
        row += 72;

        if (ffmpeg.state() == FfmpegRuntimeManager.State.FAILED) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.minescreen.runtime_download.manual_file",
                            ffmpeg.fileName()), center, row, 0xFFFFD35E);
            graphics.drawCenteredString(font, elide(ffmpeg.manualUrl(), width - 44), center,
                    row + 13, 0xFF9FC7F0);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.minescreen.runtime_download.warning"), center,
                    row + 28, 0xFFFF7777);
        }
        if (!notice.getString().isBlank()) {
            graphics.drawCenteredString(font, notice, center, height - 32, 0xFFFF7777);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawProgress(GuiGraphics graphics, int center, int y, Component label,
            String detail, double progress, boolean failed) {
        int barWidth = Math.min(640, width - 60);
        int left = center - barWidth / 2;
        int percent = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * 100.0D);
        graphics.drawCenteredString(font, label, center, y, failed ? 0xFFFF7777 : 0xFFF4F8FC);
        graphics.drawCenteredString(font, elide(detail, barWidth), center, y + 14, 0xFFB7C6D6);
        graphics.drawCenteredString(font, percent + "%", center, y + 28, 0xFFF4F8FC);
        int barTop = y + 42;
        graphics.fill(left, barTop, left + barWidth, barTop + 14, 0xFFF4F8FC);
        graphics.fill(left + 2, barTop + 2, left + barWidth - 2, barTop + 12, 0xFF10141B);
        int fill = (int) Math.round((barWidth - 4) * Math.max(0.0D, Math.min(1.0D, progress)));
        if (fill > 0) {
            graphics.fill(left + 2, barTop + 2, left + 2 + fill, barTop + 12,
                    failed ? 0xFFFF5F5F : 0xFFFFC95A);
        }
    }

    private void chooseArchive() {
        try {
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.minescreen.runtime_download.import_title")
                            .getString(), "", null,
                    null, false);
            if (selected != null && !selected.isBlank()) {
                FfmpegRuntimeManager.installArchiveAsync(Path.of(selected));
                notice = Component.empty();
            }
        } catch (Throwable failure) {
            notice = Component.translatable("screen.minescreen.runtime_download.picker_failed");
        }
    }

    private void copyManualUrl() {
        minecraft.keyboardHandler.setClipboard(FfmpegRuntimeManager.snapshot().manualUrl());
        notice = Component.translatable("screen.minescreen.runtime_download.url_copied");
    }

    private void finish() {
        var mcef = McefDownloadProgressBridge.snapshot();
        if (mcefDownloader != null && (mcef.done() || mcef.failed())) {
            minecraft.setScreen(mcefDownloader);
            RuntimeDownloadCoordinator.finishedMcefScreen();
        } else {
            minecraft.setScreen(parent == this ? null : parent);
        }
    }

    private String cleanTask(String task) {
        if (task == null || task.equals("null") || task.isBlank()) {
            return Component.translatable("screen.minescreen.runtime_download.mcef_waiting")
                    .getString();
        }
        return task;
    }

    private String elide(String text, int pixelWidth) {
        if (text == null) return "";
        if (font.width(text) <= pixelWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(20, pixelWidth - font.width("…"))) + "…";
    }
}
