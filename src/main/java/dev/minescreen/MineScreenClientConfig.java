package dev.minescreen;

import java.nio.file.Path;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-local compatibility settings. Paths in this spec are never synchronized to a server. */
public final class MineScreenClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<CompatibilityMode> COMPATIBILITY_MODE = BUILDER
            .comment("AUTO probes optional native and external content backends lazily. CORE_ONLY "
                    + "never starts Chromium or FFmpeg and keeps IDLE/text/traffic/VNC available.")
            .defineEnum("compatibility_mode", CompatibilityMode.AUTO);
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_BROWSER_PATH = BUILDER
            .comment("Optional absolute path to an already-installed Chromium-compatible browser. "
                    + "MineScreen never downloads a browser.")
            .define("external_browser_path", "", MineScreenClientConfig::validOptionalPath);
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_FFMPEG_PATH = BUILDER
            .comment("Optional absolute path to an already-installed ffmpeg executable. MineScreen "
                    + "uses ProcessBuilder directly and never invokes a command shell.")
            .define("external_ffmpeg_path", "", MineScreenClientConfig::validOptionalPath);
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_FFPROBE_PATH = BUILDER
            .comment("Optional absolute path to ffprobe. When blank, MineScreen looks beside ffmpeg "
                    + "and then in explicit PATH entries.")
            .define("external_ffprobe_path", "", MineScreenClientConfig::validOptionalPath);
    public static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD_FFMPEG_RUNTIME = BUILDER
            .comment("Prepare the matching FFmpeg runtime after the main menu appears. Downloads use only "
                    + "built-in HTTPS hosts, normal JVM certificate/hostname validation, private-IP "
                    + "rejection and a release-pinned SHA-256 hash. Disable this to require a "
                    + "manually selected ffmpeg/ffprobe pair.")
            .define("auto_download_ffmpeg_runtime", true);
    public static final ModConfigSpec.IntValue ANDROID_VIDEO_MAX_WIDTH = BUILDER
            .comment("Default VIDEO decode width cap for Pojav, FCL, ZL2 and other Android launchers. "
                    + "Height follows the screen aspect ratio.")
            .defineInRange("android_video_max_width", 854, 320, 1920);
    public static final ModConfigSpec.IntValue ANDROID_VIDEO_MAX_FPS = BUILDER
            .comment("VIDEO frame-rate cap used on Android launchers to reduce heat and memory use.")
            .defineInRange("android_video_max_fps", 15, 5, 30);
    public static final ModConfigSpec.IntValue ANDROID_VIDEO_FAR_FPS = BUILDER
            .comment("VIDEO frame-rate cap for distant screens on Android launchers.")
            .defineInRange("android_video_far_fps", 5, 1, 15);
    public static final ModConfigSpec.IntValue EXTERNAL_WEB_MAX_WIDTH = BUILDER
            .comment("Maximum compatibility-browser screencast width.")
            .defineInRange("external_web_max_width", 1280, 320, 3840);
    public static final ModConfigSpec.IntValue EXTERNAL_WEB_MAX_FPS = BUILDER
            .comment("Maximum compatibility-browser screencast frame rate.")
            .defineInRange("external_web_max_fps", 15, 1, 30);
    public static final ModConfigSpec.IntValue EXTERNAL_WEB_JPEG_QUALITY = BUILDER
            .comment("Compatibility-browser JPEG screencast quality.")
            .defineInRange("external_web_jpeg_quality", 70, 20, 90);
    public static final ModConfigSpec.BooleanValue SHOW_COMPATIBILITY_DIAGNOSTICS = BUILDER
            .comment("Show technical operating-system, architecture and backend failure details.")
            .define("show_compatibility_diagnostics", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public enum CompatibilityMode implements TranslatableEnum {
        AUTO,
        CORE_ONLY;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("minescreen.configuration.compatibility_mode."
                    + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Saves a user-selected executable immediately to the native NeoForge client config. */
    public static void setExternalBrowser(Path executable) {
        savePath(EXTERNAL_BROWSER_PATH, executable);
    }

    public static void setExternalFfmpeg(Path executable) {
        savePath(EXTERNAL_FFMPEG_PATH, executable);
    }

    public static void setExternalFfprobe(Path executable) {
        savePath(EXTERNAL_FFPROBE_PATH, executable);
    }

    private static void savePath(ModConfigSpec.ConfigValue<String> value, Path executable) {
        String normalized = executable == null ? ""
                : executable.toAbsolutePath().normalize().toString();
        value.set(normalized);
        value.save();
    }

    private static boolean validOptionalPath(Object value) {
        return value instanceof String text && text.length() <= 32_767;
    }

    private MineScreenClientConfig() {
    }
}
