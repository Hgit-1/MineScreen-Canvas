package dev.minescreen.client.web;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/**
 * Small client-local WEB snapshots used only while Chromium restores a page. GPU readback happens
 * once when a visible tab closes; scaling, JPEG encoding and disk IO run on one bounded worker.
 */
final class WebThumbnailCache {
    static final int MAX_WIDTH = 640;
    static final int MAX_HEIGHT = 360;
    private static final int MAX_FILES = 128;
    private static final float JPEG_QUALITY = 0.72F;
    private static final Path DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("minescreen-web-thumbnails");
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "minescreen-web-thumbnail");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private WebThumbnailCache() {
    }

    static void load(UUID screenId, String url, Consumer<Thumbnail> receiver) {
        Path file = file(screenId, url);
        try {
            IO.execute(() -> {
                Thumbnail thumbnail = read(file);
                if (thumbnail != null) {
                    Minecraft minecraft = Minecraft.getInstance();
                    minecraft.execute(() -> receiver.accept(thumbnail));
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Must be called before MCEF destroys the texture and only from Minecraft's render thread. */
    static void capture(UUID screenId, String url, int textureId, int sourceWidth,
            int sourceHeight) {
        if (textureId <= 0 || sourceWidth < 2 || sourceHeight < 2
                || url == null || url.isBlank() || !RenderSystem.isOnRenderThread()) {
            return;
        }
        long byteCount = (long) sourceWidth * sourceHeight * 4L;
        if (byteCount > Integer.MAX_VALUE) {
            return;
        }
        ByteBuffer pixels = null;
        try {
            pixels = MemoryUtil.memAlloc((int) byteCount);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, pixels);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            }
            byte[] rgba = new byte[(int) byteCount];
            pixels.get(0, rgba);
            Path file = file(screenId, url);
            try {
                IO.execute(() -> write(file, rgba, sourceWidth, sourceHeight));
            } catch (RejectedExecutionException ignored) {
            }
        } catch (RuntimeException ignored) {
            // A disappearing CEF texture should never prevent the browser/session from closing.
        } finally {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private static Thumbnail read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            int[] abgr = new int[argb.length];
            for (int index = 0; index < argb.length; index++) {
                int pixel = argb[index];
                abgr[index] = pixel & 0xFF00FF00
                        | (pixel & 0x00FF0000) >>> 16
                        | (pixel & 0x000000FF) << 16;
            }
            return new Thumbnail(width, height, abgr);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void write(Path file, byte[] rgba, int sourceWidth, int sourceHeight) {
        double scale = Math.min(1.0D, Math.min(MAX_WIDTH / (double) sourceWidth,
                MAX_HEIGHT / (double) sourceHeight));
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int output = 0;
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(sourceHeight - 1, y * sourceHeight / height);
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(sourceWidth - 1, x * sourceWidth / width);
                int input = (sourceY * sourceWidth + sourceX) * 4;
                bgr[output++] = rgba[input + 2];
                bgr[output++] = rgba[input + 1];
                bgr[output++] = rgba[input];
            }
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(DIRECTORY);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(temporary.toFile())) {
                writer.setOutput(stream);
                ImageWriteParam parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally {
                writer.dispose();
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            pruneOldFiles();
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
            }
        }
    }

    private static void pruneOldFiles() {
        try (java.util.stream.Stream<Path> files = Files.list(DIRECTORY)) {
            java.util.List<Path> thumbnails = files
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted(java.util.Comparator.comparingLong(WebThumbnailCache::modifiedAt)
                            .reversed())
                    .toList();
            for (int index = MAX_FILES; index < thumbnails.size(); index++) {
                Files.deleteIfExists(thumbnails.get(index));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Path file(UUID screenId, String url) {
        String cleanUrl = url == null ? "" : url;
        String digest;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(cleanUrl.getBytes(StandardCharsets.UTF_8));
            digest = HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            digest = Integer.toUnsignedString(cleanUrl.hashCode(), 16);
        }
        return DIRECTORY.resolve(screenId.toString() + "-" + digest + ".jpg");
    }

    record Thumbnail(int width, int height, int[] pixels) {
    }
}
