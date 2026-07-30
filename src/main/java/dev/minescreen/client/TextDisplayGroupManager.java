package dev.minescreen.client;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import dev.minescreen.MineScreen;
import dev.minescreen.TextDisplayGroup;
import dev.minescreen.TextDisplayGroupResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-side, tick-batched cache; no topology BFS is performed from the BER render loop. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class TextDisplayGroupManager {
    private static final Map<Level, Map<Long, TextDisplayGroup>> CACHES = new WeakHashMap<>();

    private TextDisplayGroupManager() {
    }

    public static TextDisplayGroup groupAt(Level level, BlockPos pos) {
        Map<Long, TextDisplayGroup> cache = CACHES.computeIfAbsent(level, ignored -> new HashMap<>());
        TextDisplayGroup group = cache.get(pos.asLong());
        if (group != null && group.contains(pos)) {
            return group;
        }
        group = TextDisplayGroupResolver.resolve(level, pos);
        if (group != null) {
            for (BlockPos tile : group.tiles()) {
                cache.put(tile.asLong(), group);
            }
        }
        return group;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            CACHES.clear();
            return;
        }
        // Topology is repopulated lazily after the tick boundary. This makes breaking or placing
        // a board visible on the very next rendered frame instead of retaining an obsolete master
        // component for up to half a second (or indefinitely in paused/frozen virtual levels).
        CACHES.values().forEach(Map::clear);
    }
}
