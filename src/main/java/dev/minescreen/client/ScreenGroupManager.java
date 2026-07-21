package dev.minescreen.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenBlockEntity;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Tick-batched topology cache for physically adjacent coplanar screen tiles. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenGroupManager {
    private static final int MAX_NETWORK_NODES = 4096;
    private static final Map<UUID, ScreenGroup> GROUPS = new HashMap<>();
    private static final Map<BlockPos, ScreenGroup> BY_TILE = new HashMap<>();
    private static ClientLevel level;
    private static long signature = Long.MIN_VALUE;

    private ScreenGroupManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensure(Minecraft.getInstance().level);
    }

    public static void ensure(ClientLevel currentLevel) {
        if (currentLevel == null) {
            GROUPS.clear();
            BY_TILE.clear();
            level = null;
            signature = Long.MIN_VALUE;
            return;
        }
        List<ScreenBlockEntity> screens = ScreenTileIndex.snapshot(currentLevel);
        long nextSignature = signature(screens, currentLevel);
        if (level == currentLevel && nextSignature == signature) {
            return;
        }
        level = currentLevel;
        signature = nextSignature;
        rebuild(currentLevel, screens);
    }

    public static ScreenGroup groupAt(ScreenBlockEntity screen) {
        return BY_TILE.get(screen.getBlockPos());
    }

    public static ScreenGroup group(UUID id) {
        return GROUPS.get(id);
    }

    public static List<ScreenGroup> groups() {
        return List.copyOf(GROUPS.values());
    }

    private static void rebuild(ClientLevel currentLevel, List<ScreenBlockEntity> screens) {
        Map<BlockPos, ScreenBlockEntity> byPos = new HashMap<>();
        for (ScreenBlockEntity screen : screens) {
            byPos.put(screen.getBlockPos(), screen);
        }
        Set<BlockPos> unvisited = new HashSet<>(byPos.keySet());
        Map<UUID, ScreenGroup> nextGroups = new HashMap<>();
        Map<BlockPos, ScreenGroup> nextByTile = new HashMap<>();

        while (!unvisited.isEmpty()) {
            BlockPos start = unvisited.iterator().next();
            ScreenBlockEntity startScreen = byPos.get(start);
            if (startScreen.isLegacyAnchor()) {
                unvisited.remove(start);
                ScreenGroup legacy = legacyGroup(currentLevel.dimension(), startScreen);
                nextGroups.put(legacy.groupId(), legacy);
                nextByTile.put(start, legacy);
                continue;
            }
            List<ScreenBlockEntity> component = connectedComponent(startScreen, byPos, unvisited);
            ScreenGroup group = makeGroup(currentLevel.dimension(), component);
            nextGroups.put(group.groupId(), group);
            group.tiles().forEach(tile -> nextByTile.put(tile, group));
        }
        GROUPS.clear();
        GROUPS.putAll(nextGroups);
        BY_TILE.clear();
        BY_TILE.putAll(nextByTile);
        ScreenContentManager.onGroupsChanged(List.copyOf(nextGroups.values()));
    }

    private static List<ScreenBlockEntity> connectedComponent(ScreenBlockEntity start,
            Map<BlockPos, ScreenBlockEntity> byPos,
            Set<BlockPos> unvisited) {
        Direction facing = start.facing();
        int plane = ScreenGeometry.planeCoordinate(start.getBlockPos(), facing);
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        Direction[] screenSteps = {right, right.getOpposite(), up, up.getOpposite()};
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visitedNodes = new HashSet<>();
        Set<BlockPos> addedScreens = new HashSet<>();
        List<ScreenBlockEntity> component = new ArrayList<>();
        queue.add(start.getBlockPos());
        visitedNodes.add(start.getBlockPos());
        unvisited.remove(start.getBlockPos());

        while (!queue.isEmpty() && visitedNodes.size() <= MAX_NETWORK_NODES) {
            BlockPos current = queue.removeFirst();
            ScreenBlockEntity screen = byPos.get(current);
            if (screen != null) {
                if (screen.isLegacyAnchor() || screen.facing() != facing
                        || ScreenGeometry.planeCoordinate(current, facing) != plane
                        || !addedScreens.add(current)) {
                    continue;
                }
                component.add(screen);
                for (Direction step : screenSteps) {
                    enqueueScreen(current.relative(step), facing, plane, byPos, unvisited,
                            visitedNodes, queue);
                }
            }
        }
        return component;
    }

    private static void enqueueScreen(BlockPos pos, Direction facing, int plane,
            Map<BlockPos, ScreenBlockEntity> byPos, Set<BlockPos> unvisited,
            Set<BlockPos> visitedNodes, ArrayDeque<BlockPos> queue) {
        ScreenBlockEntity candidate = byPos.get(pos);
        if (candidate != null && !candidate.isLegacyAnchor() && candidate.facing() == facing
                && ScreenGeometry.planeCoordinate(pos, facing) == plane
                && visitedNodes.add(pos.immutable())) {
            unvisited.remove(pos);
            queue.addLast(pos.immutable());
        }
    }

    private static ScreenGroup makeGroup(ResourceKey<Level> dimension,
            List<ScreenBlockEntity> component) {
        ScreenBlockEntity first = component.getFirst();
        Direction facing = first.facing();
        Direction right = ScreenGeometry.rightDirection(facing);
        Direction up = ScreenGeometry.upDirection(facing);
        int minRight = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        int minUp = Integer.MAX_VALUE;
        int maxUp = Integer.MIN_VALUE;
        UUID groupId = null;
        ScreenBlockEntity master = first;
        for (ScreenBlockEntity screen : component) {
            BlockPos pos = screen.getBlockPos();
            int rightCoordinate = ScreenGeometry.coordinate(pos, right);
            int upCoordinate = ScreenGeometry.coordinate(pos, up);
            minRight = Math.min(minRight, rightCoordinate);
            maxRight = Math.max(maxRight, rightCoordinate);
            minUp = Math.min(minUp, upCoordinate);
            maxUp = Math.max(maxUp, upCoordinate);
            if (groupId == null || screen.tileId().compareTo(groupId) < 0) {
                groupId = screen.tileId();
            }
            if (compareMaster(screen, master, right, up) < 0) {
                master = screen;
            }
        }
        BlockPos sample = first.getBlockPos();
        BlockPos origin = sample.relative(right,
                minRight - ScreenGeometry.coordinate(sample, right)).relative(up,
                        minUp - ScreenGeometry.coordinate(sample, up));
        Set<BlockPos> tiles = new HashSet<>();
        component.forEach(screen -> tiles.add(screen.getBlockPos().immutable()));
        int columns = maxRight - minRight + 1;
        int rows = maxUp - minUp + 1;
        return new ScreenGroup(groupId, dimension, facing, master.getBlockPos(), origin,
                columns, rows, tiles, bounds(origin, facing, columns, rows), false);
    }

    private static ScreenGroup legacyGroup(ResourceKey<Level> dimension, ScreenBlockEntity screen) {
        return new ScreenGroup(screen.tileId(), dimension, screen.facing(), screen.getBlockPos(),
                screen.getBlockPos(), screen.getScreenWidth(), screen.getScreenHeight(),
                Set.of(screen.getBlockPos()), bounds(screen.getBlockPos(), screen.facing(),
                        screen.getScreenWidth(), screen.getScreenHeight()), true);
    }

    private static AABB bounds(BlockPos origin, Direction facing, int columns, int rows) {
        Vec3 first = ScreenGeometry.origin(origin, facing);
        Vec3 second = first.add(ScreenGeometry.right(facing).scale(columns))
                .add(ScreenGeometry.up(facing).scale(rows));
        return new AABB(first, second).inflate(0.05D);
    }

    private static int compareMaster(ScreenBlockEntity first, ScreenBlockEntity second,
            Direction right, Direction up) {
        int vertical = Integer.compare(ScreenGeometry.coordinate(first.getBlockPos(), up),
                ScreenGeometry.coordinate(second.getBlockPos(), up));
        if (vertical != 0) {
            return vertical;
        }
        int horizontal = Integer.compare(ScreenGeometry.coordinate(first.getBlockPos(), right),
                ScreenGeometry.coordinate(second.getBlockPos(), right));
        return horizontal != 0 ? horizontal : first.tileId().compareTo(second.tileId());
    }

    private static long signature(List<ScreenBlockEntity> screens, ClientLevel level) {
        long hash = screens.stream().sorted(Comparator.comparing(screen -> screen.getBlockPos().asLong()))
                .mapToLong(screen -> {
                    BlockPos pos = screen.getBlockPos();
                    long value = 17L;
                    value = value * 31L + level.dimension().location().hashCode();
                    value = value * 31L + pos.asLong();
                    value = value * 31L + screen.tileId().getMostSignificantBits();
                    value = value * 31L + screen.tileId().getLeastSignificantBits();
                    value = value * 31L + screen.facing().ordinal();
                    value = value * 31L + (screen.isLegacyAnchor() ? 1 : 0);
                    value = value * 31L + screen.getScreenWidth();
                    value = value * 31L + screen.getScreenHeight();
                    return value;
                }).reduce(1L, (first, second) -> first * 31L + second);

        // Extension cables connect already-resolved physical surfaces at the host-network layer.
        // They must not affect a plane's identity or force Chromium/video sessions to restart.
        return hash;
    }
}
