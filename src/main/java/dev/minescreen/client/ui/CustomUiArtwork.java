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
    private static NativeImage panel;
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

    /**
     * Places the artwork inside the lower gray IDLE band. The opaque test bars and the central
     * IDLE label remain unobstructed; transparent artwork is cropped to its actual alpha bounds so
     * a 1024x1024 canvas with a small corner illustration does not become visually microscopic.
     */
    public static void drawIdleDecoration(NativeImage target) {
        if (!MineScreenConfig.UI_SHOW_CUSTOM_DECORATION.get() || target == null) {
            return;
        }
        NativeImage source = panel();
        if (source == null) {
            source = loading();
        }
        if (source == null) {
            return;
        }
        Bounds bounds = alphaBounds(source);
        if (bounds == null) {
            return;
        }
        int bandTop = target.getHeight() * 2 / 3;
        int bandHeight = target.getHeight() - bandTop;
        int maxWidth = Math.max(1, target.getWidth() / 3);
        int maxHeight = Math.max(1, bandHeight - 8);
        double scale = Math.min(maxWidth / (double) bounds.width(),
                maxHeight / (double) bounds.height());
        int drawWidth = Math.max(1, (int) Math.round(bounds.width() * scale));
        int drawHeight = Math.max(1, (int) Math.round(bounds.height() * scale));
        int left = target.getWidth() - drawWidth - 8;
        int top = target.getHeight() - drawHeight - 5;
        for (int y = 0; y < drawHeight; y++) {
            double sourceY = bounds.top() + (y + 0.5D) * bounds.height() / drawHeight - 0.5D;
            for (int x = 0; x < drawWidth; x++) {
                double sourceX = bounds.left() + (x + 0.5D) * bounds.width() / drawWidth - 0.5D;
                int foreground = bilinear(source, sourceX, sourceY);
                if ((foreground >>> 24) != 0) {
                    target.setPixelRGBA(left + x, top + y,
                            blend(foreground, target.getPixelRGBA(left + x, top + y)));
                }
            }
        }
    }

    @Nullable
    private static synchronized NativeImage panel() {
        if (panelChecked) {
            return panel;
        }
        panelChecked = true;
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(PANEL)
                .orElse(null);
        if (resource == null) {
            return null;
        }
        try (InputStream stream = resource.open()) {
            NativeImage candidate = NativeImage.read(stream);
            panelWidth = candidate.getWidth();
            panelHeight = candidate.getHeight();
            if (panelWidth > 1024 || panelHeight > 1024) {
                LOGGER.warn("Ignoring {}: panel artwork exceeds 1024x1024 ({}x{})", PANEL,
                        panelWidth, panelHeight);
                candidate.close();
                return null;
            }
            panel = candidate;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to load optional MineScreen artwork {}", PANEL, exception);
        }
        return panel;
    }

    private static synchronized boolean panelAvailable() {
        panelAvailable = panel() != null;
        return panelAvailable;
    }

    @Nullable
    private static Bounds alphaBounds(NativeImage image) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) < 8) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        return right < left || bottom < top ? null
                : new Bounds(left, top, right - left + 1, bottom - top + 1);
    }

    private static int bilinear(NativeImage image, double x, double y) {
        int x0 = Math.max(0, Math.min(image.getWidth() - 1, (int) Math.floor(x)));
        int y0 = Math.max(0, Math.min(image.getHeight() - 1, (int) Math.floor(y)));
        int x1 = Math.min(image.getWidth() - 1, x0 + 1);
        int y1 = Math.min(image.getHeight() - 1, y0 + 1);
        double tx = Math.max(0.0D, Math.min(1.0D, x - Math.floor(x)));
        double ty = Math.max(0.0D, Math.min(1.0D, y - Math.floor(y)));
        return mix(mix(image.getPixelRGBA(x0, y0), image.getPixelRGBA(x1, y0), tx),
                mix(image.getPixelRGBA(x0, y1), image.getPixelRGBA(x1, y1), tx), ty);
    }

    private static int mix(int first, int second, double amount) {
        return lerp(first >>> 24, second >>> 24, amount) << 24
                | lerp(first >>> 16 & 0xFF, second >>> 16 & 0xFF, amount) << 16
                | lerp(first >>> 8 & 0xFF, second >>> 8 & 0xFF, amount) << 8
                | lerp(first & 0xFF, second & 0xFF, amount);
    }

    private static int lerp(int first, int second, double amount) {
        return Math.max(0, Math.min(255,
                (int) Math.round(first + (second - first) * amount)));
    }

    private static int blend(int foreground, int background) {
        int alpha = foreground >>> 24;
        if (alpha >= 255) {
            return foreground;
        }
        if (alpha <= 0) {
            return background;
        }
        double amount = alpha / 255.0D;
        return 0xFF000000
                | lerp(background & 0xFF, foreground & 0xFF, amount)
                | lerp(background >>> 8 & 0xFF, foreground >>> 8 & 0xFF, amount) << 8
                | lerp(background >>> 16 & 0xFF, foreground >>> 16 & 0xFF, amount) << 16;
    }

    private record Bounds(int left, int top, int width, int height) {
    }
}
