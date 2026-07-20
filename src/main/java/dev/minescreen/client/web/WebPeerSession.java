package dev.minescreen.client.web;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.network.WebPeerDirectoryPayload;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/**
 * One peer-distributed browser surface. A deterministic binary tree limits every source/relay to
 * two normal children; unreachable parents fall back toward the root. JPEG bytes are relayed
 * unchanged, so intermediate players neither decode/re-encode for forwarding nor reduce quality.
 */
final class WebPeerSession {
    static final int MESSAGE_FRAME = 1;
    static final int MESSAGE_STATE = 2;
    static final int MESSAGE_COMMAND = 3;
    static final int MESSAGE_INPUT = 4;

    static final int COMMAND_NAVIGATE = 1;
    static final int COMMAND_BACK = 2;
    static final int COMMAND_FORWARD = 3;
    static final int COMMAND_RELOAD = 4;
    static final int COMMAND_OPEN_TAB = 5;
    static final int COMMAND_ACTIVATE_TAB = 6;
    static final int COMMAND_CLOSE_TAB = 7;

    static final int INPUT_FOCUS = 1;
    static final int INPUT_MOUSE_MOVE = 2;
    static final int INPUT_MOUSE_PRESS = 3;
    static final int INPUT_MOUSE_RELEASE = 4;
    static final int INPUT_MOUSE_WHEEL = 5;
    static final int INPUT_KEY_PRESS = 6;
    static final int INPUT_KEY_RELEASE = 7;
    static final int INPUT_KEY_TYPED = 8;

    private static final int MAX_MESSAGE_BYTES = 8_500_000;
    private static final int MAX_JPEG_BYTES = 8_388_608;
    private static final ExecutorService CODEC_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "minescreen-web-peer-codec");
        thread.setDaemon(true);
        return thread;
    });

    private final UUID groupId;
    private final McefBrowserSession owner;
    private final PeerFrameTexture frameTexture;
    private final ConcurrentHashMap<UUID, PeerLink> children = new ConcurrentHashMap<>();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean capturePending = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private volatile ScreenGroup group;
    private volatile UUID token;
    private volatile List<WebPeerDirectoryPayload.Peer> peers = List.of();
    private volatile PeerLink parent;
    private volatile UUID desiredParent;
    private volatile boolean root;
    private volatile long lastFrameNanos;
    private volatile long lastAnnounceNanos;
    private volatile long lastCaptureNanos;
    private volatile long lastStateNanos;
    private volatile List<BrowserSession.TabInfo> remoteTabs = List.of();
    private volatile int remoteActiveTab = -1;
    private volatile long lastEncodedCrc = Long.MIN_VALUE;
    private long uploadWindowNanos;
    private long uploadWindowBytes;
    private volatile boolean closed;

    WebPeerSession(ScreenGroup group, McefBrowserSession owner) {
        this.group = group;
        this.groupId = group.groupId();
        this.owner = owner;
        this.frameTexture = new PeerFrameTexture(groupId, CODEC_EXECUTOR);
    }

    void updateDirectory(WebPeerDirectoryPayload directory) {
        if (closed || !directory.screenId().equals(groupId)) {
            return;
        }
        token = directory.token();
        peers = directory.peers().stream()
                .sorted(Comparator.comparing(WebPeerDirectoryPayload.Peer::playerId)).toList();
        Minecraft minecraft = Minecraft.getInstance();
        UUID localId = minecraft.player == null ? null : minecraft.player.getUUID();
        int localIndex = -1;
        for (int index = 0; index < peers.size(); index++) {
            if (peers.get(index).playerId().equals(localId)) {
                localIndex = index;
                break;
            }
        }
        root = localIndex == 0;
        desiredParent = localIndex > 0
                ? peers.get((localIndex - 1) / 2).playerId() : null;
        PeerLink current = parent;
        if (current != null && (root || !containsPeer(current.remoteId)
                || current.remoteId.equals(localId))) {
            current.close();
            parent = null;
        }
        children.entrySet().removeIf(entry -> {
            if (containsPeer(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    boolean accept(Socket socket, DataInputStream input, UUID suppliedToken, UUID playerId) {
        UUID expected = token;
        if (closed || expected == null || !expected.equals(suppliedToken)
                || !containsPeer(playerId)) {
            return false;
        }
        try {
            socket.setSoTimeout(0);
            PeerLink link = new PeerLink(socket, input,
                    new DataOutputStream(new BufferedOutputStream(socket.getOutputStream())), playerId);
            PeerLink previous = children.put(playerId, link);
            if (previous != null) {
                previous.close();
            }
            link.start(false);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    void tick(ScreenGroup currentGroup, int textureId, int sourceWidth, int sourceHeight,
            List<BrowserSession.TabInfo> tabs, boolean visible) {
        if (closed) {
            return;
        }
        group = currentGroup;
        long now = System.nanoTime();
        if (now - lastAnnounceNanos >= TimeUnit.SECONDS.toNanos(5L)) {
            lastAnnounceNanos = now;
            dev.minescreen.client.network.ClientNetworkState.sendWebPeerAnnouncement(
                    currentGroup, WebPeerServicePort.port(), true);
        }
        if (!root && parent == null && token != null && !peers.isEmpty()) {
            connectParent();
        }
        if (!root || children.isEmpty()) {
            return;
        }
        if (now - lastStateNanos >= TimeUnit.SECONDS.toNanos(1L)) {
            lastStateNanos = now;
            broadcast(encodeState(tabs));
        }
        long interval = TimeUnit.SECONDS.toNanos(1L)
                / Math.max(1, MineScreenConfig.WEB_PEER_FPS.get());
        if (visible && RenderSystem.isOnRenderThread() && textureId > 0
                && sourceWidth > 0 && sourceHeight > 0
                && now - lastCaptureNanos >= interval && capturePending.compareAndSet(false, true)) {
            lastCaptureNanos = now;
            capture(textureId, sourceWidth, sourceHeight);
        }
    }

    boolean usesRemoteFrame() {
        return !root && parent != null && frameTexture.ready()
                && System.nanoTime() - lastFrameNanos < TimeUnit.SECONDS.toNanos(5L);
    }

    ScreenRenderSource renderSource() {
        return usesRemoteFrame() ? frameTexture.renderSource() : null;
    }

    List<BrowserSession.TabInfo> remoteTabs() {
        return usesRemoteFrame() ? remoteTabs : List.of();
    }

    int remoteActiveTab() {
        return usesRemoteFrame() ? remoteActiveTab : -1;
    }

    boolean sendCommand(MessageWriter writer) {
        return sendUpstream(MESSAGE_COMMAND, writer);
    }

    boolean sendInput(MessageWriter writer) {
        return sendUpstream(MESSAGE_INPUT, writer);
    }

    void shutdown(boolean announce) {
        if (closed) {
            return;
        }
        closed = true;
        if (announce) {
            dev.minescreen.client.network.ClientNetworkState.sendWebPeerAnnouncement(
                    group, WebPeerServicePort.port(), false);
        }
        PeerLink upstream = parent;
        parent = null;
        if (upstream != null) {
            upstream.close();
        }
        children.values().forEach(PeerLink::close);
        children.clear();
        frameTexture.close();
    }

    private boolean sendUpstream(int type, MessageWriter writer) {
        if (!usesRemoteFrame()) {
            return false;
        }
        PeerLink upstream = parent;
        if (upstream == null) {
            return false;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(type);
                writer.write(output);
            }
            return upstream.send(bytes.toByteArray());
        } catch (IOException exception) {
            return false;
        }
    }

    private void connectParent() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        UUID localId = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getUUID();
        UUID currentToken = token;
        List<WebPeerDirectoryPayload.Peer> candidates = parentCandidates(localId);
        Thread thread = new Thread(() -> {
            try {
                for (WebPeerDirectoryPayload.Peer candidate : candidates) {
                    if (closed || parent != null || currentToken == null) {
                        return;
                    }
                    PeerLink link = connect(candidate, localId, currentToken);
                    if (link != null) {
                        parent = link;
                        link.start(true);
                        return;
                    }
                }
            } finally {
                connecting.set(false);
            }
        }, "minescreen-web-peer-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private List<WebPeerDirectoryPayload.Peer> parentCandidates(UUID localId) {
        Set<UUID> order = new LinkedHashSet<>();
        if (desiredParent != null) {
            order.add(desiredParent);
        }
        int localIndex = -1;
        for (int index = 0; index < peers.size(); index++) {
            if (peers.get(index).playerId().equals(localId)) {
                localIndex = index;
                break;
            }
        }
        for (int index = 0; index < localIndex; index++) {
            order.add(peers.get(index).playerId());
        }
        List<WebPeerDirectoryPayload.Peer> result = new ArrayList<>();
        for (UUID id : order) {
            peers.stream().filter(peer -> peer.playerId().equals(id)).findFirst()
                    .ifPresent(result::add);
        }
        return result;
    }

    private PeerLink connect(WebPeerDirectoryPayload.Peer peer, UUID localId, UUID currentToken) {
        if (localId == null) {
            return null;
        }
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(peer.host(), peer.port()), 2_500);
            socket.setTcpNoDelay(true);
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            output.writeInt(WebPeerService.MAGIC);
            output.writeByte(WebPeerService.VERSION);
            output.writeLong(groupId.getMostSignificantBits());
            output.writeLong(groupId.getLeastSignificantBits());
            output.writeLong(currentToken.getMostSignificantBits());
            output.writeLong(currentToken.getLeastSignificantBits());
            output.writeLong(localId.getMostSignificantBits());
            output.writeLong(localId.getLeastSignificantBits());
            output.flush();
            return new PeerLink(socket,
                    new DataInputStream(new BufferedInputStream(socket.getInputStream())),
                    output, peer.playerId());
        } catch (IOException exception) {
            return null;
        }
    }

    private void capture(int textureId, int sourceWidth, int sourceHeight) {
        ByteBuffer pixels = null;
        try {
            long byteCount = (long) sourceWidth * sourceHeight * 4L;
            if (byteCount > Integer.MAX_VALUE) {
                capturePending.set(false);
                return;
            }
            pixels = MemoryUtil.memAlloc((int) byteCount);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, pixels);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            }
            byte[] rgba = new byte[(int) byteCount];
            pixels.get(0, rgba);
            CODEC_EXECUTOR.execute(() -> {
                try {
                    byte[] jpeg = encodeJpeg(rgba, sourceWidth, sourceHeight);
                    CRC32 checksum = new CRC32();
                    checksum.update(jpeg);
                    long value = checksum.getValue();
                    if (value != lastEncodedCrc) {
                        lastEncodedCrc = value;
                        broadcast(encodeFrame(jpeg));
                    }
                } finally {
                    capturePending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            capturePending.set(false);
        } finally {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private static byte[] encodeJpeg(byte[] rgba, int sourceWidth, int sourceHeight) {
        double scale = Math.min(1.0D,
                MineScreenConfig.WEB_PEER_MAX_WIDTH.get() / (double) sourceWidth);
        scale = Math.min(scale, Math.sqrt(2_073_600.0D / ((double) sourceWidth * sourceHeight)));
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
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(width * height / 4);
            try (ImageOutputStream outputStream = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(outputStream);
                ImageWriteParam parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(MineScreenConfig.WEB_PEER_JPEG_QUALITY.get() / 100.0F);
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally {
                writer.dispose();
            }
            byte[] jpeg = bytes.toByteArray();
            if (jpeg.length > MAX_JPEG_BYTES) {
                throw new IllegalStateException("Peer WEB JPEG exceeds frame limit");
            }
            return jpeg;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode peer WEB frame", exception);
        }
    }

    private byte[] encodeFrame(byte[] jpeg) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(jpeg.length + 32);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(MESSAGE_FRAME);
                output.writeLong(sequence.incrementAndGet());
                output.writeInt(jpeg.length);
                output.write(jpeg);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] encodeState(List<BrowserSession.TabInfo> tabs) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(MESSAGE_STATE);
                output.writeByte(Math.min(16, tabs.size()));
                for (BrowserSession.TabInfo tab : tabs.stream().limit(16).toList()) {
                    output.writeInt(tab.index());
                    output.writeBoolean(tab.active());
                    output.writeUTF(limit(tab.title(), 256));
                    output.writeUTF(limit(tab.url(), 2048));
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void sendState(PeerLink link, List<BrowserSession.TabInfo> tabs) {
        link.send(encodeState(tabs));
    }

    private void broadcast(byte[] message) {
        if (message.length > 0 && (message[0] & 0xFF) == MESSAGE_FRAME
                && !reserveUpload((long) message.length * Math.max(1, children.size()))) {
            return;
        }
        children.values().forEach(link -> link.send(message));
    }

    private synchronized boolean reserveUpload(long bytes) {
        long now = System.nanoTime();
        if (uploadWindowNanos == 0L || now - uploadWindowNanos >= TimeUnit.SECONDS.toNanos(1L)) {
            uploadWindowNanos = now;
            uploadWindowBytes = 0L;
        }
        long limit = MineScreenConfig.WEB_PEER_MAX_UPLOAD_KBPS.get() * 1024L / 8L;
        if (uploadWindowBytes + bytes > limit) {
            return false;
        }
        uploadWindowBytes += bytes;
        return true;
    }

    private void onDownstream(byte[] message) {
        if (message.length < 1) {
            return;
        }
        int type = message[0] & 0xFF;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            input.readUnsignedByte();
            if (type == MESSAGE_FRAME) {
                input.readLong();
                int length = input.readInt();
                if (length < 1 || length > MAX_JPEG_BYTES || length != input.available()) {
                    return;
                }
                byte[] jpeg = input.readNBytes(length);
                lastFrameNanos = System.nanoTime();
                frameTexture.accept(jpeg);
                broadcast(message);
            } else if (type == MESSAGE_STATE) {
                int count = input.readUnsignedByte();
                if (count > 16) {
                    return;
                }
                List<BrowserSession.TabInfo> tabs = new ArrayList<>(count);
                int active = -1;
                for (int index = 0; index < count; index++) {
                    int tabIndex = input.readInt();
                    boolean selected = input.readBoolean();
                    String title = input.readUTF();
                    String url = input.readUTF();
                    tabs.add(new BrowserSession.TabInfo(tabIndex, title, url, selected));
                    if (selected) {
                        active = tabIndex;
                    }
                }
                remoteTabs = List.copyOf(tabs);
                remoteActiveTab = active;
                broadcast(message);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void onUpstream(byte[] message) {
        if (message.length < 1) {
            return;
        }
        int type = message[0] & 0xFF;
        if (type != MESSAGE_COMMAND && type != MESSAGE_INPUT) {
            return;
        }
        PeerLink upstream = parent;
        if (!root && upstream != null) {
            upstream.send(message);
        } else if (root) {
            Minecraft.getInstance().execute(() -> owner.applyPeerMessage(message));
        }
    }

    private boolean containsPeer(UUID playerId) {
        return peers.stream().anyMatch(peer -> peer.playerId().equals(playerId));
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @FunctionalInterface
    interface MessageWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private final class PeerLink implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final UUID remoteId;
        private final AtomicBoolean linkClosed = new AtomicBoolean();

        private PeerLink(Socket socket, DataInputStream input, DataOutputStream output, UUID remoteId) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.remoteId = remoteId;
        }

        private void start(boolean fromParent) {
            Thread reader = new Thread(() -> readLoop(fromParent),
                    "minescreen-web-peer-" + remoteId.toString().substring(0, 8));
            reader.setDaemon(true);
            reader.start();
        }

        private boolean send(byte[] message) {
            if (linkClosed.get() || message.length < 1 || message.length > MAX_MESSAGE_BYTES) {
                return false;
            }
            try {
                synchronized (output) {
                    output.writeInt(message.length);
                    output.write(message);
                    output.flush();
                }
                return true;
            } catch (IOException exception) {
                close();
                return false;
            }
        }

        private void readLoop(boolean fromParent) {
            try {
                while (!closed && !linkClosed.get()) {
                    int length = input.readInt();
                    if (length < 1 || length > MAX_MESSAGE_BYTES) {
                        break;
                    }
                    byte[] message = input.readNBytes(length);
                    if (message.length != length) {
                        break;
                    }
                    if (fromParent) {
                        onDownstream(message);
                    } else {
                        onUpstream(message);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
                if (fromParent && parent == this) {
                    parent = null;
                } else {
                    children.remove(remoteId, this);
                }
            }
        }

        @Override
        public void close() {
            if (!linkClosed.compareAndSet(false, true)) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Avoids exposing WebPeerService's mutable listener while keeping session heartbeats cheap. */
    private static final class WebPeerServicePort {
        private static int port() {
            return WebPeerService.listenPort();
        }
    }
}
