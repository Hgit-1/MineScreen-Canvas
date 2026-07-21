package dev.minescreen.client.vnc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.zip.Deflater;

/** Dependency-free protocol vectors executed by Gradle's tightDecoderTest task. */
public final class TightDecoderTestHarness {
    private TightDecoderTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        try (TightDecoder decoder = new TightDecoder()) {
            assertBytes("fill", decoder.decode(input(0x80, 0x12, 0x34, 0x56), 2, 1),
                    rgba(0x12, 0x34, 0x56, 0x12, 0x34, 0x56));
            assertBytes("copy", decoder.decode(input(0x00, 1, 2, 3), 1, 1),
                    rgba(1, 2, 3));
            assertBytes("palette", decoder.decode(input(0x40, 1, 1,
                    10, 20, 30, 40, 50, 60, 0x40, 0x80), 2, 2),
                    rgba(10, 20, 30, 40, 50, 60, 40, 50, 60, 10, 20, 30));
            assertBytes("gradient", decoder.decode(input(0x40, 2,
                    10, 20, 30, 10, 20, 30), 2, 1),
                    rgba(10, 20, 30, 20, 40, 60));

            byte[] rgb = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            byte[] compressed = deflate(rgb);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(0x01); // reset stream 0, basic copy filter
            writeCompact(payload, compressed.length);
            payload.write(compressed);
            assertBytes("zlib", decoder.decode(new DataInputStream(
                    new ByteArrayInputStream(payload.toByteArray())), 2, 2),
                    rgba(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));

            Deflater persistent = new Deflater(9);
            try {
                byte[] firstPixels = {12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
                byte[] secondPixels = {21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
                assertBytes("persistent-zlib-first",
                        decoder.decode(compressedInput(0x01,
                                deflateChunk(persistent, firstPixels)), 2, 2),
                        rgba(12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1));
                assertBytes("persistent-zlib-second",
                        decoder.decode(compressedInput(0x00,
                                deflateChunk(persistent, secondPixels)), 2, 2),
                        rgba(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32));
            } finally {
                persistent.end();
            }
        }
        RfbClientTestHarness.run();
    }

    private static DataInputStream input(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static byte[] rgba(int... rgb) {
        byte[] result = new byte[rgb.length / 3 * 4];
        int input = 0;
        int output = 0;
        while (input < rgb.length) {
            result[output++] = (byte) rgb[input++];
            result[output++] = (byte) rgb[input++];
            result[output++] = (byte) rgb[input++];
            result[output++] = (byte) 0xFF;
        }
        return result;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(9);
        try {
            deflater.setInput(input);
            byte[] output = new byte[128];
            int length = deflater.deflate(output, 0, output.length, Deflater.SYNC_FLUSH);
            return Arrays.copyOf(output, length);
        } finally {
            deflater.end();
        }
    }

    private static byte[] deflateChunk(Deflater deflater, byte[] input) {
        deflater.setInput(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        do {
            int length = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
            output.write(buffer, 0, length);
        } while (!deflater.needsInput());
        return output.toByteArray();
    }

    private static DataInputStream compressedInput(int control, byte[] compressed) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(control);
        writeCompact(output, compressed.length);
        output.writeBytes(compressed);
        return new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
    }

    private static void writeCompact(ByteArrayOutputStream output, int value) {
        int first = value & 0x7F;
        value >>>= 7;
        if (value == 0) {
            output.write(first);
            return;
        }
        output.write(first | 0x80);
        int second = value & 0x7F;
        value >>>= 7;
        if (value == 0) {
            output.write(second);
            return;
        }
        output.write(second | 0x80);
        output.write(value & 0xFF);
    }

    private static void assertBytes(String name, byte[] actual, byte[] expected) {
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError(name + " mismatch\nexpected=" + Arrays.toString(expected)
                    + "\nactual=" + Arrays.toString(actual));
        }
    }
}
