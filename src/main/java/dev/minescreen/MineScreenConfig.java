package dev.minescreen;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config shared by the optional server state channel and the client renderer. */
public final class MineScreenConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue UNRESTRICTED_SINGLEPLAYER = BUILDER.comment(
            "When true, an integrated single-player world that is not open to LAN bypasses URL, IP, whitelist, HTTP, localhost/private-network, metadata, and local-file restrictions. Multiplayer and LAN worlds remain protected.")
            .define("unrestricted_singleplayer", true);

    public static final ModConfigSpec.BooleanValue ALLOW_HTTP = BUILDER.comment(
            "Risk: clear-text HTTP permits network observers to read or alter a page/media request.")
            .define("allow_http", false);
    public static final ModConfigSpec.BooleanValue ALLOW_LOCALHOST = BUILDER.comment(
            "Risk: localhost access exposes services running on the player's machine.")
            .define("allow_localhost", false);
    public static final ModConfigSpec.BooleanValue ALLOW_PRIVATE_IP = BUILDER.comment(
            "Risk: private RFC1918 addresses can reach routers, NAS devices, and internal services.")
            .define("allow_private_ip", false);
    public static final ModConfigSpec.BooleanValue ALLOW_CLOUD_METADATA = BUILDER.comment(
            "Risk: cloud metadata endpoints can disclose instance credentials; keep disabled.")
            .define("allow_cloud_metadata", false);
    public static final ModConfigSpec.BooleanValue DISABLE_WHITELIST = BUILDER.comment(
            "Risk: disabling the allow-list permits arbitrary remote domains.")
            .define("disable_whitelist", false);
    public static final ModConfigSpec.BooleanValue ALLOW_FILE_PROTOCOL = BUILDER.comment(
            "Risk: file:// and local MP4 access expose files from the player's machine.")
            .define("allow_file_protocol", false);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DOMAIN_WHITELIST = BUILDER.comment(
            "Allowed hostnames for browser and VNC destinations unless disable_whitelist is enabled.")
            .defineListAllowEmpty("domain_whitelist", List.of(), () -> "example.com",
                    value -> value instanceof String host && !host.isBlank());

    public static final ModConfigSpec.IntValue DEFAULT_WIDTH = BUILDER.comment(
            "Legacy Stage-1 anchor width. Newly placed screens are always one auto-joining tile.")
            .defineInRange("default_width_blocks", 2, 1, 32);
    public static final ModConfigSpec.IntValue DEFAULT_HEIGHT = BUILDER.comment(
            "Legacy Stage-1 anchor height. Newly placed screens are always one auto-joining tile.")
            .defineInRange("default_height_blocks", 1, 1, 32);
    public static final ModConfigSpec.IntValue MAX_RENDER_DISTANCE = BUILDER.comment(
            "Distance at which decoder/browser rendering is paused.")
            .defineInRange("max_render_distance", 96, 8, 256);
    public static final ModConfigSpec.IntValue DEFAULT_SCREEN_SOUND_DISTANCE = BUILDER.comment(
            "Maximum audible distance in blocks from a screen when no connected speaker is closer.")
            .defineInRange("default_screen_sound_distance", 32, 1, 256);
    public static final ModConfigSpec.IntValue SPEAKER_SOUND_DISTANCE = BUILDER.comment(
            "Maximum audible distance in blocks from each directly/cable-connected speaker.")
            .defineInRange("speaker_sound_distance", 96, 1, 512);
    public static final ModConfigSpec.IntValue PIXELS_PER_TILE = BUILDER.comment(
            "Logical pixels contributed by one screen block along each axis.")
            .defineInRange("pixels_per_tile", 720, 64, 2160);
    public static final ModConfigSpec.IntValue DEFAULT_RESOLUTION_PERCENT = BUILDER.comment(
            "Default per-screen render resolution percentage. Lower WEB values make page UI larger and reduce GPU/CPU cost.")
            .defineInRange("default_resolution_percent", 100, 25, 100);
    public static final ModConfigSpec.IntValue MAX_CANVAS_WIDTH = BUILDER.comment(
            "Maximum joined canvas width in pixels.")
            .defineInRange("max_canvas_width", 3840, 256, 8192);
    public static final ModConfigSpec.IntValue MAX_CANVAS_HEIGHT = BUILDER.comment(
            "Maximum joined canvas height in pixels.")
            .defineInRange("max_canvas_height", 2160, 256, 8192);
    public static final ModConfigSpec.IntValue MAX_CANVAS_PIXELS = BUILDER.comment(
            "Maximum joined canvas pixel count; joined dimensions are scaled down uniformly.")
            .defineInRange("max_canvas_pixels", 8_294_400, 65_536, 67_108_864);
    public static final ModConfigSpec.IntValue VIDEO_MAX_FPS = BUILDER.comment(
            "Maximum near-distance video decode/upload rate.")
            .defineInRange("video_max_fps", 30, 1, 30);
    public static final ModConfigSpec.IntValue VIDEO_FAR_FPS = BUILDER.comment(
            "Video decode/upload rate beyond half of max_render_distance.")
            .defineInRange("video_far_fps", 10, 5, 10);
    public static final ModConfigSpec.IntValue MAX_ACTIVE_CONTENT_SESSIONS = BUILDER.comment(
            "Maximum simultaneously finalized VIDEO/WEB/VNC backends. Additional screens remain in the asynchronous loading state until a slot is released.")
            .defineInRange("max_active_content_sessions", 8, 1, 32);
    public static final ModConfigSpec.IntValue MAX_ACTIVE_CANVAS_PIXELS = BUILDER.comment(
            "Aggregate logical-pixel reservation for finalized content backends. This bounds NativeImage/Chromium/VNC memory bursts; one oversized visible session is still allowed.")
            .defineInRange("max_active_canvas_pixels", 33_177_600, 1_048_576, 134_217_728);
    public static final ModConfigSpec.IntValue MAX_WEB_TABS_PER_SESSION = BUILDER.comment(
            "Maximum Chromium tabs kept by one WEB session. Restored tabs are created one per client tick to avoid a native-memory and render-thread spike.")
            .defineInRange("max_web_tabs_per_session", 6, 1, 16);
    public static final ModConfigSpec.IntValue VNC_COMPRESSION_LEVEL = BUILDER.comment(
            "TightVNC zlib compression level requested from the RFB server. 9 saves the most bandwidth but uses more server CPU.")
            .defineInRange("vnc_compression_level", 9, 0, 9);
    public static final ModConfigSpec.IntValue VNC_JPEG_QUALITY = BUILDER.comment(
            "TightVNC JPEG quality level from 0 to 9. Lower values are more aggressive; 5 is a balanced bandwidth-first default.")
            .defineInRange("vnc_jpeg_quality", 5, 0, 9);
    public static final ModConfigSpec.IntValue VNC_MAX_FPS = BUILDER.comment(
            "Default maximum VNC framebuffer request rate. Individual screens may override this value.")
            .defineInRange("vnc_max_fps", 20, 1, 60);
    public static final ModConfigSpec.BooleanValue WEB_PEER_DISTRIBUTION = BUILDER.comment(
            "Enable direct peer-assisted WEB framebuffer distribution. Risk: nearby players learn each other's network addresses and page pixels travel without transport encryption. Enable on both server and clients; frames never traverse the Minecraft server.")
            .define("web_peer_distribution", false);
    public static final ModConfigSpec.IntValue WEB_PEER_LISTEN_PORT = BUILDER.comment(
            "TCP port for direct WEB peers. 0 selects an ephemeral port. Internet peers normally require a fixed forwarded port; LAN peers usually work without forwarding.")
            .defineInRange("web_peer_listen_port", 0, 0, 65535);
    public static final ModConfigSpec.IntValue WEB_PEER_FPS = BUILDER.comment(
            "Maximum peer WEB framebuffer rate. Navigation/tab state is sent separately.")
            .defineInRange("web_peer_fps", 8, 1, 15);
    public static final ModConfigSpec.IntValue WEB_PEER_MAX_WIDTH = BUILDER.comment(
            "Maximum width of the JPEG framebuffer distributed to peers; height follows the source aspect ratio.")
            .defineInRange("web_peer_max_width", 1280, 320, 1920);
    public static final ModConfigSpec.IntValue WEB_PEER_JPEG_QUALITY = BUILDER.comment(
            "Peer WEB JPEG quality percentage. Lower values save bandwidth; 58 is an aggressive text-aware compromise.")
            .defineInRange("web_peer_jpeg_quality", 58, 20, 90);
    public static final ModConfigSpec.IntValue WEB_PEER_MAX_UPLOAD_KBPS = BUILDER.comment(
            "Approximate total WEB peer upload ceiling per client, including all tree children. Frames are dropped rather than queued when the budget is exhausted.")
            .defineInRange("web_peer_max_upload_kbps", 8000, 256, 50000);
    public static final ModConfigSpec.ConfigValue<String> UI_PROVIDER = BUILDER.comment(
            "MineScreen UI provider id. auto selects the highest-priority registered adapter; vanilla disables adapters; missing ids fall back safely.")
            .define("ui_provider", "auto", value -> value instanceof String text && !text.isBlank());
    public static final ModConfigSpec.BooleanValue COMPOSITE_UI_LAYER = BUILDER.comment(
            "Render MineScreen media, widget frames, text and input carets into one texture before compositing. Recommended for ModernUI compatibility.")
            .define("composite_ui_layer", true);
    public static final ModConfigSpec.ConfigValue<String> CONTROL_INDICATOR_COLOR = BUILDER.comment(
            "Small control indicator color as an ARGB hex string.")
            .define("control_indicator_color", "FFFFD43B");
    public static final ModConfigSpec.ConfigValue<String> CONTROL_INDICATOR_CORNER = BUILDER.comment(
            "Control indicator corner: bottom_right, bottom_left, top_right, or top_left.")
            .define("control_indicator_corner", "bottom_right");
    public static final ModConfigSpec.BooleanValue SHOW_IDLE_INDICATOR = BUILDER.comment(
            "Whether an optional controller overlay may show a faint idle mark when nobody controls it.")
            .define("show_idle_indicator", false);
    public static final ModConfigSpec.EnumValue<WebLoadingStyle> WEB_LOADING_STYLE = BUILDER.comment(
            "WEB loading animation style: ORBIT, PULSE, or MINIMAL.")
            .defineEnum("web_loading_style", WebLoadingStyle.ORBIT);
    public static final ModConfigSpec.ConfigValue<String> WEB_LOADING_ACCENT_COLOR = BUILDER.comment(
            "WEB loading animation accent as an ARGB hex string.")
            .define("web_loading_accent_color", "FFFFD43B", MineScreenConfig::validArgb);
    public static final ModConfigSpec.ConfigValue<String> WEB_LOADING_BACKGROUND_COLOR = BUILDER.comment(
            "WEB loading page base color as an ARGB hex string.")
            .define("web_loading_background_color", "FF101722", MineScreenConfig::validArgb);
    public static final ModConfigSpec.IntValue WEB_LOADING_SPEED_PERCENT = BUILDER.comment(
            "WEB loading animation speed percentage. 100 is the default speed.")
            .defineInRange("web_loading_speed_percent", 100, 25, 300);
    public static final ModConfigSpec.BooleanValue WEB_LOADING_SHOW_THUMBNAIL = BUILDER.comment(
            "Show the last local page thumbnail behind the WEB loading animation.")
            .define("web_loading_show_thumbnail", true);
    public static final ModConfigSpec.BooleanValue WEB_LOADING_SHOW_MASCOT = BUILDER.comment(
            "Show the small pixel assistant on WEB loading and error pages.")
            .define("web_loading_show_mascot", true);
    public static final ModConfigSpec.BooleanValue UI_SHOW_MASCOT = BUILDER.comment(
            "Show the small pixel assistant in MineScreen configuration panels.")
            .define("ui_show_mascot", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static int defaultWidthBlocks() {
        return DEFAULT_WIDTH.get();
    }

    public static int defaultHeightBlocks() {
        return DEFAULT_HEIGHT.get();
    }

    private static boolean validArgb(Object value) {
        return value instanceof String text
                && text.replace("#", "").matches("(?i)[0-9a-f]{6}([0-9a-f]{2})?");
    }

    public enum WebLoadingStyle implements TranslatableEnum {
        ORBIT,
        PULSE,
        MINIMAL;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("minescreen.configuration.web_loading_style."
                    + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private MineScreenConfig() {
    }
}
