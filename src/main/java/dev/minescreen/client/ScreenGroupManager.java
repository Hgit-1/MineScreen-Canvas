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

/** Rebuilds connected tile groups only when the loaded-tile signature changes. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenGroupManager {
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
        long nextSignature = signature(screens, currentLevel.dimension());
        if (level == currentLevel && nextSignature == signature) {
            return;
        }
        level = currentLevel;
        signature = nextSignature;
        rebuild(currentLevel, screens);
    }

    public static ScreenGroup groupAt(ScreenBlockEntity screen) {
        // Deliberately lookup-only: connected-component BFS is batched on the client tick and is
        // never triggered by a BER render call.
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
            for (BlockPos tile : group.tiles()) {
                nextByTile.put(tile, group);
            }
        }
        GROUPS.clear();
        GROUPS.putAll(nextGroups);
        BY_TILE.clear();
        BY_TILE.putAll(nextByTile);
        ScreenContentManager.onGroupsChanged(List.copyOf(nextGroups.values()));
    }

    private static List<ScreenBlockEntity> connectedComponent(ScreenBlockEntity start,
            Map<BlockPos, ScreenBlockEntity> byPos, Set<BlockPos> unvisited) {
        List<ScreenBlockEntity> component = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.getBlockPos());
        unvisited.remove(start.getBlockPos());
        Direction right = ScreenGeometry.rightDirection(start.facing());
        Direction[] steps = {right, right.getOpposite(), Direction.UP, Direction.DOWN};
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            ScreenBlockEntity screen = byPos.get(pos);
            component.add(screen);
            for (Direction step : steps) {
                BlockPos nextPos = pos.relative(step);
                ScreenBlockEntity next = byPos.get(nextPos);
                if (next != null && !next.isLegacyAnchor() && next.facing() == start.facing()
                        && unvisited.remove(nextPos)) {
                    queue.addLast(nextPos);
                }
            }
        }
        return component;
    }

    private static ScreenGroup makeGroup(ResourceKey<Level> dimension,
            List<ScreenBlockEntity> component) {
        ScreenBlockEntity first = component.get(0);
        Direction facing = first.facing();
        Direction right = ScreenGeometry.rightDirection(facing);
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        UUID groupId = null;
        ScreenBlockEntity master = first;
        for (ScreenBlockEntity screen : component) {
            BlockPos pos = screen.getBlockPos();
            int h = horizontalCoordinate(pos, right);
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            if (groupId == null || screen.tileId().compareTo(groupId) < 0) {
                groupId = screen.tileId();
            }
            if (compareMaster(screen, master, right) < 0) {
                master = screen;
            }
        }
        BlockPos sample = first.getBlockPos();
        int sampleH = horizontalCoordinate(sample, right);
        BlockPos origin = sample.offset(right.getStepX() * (minH - sampleH), minY - sample.getY(),
                right.getStepZ() * (minH - sampleH));
        Set<BlockPos> tiles = new HashSet<>();
        for (ScreenBlockEntity screen : component) {
            tiles.add(screen.getBlockPos().immutable());
        }
        return new ScreenGroup(groupId, dimension, facing, master.getBlockPos(), origin,
                maxH - minH + 1, maxY - minY + 1, tiles, bounds(origin, facing, maxH - minH + 1,
                        maxY - minY + 1), false);
    }

    private static ScreenGroup legacyGroup(ResourceKey<Level> dimension, ScreenBlockEntity screen) {
        return new ScreenGroup(screen.tileId(), dimension, screen.facing(), screen.getBlockPos(),
                screen.getBlockPos(), screen.getScreenWidth(), screen.getScreenHeight(),
                Set.of(screen.getBlockPos()), bounds(screen.getBlockPos(), screen.facing(),
                        screen.getScreenWidth(), screen.getScreenHeight()), true);
    }

    private static AABB bounds(BlockPos origin, Direction facing, int columns, int rows) {
        Vec3 p0 = ScreenGeometry.origin(origin, facing);
        Vec3 p1 = p0.add(ScreenGeometry.right(facing).scale(columns)).add(ScreenGeometry.up().scale(rows));
        return new AABB(p0, p1).inflate(0.05D);
    }

    private static int compareMaster(ScreenBlockEntity a, ScreenBlockEntity b, Direction right) {
        BlockPos pa = a.getBlockPos();
        BlockPos pb = b.getBlockPos();
        int y = Integer.compare(pa.getY(), pb.getY());
        if (y != 0) {
            return y;
        }
        int h = Integer.compare(horizontalCoordinate(pa, right), horizontalCoordinate(pb, right));
        return h != 0 ? h : a.tileId().compareTo(b.tileId());
    }

    private static int horizontalCoordinate(BlockPos pos, Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    private static long signature(List<ScreenBlockEntity> screens, ResourceKey<Level> dimension) {
        return screens.stream().sorted(Comparator.comparing(screen -> screen.getBlockPos().asLong()))
                .mapToLong(screen -> {
                    BlockPos pos = screen.getBlockPos();
                    long hash = 17L;
                    hash = hash * 31L + dimension.location().hashCode();
                    hash = hash * 31L + pos.asLong();
                    hash = hash * 31L + screen.tileId().getMostSignificantBits();
                    hash = hash * 31L + screen.tileId().getLeastSignificantBits();
                    hash = hash * 31L + screen.facing().ordinal();
                    hash = hash * 31L + (screen.isLegacyAnchor() ? 1 : 0);
                    hash = hash * 31L + screen.getScreenWidth();
                    hash = hash * 31L + screen.getScreenHeight();
                    return hash;
                }).reduce(1L, (a, b) -> a * 31L + b);
    }
}
