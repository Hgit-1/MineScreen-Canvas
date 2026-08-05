package dev.minescreen.client.compat;

import java.util.Map;

/** Dependency-free regression matrix for runtimes that must never trigger native probing. */
public final class PlatformFingerprintTestHarness {
    private PlatformFingerprintTestHarness() {
    }

    public static void main(String[] args) {
        PlatformFingerprint win7 = detect("Windows 7", "6.1", "amd64", "Temurin", Map.of());
        require(win7.os() == PlatformFingerprint.OsFamily.WINDOWS, "Win7 OS detection");
        require(!win7.windowsAtLeast(10), "Win7 must not be treated as Win10+");
        require(!CompatibilityManager.supportsMcefPlatform(win7),
                "Win7 must not probe MCEF");
        require(!CompatibilityManager.supportsEmbeddedFfmpeg(win7),
                "Win7 must not initialize embedded FFmpeg");

        PlatformFingerprint pojav = detect("Linux", "5.10", "aarch64", "Android",
                Map.of("POJAV_RENDERER", "opengles3"));
        require(pojav.os() == PlatformFingerprint.OsFamily.ANDROID, "Pojav OS detection");
        require(pojav.runtime() == PlatformFingerprint.RuntimeFlavor.POJAV,
                "Pojav runtime detection");
        require(!pojav.desktopLike(), "Pojav must not load desktop-native backends");
        require(!CompatibilityManager.supportsMcefPlatform(pojav)
                        && !CompatibilityManager.supportsEmbeddedFfmpeg(pojav),
                "Pojav must reject optional desktop native backends before class probing");

        PlatformFingerprint harmony = detect("Linux", "5.10", "aarch64", "OpenJDK",
                Map.of("OHOS_SDK_HOME", "/opt/ohos"));
        require(harmony.os() == PlatformFingerprint.OsFamily.HARMONY,
                "Harmony runtime detection");
        require(!harmony.desktopLike(), "Harmony must not load desktop-native backends");
        require(!CompatibilityManager.supportsMcefPlatform(harmony)
                        && !CompatibilityManager.supportsEmbeddedFfmpeg(harmony),
                "Harmony must reject optional desktop native backends before class probing");

        PlatformFingerprint loong = detect("Linux", "6.6", "loongarch64", "OpenJDK", Map.of());
        require(loong.architecture() == PlatformFingerprint.CpuArchitecture.LOONGARCH64,
                "LoongArch detection");
        require(loong.desktopLike(), "LoongArch Linux can use external pure process backends");
        require(!CompatibilityManager.supportsMcefPlatform(loong)
                        && !CompatibilityManager.supportsEmbeddedFfmpeg(loong),
                "LoongArch must use external rather than bundled native backends");

        PlatformFingerprint unknown = detect("MysteryOS", "1", "mystery", "Unknown", Map.of());
        require(unknown.os() == PlatformFingerprint.OsFamily.UNKNOWN, "unknown OS detection");
        require(!unknown.desktopLike(), "unknown OS must stay core-only for native probing");
        System.out.println("platformFingerprintTest=passed");
    }

    private static PlatformFingerprint detect(String os, String version, String arch,
            String vendor, Map<String, String> environment) {
        return PlatformFingerprint.detect(Map.of(
                "os.name", os,
                "os.version", version,
                "os.arch", arch,
                "java.vendor", vendor,
                "java.runtime.name", vendor + " Runtime",
                "java.vm.name", vendor + " VM"), environment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
