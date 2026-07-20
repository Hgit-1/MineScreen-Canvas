package dev.minescreen.client.video;

import java.lang.reflect.Field;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/** Fast RGBA copy bridge; NativeImage stores its pixels in one native allocation. */
public final class NativeImageAccess {
    private static final Field PIXELS;

    static {
        try {
            PIXELS = NativeImage.class.getDeclaredField("pixels");
            PIXELS.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private NativeImageAccess() {
    }

    public static long address(NativeImage image) {
        try {
            return PIXELS.getLong(image);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access NativeImage storage", exception);
        }
    }

    public static void copyRgba(NativeImage image, long sourceAddress, long bytes) {
        MemoryUtil.memCopy(sourceAddress, address(image), bytes);
    }

    public static void clear(NativeImage image, long bytes) {
        MemoryUtil.memSet(address(image), 0, bytes);
    }

    public static void copyRgbaLetterboxed(NativeImage image, long sourceAddress, int sourceWidth,
            int sourceHeight, int destinationWidth, int offsetX, int offsetY) {
        long destination = address(image);
        long rowBytes = (long) sourceWidth * 4L;
        for (int y = 0; y < sourceHeight; y++) {
            MemoryUtil.memCopy(sourceAddress + y * rowBytes,
                    destination + ((long) (offsetY + y) * destinationWidth + offsetX) * 4L, rowBytes);
        }
    }

    public static void copyRgbaRegion(NativeImage image, long sourceAddress, int sourceWidth,
            int sourceHeight, int destinationWidth, int destinationX, int destinationY) {
        long destination = address(image);
        long rowBytes = (long) sourceWidth * 4L;
        for (int y = 0; y < sourceHeight; y++) {
            MemoryUtil.memCopy(sourceAddress + y * rowBytes,
                    destination + ((long) (destinationY + y) * destinationWidth + destinationX) * 4L,
                    rowBytes);
        }
    }
}
