package dev.minescreen.client;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded client-side cable BFS shared by fixed keyboards and computer hosts. */
public final class ScreenLinkResolver {
    private static final int MAX_NODES = 256;

    private ScreenLinkResolver() {
    }

    @Nullable
    public static ScreenGroup findGroup(ClientLevel level, BlockPos endpoint) {
        ScreenGroupManager.ensure(level);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(endpoint.immutable());
        visited.add(endpoint.immutable());
        while (!queue.isEmpty() && visited.size() <= MAX_NODES) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (level.getBlockEntity(next) instanceof ScreenBlockEntity screen) {
                    ScreenGroup group = ScreenGroupManager.groupAt(screen);
                    if (group != null) {
                        return group;
                    }
                }
                if (level.getBlockState(next).is(MineScreen.SCREEN_CABLE_BLOCK.get())
                        && visited.add(next.immutable())) {
                    queue.addLast(next.immutable());
                }
            }
        }
        return null;
    }
}
