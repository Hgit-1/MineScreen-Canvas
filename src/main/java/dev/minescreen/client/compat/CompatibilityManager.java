package dev.minescreen.client.compat;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import dev.minescreen.MineScreenClientConfig;
import dev.minescreen.client.vnc.RfbEncodingCapabilities;
import net.neoforged.fml.ModList;

/** Lazy, failure-caching compatibility probe. No method is called on a dedicated server. */
public final class CompatibilityManager {
    private static final Map<Capability, CapabilityResult> CACHE = new EnumMap<>(Capability.class);
    private static PlatformFingerprint platform = PlatformFingerprint.detect();
    private static ExternalProgramDetector.Program browserProgram;
    private static ExternalProgramDetector.Program ffmpegProgram;
    private static ExternalProgramDetector.Program ffprobeProgram;

    private CompatibilityManager() {
    }

    public static synchronized PlatformFingerprint platform() {
        return platform;
    }

    public static synchronized CapabilityResult probe(Capability capability) {
        return CACHE.computeIfAbsent(capability, CompatibilityManager::probeUncached);
    }

    public static synchronized void refresh() {
        CACHE.clear();
        browserProgram = null;
        ffmpegProgram = null;
        ffprobeProgram = null;
        platform = PlatformFingerprint.detect();
    }

    public static synchronized Optional<Path> externalBrowser() {
        probe(Capability.EXTERNAL_BROWSER);
        return Optional.ofNullable(browserProgram).map(ExternalProgramDetector.Program::path);
    }

    public static synchronized Optional<Path> externalFfmpeg() {
        Optional<FfmpegRuntimeManager.ProgramPair> downloaded =
                FfmpegRuntimeManager.installedRuntime();
        if (downloaded.isPresent()) return Optional.of(downloaded.get().ffmpeg());
        probe(Capability.EXTERNAL_FFMPEG);
        return Optional.ofNullable(ffmpegProgram).map(ExternalProgramDetector.Program::path);
    }

    public static synchronized Optional<Path> externalFfprobe() {
        Optional<FfmpegRuntimeManager.ProgramPair> downloaded =
                FfmpegRuntimeManager.installedRuntime();
        if (downloaded.isPresent()) return Optional.of(downloaded.get().ffprobe());
        probe(Capability.EXTERNAL_FFMPEG);
        return Optional.ofNullable(ffprobeProgram).map(ExternalProgramDetector.Program::path);
    }

    public static synchronized CapabilityResult selectedBrowser() {
        CapabilityResult mcef = probe(Capability.MCEF_BROWSER);
        return mcef.available() ? mcef : probe(Capability.EXTERNAL_BROWSER);
    }

    public static synchronized CapabilityResult selectedVideo() {
        CapabilityResult downloaded = probe(Capability.DOWNLOADED_FFMPEG);
        return downloaded.available() ? downloaded : probe(Capability.EXTERNAL_FFMPEG);
    }

    public static synchronized String diagnostics() {
        StringBuilder result = new StringBuilder();
        result.append("MineScreen compatibility: os=").append(platform.os())
                .append(' ').append(platform.osVersion()).append(", arch=")
                .append(platform.architecture()).append(", runtime=").append(platform.runtime());
        for (Capability capability : Capability.values()) {
            CapabilityResult status = probe(capability);
            result.append("\n").append(capability).append('=').append(status.availability())
                    .append('/').append(status.backend());
            if (!status.reason().isBlank()) {
                result.append(" (").append(status.reason()).append(')');
            }
        }
        return result.toString();
    }

    /** Called after a backend throws so the same native initializer is not retried every tick. */
    public static synchronized void markFailed(Capability capability, Throwable failure) {
        String reason = failure == null ? "backend failed" : failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        CACHE.put(capability, CapabilityResult.unavailable(capability, reason));
    }

    private static CapabilityResult probeUncached(Capability capability) {
        boolean coreOnly = MineScreenClientConfig.COMPATIBILITY_MODE.get()
                == MineScreenClientConfig.CompatibilityMode.CORE_ONLY;
        return switch (capability) {
            case CORE_DISPLAY -> CapabilityResult.available(capability, BackendKind.CORE);
            case VNC_LOSSLESS -> CapabilityResult.available(capability, BackendKind.PURE_JAVA_VNC);
            case VNC_JPEG -> probeImageIo(capability);
            case MCEF_BROWSER -> coreOnly
                    ? CapabilityResult.unavailable(capability, "core-only mode")
                    : probeMcef(capability);
            case EXTERNAL_BROWSER -> coreOnly
                    ? CapabilityResult.unavailable(capability, "core-only mode")
                    : probeBrowserProgram(capability);
            case DOWNLOADED_FFMPEG -> coreOnly
                    ? CapabilityResult.unavailable(capability, "core-only mode")
                    : probeDownloadedFfmpeg(capability);
            case EXTERNAL_FFMPEG -> coreOnly
                    ? CapabilityResult.unavailable(capability, "core-only mode")
                    : probeFfmpegProgram(capability);
        };
    }

    private static CapabilityResult probeMcef(Capability capability) {
        if (!supportsMcefPlatform(platform)) {
            return CapabilityResult.unavailable(capability, "platform is outside MCEF support");
        }
        if (!ModList.get().isLoaded("mcef")) {
            return CapabilityResult.unavailable(capability, "optional MCEF mod is not installed");
        }
        try {
            Class.forName("com.cinemamod.mcef.MCEF", false,
                    CompatibilityManager.class.getClassLoader());
            return CapabilityResult.available(capability, BackendKind.MCEF);
        } catch (Throwable failure) {
            return CapabilityResult.unavailable(capability, failure.getClass().getSimpleName());
        }
    }

    private static CapabilityResult probeDownloadedFfmpeg(Capability capability) {
        Optional<FfmpegRuntimeManager.ProgramPair> runtime =
                FfmpegRuntimeManager.installedRuntime();
        if (runtime.isPresent()) {
            return CapabilityResult.available(capability, BackendKind.DOWNLOADED_FFMPEG);
        }
        // Respect an explicitly configured or already installed system pair. Do not start a
        // 20–25 MiB download when a working local FFmpeg can satisfy VIDEO immediately.
        CapabilityResult system = probeFfmpegProgram(Capability.EXTERNAL_FFMPEG);
        if (system.available()) {
            return CapabilityResult.unavailable(capability,
                    "a working system FFmpeg is already available");
        }
        FfmpegRuntimeManager.ensureInstalledAsync();
        FfmpegRuntimeManager.Snapshot state = FfmpegRuntimeManager.snapshot();
        String reason = state.state().active() ? "secure FFmpeg download is in progress"
                : state.error().isBlank() ? "FFmpeg runtime is not installed" : state.error();
        return CapabilityResult.unavailable(capability, reason);
    }

    static boolean supportsMcefPlatform(PlatformFingerprint fingerprint) {
        if (!fingerprint.desktopLike()) return false;
        boolean architecture = fingerprint.architecture()
                == PlatformFingerprint.CpuArchitecture.X86_64
                || fingerprint.architecture() == PlatformFingerprint.CpuArchitecture.ARM64;
        boolean operatingSystem = switch (fingerprint.os()) {
            case WINDOWS -> fingerprint.windowsAtLeast(10);
            case MACOS, LINUX -> true;
            default -> false;
        };
        return architecture && operatingSystem;
    }

    static boolean supportsDownloadedFfmpeg(PlatformFingerprint fingerprint) {
        return FfmpegRuntimeManager.specFor(fingerprint).isPresent();
    }

    private static CapabilityResult probeBrowserProgram(Capability capability) {
        if (!platform.desktopLike()) {
            return CapabilityResult.unavailable(capability, "external browser disabled on mobile runtime");
        }
        browserProgram = ExternalProgramDetector.browser(platform).orElse(null);
        if (browserProgram == null) {
            return CapabilityResult.unavailable(capability,
                    "no compatible installed browser detected");
        }
        String reason = browserProgram.path().getFileName() + ": "
                + browserProgram.conciseVersion();
        if (platform.os() == PlatformFingerprint.OsFamily.WINDOWS
                && !platform.windowsAtLeast(10)) {
            reason += "; security warning: this operating system/browser combination may no longer receive upstream fixes";
        }
        return CapabilityResult.compatibility(capability, BackendKind.EXTERNAL_CHROMIUM, reason);
    }

    private static CapabilityResult probeFfmpegProgram(Capability capability) {
        if (platform.os() == PlatformFingerprint.OsFamily.UNKNOWN) {
            return CapabilityResult.unavailable(capability, "unknown operating system");
        }
        ffmpegProgram = ExternalProgramDetector.ffmpeg(platform).orElse(null);
        if (ffmpegProgram == null) {
            return CapabilityResult.unavailable(capability, "no installed ffmpeg detected");
        }
        ffprobeProgram = ExternalProgramDetector.ffprobe(platform, ffmpegProgram.path()).orElse(null);
        if (ffprobeProgram == null) {
            return CapabilityResult.unavailable(capability, "ffprobe was not found beside ffmpeg");
        }
        return CapabilityResult.compatibility(capability, BackendKind.SYSTEM_FFMPEG,
                ffmpegProgram.path().getFileName() + ": " + ffmpegProgram.conciseVersion());
    }

    private static CapabilityResult probeImageIo(Capability capability) {
        return RfbEncodingCapabilities.jpegAvailable()
                ? CapabilityResult.available(capability, BackendKind.PURE_JAVA_VNC)
                : CapabilityResult.unavailable(capability, "JPEG ImageIO reader is unavailable");
    }
}
