package dev.minescreen.client.web;

import java.util.Locale;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.ui.MineScreenAssistant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Reused loading/error texture for one browser tab; spinner uploads are capped at 8 FPS. */
final class BrowserStatusTexture implements AutoCloseable {
    private static final long BASE_FRAME_NANOS = 125_000_000L;
    private static final String[] EMPTY_GLYPH = {"000", "000", "000", "000", "000"};

    private final UUID screenId;
    private final ResourceLocation location;
    private final ScreenRenderSource source;
    private NativeImage image;
    private DynamicTexture texture;
    private WebThumbnailCache.Thumbnail thumbnail;
    private String requestedUrl = "";
    private String message = "";
    private Phase phase = Phase.LOADING;
    private int width;
    private int height;
    private int generation;
    private int lastSpinnerFrame = -1;
    private boolean dirty = true;
    private boolean closed;

    BrowserStatusTexture(UUID screenId, int sourceWidth, int sourceHeight) {
        this.screenId = screenId;
        location = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "web/status_" + UUID.randomUUID().toString().replace("-", ""));
        source = new ScreenRenderSource(ScreenRenderType.screen(location), this::prepare);
        resize(sourceWidth, sourceHeight);
    }

    void loading(String url) {
        phase = Phase.LOADING;
        message = "";
        lastSpinnerFrame = -1;
        dirty = true;
        String nextUrl = url == null ? "" : url;
        if (nextUrl.isBlank() || nextUrl.equals(requestedUrl)) {
            return;
        }
        requestedUrl = nextUrl;
        int requestGeneration = ++generation;
        WebThumbnailCache.load(screenId, nextUrl, loaded -> {
            if (!closed && generation == requestGeneration && requestedUrl.equals(nextUrl)) {
                thumbnail = loaded;
                dirty = true;
            }
        });
    }

    void ready(String url) {
        phase = Phase.READY;
        message = "";
        if (url != null && !url.isBlank()) {
            requestedUrl = url;
        }
    }

    void error(String url, int statusCode, String details) {
        phase = Phase.ERROR;
        if (url != null && !url.isBlank()) {
            requestedUrl = url;
        }
        String clean = details == null ? "" : details.replace('\n', ' ').replace('\r', ' ').trim();
        message = statusCode == 0 ? clean : "CODE " + statusCode + (clean.isBlank() ? "" : " " + clean);
        dirty = true;
    }

    boolean loading() {
        return phase == Phase.LOADING;
    }

    boolean failed() {
        return phase == Phase.ERROR;
    }

    void resize(int sourceWidth, int sourceHeight) {
        int safeWidth = Math.max(1, sourceWidth);
        int safeHeight = Math.max(1, sourceHeight);
        double scale = Math.min(1.0D, Math.min(WebThumbnailCache.MAX_WIDTH / (double) safeWidth,
                WebThumbnailCache.MAX_HEIGHT / (double) safeHeight));
        int nextWidth = Math.max(64, (int) Math.round(safeWidth * scale));
        int nextHeight = Math.max(36, (int) Math.round(safeHeight * scale));
        if (nextWidth == width && nextHeight == height && texture != null) {
            return;
        }
        width = nextWidth;
        height = nextHeight;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        }
        image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        dirty = true;
        lastSpinnerFrame = -1;
    }

    ScreenRenderSource renderSource() {
        return source;
    }

    int textureIdForCapture() {
        update();
        return texture == null ? 0 : texture.getId();
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    private void prepare() {
        update();
        RenderSystem.setShaderTexture(0, location);
    }

    private void update() {
        if (closed || image == null) {
            return;
        }
        if (phase == Phase.LOADING) {
            long frameNanos = Math.max(20_000_000L, BASE_FRAME_NANOS * 100L
                    / MineScreenConfig.WEB_LOADING_SPEED_PERCENT.get());
            int spinnerFrame = (int) (System.nanoTime() / frameNanos % 24L);
            if (spinnerFrame != lastSpinnerFrame) {
                lastSpinnerFrame = spinnerFrame;
                dirty = true;
            }
        }
        if (!dirty) {
            return;
        }
        drawBackground();
        if (phase == Phase.ERROR) {
            drawError();
        } else {
            drawLoading(lastSpinnerFrame < 0 ? 0 : lastSpinnerFrame);
        }
        if (MineScreenConfig.WEB_LOADING_SHOW_MASCOT.get()) {
            int scale = Math.min(width, height) >= 240 ? 2 : 1;
            MineScreenAssistant.drawNative(image,
                    width - MineScreenAssistant.pixelWidth(scale) - 10,
                    height - MineScreenAssistant.pixelHeight(scale) - 8,
                    scale, phase == Phase.ERROR ? 210 : 235);
        }
        texture.upload();
        dirty = false;
    }

    private void drawBackground() {
        if (thumbnail == null || !MineScreenConfig.WEB_LOADING_SHOW_THUMBNAIL.get()) {
            int base = configColor(MineScreenConfig.WEB_LOADING_BACKGROUND_COLOR.get(),
                    rgba(16, 23, 34, 255));
            for (int y = 0; y < height; y++) {
                float shade = 0.84F + 0.24F * y / Math.max(1, height - 1);
                for (int x = 0; x < width; x++) {
                    image.setPixelRGBA(x, y, scaleColor(base, shade));
                }
            }
            int grid = scaleColor(base, 1.38F);
            for (int x = 0; x < width; x += Math.max(24, width / 16)) {
                fillRect(x, 0, 1, height, grid);
            }
            for (int y = 0; y < height; y += Math.max(24, height / 10)) {
                fillRect(0, y, width, 1, grid);
            }
            return;
        }
        int[] pixels = thumbnail.pixels();
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(thumbnail.height() - 1, y * thumbnail.height() / height);
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(thumbnail.width() - 1, x * thumbnail.width() / width);
                image.setPixelRGBA(x, y, darken(pixels[sourceY * thumbnail.width() + sourceX],
                        phase == Phase.LOADING ? 0.48F : 0.22F));
            }
        }
    }

    private void drawLoading(int frame) {
        int accent = configColor(MineScreenConfig.WEB_LOADING_ACCENT_COLOR.get(),
                rgba(255, 212, 59, 255));
        switch (MineScreenConfig.WEB_LOADING_STYLE.get()) {
            case ORBIT -> drawOrbit(frame, accent);
            case PULSE -> drawPulse(frame, accent);
            case MINIMAL -> drawMinimal(frame, accent);
        }
    }

    private void drawOrbit(int frame, int accent) {
        int centerX = width / 2;
        int centerY = Math.max(24, height / 2 - Math.max(8, height / 14));
        int radius = Math.max(11, Math.min(width, height) / 14);
        int dotRadius = Math.max(2, radius / 6);
        drawCircle(centerX, centerY, radius + dotRadius + 3, scaleColor(accent, 0.36F));
        for (int index = 0; index < 12; index++) {
            double angle = Math.PI * 2.0D * index / 12.0D - Math.PI / 2.0D;
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            int age = Math.floorMod(index - frame / 2, 12);
            fillCircle(x, y, dotRadius, scaleColor(accent, 1.0F - age * 0.055F));
        }
        drawCentered("LOADING", centerX, centerY + radius + dotRadius + 14,
                Math.max(2, Math.min(width, height) / 150), accent);
        drawProgressSweep(frame, accent);
    }

    private void drawPulse(int frame, int accent) {
        int centerX = width / 2;
        int centerY = height / 2 - Math.max(8, height / 15);
        double phase = frame / 24.0D * Math.PI * 2.0D;
        int baseRadius = Math.max(10, Math.min(width, height) / 18);
        int pulse = Math.max(2, (int) Math.round((Math.sin(phase) + 1.0D) * baseRadius / 4.0D));
        drawCircle(centerX, centerY, baseRadius + pulse, scaleColor(accent, 0.42F));
        drawCircle(centerX, centerY, Math.max(3, baseRadius - pulse / 2), accent);
        fillCircle(centerX, centerY, Math.max(2, baseRadius / 4), accent);
        drawCentered("CONNECTING", centerX, centerY + baseRadius + pulse + 16,
                Math.max(1, Math.min(width, height) / 160), accent);
        drawProgressSweep(frame, accent);
    }

    private void drawMinimal(int frame, int accent) {
        int centerX = width / 2;
        int centerY = height / 2 - 8;
        int scale = Math.max(1, Math.min(width, height) / 150);
        drawCentered("MINESCREEN", centerX, centerY - 9, scale, accent);
        drawCentered("LOADING", centerX, centerY + 11, Math.max(1, scale - 1),
                scaleColor(accent, 0.72F));
        drawProgressSweep(frame, accent);
    }

    private void drawProgressSweep(int frame, int accent) {
        int barWidth = Math.max(80, width / 3);
        int left = (width - barWidth) / 2;
        int top = Math.min(height - 12, height * 3 / 4);
        fillRect(left, top, barWidth, 2, scaleColor(accent, 0.28F));
        int segment = Math.max(12, barWidth / 5);
        int travel = barWidth + segment;
        int offset = frame * travel / 24 - segment;
        int start = Math.max(0, offset);
        int end = Math.min(barWidth, offset + segment);
        if (end > start) {
            fillRect(left + start, top, end - start, 2, accent);
        }
    }

    private void drawError() {
        int panelWidth = Math.min(width - 16, Math.max(150, width * 3 / 4));
        int panelHeight = Math.min(height - 16, Math.max(70, height / 2));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        fillRect(left, top, panelWidth, panelHeight, rgba(42, 12, 18, 240));
        fillRect(left, top, 4, panelHeight, rgba(255, 64, 76, 255));
        int scale = Math.max(2, Math.min(width, height) / 150);
        drawCentered("PAGE ERROR", width / 2, top + 17, scale, rgba(255, 105, 112, 255));
        String details = message.isBlank() ? "LOAD FAILED" : message.toUpperCase(Locale.ROOT);
        if (details.length() > 30) {
            details = details.substring(0, 30);
        }
        drawCentered(details, width / 2, top + panelHeight - 20, Math.max(1, scale - 1),
                rgba(235, 225, 228, 255));
    }

    private void drawCentered(String text, int centerX, int centerY, int scale, int color) {
        int glyphWidth = 3 * scale;
        int gap = scale;
        int total = text.isEmpty() ? 0 : text.length() * glyphWidth + (text.length() - 1) * gap;
        int x = centerX - total / 2;
        int y = centerY - 5 * scale / 2;
        for (int index = 0; index < text.length(); index++) {
            String[] glyph = glyph(text.charAt(index));
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if (glyph[row].charAt(column) == '1') {
                        fillRect(x + column * scale, y + row * scale, scale, scale, color);
                    }
                }
            }
            x += glyphWidth + gap;
        }
    }

    private static String[] glyph(char character) {
        return switch (character) {
            case 'A' -> new String[] {"010", "101", "111", "101", "101"};
            case 'B' -> new String[] {"110", "101", "110", "101", "110"};
            case 'C' -> new String[] {"011", "100", "100", "100", "011"};
            case 'D' -> new String[] {"110", "101", "101", "101", "110"};
            case 'E' -> new String[] {"111", "100", "110", "100", "111"};
            case 'F' -> new String[] {"111", "100", "110", "100", "100"};
            case 'G' -> new String[] {"011", "100", "101", "101", "011"};
            case 'H' -> new String[] {"101", "101", "111", "101", "101"};
            case 'I' -> new String[] {"111", "010", "010", "010", "111"};
            case 'J' -> new String[] {"001", "001", "001", "101", "010"};
            case 'K' -> new String[] {"101", "101", "110", "101", "101"};
            case 'L' -> new String[] {"100", "100", "100", "100", "111"};
            case 'M' -> new String[] {"101", "111", "111", "101", "101"};
            case 'N' -> new String[] {"101", "111", "111", "111", "101"};
            case 'O' -> new String[] {"010", "101", "101", "101", "010"};
            case 'P' -> new String[] {"110", "101", "110", "100", "100"};
            case 'Q' -> new String[] {"010", "101", "101", "111", "011"};
            case 'R' -> new String[] {"110", "101", "110", "101", "101"};
            case 'S' -> new String[] {"011", "100", "010", "001", "110"};
            case 'T' -> new String[] {"111", "010", "010", "010", "010"};
            case 'U' -> new String[] {"101", "101", "101", "101", "111"};
            case 'V' -> new String[] {"101", "101", "101", "101", "010"};
            case 'W' -> new String[] {"101", "101", "111", "111", "101"};
            case 'X' -> new String[] {"101", "101", "010", "101", "101"};
            case 'Y' -> new String[] {"101", "101", "010", "010", "010"};
            case 'Z' -> new String[] {"111", "001", "010", "100", "111"};
            case '0' -> new String[] {"111", "101", "101", "101", "111"};
            case '1' -> new String[] {"010", "110", "010", "010", "111"};
            case '2' -> new String[] {"110", "001", "010", "100", "111"};
            case '3' -> new String[] {"110", "001", "010", "001", "110"};
            case '4' -> new String[] {"101", "101", "111", "001", "001"};
            case '5' -> new String[] {"111", "100", "110", "001", "110"};
            case '6' -> new String[] {"011", "100", "111", "101", "111"};
            case '7' -> new String[] {"111", "001", "010", "010", "010"};
            case '8' -> new String[] {"111", "101", "111", "101", "111"};
            case '9' -> new String[] {"111", "101", "111", "001", "110"};
            case '-' -> new String[] {"000", "000", "111", "000", "000"};
            case ':' -> new String[] {"000", "010", "000", "010", "000"};
            case '.' -> new String[] {"000", "000", "000", "000", "010"};
            default -> EMPTY_GLYPH;
        };
    }

    private void fillRect(int x, int y, int rectangleWidth, int rectangleHeight, int color) {
        for (int py = Math.max(0, y); py < Math.min(height, y + rectangleHeight); py++) {
            for (int px = Math.max(0, x); px < Math.min(width, x + rectangleWidth); px++) {
                image.setPixelRGBA(px, py, color);
            }
        }
    }

    private void fillCircle(int centerX, int centerY, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + y * y <= radius * radius) {
                    int px = centerX + x;
                    int py = centerY + y;
                    if (px >= 0 && py >= 0 && px < width && py < height) {
                        image.setPixelRGBA(px, py, color);
                    }
                }
            }
        }
    }

    private void drawCircle(int centerX, int centerY, int radius, int color) {
        int inner = Math.max(0, radius - Math.max(1, radius / 8));
        int radiusSquared = radius * radius;
        int innerSquared = inner * inner;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                int distance = x * x + y * y;
                if (distance <= radiusSquared && distance >= innerSquared) {
                    int px = centerX + x;
                    int py = centerY + y;
                    if (px >= 0 && py >= 0 && px < width && py < height) {
                        image.setPixelRGBA(px, py, color);
                    }
                }
            }
        }
    }

    private static int darken(int abgr, float factor) {
        int alpha = abgr >>> 24;
        int blue = (int) (((abgr >>> 16) & 0xFF) * factor);
        int green = (int) (((abgr >>> 8) & 0xFF) * factor);
        int red = (int) ((abgr & 0xFF) * factor);
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    private static int configColor(String text, int fallback) {
        try {
            String hex = text == null ? "" : text.replace("#", "").trim();
            long argb = Long.parseLong(hex.length() == 6 ? "FF" + hex : hex, 16);
            int red = (int) (argb >>> 16) & 0xFF;
            int green = (int) (argb >>> 8) & 0xFF;
            int blue = (int) argb & 0xFF;
            int alpha = (int) (argb >>> 24) & 0xFF;
            return rgba(red, green, blue, alpha);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int scaleColor(int abgr, float factor) {
        int alpha = abgr >>> 24;
        int blue = Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * factor));
        int green = Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * factor));
        int red = Math.min(255, Math.round((abgr & 0xFF) * factor));
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation++;
        ScreenRenderType.release(location);
        Minecraft.getInstance().getTextureManager().release(location);
        texture = null;
        image = null;
    }

    private enum Phase {
        LOADING,
        READY,
        ERROR
    }
}
