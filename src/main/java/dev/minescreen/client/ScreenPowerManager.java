package dev.minescreen.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Cached logical power network. Redstone powers one adjacent extension cable and MineScreen then
 * distributes that state through the bounded cable/screen component without turning the cable
 * into a vanilla redstone conductor.
 */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenPowerManager {
    private static final long REFRESH_TICKS = 5L;
    private static final Map<UUID, Boolean> POWERED = new HashMap<>();
    private static ClientLevel level;
    private static long refreshedAt = Long.MIN_VALUE;

    private ScreenPowerManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensure(Minecraft.getInstance().level);
    }

    public static boolean isPowered(ScreenGroup group) {
        if (group == null) {
            return false;
        }
        ClientLevel current = Minecraft.getInstance().level;
        ensure(current);
        Boolean cached = POWERED.get(group.groupId());
        if (cached != null || current == null) {
            return Boolean.TRUE.equals(cached);
        }
        // Renderers can ask during the same client tick in which a chunk first provides its
        // screen BE. Never cache a transient "no groups yet" result as an unpowered screen.
        boolean powered = isNetworkPowered(current, ScreenLinkResolver.findNetwork(current, group));
        POWERED.put(group.groupId(), powered);
        return powered;
    }

    public static void invalidate() {
        refreshedAt = Long.MIN_VALUE;
    }

    private static void ensure(ClientLevel current) {
        if (current == null) {
            POWERED.clear();
            level = null;
            refreshedAt = Long.MIN_VALUE;
            return;
        }
        // Both managers subscribe to ClientTick.Post. Establishing groups here removes their
        // subscriber-order dependency, which otherwise produces a black first frame and can
        // tear down an already-active content session before the next power refresh.
        ScreenGroupManager.ensure(current);
        long now = current.getGameTime();
        if (level == current && refreshedAt != Long.MIN_VALUE && now >= refreshedAt
                && now - refreshedAt < REFRESH_TICKS) {
            return;
        }
        level = current;
        refreshedAt = now;
        rebuild(current);
    }

    private static void rebuild(ClientLevel current) {
        Map<UUID, Boolean> next = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        for (ScreenGroup group : ScreenGroupManager.groups()) {
            if (!visited.add(group.groupId())) {
                continue;
            }
            ScreenLinkResolver.LinkedNetwork network =
                    ScreenLinkResolver.findNetwork(current, group);
            boolean powered = isNetworkPowered(current, network);
            for (ScreenGroup member : network.groups()) {
                visited.add(member.groupId());
                next.put(member.groupId(), powered);
            }
        }
        POWERED.clear();
        POWERED.putAll(next);
    }

    private static boolean isNetworkPowered(ClientLevel current,
            ScreenLinkResolver.LinkedNetwork network) {
        return network.cables().stream().anyMatch(cable -> hasSignalInput(current, cable));
    }

    /**
     * Level#hasNeighborSignal is the canonical check. The state fallbacks cover client-side
     * updates where a powered lever/button or redstone-wire power property arrives one update
     * before the neighbour-signal graph recalculates. Lever orientation intentionally does not
     * matter for MineScreen power input.
     */
    private static boolean hasSignalInput(ClientLevel current, BlockPos cable) {
        if (current.hasNeighborSignal(cable)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockState neighbor = current.getBlockState(cable.relative(direction));
            if (neighbor.is(Blocks.REDSTONE_WIRE)
                    && neighbor.getValue(RedStoneWireBlock.POWER) > 0) {
                return true;
            }
            if (neighbor.hasProperty(BlockStateProperties.POWERED)
                    && neighbor.getValue(BlockStateProperties.POWERED)) {
                return true;
            }
        }
        return false;
    }
}
