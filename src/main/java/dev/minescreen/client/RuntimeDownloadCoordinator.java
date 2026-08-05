package dev.minescreen.client;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenClientConfig;
import dev.minescreen.client.compat.CompatibilityManager;
import dev.minescreen.client.compat.FfmpegRuntimeManager;
import dev.minescreen.client.compat.McefDownloadProgressBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opens a single progress surface when MineScreen and MCEF install runtimes concurrently. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class RuntimeDownloadCoordinator {
    private static Screen mcefDownloader;
    private static FfmpegRuntimeManager.Snapshot shownFailure;
    private static boolean startupProbeTriggered;

    private RuntimeDownloadCoordinator() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current instanceof RuntimeDownloadScreen) return;
        // Match MCEF's player-facing lifecycle: prepare the media runtime once the normal main
        // menu is actually visible, not during static mod loading or before Minecraft has a GUI.
        // Automated smoke clients opt out so release verification never depends on the network.
        if (!startupProbeTriggered && current instanceof TitleScreen
                && !Boolean.getBoolean("minescreen.qa.autoStopClient")) {
            startupProbeTriggered = true;
            CompatibilityManager.selectedVideo();
        }
        if (McefDownloadProgressBridge.isDownloader(current)) {
            mcefDownloader = current;
            if (MineScreenClientConfig.COMPATIBILITY_MODE.get()
                    == MineScreenClientConfig.CompatibilityMode.AUTO
                    && MineScreenClientConfig.AUTO_DOWNLOAD_FFMPEG_RUNTIME.get()) {
                FfmpegRuntimeManager.ensureInstalledAsync();
            }
        }
        var ffmpeg = FfmpegRuntimeManager.snapshot();
        boolean mcefActive = McefDownloadProgressBridge.snapshot().active();
        boolean showFailure = ffmpeg.state() == FfmpegRuntimeManager.State.FAILED
                && ffmpeg != shownFailure;
        if (mcefActive || ffmpeg.state().active() || showFailure) {
            shownFailure = showFailure ? ffmpeg : shownFailure;
            minecraft.setScreen(new RuntimeDownloadScreen(current, mcefDownloader));
        }
    }

    static void finishedMcefScreen() {
        mcefDownloader = null;
        McefDownloadProgressBridge.clearObservation();
    }
}
