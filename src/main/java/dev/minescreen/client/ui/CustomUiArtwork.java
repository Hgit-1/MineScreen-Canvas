package dev.minescreen.client.ui;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

/** Lazily loads optional, user-supplied UI art without producing missing-texture markers. */
public final class CustomUiArtwork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation LOADING = ResourceLocation.fromNamespaceAndPath(
            MineScreen.MOD_ID, "textures/gui/custom/loading_decoration.png");
    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(
            MineScreen.MOD_ID, "textures/gui/custom/panel_decoration.png");
    private static NativeImage loading;
    private static boolean loadingChecked;
    private static boolean panelChecked;
    private static boolean panelAvailable;
    private static int panelWidth;
    private static int panelHeight;

    private CustomUiArtwork() {
    }

    /** Shared read-only image; the packaged resource is immutable for the lifetime of the client. */
    @Nullable
    public static synchronized NativeImage loading() {
        if (loadingChecked) {
            return loading;
        }
        loadingChecked = true;
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(LOADING)
                .orElse(null);
        if (resource == null) {
            return null;
        }
        try (InputStream stream = resource.open()) {
            NativeImage candidate = NativeImage.read(stream);
            if (candidate.getWidth() > 2048 || candidate.getHeight() > 2048) {
                LOGGER.warn("Ignoring {}: custom loading artwork exceeds 2048x2048 ({}x{})",
                        LOADING, candidate.getWidth(), candidate.getHeight());
                candidate.close();
                return null;
            }
            loading = candidate;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to load optional MineScreen artwork {}", LOADING, exception);
        }
        return loading;
    }

    /** Draws optional transparent artwork behind labels/widgets in the same composited GUI pass. */
    public static void drawPanel(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!MineScreenConfig.UI_SHOW_CUSTOM_DECORATION.get() || !panelAvailable()) {
            return;
        }
        int maximumWidth = Math.max(1, Math.min(260, width / 3));
        int maximumHeight = Math.max(1, height - 46);
        double scale = Math.min(maximumWidth / (double) panelWidth,
                maximumHeight / (double) panelHeight);
        int drawWidth = Math.max(1, (int) Math.round(panelWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(panelHeight * scale));
        int x = left + width - drawWidth - 8;
        int y = top + height - drawHeight - 8;
        float opacity = MineScreenConfig.UI_CUSTOM_DECORATION_OPACITY_PERCENT.get() / 100.0F;
        graphics.flush();
        graphics.setColor(1.0F, 1.0F, 1.0F, opacity);
        graphics.blit(PANEL, x, y, 0.0F, 0.0F, drawWidth, drawHeight, panelWidth, panelHeight);
        graphics.flush();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static synchronized boolean panelAvailable() {
        if (panelChecked) {
            return panelAvailable;
        }
        panelChecked = true;
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(PANEL)
                .orElse(null);
        if (resource == null) {
            return false;
        }
        try (InputStream stream = resource.open(); NativeImage candidate = NativeImage.read(stream)) {
            panelWidth = candidate.getWidth();
            panelHeight = candidate.getHeight();
            panelAvailable = panelWidth <= 1024 && panelHeight <= 1024;
            if (!panelAvailable) {
                LOGGER.warn("Ignoring {}: panel artwork exceeds 1024x1024 ({}x{})", PANEL,
                        panelWidth, panelHeight);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to load optional MineScreen artwork {}", PANEL, exception);
        }
        return panelAvailable;
    }
}
