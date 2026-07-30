package dev.minescreen.client.traffic;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.network.TrafficTemplateAnnouncePayload;
import dev.minescreen.network.TrafficTemplateCatalogRequestPayload;
import dev.minescreen.network.TrafficTemplateChunkPayload;
import dev.minescreen.network.TrafficTemplateManifest;
import dev.minescreen.network.TrafficTemplateRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/** Client half of hash-based, declarative-only traffic template synchronization. */
public final class TrafficTemplateSyncClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, String> ANNOUNCED = new HashMap<>();
    private static final Map<Key, Assembly> DOWNLOADS = new HashMap<>();
    private static Level connectionLevel;
    private static boolean catalogRequested;
    private static boolean repositoryDirty = true;
    private static final long ASSEMBLY_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(30L);

    private TrafficTemplateSyncClient() {
    }

    public static void repositoryReloaded() {
        repositoryDirty = true;
    }

    public static void tick(Level level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get() || level == null
                || minecraft.getConnection() == null) {
            reset(null);
            return;
        }
        if (connectionLevel != level) {
            reset(level);
        }
        if (!catalogRequested) {
            PacketDistributor.sendToServer(new TrafficTemplateCatalogRequestPayload());
            catalogRequested = true;
        }
        if (!repositoryDirty) return;
        repositoryDirty = false;
        Map<String, String> current = new HashMap<>();
        for (TrafficTemplateRepository.SynchronizedTemplateInfo template
                : TrafficTemplateRepository.synchronizedTemplateCatalog()) {
            current.put(template.id(), template.sha256());
            if (!template.sha256().equals(ANNOUNCED.get(template.id()))) {
                PacketDistributor.sendToServer(new TrafficTemplateAnnouncePayload(template.id(),
                        template.sha256(), template.size()));
                ANNOUNCED.put(template.id(), template.sha256());
            }
        }
        ANNOUNCED.keySet().removeIf(id -> !current.containsKey(id));
    }

    public static void acceptAnnouncement(TrafficTemplateAnnouncePayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get()
                || !TrafficTemplateManifest.validId(payload.templateId())
                || !TrafficTemplateManifest.validHash(payload.sha256())
                || payload.size() < 2 || payload.size() > TrafficTemplateManifest.MAX_BYTES
                || TrafficTemplateRepository.hasSynchronizedTemplate(payload.templateId(),
                        payload.sha256())) {
            return;
        }
        PacketDistributor.sendToServer(new TrafficTemplateRequestPayload(payload.templateId(),
                payload.sha256()));
    }

    public static void acceptRequest(TrafficTemplateRequestPayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get()) return;
        byte[] bytes = TrafficTemplateRepository.synchronizedTemplateBytes(payload.templateId(),
                payload.sha256());
        if (bytes == null) return;
        sendChunks(bytes, payload.templateId(), payload.sha256());
    }

    public static void acceptChunk(TrafficTemplateChunkPayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get() || !valid(payload)
                || TrafficTemplateRepository.hasSynchronizedTemplate(payload.templateId(),
                        payload.sha256())) return;
        long now = System.nanoTime();
        DOWNLOADS.entrySet().removeIf(entry ->
                now - entry.getValue().lastUpdate > ASSEMBLY_TIMEOUT_NANOS);
        Key key = new Key(payload.templateId(), payload.sha256());
        if (!DOWNLOADS.containsKey(key) && DOWNLOADS.size() >= 4) return;
        Assembly assembly = DOWNLOADS.computeIfAbsent(key,
                ignored -> new Assembly(payload.totalSize(), payload.totalChunks()));
        if (!assembly.compatible(payload.totalSize(), payload.totalChunks())
                || !assembly.accept(payload.index(), payload.data())) {
            DOWNLOADS.remove(key);
            return;
        }
        if (!assembly.complete()) return;
        DOWNLOADS.remove(key);
        byte[] bytes = assembly.join();
        if (!TrafficTemplateManifest.sha256(bytes).equals(payload.sha256())) return;
        try {
            TrafficTemplateRepository.installSynchronizedTemplate(payload.templateId(),
                    payload.sha256(), bytes);
        } catch (IOException exception) {
            LOGGER.warn("Unable to install synchronized traffic template {}", payload.templateId(),
                    exception);
        }
    }

    private static void sendChunks(byte[] bytes, String id, String sha256) {
        int total = (bytes.length + TrafficTemplateManifest.CHUNK_BYTES - 1)
                / TrafficTemplateManifest.CHUNK_BYTES;
        for (int index = 0; index < total; index++) {
            int start = index * TrafficTemplateManifest.CHUNK_BYTES;
            int end = Math.min(bytes.length, start + TrafficTemplateManifest.CHUNK_BYTES);
            PacketDistributor.sendToServer(new TrafficTemplateChunkPayload(id, sha256,
                    bytes.length, index, total, Arrays.copyOfRange(bytes, start, end)));
        }
    }

    private static boolean valid(TrafficTemplateChunkPayload payload) {
        if (!TrafficTemplateManifest.validId(payload.templateId())
                || !TrafficTemplateManifest.validHash(payload.sha256())
                || payload.totalSize() < 2 || payload.totalSize() > TrafficTemplateManifest.MAX_BYTES
                || payload.totalChunks() < 1
                || payload.totalChunks() > TrafficTemplateManifest.MAX_CHUNKS
                || payload.totalChunks() != (payload.totalSize()
                        + TrafficTemplateManifest.CHUNK_BYTES - 1)
                        / TrafficTemplateManifest.CHUNK_BYTES
                || payload.index() < 0 || payload.index() >= payload.totalChunks()) return false;
        int expected = payload.index() == payload.totalChunks() - 1
                ? payload.totalSize() - payload.index() * TrafficTemplateManifest.CHUNK_BYTES
                : TrafficTemplateManifest.CHUNK_BYTES;
        return payload.data().length == expected;
    }

    private static void reset(Level nextLevel) {
        if (connectionLevel == nextLevel && nextLevel != null) return;
        connectionLevel = nextLevel;
        catalogRequested = false;
        repositoryDirty = true;
        ANNOUNCED.clear();
        DOWNLOADS.clear();
    }

    private record Key(String id, String hash) {
    }

    private static final class Assembly {
        private final int totalSize;
        private final byte[][] chunks;
        private int received;
        private long lastUpdate = System.nanoTime();

        private Assembly(int totalSize, int totalChunks) {
            this.totalSize = totalSize;
            this.chunks = new byte[totalChunks][];
        }

        private boolean compatible(int size, int totalChunks) {
            return totalSize == size && chunks.length == totalChunks;
        }

        private boolean accept(int index, byte[] bytes) {
            lastUpdate = System.nanoTime();
            if (chunks[index] != null) return Arrays.equals(chunks[index], bytes);
            chunks[index] = Arrays.copyOf(bytes, bytes.length);
            received++;
            return true;
        }

        private boolean complete() {
            return received == chunks.length;
        }

        private byte[] join() {
            byte[] joined = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, joined, offset, chunk.length);
                offset += chunk.length;
            }
            return joined;
        }
    }
}
