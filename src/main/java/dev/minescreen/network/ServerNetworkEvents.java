package dev.minescreen.network;

import dev.minescreen.MineScreen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Releases exclusive VNC control when a player leaves. */
@EventBusSubscriber(modid = MineScreen.MOD_ID)
public final class ServerNetworkEvents {
    private ServerNetworkEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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
