package dev.minescreen.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;

/** Small original pixel-art display assistant used as optional, non-interactive UI decoration. */
public final class MineScreenAssistant {
    private static final String[] PIXELS = {
            "....HHHH....",
            "..HHHHHHHH..",
            ".HHHHHHHHHH.",
            ".HHFFFFFFHH.",
            "HHFFFFFFFFHH",
            "HHFEEFFEEFHH",
            "HFFFFFFFFFFH",
            "HFFFFSFFFFFH",
            ".HFFSSSFFFH.",
            ".HHFFFFFFHH.",
            "..HFFFFFFH..",
            "..JJJHHJJJ..",
            ".JJJJJJJJJJ.",
            ".JJAJJJJAJJ.",
            "..JJJJJJJJ..",
            "...JJ..JJ..."
    };

    private MineScreenAssistant() {
    }

    public static void drawGui(GuiGraphics graphics, int left, int top, int scale, int alpha) {
        for (int row = 0; row < PIXELS.length; row++) {
            String line = PIXELS[row];
            for (int column = 0; column < line.length(); column++) {
                int color = color(line.charAt(column), alpha);
                if (color != 0) {
                    graphics.fill(left + column * scale, top + row * scale,
                            left + (column + 1) * scale, top + (row + 1) * scale, color);
                }
            }
        }
    }

    public static void drawNative(NativeImage image, int left, int top, int scale, int alpha) {
        for (int row = 0; row < PIXELS.length; row++) {
            String line = PIXELS[row];
            for (int column = 0; column < line.length(); column++) {
                int argb = color(line.charAt(column), alpha);
                if (argb == 0) {
                    continue;
                }
                int abgr = argbToAbgr(argb);
                for (int y = 0; y < scale; y++) {
                    int py = top + row * scale + y;
                    if (py < 0 || py >= image.getHeight()) {
                        continue;
                    }
                    for (int x = 0; x < scale; x++) {
                        int px = left + column * scale + x;
                        if (px >= 0 && px < image.getWidth()) {
                            image.setPixelRGBA(px, py, abgr);
                        }
                    }
                }
            }
        }
    }

    public static int pixelWidth(int scale) {
        return 12 * scale;
    }

    public static int pixelHeight(int scale) {
        return PIXELS.length * scale;
    }

    private static int color(char pixel, int alpha) {
        int rgb = switch (pixel) {
            case 'H' -> 0x27334A; // hair
            case 'F' -> 0xF3C9B7; // face
            case 'E' -> 0x55E6E6; // eyes
            case 'S' -> 0xD87386; // expression
            case 'J' -> 0xE8EDF6; // jacket
            case 'A' -> 0xFFD43B; // MineScreen accent
            default -> -1;
        };
        return rgb < 0 ? 0 : Math.max(0, Math.min(255, alpha)) << 24 | rgb;
    }

    private static int argbToAbgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0x00FF0000) >>> 16
                | (argb & 0x000000FF) << 16;
    }
}
