package dev.minescreen.client.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import dev.minescreen.MineScreenConfig;
import org.lwjgl.system.MemoryUtil;

/**
 * Minimal pure-Java RFB 3.3/3.7/3.8 client. It requests 32-bit little-endian true color and handles
 * Tight (zlib/palette/gradient/JPEG/PNG), Raw, CopyRect and DesktopSize rectangles. The worker only
 * performs socket/CPU work; immutable dirty rectangles cross to the render thread through a queue.
 */
public final class RfbClient implements AutoCloseable {
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_COPY_RECT = 1;
    private static final int ENCODING_TIGHT = 7;
    private static final int ENCODING_DESKTOP_SIZE = -223;
    private static final int ENCODING_LAST_RECT = -224;
    private static final int ENCODING_COMPRESS_LEVEL_0 = -256;
    private static final int ENCODING_QUALITY_LEVEL_0 = -32;
    private static final int MAX_UPDATE_QUEUE = 32;

    private final RfbEndpoint endpoint;
    private final String password;
    private final ArrayBlockingQueue<RfbFramebufferUpdate> updates =
            new ArrayBlockingQueue<>(MAX_UPDATE_QUEUE);
    private volatile boolean running = true;
    private volatile boolean updatesEnabled = true;
    private volatile String errorMessage;
    private volatile int width;
    private volatile int height;
    private byte[] framebuffer = new byte[0];
    private Socket socket;
    private DataOutputStream output;
    private Thread thread;
    private int buttonMask;
    private final TightDecoder tightDecoder = new TightDecoder();

    public RfbClient(RfbEndpoint endpoint, String password) {
        this.endpoint = endpoint;
        this.password = password == null ? "" : password;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::run, "minescreen-rfb-" + endpoint.host());
        thread.setDaemon(true);
        thread.start();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public RfbFramebufferUpdate pollUpdate() {
        return updates.poll();
    }

    public void setUpdatesEnabled(boolean enabled) {
        boolean changed = updatesEnabled != enabled;
        updatesEnabled = enabled;
        if (changed && enabled && output != null && width > 0 && height > 0) {
            try {
                requestFramebufferUpdate(false);
            } catch (IOException exception) {
                fail(exception);
            }
        }
    }

    public synchronized void pointerEvent(int x, int y, int mask) {
        if (output == null || !running) {
            return;
        }
        try {
            output.writeByte(5);
            output.writeByte(mask & 0xFF);
            output.writeShort(clamp(x, width));
            output.writeShort(clamp(y, height));
            output.flush();
            buttonMask = mask;
        } catch (IOException exception) {
            fail(exception);
        }
    }

    public int buttonMask() {
        return buttonMask;
    }

    public synchronized void keyEvent(boolean down, int keySym) {
        if (output == null || !running || keySym == 0) {
            return;
        }
        try {
            output.writeByte(4);
            output.writeByte(down ? 1 : 0);
            output.writeShort(0);
            output.writeInt(keySym);
            output.flush();
        } catch (IOException exception) {
            fail(exception);
        }
    }

    private void run() {
        try (Socket connected = new Socket()) {
            socket = connected;
            connected.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 10_000);
            connected.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(connected.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(connected.getOutputStream()));
            int protocolMinor = negotiateVersion(input);
            negotiateSecurity(input, protocolMinor);
            output.writeByte(1); // shared desktop; exclusivity is coordinated by MineScreen.
            output.flush();
            readServerInit(input);
            sendPixelFormat();
            sendEncodings();
            requestFramebufferUpdate(false);
            while (running) {
                int message = input.readUnsignedByte();
                switch (message) {
                    case 0 -> readFramebufferUpdate(input);
                    case 2 -> { // bell
                    }
                    case 3 -> readServerCutText(input);
                    default -> throw new IOException("Unsupported RFB server message: " + message);
                }
            }
        } catch (EOFException exception) {
            if (running) {
                fail(new IOException("VNC server closed the connection", exception));
            }
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            if (running) {
                fail(exception);
            }
        } finally {
            running = false;
            output = null;
        }
    }

    private int negotiateVersion(DataInputStream input) throws IOException {
        byte[] banner = input.readNBytes(12);
        if (banner.length != 12 || !new String(banner, StandardCharsets.US_ASCII).startsWith("RFB ")) {
            throw new IOException("Invalid RFB protocol banner");
        }
        int serverMinor;
        try {
            serverMinor = Integer.parseInt(new String(banner, 8, 3, StandardCharsets.US_ASCII));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid RFB version", exception);
        }
        int minor = serverMinor >= 8 ? 8 : serverMinor >= 7 ? 7 : 3;
        output.write(String.format(java.util.Locale.ROOT, "RFB 003.%03d\n", minor)
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return minor;
    }

    private void negotiateSecurity(DataInputStream input, int protocolMinor)
            throws IOException, GeneralSecurityException {
        int selected;
        if (protocolMinor == 3) {
            selected = input.readInt();
            if (selected == 0) {
                throw new IOException(readReason(input));
            }
        } else {
            int count = input.readUnsignedByte();
            if (count == 0) {
                throw new IOException(readReason(input));
            }
            byte[] offered = input.readNBytes(count);
            boolean none = contains(offered, 1);
            boolean vnc = contains(offered, 2);
            selected = !password.isEmpty() && vnc ? 2 : none ? 1 : vnc ? 2 : -1;
            if (selected < 0) {
                throw new IOException("VNC server offers no supported security type");
            }
            output.writeByte(selected);
            output.flush();
        }
        if (selected == 2) {
            byte[] challenge = input.readNBytes(16);
            if (challenge.length != 16) {
                throw new EOFException("Incomplete VNC authentication challenge");
            }
            output.write(vncChallengeResponse(challenge, password));
            output.flush();
        } else if (selected != 1) {
            throw new IOException("Unsupported RFB security type: " + selected);
        }
        if (protocolMinor >= 7 || selected == 2) {
            int result = input.readInt();
            if (result != 0) {
                String reason = protocolMinor >= 8 ? readReason(input) : "VNC authentication failed";
                throw new IOException(reason);
            }
        }
    }

    private void readServerInit(DataInputStream input) throws IOException {
        int nextWidth = input.readUnsignedShort();
        int nextHeight = input.readUnsignedShort();
        input.skipNBytes(16); // server pixel format; replaced immediately below.
        long nameLength = Integer.toUnsignedLong(input.readInt());
        if (nameLength > 1_048_576L) {
            throw new IOException("Unreasonable RFB desktop name length");
        }
        input.skipNBytes(nameLength);
        resizeFramebuffer(nextWidth, nextHeight);
    }

    private synchronized void sendPixelFormat() throws IOException {
        output.writeByte(0);
        output.write(new byte[3]);
        output.writeByte(32); // bits per pixel
        output.writeByte(24); // depth
        output.writeByte(0);  // little endian
        output.writeByte(1);  // true color
        output.writeShort(255);
        output.writeShort(255);
        output.writeShort(255);
        output.writeByte(0);  // red shift => wire bytes R,G,B,x
        output.writeByte(8);
        output.writeByte(16);
        output.write(new byte[3]);
        output.flush();
    }

    private synchronized void sendEncodings() throws IOException {
        int compression = MineScreenConfig.VNC_COMPRESSION_LEVEL.get();
        int quality = MineScreenConfig.VNC_JPEG_QUALITY.get();
        output.writeByte(2);
        output.writeByte(0);
        output.writeShort(7);
        output.writeInt(ENCODING_TIGHT);
        output.writeInt(ENCODING_COPY_RECT);
        output.writeInt(ENCODING_RAW);
        output.writeInt(ENCODING_DESKTOP_SIZE);
        output.writeInt(ENCODING_LAST_RECT);
        output.writeInt(ENCODING_COMPRESS_LEVEL_0 + compression);
        output.writeInt(ENCODING_QUALITY_LEVEL_0 + quality);
        output.flush();
    }

    private void readFramebufferUpdate(DataInputStream input) throws IOException {
        input.readUnsignedByte();
        int rectangles = input.readUnsignedShort();
        for (int i = 0; i < rectangles; i++) {
            int x = input.readUnsignedShort();
            int y = input.readUnsignedShort();
            int rectangleWidth = input.readUnsignedShort();
            int rectangleHeight = input.readUnsignedShort();
            int encoding = input.readInt();
            if (encoding == ENCODING_DESKTOP_SIZE) {
                resizeFramebuffer(rectangleWidth, rectangleHeight);
            } else if (encoding == ENCODING_LAST_RECT) {
                break;
            } else if (encoding == ENCODING_TIGHT) {
                readTightRectangle(input, x, y, rectangleWidth, rectangleHeight);
            } else if (encoding == ENCODING_RAW) {
                readRawRectangle(input, x, y, rectangleWidth, rectangleHeight);
            } else if (encoding == ENCODING_COPY_RECT) {
                int sourceX = input.readUnsignedShort();
                int sourceY = input.readUnsignedShort();
                copyRectangle(sourceX, sourceY, x, y, rectangleWidth, rectangleHeight);
            } else {
                throw new IOException("Unsupported RFB rectangle encoding: " + encoding);
            }
        }
        if (updatesEnabled && running) {
            requestFramebufferUpdate(true);
        }
    }

    private void readTightRectangle(DataInputStream input, int x, int y, int rectangleWidth,
            int rectangleHeight) throws IOException {
        validateRectangle(x, y, rectangleWidth, rectangleHeight);
        byte[] rgba = tightDecoder.decode(input, rectangleWidth, rectangleHeight);
        for (int row = 0; row < rectangleHeight; row++) {
            System.arraycopy(rgba, row * rectangleWidth * 4, framebuffer,
                    ((y + row) * width + x) * 4, rectangleWidth * 4);
        }
        publish(x, y, rectangleWidth, rectangleHeight);
    }

    private void readRawRectangle(DataInputStream input, int x, int y, int rectangleWidth,
            int rectangleHeight) throws IOException {
        validateRectangle(x, y, rectangleWidth, rectangleHeight);
        long bytes = (long) rectangleWidth * rectangleHeight * 4L;
        if (bytes > Integer.MAX_VALUE) {
            throw new IOException("RFB rectangle too large");
        }
        byte[] wire = input.readNBytes((int) bytes);
        if (wire.length != bytes) {
            throw new EOFException("Incomplete RFB rectangle");
        }
        int inputOffset = 0;
        for (int row = 0; row < rectangleHeight; row++) {
            int destination = ((y + row) * width + x) * 4;
            for (int column = 0; column < rectangleWidth; column++) {
                framebuffer[destination++] = wire[inputOffset++];
                framebuffer[destination++] = wire[inputOffset++];
                framebuffer[destination++] = wire[inputOffset++];
                inputOffset++; // unused high byte in requested 24-bit depth
                framebuffer[destination++] = (byte) 0xFF;
            }
        }
        publish(x, y, rectangleWidth, rectangleHeight);
    }

    private void copyRectangle(int sourceX, int sourceY, int x, int y, int rectangleWidth,
            int rectangleHeight) throws IOException {
        validateRectangle(sourceX, sourceY, rectangleWidth, rectangleHeight);
        validateRectangle(x, y, rectangleWidth, rectangleHeight);
        byte[] copy = new byte[rectangleWidth * rectangleHeight * 4];
        for (int row = 0; row < rectangleHeight; row++) {
            System.arraycopy(framebuffer, ((sourceY + row) * width + sourceX) * 4,
                    copy, row * rectangleWidth * 4, rectangleWidth * 4);
        }
        for (int row = 0; row < rectangleHeight; row++) {
            System.arraycopy(copy, row * rectangleWidth * 4,
                    framebuffer, ((y + row) * width + x) * 4, rectangleWidth * 4);
        }
        publish(x, y, rectangleWidth, rectangleHeight);
    }

    private void resizeFramebuffer(int nextWidth, int nextHeight) throws IOException {
        long pixels = (long) nextWidth * nextHeight;
        if (nextWidth < 1 || nextHeight < 1 || pixels > MineScreenConfig.MAX_CANVAS_PIXELS.get()) {
            throw new IOException("VNC desktop exceeds max_canvas_pixels: " + nextWidth + "x" + nextHeight);
        }
        width = nextWidth;
        height = nextHeight;
        framebuffer = new byte[Math.toIntExact(pixels * 4L)];
        for (int index = 3; index < framebuffer.length; index += 4) {
            framebuffer[index] = (byte) 0xFF;
        }
        publish(0, 0, width, height);
    }

    private void publish(int x, int y, int rectangleWidth, int rectangleHeight) {
        RfbFramebufferUpdate update = snapshot(x, y, rectangleWidth, rectangleHeight);
        if (updates.offer(update)) {
            return;
        }
        update.close();
        // Dropping an arbitrary dirty rectangle could leave the GPU texture permanently stale.
        // Collapse backlog to one current full snapshot instead.
        RfbFramebufferUpdate dropped;
        while ((dropped = updates.poll()) != null) {
            dropped.close();
        }
        RfbFramebufferUpdate full = snapshot(0, 0, width, height);
        if (!updates.offer(full)) {
            full.close();
        }
    }

    private RfbFramebufferUpdate snapshot(int x, int y, int rectangleWidth, int rectangleHeight) {
        int length = Math.multiplyExact(Math.multiplyExact(rectangleWidth, rectangleHeight), 4);
        ByteBuffer rgba = MemoryUtil.memAlloc(length);
        for (int row = 0; row < rectangleHeight; row++) {
            rgba.put(framebuffer, ((y + row) * width + x) * 4, rectangleWidth * 4);
        }
        rgba.flip();
        return new RfbFramebufferUpdate(width, height, x, y, rectangleWidth, rectangleHeight, rgba);
    }

    private synchronized void requestFramebufferUpdate(boolean incremental) throws IOException {
        if (output == null || width <= 0 || height <= 0) {
            return;
        }
        output.writeByte(3);
        output.writeByte(incremental ? 1 : 0);
        output.writeShort(0);
        output.writeShort(0);
        output.writeShort(width);
        output.writeShort(height);
        output.flush();
    }

    private static byte[] vncChallengeResponse(byte[] challenge, String password)
            throws GeneralSecurityException {
        byte[] key = Arrays.copyOf(password.getBytes(StandardCharsets.ISO_8859_1), 8);
        for (int i = 0; i < key.length; i++) {
            key[i] = reverseBits(key[i]);
        }
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return cipher.doFinal(challenge);
    }

    private static byte reverseBits(byte value) {
        int input = value & 0xFF;
        int output = 0;
        for (int bit = 0; bit < 8; bit++) {
            output = (output << 1) | (input & 1);
            input >>>= 1;
        }
        return (byte) output;
    }

    private static boolean contains(byte[] values, int target) {
        for (byte value : values) {
            if ((value & 0xFF) == target) {
                return true;
            }
        }
        return false;
    }

    private static String readReason(DataInputStream input) throws IOException {
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > 1_048_576L) {
            throw new IOException("Unreasonable RFB error length");
        }
        return new String(input.readNBytes((int) length), StandardCharsets.UTF_8);
    }

    private static void readServerCutText(DataInputStream input) throws IOException {
        input.skipNBytes(3);
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > 16_777_216L) {
            throw new IOException("Unreasonable RFB clipboard length");
        }
        input.skipNBytes(length);
    }

    private void validateRectangle(int x, int y, int rectangleWidth, int rectangleHeight)
            throws IOException {
        if (rectangleWidth < 0 || rectangleHeight < 0 || x < 0 || y < 0
                || x + rectangleWidth > width || y + rectangleHeight > height) {
            throw new IOException("RFB rectangle outside framebuffer");
        }
    }

    private void fail(Exception exception) {
        errorMessage = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getMessage();
        running = false;
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int clamp(int value, int extent) {
        return Math.max(0, Math.min(Math.max(0, extent - 1), value));
    }

    @Override
    public void close() {
        running = false;
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        RfbFramebufferUpdate update;
        while ((update = updates.poll()) != null) {
            update.close();
        }
        tightDecoder.close();
    }
}
