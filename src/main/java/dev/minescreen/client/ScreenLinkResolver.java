package dev.minescreen.client;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

import javax.annotation.Nullable;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded cable BFS shared by fixed keyboards and multi-plane computer hosts. */
public final class ScreenLinkResolver {
    private static final int MAX_NODES = 4096;

    private ScreenLinkResolver() {
    }

    @Nullable
    public static ScreenGroup findGroup(ClientLevel level, BlockPos endpoint) {
        List<ScreenGroup> groups = findNetwork(level, endpoint).groups();
        return groups.isEmpty() ? null : groups.getFirst();
    }

    /** Finds every plane attached to a computer/fixed-keyboard cable endpoint. */
    public static LinkedNetwork findNetwork(ClientLevel level, BlockPos endpoint) {
        ScreenGroupManager.ensure(level);
        Search search = new Search(level);
        search.computers.add(endpoint.immutable());
        search.inspectEndpoint(endpoint);
        search.run();
        return search.result();
    }

    /** Finds the computer/cable component containing one already-resolved screen plane. */
    public static LinkedNetwork findNetwork(ClientLevel level, ScreenGroup seed) {
        ScreenGroupManager.ensure(level);
        Search search = new Search(level);
        search.attach(seed);
        search.run();
        return search.result();
    }

    public record LinkedNetwork(List<ScreenGroup> groups, Set<BlockPos> computers,
            Set<BlockPos> cables) {
        public LinkedNetwork {
            groups = List.copyOf(groups);
            computers = Set.copyOf(computers);
            cables = Set.copyOf(cables);
        }

        public boolean hasComputer() {
            return !computers.isEmpty();
        }
    }

    private static final class Search {
        private final ClientLevel level;
        private final ArrayDeque<BlockPos> cables = new ArrayDeque<>();
        private final Set<BlockPos> visitedCables = new HashSet<>();
        private final java.util.Map<java.util.UUID, ScreenGroup> groups = new java.util.HashMap<>();
        private final Set<BlockPos> computers = new HashSet<>();

        private Search(ClientLevel level) {
            this.level = level;
        }

        private void inspectEndpoint(BlockPos endpoint) {
            for (Direction direction : Direction.values()) {
                inspectNeighbor(endpoint.relative(direction));
            }
        }

        private void run() {
            while (!cables.isEmpty() && visitedCables.size() <= MAX_NODES) {
                BlockPos cable = cables.removeFirst();
                for (Direction direction : Direction.values()) {
                    inspectNeighbor(cable.relative(direction));
                }
            }
        }

        private void inspectNeighbor(BlockPos pos) {
            if (level.getBlockState(pos).is(MineScreen.SCREEN_CABLE_BLOCK.get())) {
                if (visitedCables.add(pos.immutable())) {
                    cables.addLast(pos.immutable());
                }
                return;
            }
            if (level.getBlockState(pos).is(MineScreen.COMPUTER_BLOCK.get())) {
                computers.add(pos.immutable());
                return;
            }
            if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
                ScreenGroup group = ScreenGroupManager.groupAt(screen);
                if (group != null) {
                    attach(group);
                }
            }
        }

        private void attach(ScreenGroup group) {
            if (groups.putIfAbsent(group.groupId(), group) != null) {
                return;
            }
            // A screen plane may have cables connected at different tiles. It may bridge those
            // cable branches, but a differently-facing neighboring screen is never admitted
            // directly:异面 joining therefore requires an actual extension-cable path.
            for (BlockPos tile : group.tiles()) {
                for (Direction direction : Direction.values()) {
                    BlockPos next = tile.relative(direction);
                    if (level.getBlockState(next).is(MineScreen.SCREEN_CABLE_BLOCK.get())) {
                        if (visitedCables.add(next.immutable())) {
                            cables.addLast(next.immutable());
                        }
                    } else if (level.getBlockState(next).is(MineScreen.COMPUTER_BLOCK.get())) {
                        computers.add(next.immutable());
                    }
                }
            }
        }

        private LinkedNetwork result() {
            List<ScreenGroup> ordered = new ArrayList<>(groups.values());
            ordered.sort(Comparator.comparingInt((ScreenGroup group) -> facingOrder(group.facing()))
                    .thenComparingLong(group -> group.master().asLong())
                    .thenComparing(ScreenGroup::groupId));
            return new LinkedNetwork(ordered, computers, visitedCables);
        }
    }

    private static int facingOrder(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            case UP -> 4;
            case DOWN -> 5;
        };
    }
}
