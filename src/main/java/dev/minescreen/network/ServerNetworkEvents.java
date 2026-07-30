package dev.minescreen.network;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.minescreen.MineScreen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Releases exclusive VNC control when a player leaves. */
@EventBusSubscriber(modid = MineScreen.MOD_ID)
public final class ServerNetworkEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String QA_AUTO_STOP_PROPERTY = "minescreen.qa.autoStopServer";

    private ServerNetworkEvents() {
    }

    /**
     * CI-only lifecycle probe. The property is never set by normal clients or servers. Reaching
     * this event proves that common registration, datapacks, recipes, world loading and the first
     * dedicated-server lifecycle all completed without loading a client-only MineScreen class.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(QA_AUTO_STOP_PROPERTY)) return;
        LOGGER.info("MineScreen dedicated-server smoke test reached SERVER_STARTED; stopping cleanly");
        event.getServer().halt(false);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        dev.minescreen.compat.CreateTrainScheduleServerCompat.tickServer(event.getServer());
        if (event.getServer().getTickCount() % 100 == 0) {
            WebPeerRegistry.tick(event.getServer());
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Five-second authoritative heartbeat: enough to correct video clocks and late-arriving
            // nearby players without streaming per-tick state or any web/video framebuffer data.
            if (level.getGameTime() % 100L != 0L) {
                continue;
            }
            for (ServerScreenStateData.State state : ServerScreenStateData.get(level).states()) {
                MineScreenNetwork.sendStateNear(level, state);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MineScreenNetwork.releasePlayer(player.getUUID());
        for (ServerLevel level : player.getServer().getAllLevels()) {
            ServerScreenStateData data = ServerScreenStateData.get(level);
            for (ServerScreenStateData.State state : data.states()) {
                if (player.getUUID().equals(state.controller)) {
                    state.controller = null;
                    data.setDirty();
                    MineScreenNetwork.sendStateNear(level, state);
                }
            }
        }
    }
}
