package dev.minescreen.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import dev.minescreen.mixin.client.NativeImageAccessor;
import org.lwjgl.system.MemoryUtil;

/** Fast RGBA copy bridge; NativeImage stores its pixels in one native allocation. */
public final class NativeImageAccess {
    private NativeImageAccess() {
    }

    public static long address(NativeImage image) {
        if ((Object) image instanceof NativeImageAccessor accessor) {
            return accessor.minescreen$getPixels();
        }
        // Standalone decoder probes do not bootstrap Mixin. Keep a development-only fallback so
        // the exact FFmpeg/ring path remains testable; production always takes the remapped accessor.
        try {
            java.lang.reflect.Field pixels = NativeImage.class.getDeclaredField("pixels");
            pixels.setAccessible(true);
            return pixels.getLong(image);
        } catch (ReflectiveOperationException exception) {
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
