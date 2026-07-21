package dev.minescreen.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureManager;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import dev.minescreen.MineScreen;
import dev.minescreen.client.content.ScreenRenderSource;

/** Owns the persistent IDLE color-test texture; renderers never allocate it per tick. */
public final class ScreenTextureManager {
    private static final int WIDTH = 512;
    private static final int HEIGHT = 288;
    private static final ResourceLocation IDLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "dynamic/idle_pattern");
    private static final ResourceLocation BLACK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "dynamic/powered_off");
    private static volatile boolean initialized;

    private ScreenTextureManager() {
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ScreenTextureManager::initialize);
    }

    public static ResourceLocation textureLocation() {
        return IDLE_TEXTURE;
    }

    public static ScreenRenderSource idleRenderSource() {
        return new ScreenRenderSource(ScreenRenderType.screen(IDLE_TEXTURE),
                () -> RenderSystem.setShaderTexture(0, IDLE_TEXTURE));
    }

    public static ScreenRenderSource blackRenderSource() {
        return new ScreenRenderSource(ScreenRenderType.screen(BLACK_TEXTURE),
                () -> RenderSystem.setShaderTexture(0, BLACK_TEXTURE));
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        TextureManager textureManager = minecraft.getTextureManager();
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);

        int[] bars = {
                rgba(255, 255, 255), rgba(255, 220, 48), rgba(64, 220, 255), rgba(80, 220, 96),
                rgba(255, 80, 210), rgba(255, 72, 72), rgba(64, 96, 255), rgba(20, 24, 32)
        };
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int bar = Math.min(bars.length - 1, x * bars.length / WIDTH);
                int color = y < HEIGHT * 2 / 3 ? bars[bar]
                        : rgba(18 + x * 24 / WIDTH, 22 + x * 24 / WIDTH, 31 + x * 32 / WIDTH);
                image.setPixelRGBA(x, y, color);
            }
        }
        // Subtle safe-area/grid marks make orientation and joined-tile continuity easy to verify.
        for (int x = 0; x < WIDTH; x++) {
            image.setPixelRGBA(x, HEIGHT / 2, rgba(255, 255, 255));
        }
        for (int y = 0; y < HEIGHT; y++) {
            image.setPixelRGBA(WIDTH / 2, y, rgba(255, 255, 255));
        }
        fill(image, WIDTH / 2 - 82, HEIGHT / 2 - 30, 164, 60, rgba(10, 13, 20));
        drawWord(image, "IDLE", WIDTH / 2, HEIGHT / 2, 9, rgba(255, 221, 59));

        textureManager.register(IDLE_TEXTURE, new DynamicTexture(image));
        NativeImage black = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        black.setPixelRGBA(0, 0, rgba(0, 0, 0));
        textureManager.register(BLACK_TEXTURE, new DynamicTexture(black));
        initialized = true;
    }

    private static void fill(NativeImage image, int x, int y, int width, int height, int color) {
        for (int py = Math.max(0, y); py < Math.min(HEIGHT, y + height); py++) {
            for (int px = Math.max(0, x); px < Math.min(WIDTH, x + width); px++) {
                image.setPixelRGBA(px, py, color);
            }
        }
    }

    private static void drawWord(NativeImage image, String text, int centerX, int centerY,
            int scale, int color) {
        String[] glyphs = {
                "111010010010111", // I
                "110101101101110", // D
                "100100100100111", // L
                "111100110100111"  // E
        };
        int glyphWidth = 3 * scale;
        int gap = scale;
        int totalWidth = text.length() * glyphWidth + (text.length() - 1) * gap;
        int startX = centerX - totalWidth / 2;
        int startY = centerY - 5 * scale / 2;
        for (int index = 0; index < text.length(); index++) {
            String glyph = glyphs[index];
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if (glyph.charAt(row * 3 + column) == '1') {
                        fill(image, startX + index * (glyphWidth + gap) + column * scale,
                                startY + row * scale, scale, scale, color);
                    }
                }
            }
        }
    }

    private static int rgba(int red, int green, int blue) {
        return 0xFF000000 | blue << 16 | green << 8 | red;
    }
}
