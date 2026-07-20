package dev.minescreen.client.vnc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;

/**
 * RFB Tight decoder with the four persistent zlib streams defined by the TightVNC protocol.
 * Supports Copy, Palette, Gradient, Fill, JPEG and TightPNG subencodings. Output is always RGBA.
 */
final class TightDecoder implements AutoCloseable {
    private static final int FILTER_COPY = 0;
    private static final int FILTER_PALETTE = 1;
    private static final int FILTER_GRADIENT = 2;
    private static final int SUBENCODING_FILL = 8;
    private static final int SUBENCODING_JPEG = 9;
    private static final int SUBENCODING_PNG = 10;
    private static final int MAX_COMPACT_LENGTH = 0x3FFFFF;

    private final Inflater[] streams = {
            new Inflater(), new Inflater(), new Inflater(), new Inflater()
    };

    byte[] decode(DataInputStream input, int width, int height) throws IOException {
        int pixels = Math.multiplyExact(width, height);
        byte[] rgba = new byte[Math.multiplyExact(pixels, 4)];
        int control = input.readUnsignedByte();
        int resetMask = control & 0x0F;
        for (int stream = 0; stream < streams.length; stream++) {
            if ((resetMask & 1 << stream) != 0) {
                streams[stream].reset();
            }
        }
        int subencoding = control >>> 4;
        if (subencoding == SUBENCODING_FILL) {
            byte[] color = readExact(input, 3);
            for (int offset = 0; offset < rgba.length; offset += 4) {
                rgba[offset] = color[0];
                rgba[offset + 1] = color[1];
                rgba[offset + 2] = color[2];
                rgba[offset + 3] = (byte) 0xFF;
            }
            return rgba;
        }
        if (subencoding == SUBENCODING_JPEG || subencoding == SUBENCODING_PNG) {
            return decodeImage(input, width, height, subencoding == SUBENCODING_JPEG ? "JPEG" : "PNG");
        }
        if (subencoding > 7) {
            throw new IOException("Unsupported Tight subencoding: " + subencoding);
        }

        int streamId = subencoding & 0x03;
        int filter = (subencoding & 0x04) == 0 ? FILTER_COPY : input.readUnsignedByte();
        return switch (filter) {
            case FILTER_COPY -> decodeCopy(input, streamId, pixels, rgba);
            case FILTER_PALETTE -> decodePalette(input, streamId, width, height, rgba);
            case FILTER_GRADIENT -> decodeGradient(input, streamId, width, height, rgba);
            default -> throw new IOException("Unsupported Tight filter: " + filter);
        };
    }

    private byte[] decodeCopy(DataInputStream input, int streamId, int pixels, byte[] rgba)
            throws IOException {
        byte[] rgb = readBasicData(input, streamId, Math.multiplyExact(pixels, 3));
        rgbToRgba(rgb, rgba);
        return rgba;
    }

    private byte[] decodePalette(DataInputStream input, int streamId, int width, int height,
            byte[] rgba) throws IOException {
        int paletteSize = input.readUnsignedByte() + 1;
        byte[] palette = readExact(input, Math.multiplyExact(paletteSize, 3));
        int rowBytes = paletteSize == 2 ? (width + 7) / 8 : width;
        byte[] indices = readBasicData(input, streamId, Math.multiplyExact(rowBytes, height));
        int output = 0;
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowBytes;
            for (int x = 0; x < width; x++) {
                int index = paletteSize == 2
                        ? (indices[rowStart + (x >>> 3)] >>> (7 - (x & 7))) & 1
                        : indices[rowStart + x] & 0xFF;
                if (index >= paletteSize) {
                    throw new IOException("Tight palette index outside palette: " + index);
                }
                int color = index * 3;
                rgba[output++] = palette[color];
                rgba[output++] = palette[color + 1];
                rgba[output++] = palette[color + 2];
                rgba[output++] = (byte) 0xFF;
            }
        }
        return rgba;
    }

    private byte[] decodeGradient(DataInputStream input, int streamId, int width, int height,
            byte[] rgba) throws IOException {
        byte[] deltas = readBasicData(input, streamId,
                Math.multiplyExact(Math.multiplyExact(width, height), 3));
        int[] previous = new int[Math.multiplyExact(width, 3)];
        int[] current = new int[previous.length];
        int source = 0;
        int output = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = x * 3;
                for (int channel = 0; channel < 3; channel++) {
                    int left = x == 0 ? 0 : current[pixel - 3 + channel];
                    int up = previous[pixel + channel];
                    int upperLeft = x == 0 ? 0 : previous[pixel - 3 + channel];
                    int estimate = Math.max(0, Math.min(255, left + up - upperLeft));
                    int value = (estimate + (deltas[source++] & 0xFF)) & 0xFF;
                    current[pixel + channel] = value;
                    rgba[output++] = (byte) value;
                }
                rgba[output++] = (byte) 0xFF;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            Arrays.fill(current, 0);
        }
        return rgba;
    }

    private byte[] decodeImage(DataInputStream input, int width, int height, String kind)
            throws IOException {
        int length = readCompactLength(input);
        byte[] encoded = readExact(input, length);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("Invalid Tight " + kind + " rectangle dimensions");
        }
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[Math.multiplyExact(argb.length, 4)];
        int output = 0;
        for (int color : argb) {
            rgba[output++] = (byte) (color >>> 16);
            rgba[output++] = (byte) (color >>> 8);
            rgba[output++] = (byte) color;
            rgba[output++] = (byte) 0xFF;
        }
        return rgba;
    }

    private byte[] readBasicData(DataInputStream input, int streamId, int expectedLength)
            throws IOException {
        if (expectedLength < 12) {
            return readExact(input, expectedLength);
        }
        int compressedLength = readCompactLength(input);
        byte[] compressed = readExact(input, compressedLength);
        byte[] decoded = new byte[expectedLength];
        Inflater inflater = streams[streamId];
        inflater.setInput(compressed);
        int offset = 0;
        try {
            while (offset < decoded.length) {
                int count = inflater.inflate(decoded, offset, decoded.length - offset);
                if (count > 0) {
                    offset += count;
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new IOException("Tight zlib stream requested a dictionary");
                }
                if (inflater.needsInput() || inflater.finished()) {
                    break;
                }
                throw new IOException("Tight zlib stream made no progress");
            }
        } catch (DataFormatException exception) {
            throw new IOException("Invalid Tight zlib stream", exception);
        }
        if (offset != decoded.length) {
            throw new EOFException("Incomplete Tight rectangle: " + offset + "/" + decoded.length);
        }
        return decoded;
    }

    private static int readCompactLength(DataInputStream input) throws IOException {
        int first = input.readUnsignedByte();
        int length = first & 0x7F;
        if ((first & 0x80) != 0) {
            int second = input.readUnsignedByte();
            length |= (second & 0x7F) << 7;
            if ((second & 0x80) != 0) {
                length |= input.readUnsignedByte() << 14;
            }
        }
        if (length < 0 || length > MAX_COMPACT_LENGTH) {
            throw new IOException("Invalid Tight compact length: " + length);
        }
        return length;
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Incomplete Tight payload");
        }
        return bytes;
    }

    private static void rgbToRgba(byte[] rgb, byte[] rgba) {
        int input = 0;
        int output = 0;
        while (input < rgb.length) {
            rgba[output++] = rgb[input++];
            rgba[output++] = rgb[input++];
            rgba[output++] = rgb[input++];
            rgba[output++] = (byte) 0xFF;
        }
    }

    @Override
    public void close() {
        for (Inflater stream : streams) {
            stream.end();
        }
    }
}
