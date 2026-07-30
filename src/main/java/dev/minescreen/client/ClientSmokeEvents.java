package dev.minescreen.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import com.cinemamod.mcef.MCEF;
import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

/**
 * Opt-in release diagnostic that proves client initialization reached an actual rendered frame.
 * The property is set only by the Gradle {@code clientSmoke} run and has no effect in normal games.
 */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean STOPPING = new AtomicBoolean();

    private ClientSmokeEvents() {
    }

    @SubscribeEvent
    public static void afterFirstFrame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("minescreen.qa.autoStopClient")
                || !MCEF.isInitialized()
                || !STOPPING.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("MineScreen client smoke test reached MCEF_READY_AND_FIRST_RENDERED_FRAME; stopping cleanly");
        // MCEF may still have a non-daemon native-library download worker during a pristine test
        // run. Ask Minecraft to shut down normally first, then give shutdown hooks ten seconds
        // before ending this opt-in QA process. This branch is unreachable in a normal game.
        Thread.ofPlatform()
                .daemon(true)
                .name("minescreen-client-smoke-exit-watchdog")
                .start(() -> {
                    LockSupport.parkNanos(java.time.Duration.ofSeconds(10).toNanos());
                    LOGGER.warn("MineScreen client smoke shutdown watchdog ending the QA process");
                    System.exit(0);
                });
        Minecraft.getInstance().stop();
    }
}
