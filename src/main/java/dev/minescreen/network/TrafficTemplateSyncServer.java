package dev.minescreen.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/** Server-side cache and relay for validated declarative traffic templates. */
final class TrafficTemplateSyncServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final long ASSEMBLY_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_ACTIVE_UPLOADS_PER_PLAYER = 4;
    private static final long CATALOG_COOLDOWN_TICKS = 20L;
    private static final long DOWNLOAD_COOLDOWN_TICKS = 10L;
    private static final long MAX_UPLOAD_BYTES_PER_SECOND =
            TrafficTemplateManifest.MAX_BYTES * 4L;

    private TrafficTemplateSyncServer() {
    }

    static void announce(ServerPlayer player, TrafficTemplateAnnouncePayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get() || !valid(payload)) return;
        State state = state(player.getServer());
        Entry current = state.entries.get(payload.templateId());
        if (current != null && current.sha256.equals(payload.sha256())) return;
        if (current != null && !canReplace(player, current)) {
            PacketDistributor.sendToPlayer(player, current.announce());
            return;
        }
        if (current == null && state.entries.size() >= MineScreenConfig.MAX_SYNCED_TRAFFIC_TEMPLATES.get()) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new TrafficTemplateRequestPayload(payload.templateId(), payload.sha256()));
    }

    static void request(ServerPlayer player, TrafficTemplateRequestPayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get()
                || !TrafficTemplateManifest.validId(payload.templateId())
                || !TrafficTemplateManifest.validHash(payload.sha256())) return;
        State state = state(player.getServer());
        Entry entry = state.entries.get(payload.templateId());
        if (entry == null || !entry.sha256.equals(payload.sha256())) return;
        if (!state.allowDownload(player, payload)) return;
        sendChunks(player, entry);
    }

    static void catalog(ServerPlayer player) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get()) return;
        State state = state(player.getServer());
        if (!state.allowCatalog(player)) return;
        for (Entry entry : state.entries.values()) {
            PacketDistributor.sendToPlayer(player, entry.announce());
        }
    }

    static void chunk(ServerPlayer player, TrafficTemplateChunkPayload payload) {
        if (!MineScreenConfig.TRAFFIC_TEMPLATE_SYNC.get() || !valid(payload)) return;
        State state = state(player.getServer());
        state.pruneAssemblies();
        if (!state.reserveUpload(player, payload.data().length)) return;
        UploadKey key = new UploadKey(player.getUUID(), payload.templateId(), payload.sha256());
        Entry current = state.entries.get(payload.templateId());
        if (current != null && current.sha256.equals(payload.sha256())) return;
        if (current != null && !canReplace(player, current)) return;
        long playerUploads = state.uploads.keySet().stream()
                .filter(existing -> existing.player.equals(player.getUUID())).count();
        if (!state.uploads.containsKey(key) && playerUploads >= MAX_ACTIVE_UPLOADS_PER_PLAYER) return;
        Assembly assembly = state.uploads.computeIfAbsent(key,
                ignored -> new Assembly(payload.totalSize(), payload.totalChunks()));
        if (!assembly.compatible(payload.totalSize(), payload.totalChunks())
                || !assembly.accept(payload.index(), payload.data())) {
            state.uploads.remove(key);
            return;
        }
        if (!assembly.complete()) return;
        state.uploads.remove(key);
        byte[] bytes = assembly.join();
        if (!TrafficTemplateManifest.sha256(bytes).equals(payload.sha256())) return;
        try {
            TrafficTemplateManifest.validate(payload.templateId(), bytes);
            Entry installed = state.install(payload.templateId(), payload.sha256(), bytes,
                    player.getUUID());
            PacketDistributor.sendToAllPlayers(installed.announce());
        } catch (IOException exception) {
            LOGGER.warn("Rejected synchronized traffic template {} from {}", payload.templateId(),
                    player.getGameProfile().getName(), exception);
        }
    }

    private static void sendChunks(ServerPlayer player, Entry entry) {
        int total = (entry.bytes.length + TrafficTemplateManifest.CHUNK_BYTES - 1)
                / TrafficTemplateManifest.CHUNK_BYTES;
        for (int index = 0; index < total; index++) {
            int start = index * TrafficTemplateManifest.CHUNK_BYTES;
            int end = Math.min(entry.bytes.length, start + TrafficTemplateManifest.CHUNK_BYTES);
            PacketDistributor.sendToPlayer(player, new TrafficTemplateChunkPayload(entry.id,
                    entry.sha256, entry.bytes.length, index, total,
                    Arrays.copyOfRange(entry.bytes, start, end)));
        }
    }

    private static boolean valid(TrafficTemplateAnnouncePayload payload) {
        return TrafficTemplateManifest.validId(payload.templateId())
                && TrafficTemplateManifest.validHash(payload.sha256())
                && payload.size() >= 2 && payload.size() <= TrafficTemplateManifest.MAX_BYTES;
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
                || payload.index() < 0 || payload.index() >= payload.totalChunks()) {
            return false;
        }
        int expected = payload.index() == payload.totalChunks() - 1
                ? payload.totalSize() - payload.index() * TrafficTemplateManifest.CHUNK_BYTES
                : TrafficTemplateManifest.CHUNK_BYTES;
        return payload.data().length == expected;
    }

    private static boolean canReplace(ServerPlayer player, Entry entry) {
        return player.hasPermissions(2) || entry.owner != null && entry.owner.equals(player.getUUID());
    }

    private static State state(MinecraftServer server) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, State::load);
        }
    }

    private static final class State {
        private final Path root;
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<UploadKey, Assembly> uploads = new HashMap<>();
        private final Map<UUID, Long> lastCatalogTick = new HashMap<>();
        private final Map<UploadKey, Long> lastDownloadTick = new HashMap<>();
        private final Map<UUID, UploadBudget> uploadBudgets = new HashMap<>();

        private State(Path root) {
            this.root = root;
        }

        private static State load(MinecraftServer server) {
            Path root = server.getWorldPath(LevelResource.ROOT)
                    .resolve("minescreen/traffic_templates").toAbsolutePath().normalize();
            State state = new State(root);
            try {
                Files.createDirectories(root);
                try (var files = Files.list(root)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .limit(128).toList()) {
                        try {
                            String name = file.getFileName().toString();
                            String id = name.substring(0, name.length() - 5);
                            if (!TrafficTemplateManifest.validId(id)) continue;
                            byte[] bytes = Files.readAllBytes(file);
                            TrafficTemplateManifest.validate(id, bytes);
                            String hash = TrafficTemplateManifest.sha256(bytes);
                            UUID owner = readOwner(root.resolve(id + ".owner"));
                            state.entries.put(id, new Entry(id, hash, bytes, owner));
                        } catch (IOException exception) {
                            LOGGER.warn("Ignoring invalid synchronized traffic template {}", file,
                                    exception);
                        }
                    }
                }
            } catch (IOException exception) {
                LOGGER.warn("Unable to load synchronized MineScreen traffic templates", exception);
            }
            return state;
        }

        private Entry install(String id, String hash, byte[] bytes, UUID owner) throws IOException {
            Files.createDirectories(root);
            Path target = root.resolve(id + ".json").normalize();
            if (!target.startsWith(root)) throw new IOException("Unsafe template path");
            Path temporary = root.resolve(id + ".json.tmp").normalize();
            Files.write(temporary, bytes);
            atomicMove(temporary, target);
            Files.writeString(root.resolve(id + ".owner"), owner.toString(),
                    StandardCharsets.UTF_8);
            Entry entry = new Entry(id, hash, Arrays.copyOf(bytes, bytes.length), owner);
            entries.put(id, entry);
            return entry;
        }

        private void pruneAssemblies() {
            long now = System.nanoTime();
            uploads.entrySet().removeIf(entry -> now - entry.getValue().lastUpdate
                    > ASSEMBLY_TIMEOUT_NANOS);
            uploadBudgets.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStartedNanos > ASSEMBLY_TIMEOUT_NANOS);
        }

        private boolean allowCatalog(ServerPlayer player) {
            long now = player.serverLevel().getGameTime();
            Long previous = lastCatalogTick.put(player.getUUID(), now);
            return previous == null || now < previous
                    || now - previous >= CATALOG_COOLDOWN_TICKS;
        }

        private boolean allowDownload(ServerPlayer player, TrafficTemplateRequestPayload payload) {
            long now = player.serverLevel().getGameTime();
            UploadKey key = new UploadKey(player.getUUID(), payload.templateId(),
                    payload.sha256());
            Long previous = lastDownloadTick.put(key, now);
            return previous == null || now < previous
                    || now - previous >= DOWNLOAD_COOLDOWN_TICKS;
        }

        private boolean reserveUpload(ServerPlayer player, int bytes) {
            long now = System.nanoTime();
            UploadBudget budget = uploadBudgets.computeIfAbsent(player.getUUID(),
                    ignored -> new UploadBudget(now));
            if (now - budget.windowStartedNanos >= 1_000_000_000L) {
                budget.windowStartedNanos = now;
                budget.bytes = 0L;
            }
            if (bytes < 0 || budget.bytes + bytes > MAX_UPLOAD_BYTES_PER_SECOND) {
                return false;
            }
            budget.bytes += bytes;
            return true;
        }
    }

    private static UUID readOwner(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).trim()) : null;
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Entry(String id, String sha256, byte[] bytes, UUID owner) {
        private TrafficTemplateAnnouncePayload announce() {
            return new TrafficTemplateAnnouncePayload(id, sha256, bytes.length);
        }
    }

    private record UploadKey(UUID player, String id, String hash) {
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

    private static final class UploadBudget {
        private long windowStartedNanos;
        private long bytes;

        private UploadBudget(long windowStartedNanos) {
            this.windowStartedNanos = windowStartedNanos;
        }
    }
}
