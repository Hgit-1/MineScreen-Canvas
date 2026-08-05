package dev.minescreen.client.compat;

import java.util.Locale;
import java.util.Map;

/** Normalized, side-effect-free description of the runtime hosting the Minecraft client. */
public record PlatformFingerprint(OsFamily os, String osVersion,
        CpuArchitecture architecture, RuntimeFlavor runtime) {
    public static PlatformFingerprint detect() {
        return detect(System.getProperties().entrySet().stream().collect(java.util.stream.Collectors
                .toMap(entry -> String.valueOf(entry.getKey()), entry -> String.valueOf(entry.getValue()))),
                System.getenv());
    }

    static PlatformFingerprint detect(Map<String, String> properties, Map<String, String> environment) {
        String osName = lower(properties.get("os.name"));
        String version = clean(properties.get("os.version"));
        String archName = lower(properties.get("os.arch"));
        String vendor = lower(properties.get("java.vendor"));
        String runtimeName = lower(properties.get("java.runtime.name"));
        String vmName = lower(properties.get("java.vm.name"));
        boolean pojavEnvironment = environment.containsKey("POJAV_RENDERER")
                || environment.containsKey("POJAV_NATIVEDIR");
        boolean androidLauncherEnvironment = environment.keySet().stream().anyMatch(key -> {
            String normalized = lower(key);
            return normalized.startsWith("fcl_") || normalized.startsWith("zl2_")
                    || normalized.contains("foldcraft");
        });
        boolean harmonyEnvironment = environment.containsKey("HARMONYOS")
                || environment.containsKey("OHOS_SDK_HOME");
        String joined = osName + " " + vendor + " " + runtimeName + " " + vmName + " "
                + lower(environment.get("POJAV_RENDERER")) + " "
                + lower(environment.get("POJAV_NATIVEDIR")) + " "
                + lower(environment.get("HARMONYOS")) + " "
                + lower(environment.get("OHOS_SDK_HOME"))
                + " " + properties.values().stream().map(PlatformFingerprint::lower)
                        .collect(java.util.stream.Collectors.joining(" "))
                + (pojavEnvironment ? " pojav" : "")
                + (androidLauncherEnvironment ? " android-launcher" : "")
                + (harmonyEnvironment ? " harmony" : "");

        RuntimeFlavor runtime;
        OsFamily os;
        if (joined.contains("harmony") || joined.contains("ohos")) {
            os = OsFamily.HARMONY;
            runtime = RuntimeFlavor.MOBILE_LAUNCHER;
        } else if (joined.contains("ios") || joined.contains("amethyst-ios")) {
            os = OsFamily.IOS;
            runtime = RuntimeFlavor.MOBILE_LAUNCHER;
        } else if (joined.contains("android") || joined.contains("pojav")
                || joined.contains("foldcraft") || joined.contains("fcl launcher")
                || joined.contains("zl2")) {
            os = OsFamily.ANDROID;
            runtime = joined.contains("pojav") ? RuntimeFlavor.POJAV
                    : joined.contains("foldcraft") || joined.contains("fcl launcher")
                            || joined.contains("zl2") || androidLauncherEnvironment
                                    ? RuntimeFlavor.ANDROID_LAUNCHER
                                    : RuntimeFlavor.MOBILE_LAUNCHER;
        } else if (osName.contains("windows")) {
            os = OsFamily.WINDOWS;
            runtime = RuntimeFlavor.DESKTOP;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = OsFamily.MACOS;
            runtime = RuntimeFlavor.DESKTOP;
        } else if (osName.contains("linux") || osName.contains("unix")) {
            os = OsFamily.LINUX;
            runtime = RuntimeFlavor.DESKTOP;
        } else {
            os = OsFamily.UNKNOWN;
            runtime = RuntimeFlavor.UNKNOWN;
        }
        return new PlatformFingerprint(os, version, CpuArchitecture.parse(archName), runtime);
    }

    public boolean desktopLike() {
        return runtime == RuntimeFlavor.DESKTOP
                && (os == OsFamily.WINDOWS || os == OsFamily.MACOS || os == OsFamily.LINUX);
    }

    public boolean windowsAtLeast(int major) {
        if (os != OsFamily.WINDOWS) {
            return false;
        }
        try {
            String first = osVersion.split("\\.", 2)[0];
            return Integer.parseInt(first) >= major;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public enum OsFamily {
        WINDOWS,
        MACOS,
        LINUX,
        ANDROID,
        IOS,
        HARMONY,
        UNKNOWN
    }

    public enum CpuArchitecture {
        X86_64,
        X86,
        ARM64,
        ARM32,
        LOONGARCH64,
        UNKNOWN;

        static CpuArchitecture parse(String value) {
            return switch (value) {
                case "amd64", "x86_64", "x64" -> X86_64;
                case "x86", "i386", "i486", "i586", "i686" -> X86;
                case "aarch64", "arm64" -> ARM64;
                case "arm", "arm32", "armv7", "armv7l" -> ARM32;
                case "loongarch64", "loong64" -> LOONGARCH64;
                default -> UNKNOWN;
            };
        }
    }

    public enum RuntimeFlavor {
        DESKTOP,
        POJAV,
        ANDROID_LAUNCHER,
        MOBILE_LAUNCHER,
        UNKNOWN
    }
}
