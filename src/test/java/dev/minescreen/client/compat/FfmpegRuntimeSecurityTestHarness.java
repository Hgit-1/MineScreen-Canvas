package dev.minescreen.client.compat;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline release gate for platform routing and the immutable download manifest. */
public final class FfmpegRuntimeSecurityTestHarness {
    private FfmpegRuntimeSecurityTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        List<PlatformFingerprint> platforms = List.of(
                platform(PlatformFingerprint.OsFamily.WINDOWS,
                        PlatformFingerprint.CpuArchitecture.X86_64),
                platform(PlatformFingerprint.OsFamily.LINUX,
                        PlatformFingerprint.CpuArchitecture.X86_64),
                platform(PlatformFingerprint.OsFamily.LINUX,
                        PlatformFingerprint.CpuArchitecture.ARM64),
                platform(PlatformFingerprint.OsFamily.MACOS,
                        PlatformFingerprint.CpuArchitecture.X86_64),
                platform(PlatformFingerprint.OsFamily.MACOS,
                        PlatformFingerprint.CpuArchitecture.ARM64),
                new PlatformFingerprint(PlatformFingerprint.OsFamily.ANDROID, "14",
                        PlatformFingerprint.CpuArchitecture.ARM64,
                        PlatformFingerprint.RuntimeFlavor.POJAV));
        Set<String> hashes = new HashSet<>();
        for (PlatformFingerprint platform : platforms) {
            var spec = FfmpegRuntimeManager.specFor(platform).orElseThrow();
            require(spec.manualUrl().startsWith("https://repo.maven.apache.org/maven2/"),
                    "manual URL must use canonical HTTPS Maven Central");
            require(spec.fileName().equals("ffmpeg-" + FfmpegRuntimeManager.VERSION + "-"
                    + spec.classifier() + ".jar"), "exact classifier filename");
            require(spec.sha256().matches("[0-9A-F]{64}"), "pinned SHA-256 format");
            require(hashes.add(spec.sha256()), "platform hashes must be distinct");
            require(spec.size() > 15_000_000L && spec.size() < 64_000_000L,
                    "bounded runtime package size");
        }
        for (String unsafe : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1",
                "192.168.1.1", "100.64.0.1", "169.254.169.254", "::1", "fe80::1",
                "fc00::1")) {
            require(FfmpegRuntimeManager.unsafeAddress(InetAddress.getByName(unsafe)),
                    "must reject private download address " + unsafe);
        }
        require(!FfmpegRuntimeManager.unsafeAddress(InetAddress.getByName("1.1.1.1")),
                "public address must remain eligible for TLS verification");
        System.out.println("ffmpegRuntimeSecurityTest=passed; specs=" + platforms.size());
    }

    private static PlatformFingerprint platform(PlatformFingerprint.OsFamily os,
            PlatformFingerprint.CpuArchitecture architecture) {
        return new PlatformFingerprint(os, os == PlatformFingerprint.OsFamily.WINDOWS
                ? "10.0" : "1", architecture, PlatformFingerprint.RuntimeFlavor.DESKTOP);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
