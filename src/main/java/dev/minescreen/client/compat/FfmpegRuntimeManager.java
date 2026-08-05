package dev.minescreen.client.compat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

import dev.minescreen.MineScreenClientConfig;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Installs one platform-specific FFmpeg runtime outside the mod jar. Every network byte is fetched
 * through the JVM's normal TLS/hostname verifier and then checked against a hash compiled into the
 * MineScreen release. No certificate verifier is replaced and redirects may not leave the small
 * built-in host allow-list.
 */
public final class FfmpegRuntimeManager {
    public static final String VERSION = "7.1-1.5.11";
    private static final System.Logger LOGGER =
            System.getLogger("MineScreen/FFmpegRuntime");
    private static final List<String> BASE_URLS = List.of(
            "https://repo.maven.apache.org/maven2/",
            "https://maven.aliyun.com/repository/central/",
            "https://repo.huaweicloud.com/repository/maven/",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/");
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "repo.maven.apache.org", "maven.aliyun.com", "repo.huaweicloud.com",
            "mirrors.cloud.tencent.com");
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 500L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 256;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(8);
    private static volatile Snapshot snapshot = Snapshot.idle();
    private static volatile ProgramPair installed;
    private static Thread worker;

    private FfmpegRuntimeManager() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static Optional<ProgramPair> installedRuntime() {
        ProgramPair value = installed;
        if (value == null) detectInstalled();
        return Optional.ofNullable(installed);
    }

    public static synchronized void ensureInstalledAsync() {
        if (installedRuntime().isPresent() || active()) return;
        if (!MineScreenClientConfig.AUTO_DOWNLOAD_FFMPEG_RUNTIME.get()) {
            return;
        }
        RuntimeSpec spec = specFor(PlatformFingerprint.detect()).orElse(null);
        if (spec == null) {
            fail("no verified FFmpeg package is published for this OS/architecture");
            return;
        }
        startWorker(() -> downloadAndInstall(spec));
    }

    public static synchronized void retry() {
        if (active()) return;
        snapshot = Snapshot.idle();
        installed = null;
        RuntimeSpec spec = specFor(PlatformFingerprint.detect()).orElse(null);
        if (spec == null) {
            fail("no verified FFmpeg package is published for this OS/architecture");
            return;
        }
        // An explicit user click overrides auto_download_ffmpeg_runtime=false for this attempt.
        startWorker(() -> downloadAndInstall(spec));
    }

    public static synchronized void installArchiveAsync(Path archive) {
        if (active()) return;
        RuntimeSpec spec = specFor(PlatformFingerprint.detect()).orElse(null);
        if (spec == null) {
            fail("no verified FFmpeg package is published for this OS/architecture");
            return;
        }
        startWorker(() -> installVerifiedArchive(spec, archive.toAbsolutePath().normalize(), false));
    }

    public static Optional<RuntimeSpec> currentSpec() {
        return specFor(PlatformFingerprint.detect());
    }

    static Optional<RuntimeSpec> specFor(PlatformFingerprint platform) {
        String classifier = switch (platform.os()) {
            case WINDOWS -> platform.architecture() == PlatformFingerprint.CpuArchitecture.X86_64
                    ? "windows-x86_64" : null;
            case LINUX -> switch (platform.architecture()) {
                case X86_64 -> "linux-x86_64";
                case ARM64 -> "linux-arm64";
                default -> null;
            };
            case MACOS -> switch (platform.architecture()) {
                case X86_64 -> "macosx-x86_64";
                case ARM64 -> "macosx-arm64";
                default -> null;
            };
            case ANDROID, HARMONY ->
                    platform.architecture() == PlatformFingerprint.CpuArchitecture.ARM64
                            ? "android-arm64" : null;
            default -> null;
        };
        if (classifier == null) return Optional.empty();
        return Optional.of(switch (classifier) {
            case "windows-x86_64" -> spec(classifier, 25_536_044L,
                    "63205DA750F3FBB7FA0E5D23B9E00FC1257E478C33D02DCEE9299268309D594B");
            case "linux-x86_64" -> spec(classifier, 24_766_189L,
                    "69A5079B881336676115D1250CDC239E89652D6C58A6BD35E84CB880E735CBBF");
            case "linux-arm64" -> spec(classifier, 24_643_830L,
                    "BC661C7B685C8AF5CCB66773F6063172352AC088C301EE6126D7165604D79C88");
            case "macosx-x86_64" -> spec(classifier, 23_073_508L,
                    "6E0C21B779BB55BF2C5B8810AC71610BC6DD6EF394C3541A80F02C26E61CC4C8");
            case "macosx-arm64" -> spec(classifier, 19_465_640L,
                    "BAE41342BE096B898E71060CCBA2010AF5EE8F5DC722D573C12EFEF791BF1D10");
            case "android-arm64" -> spec(classifier, 20_582_374L,
                    "A292D7AC25EDA14B1A2B0B2B9CADF3E0627E02B0A1F859C1E5F733AAC735808C");
            default -> throw new IllegalStateException(classifier);
        });
    }

    private static RuntimeSpec spec(String classifier, long size, String sha256) {
        String file = "ffmpeg-" + VERSION + "-" + classifier + ".jar";
        String relative = "org/bytedeco/ffmpeg/" + VERSION + "/" + file;
        String prefix = classifier.startsWith("android") ? "lib/arm64-v8a/"
                : "org/bytedeco/ffmpeg/" + classifier + "/";
        boolean windows = classifier.startsWith("windows");
        return new RuntimeSpec(classifier, file, relative, prefix,
                windows ? "ffmpeg.exe" : "ffmpeg", windows ? "ffprobe.exe" : "ffprobe",
                size, sha256, BASE_URLS.getFirst() + relative);
    }

    private static void downloadAndInstall(RuntimeSpec spec) {
        Path root = runtimeRoot();
        Path archive = root.resolve("downloads").resolve(spec.fileName() + ".part");
        Throwable last = null;
        for (int index = 0; index < BASE_URLS.size(); index++) {
            String source = BASE_URLS.get(index) + spec.relativePath();
            try {
                Files.createDirectories(archive.getParent());
                Files.deleteIfExists(archive);
                download(spec, source, archive, index + 1, BASE_URLS.size());
                installVerifiedArchive(spec, archive, true);
                Files.deleteIfExists(archive);
                return;
            } catch (Throwable failure) {
                last = failure;
                LOGGER.log(System.Logger.Level.WARNING,
                        "MineScreen rejected FFmpeg source " + source + ": "
                                + (failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage()));
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                }
            }
        }
        fail("all trusted download sources failed"
                + (last == null || last.getMessage() == null ? "" : ": " + last.getMessage()));
    }

    private static void download(RuntimeSpec spec, String source, Path target, int sourceIndex,
            int sourceCount) throws Exception {
        URI current = URI.create(source);
        HttpsURLConnection connection = null;
        for (int redirects = 0; redirects <= 3; redirects++) {
            validateRemote(current);
            URL url = current.toURL();
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "MineScreen/1.2.1 FFmpeg runtime installer");
            connection.connect();
            for (var certificate : connection.getServerCertificates()) {
                if (certificate instanceof X509Certificate x509) x509.checkValidity();
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 3) throw new IOException("unsafe redirect");
                current = current.resolve(location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            break;
        }
        if (connection == null) throw new IOException("no HTTPS connection");
        long declared = connection.getContentLengthLong();
        if (declared > MAX_ARCHIVE_BYTES || declared > 0L && declared != spec.size()) {
            throw new IOException("unexpected Content-Length " + declared);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0L;
        byte[] buffer = new byte[128 * 1024];
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count += read;
                if (count > MAX_ARCHIVE_BYTES || count > spec.size()) {
                    throw new IOException("download exceeds expected size");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                snapshot = new Snapshot(State.DOWNLOADING,
                        Math.min(0.999D, count / (double) spec.size()),
                        "Downloading " + spec.fileName(), current.toString(), spec.fileName(),
                        spec.manualUrl(), "", sourceIndex, sourceCount);
            }
        } finally {
            connection.disconnect();
        }
        if (count != spec.size()) throw new IOException("unexpected file size " + count);
        String actual = HexFormat.of().withUpperCase().formatHex(digest.digest());
        if (!actual.equals(spec.sha256())) throw new IOException("SHA-256 mismatch");
    }

    private static void installVerifiedArchive(RuntimeSpec spec, Path archive,
            boolean alreadyVerified) {
        try {
            snapshot = new Snapshot(State.VERIFYING, 1.0D, "Verifying SHA-256", "",
                    spec.fileName(), spec.manualUrl(), "", 0, BASE_URLS.size());
            if (!alreadyVerified) verifyArchive(spec, archive);
            Path root = runtimeRoot();
            Path staging = root.resolve("staging-" + Long.toUnsignedString(System.nanoTime()));
            deleteTree(staging, root);
            Files.createDirectories(staging);
            snapshot = new Snapshot(State.EXTRACTING, 1.0D, "Extracting verified runtime", "",
                    spec.fileName(), spec.manualUrl(), "", 0, BASE_URLS.size());
            extract(spec, archive, staging);
            Path ffmpeg = staging.resolve(spec.ffmpegName());
            Path ffprobe = staging.resolve(spec.ffprobeName());
            makeExecutable(ffmpeg);
            makeExecutable(ffprobe);
            snapshot = new Snapshot(State.PROBING, 1.0D, "Testing FFmpeg", "",
                    spec.fileName(), spec.manualUrl(), "", 0, BASE_URLS.size());
            probe(ffmpeg);
            probe(ffprobe);
            Files.writeString(staging.resolve(".verified-sha256"), spec.sha256(),
                    StandardCharsets.US_ASCII);
            Path destination = installDirectory(spec);
            deleteTree(destination, root);
            Files.createDirectories(destination.getParent());
            move(staging, destination);
            installed = new ProgramPair(destination.resolve(spec.ffmpegName()),
                    destination.resolve(spec.ffprobeName()), spec.classifier());
            snapshot = new Snapshot(State.READY, 1.0D, "FFmpeg is ready", "",
                    spec.fileName(), spec.manualUrl(), "", 0, BASE_URLS.size());
            CompatibilityManager.refresh();
        } catch (Throwable failure) {
            fail(failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage());
        }
    }

    private static void verifyArchive(RuntimeSpec spec, Path archive) throws Exception {
        if (!Files.isRegularFile(archive) || Files.size(archive) != spec.size()) {
            throw new IOException("selected file has the wrong size");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().withUpperCase().formatHex(digest.digest());
        if (!actual.equals(spec.sha256())) throw new IOException("SHA-256 mismatch");
    }

    private static void extract(RuntimeSpec spec, Path archive, Path staging) throws IOException {
        int entries = 0;
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith(spec.archivePrefix()) || entry.isDirectory()) {
                    continue;
                }
                String relative = entry.getName().substring(spec.archivePrefix().length());
                if (relative.isBlank() || relative.contains("/") || relative.contains("\\")) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) throw new IOException("too many archive entries");
                Path output = staging.resolve(relative).normalize();
                if (!output.startsWith(staging)) throw new IOException("unsafe archive entry");
                try (OutputStream target = Files.newOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        total += read;
                        if (total > MAX_EXTRACTED_BYTES) {
                            throw new IOException("extracted runtime is too large");
                        }
                        target.write(buffer, 0, read);
                    }
                }
            }
        }
        if (!Files.isRegularFile(staging.resolve(spec.ffmpegName()))
                || !Files.isRegularFile(staging.resolve(spec.ffprobeName()))) {
            throw new IOException("verified package does not contain ffmpeg and ffprobe");
        }
    }

    private static void validateRemote(URI uri) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IOException("download target is not an allowed HTTPS host");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (unsafeAddress(address)) {
                throw new IOException("trusted host resolved to a private or local address");
            }
        }
    }

    static boolean unsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0 || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254 || first >= 224;
    }

    private static void probe(Path executable) throws Exception {
        Process process = FfmpegProcessEnvironment.configure(
                new ProcessBuilder(List.of(executable.toString(), "-version"))
                        .redirectErrorStream(true), executable).start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(executable.getFileName() + " probe timed out");
        }
        byte[] output = process.getInputStream().readNBytes(4096);
        if (process.exitValue() != 0 || output.length == 0) {
            throw new IOException(executable.getFileName() + " could not start");
        }
    }

    private static synchronized void startWorker(Runnable action) {
        snapshot = new Snapshot(State.CHECKING, 0.0D, "Checking platform package", "", "",
                currentSpec().map(RuntimeSpec::manualUrl).orElse(""), "", 0, BASE_URLS.size());
        worker = Thread.ofPlatform().daemon(true).name("minescreen-ffmpeg-installer")
                .start(() -> {
                    try {
                        action.run();
                    } finally {
                        synchronized (FfmpegRuntimeManager.class) {
                            worker = null;
                        }
                    }
                });
    }

    private static synchronized boolean active() {
        return worker != null && worker.isAlive();
    }

    private static void detectInstalled() {
        RuntimeSpec spec = specFor(PlatformFingerprint.detect()).orElse(null);
        if (spec == null) return;
        Path directory = installDirectory(spec);
        Path ffmpeg = directory.resolve(spec.ffmpegName());
        Path ffprobe = directory.resolve(spec.ffprobeName());
        Path marker = directory.resolve(".verified-sha256");
        try {
            if (Files.isRegularFile(ffmpeg) && Files.isRegularFile(ffprobe)
                    && Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.US_ASCII).trim()
                            .equals(spec.sha256())) {
                installed = new ProgramPair(ffmpeg, ffprobe, spec.classifier());
                snapshot = new Snapshot(State.READY, 1.0D, "FFmpeg is ready", "",
                        spec.fileName(), spec.manualUrl(), "", 0, BASE_URLS.size());
            }
        } catch (IOException ignored) {
        }
    }

    private static void fail(String reason) {
        RuntimeSpec spec = currentSpec().orElse(null);
        snapshot = new Snapshot(State.FAILED, 0.0D, "FFmpeg is unavailable", "",
                spec == null ? "" : spec.fileName(), spec == null ? "" : spec.manualUrl(),
                reason == null ? "unknown failure" : reason, 0, BASE_URLS.size());
    }

    private static Path runtimeRoot() {
        Path base = mobileExecutableRoot().orElseGet(() -> FMLPaths.GAMEDIR.get()
                .resolve("config").resolve("minescreen"));
        return base
                .resolve("runtime").resolve("ffmpeg").resolve(VERSION)
                .toAbsolutePath().normalize();
    }

    private static Optional<Path> mobileExecutableRoot() {
        PlatformFingerprint.OsFamily os = PlatformFingerprint.detect().os();
        if (os != PlatformFingerprint.OsFamily.ANDROID
                && os != PlatformFingerprint.OsFamily.HARMONY) return Optional.empty();
        for (String key : List.of("POJAV_NATIVEDIR", "FCL_NATIVEDIR", "ZL2_NATIVEDIR")) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) continue;
            try {
                Path path = Path.of(value).toAbsolutePath().normalize();
                if (Files.isDirectory(path) && Files.isWritable(path)) {
                    return Optional.of(path.resolve("minescreen"));
                }
            } catch (RuntimeException ignored) {
            }
        }
        // Launchers without a declared native directory may provide an executable app-private
        // cache as java.io.tmpdir. Probe failure is surfaced instead of silently treating it as
        // usable; Android external-storage mounts are commonly noexec.
        try {
            Path temporary = Path.of(System.getProperty("java.io.tmpdir", ""))
                    .toAbsolutePath().normalize();
            if (Files.isDirectory(temporary) && Files.isWritable(temporary)) {
                return Optional.of(temporary.resolve("minescreen"));
            }
        } catch (RuntimeException ignored) {
        }
        return Optional.empty();
    }

    private static Path installDirectory(RuntimeSpec spec) {
        return runtimeRoot().resolve(spec.classifier()).normalize();
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
        path.toFile().setExecutable(true, false);
    }

    private static void deleteTree(Path target, Path root) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)
                || !Files.exists(normalized)) return;
        try (var stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public enum State {
        IDLE,
        CHECKING,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        PROBING,
        READY,
        FAILED;

        public boolean active() {
            return this == CHECKING || this == DOWNLOADING || this == VERIFYING
                    || this == EXTRACTING || this == PROBING;
        }
    }

    public record Snapshot(State state, double progress, String detail, String source,
            String fileName, String manualUrl, String error, int sourceIndex, int sourceCount) {
        static Snapshot idle() {
            return new Snapshot(State.IDLE, 0.0D, "", "", "", "", "", 0, 0);
        }
    }

    public record RuntimeSpec(String classifier, String fileName, String relativePath,
            String archivePrefix, String ffmpegName, String ffprobeName, long size,
            String sha256, String manualUrl) {
    }

    public record ProgramPair(Path ffmpeg, Path ffprobe, String classifier) {
    }
}
