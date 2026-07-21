package dev.minescreen.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.HostSurfaceLayout;
import dev.minescreen.client.content.HostSurfacePlacement;
import dev.minescreen.client.content.ScreenRotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Resolves cable-connected computer display networks without merging their physical planes. */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenHostNetworkManager {
    private static final long TOPOLOGY_REFRESH_TICKS = 5L;
    private static final Map<UUID, HostNetwork> BY_GROUP = new HashMap<>();
    private static final Map<UUID, HostNetwork> BY_ID = new HashMap<>();
    private static final Map<BlockPos, HostNetwork> BY_COMPUTER = new HashMap<>();
    private static ClientLevel level;
    private static long gameTime = Long.MIN_VALUE;

    private ScreenHostNetworkManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensure(Minecraft.getInstance().level);
    }

    public static void invalidate() {
        gameTime = Long.MIN_VALUE;
    }

    public static void ensure(ClientLevel currentLevel) {
        if (currentLevel == null) {
            BY_GROUP.clear();
            BY_ID.clear();
            BY_COMPUTER.clear();
            level = null;
            gameTime = Long.MIN_VALUE;
            return;
        }
        ScreenGroupManager.ensure(currentLevel);
        long now = currentLevel.getGameTime();
        if (level == currentLevel && gameTime != Long.MIN_VALUE && now >= gameTime
                && now - gameTime < TOPOLOGY_REFRESH_TICKS) {
            return;
        }
        level = currentLevel;
        gameTime = now;
        rebuild(currentLevel);
    }

    @Nullable
    public static HostNetwork networkFor(ScreenGroup group) {
        ensure(Minecraft.getInstance().level);
        return group == null ? null : BY_GROUP.get(group.groupId());
    }

    @Nullable
    public static HostNetwork network(UUID networkId) {
        ensure(Minecraft.getInstance().level);
        return BY_ID.get(networkId);
    }

    @Nullable
    public static HostNetwork networkAt(ClientLevel level, BlockPos computerPos) {
        ensure(level);
        return BY_COMPUTER.get(computerPos);
    }

    @Nullable
    public static Surface surface(ScreenGroup group) {
        HostNetwork network = networkFor(group);
        return network == null ? null : network.surface(group.groupId());
    }

    public static int rotationFor(ScreenGroup group) {
        Surface surface = surface(group);
        return surface == null ? 0 : surface.rotation();
    }

    private static void rebuild(ClientLevel currentLevel) {
        Map<UUID, HostNetwork> nextByGroup = new HashMap<>();
        Map<UUID, HostNetwork> nextById = new HashMap<>();
        Map<BlockPos, HostNetwork> nextByComputer = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        for (ScreenGroup group : ScreenGroupManager.groups()) {
            if (!visited.add(group.groupId())) {
                continue;
            }
            ScreenLinkResolver.LinkedNetwork linked = ScreenLinkResolver.findNetwork(currentLevel, group);
            linked.groups().forEach(member -> visited.add(member.groupId()));
            if (!linked.hasComputer() || linked.groups().isEmpty()) {
                continue;
            }
            HostNetwork network = makeNetwork(linked.groups(), linked.computers());
            nextById.put(network.networkId(), network);
            network.groups().forEach(member -> nextByGroup.put(member.groupId(), network));
            network.computers().forEach(computer -> nextByComputer.put(computer, network));
        }
        BY_GROUP.clear();
        BY_GROUP.putAll(nextByGroup);
        BY_ID.clear();
        BY_ID.putAll(nextById);
        BY_COMPUTER.clear();
        BY_COMPUTER.putAll(nextByComputer);
    }

    private static HostNetwork makeNetwork(List<ScreenGroup> groups, Set<BlockPos> computers) {
        UUID networkId = networkId(groups, computers);
        HostNetwork previous = BY_ID.get(networkId);
        ScreenGroup root = previous == null ? configuredRoot(groups)
                : groups.stream().filter(group -> group.groupId().equals(previous.rootGroupId()))
                        .findFirst().orElseGet(() -> configuredRoot(groups));
        ClientScreenProfile rootProfile = ScreenContentManager.profile(root.groupId());
        List<ScreenGroup> ordered = new ArrayList<>(groups);
        Map<String, Integer> configuredOrder = new HashMap<>();
        if (rootProfile.hostSurfaceOrder != null) {
            for (int index = 0; index < rootProfile.hostSurfaceOrder.size(); index++) {
                configuredOrder.putIfAbsent(rootProfile.hostSurfaceOrder.get(index), index);
            }
        }
        Map<UUID, Integer> fallbackOrder = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            fallbackOrder.put(groups.get(index).groupId(), index);
        }
        ordered.sort(java.util.Comparator
                .comparingInt((ScreenGroup group) -> configuredOrder.getOrDefault(
                        group.groupId().toString(), Integer.MAX_VALUE))
                .thenComparingInt(group -> fallbackOrder.getOrDefault(group.groupId(), 0)));
        HostSurfaceLayout layout = rootProfile.hostSurfaceLayout == null
                ? HostSurfaceLayout.FREE : rootProfile.hostSurfaceLayout;
        Map<UUID, Surface> surfaces = new HashMap<>();
        int columns;
        int rows;
        if (layout == HostSurfaceLayout.CUSTOM) {
            Map<UUID, HostSurfacePlacement> placements = customPlacements(ordered,
                    rootProfile.hostSurfacePositions, rootProfile.hostSurfaceRotations);
            int minX = placements.values().stream().mapToInt(HostSurfacePlacement::x).min().orElse(0);
            int minY = placements.values().stream().mapToInt(HostSurfacePlacement::y).min().orElse(0);
            int maxX = ordered.stream().mapToInt(group -> placements.get(group.groupId()).x()
                    + logicalColumns(group, rotation(rootProfile, group))).max().orElse(1);
            int maxY = ordered.stream().mapToInt(group -> placements.get(group.groupId()).y()
                    + logicalRows(group, rotation(rootProfile, group))).max().orElse(1);
            columns = Math.max(1, maxX - minX);
            rows = Math.max(1, maxY - minY);
            for (ScreenGroup group : ordered) {
                HostSurfacePlacement placement = placements.get(group.groupId());
                int column = placement.x() - minX;
                int row = placement.y() - minY;
                int turns = rotation(rootProfile, group);
                int surfaceColumns = logicalColumns(group, turns);
                int surfaceRows = logicalRows(group, turns);
                surfaces.put(group.groupId(), new Surface(group,
                        column / (float) columns, row / (float) rows,
                        (column + surfaceColumns) / (float) columns,
                        (row + surfaceRows) / (float) rows, column, row, turns,
                        surfaceColumns, surfaceRows));
            }
        } else if (layout == HostSurfaceLayout.PANORAMA_VERTICAL) {
            columns = ordered.stream().mapToInt(group -> logicalColumns(group,
                    rotation(rootProfile, group))).max().orElse(1);
            rows = ordered.stream().mapToInt(group -> logicalRows(group,
                    rotation(rootProfile, group))).sum();
            int offset = 0;
            for (ScreenGroup group : ordered) {
                int turns = rotation(rootProfile, group);
                int surfaceColumns = logicalColumns(group, turns);
                int surfaceRows = logicalRows(group, turns);
                float left = 0.0F;
                float right = surfaceColumns / (float) columns;
                float top = offset / (float) rows;
                float bottom = (offset + surfaceRows) / (float) rows;
                surfaces.put(group.groupId(), new Surface(group, left, top, right, bottom,
                        0, offset, turns, surfaceColumns, surfaceRows));
                offset += surfaceRows;
            }
        } else {
            columns = ordered.stream().mapToInt(group -> logicalColumns(group,
                    rotation(rootProfile, group))).sum();
            rows = ordered.stream().mapToInt(group -> logicalRows(group,
                    rotation(rootProfile, group))).max().orElse(1);
            int offset = 0;
            for (ScreenGroup group : ordered) {
                int turns = rotation(rootProfile, group);
                int surfaceColumns = logicalColumns(group, turns);
                int surfaceRows = logicalRows(group, turns);
                float left = offset / (float) columns;
                float right = (offset + surfaceColumns) / (float) columns;
                float top = 1.0F - surfaceRows / (float) rows;
                int row = rows - surfaceRows;
                surfaces.put(group.groupId(), new Surface(group, left, top, right, 1.0F,
                        offset, row, turns, surfaceColumns, surfaceRows));
                offset += surfaceColumns;
            }
        }
        AABB bounds = ordered.getFirst().bounds();
        Set<BlockPos> tiles = new HashSet<>();
        for (ScreenGroup group : ordered) {
            bounds = bounds.minmax(group.bounds());
            tiles.addAll(group.tiles());
        }
        UUID canvasId = UUID.nameUUIDFromBytes((networkId + ":" + layout.name())
                .getBytes(StandardCharsets.UTF_8));
        ScreenGroup canvas = new ScreenGroup(canvasId, root.dimension(), root.facing(), root.master(),
                root.origin(), Math.max(1, columns), Math.max(1, rows), tiles, bounds, false);
        return new HostNetwork(networkId, root.groupId(), ordered, Set.copyOf(computers), layout,
                canvas, Map.copyOf(surfaces), signature(ordered, computers, layout, surfaces));
    }

    private static Map<UUID, HostSurfacePlacement> customPlacements(List<ScreenGroup> groups,
            Map<String, HostSurfacePlacement> configured, Map<String, Integer> rotations) {
        Map<UUID, HostSurfacePlacement> result = new HashMap<>();
        int fallbackX = 0;
        if (configured != null) {
            for (ScreenGroup group : groups) {
                HostSurfacePlacement placement = configured.get(group.groupId().toString());
                if (placement != null) {
                    result.put(group.groupId(), placement);
                    fallbackX = Math.max(fallbackX, placement.x()
                            + logicalColumns(group, rotation(rotations, group)));
                }
            }
        }
        for (ScreenGroup group : groups) {
            if (!result.containsKey(group.groupId())) {
                result.put(group.groupId(), new HostSurfacePlacement(fallbackX, 0));
                fallbackX += logicalColumns(group, rotation(rotations, group));
            }
        }
        return result;
    }

    private static int rotation(ClientScreenProfile profile, ScreenGroup group) {
        return rotation(profile.hostSurfaceRotations, group);
    }

    private static int rotation(Map<String, Integer> rotations, ScreenGroup group) {
        return ScreenRotation.normalize(rotations == null ? 0
                : rotations.getOrDefault(group.groupId().toString(), 0));
    }

    private static int logicalColumns(ScreenGroup group, int turns) {
        return ScreenRotation.logicalWidth(group.columns(), group.rows(), turns);
    }

    private static int logicalRows(ScreenGroup group, int turns) {
        return ScreenRotation.logicalHeight(group.columns(), group.rows(), turns);
    }

    private static ScreenGroup configuredRoot(List<ScreenGroup> groups) {
        return groups.stream().max(java.util.Comparator
                .comparingInt((ScreenGroup group) -> profileScore(
                        ScreenContentManager.profile(group.groupId())))
                .thenComparing(ScreenGroup::groupId, java.util.Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static int profileScore(ClientScreenProfile profile) {
        int score = 0;
        if (profile.hostSurfaceLayout != null && profile.hostSurfaceLayout != HostSurfaceLayout.FREE) {
            score += 10_000;
        }
        score += profile.hostSurfaceOrder == null ? 0 : Math.min(1000, profile.hostSurfaceOrder.size() * 50);
        score += profile.hostSurfacePositions == null ? 0
                : Math.min(1000, profile.hostSurfacePositions.size() * 50);
        score += profile.hostSurfaceRotations == null ? 0
                : Math.min(1000, profile.hostSurfaceRotations.size() * 50);
        if (profile.contentType != null
                && profile.contentType != dev.minescreen.client.content.ScreenContentType.IDLE
                && profile.source != null && !profile.source.isBlank()) {
            score += 2_000;
        }
        score += profile.regions == null ? 0 : Math.min(500, profile.regions.size() * 50);
        score += profile.tileRegions == null ? 0 : Math.min(500, profile.tileRegions.size() * 5);
        return score;
    }

    private static UUID networkId(List<ScreenGroup> groups, Set<BlockPos> computers) {
        String value = computers.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
                .map(pos -> Long.toUnsignedString(pos.asLong()))
                .collect(java.util.stream.Collectors.joining(":"));
        if (value.isEmpty()) {
            value = groups.stream().map(group -> group.groupId().toString()).sorted()
                    .collect(java.util.stream.Collectors.joining(":"));
        } else {
            value = groups.getFirst().dimension().location() + ":" + value;
        }
        return UUID.nameUUIDFromBytes(("minescreen-host:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static long signature(List<ScreenGroup> groups, Set<BlockPos> computers,
            HostSurfaceLayout layout, Map<UUID, Surface> surfaces) {
        long value = layout.ordinal() + 1L;
        for (ScreenGroup group : groups) {
            value = value * 31L + group.groupId().hashCode();
            value = value * 31L + group.facing().ordinal();
            value = value * 31L + group.origin().asLong();
            value = value * 31L + group.columns();
            value = value * 31L + group.rows();
            Surface surface = surfaces.get(group.groupId());
            if (surface != null) {
                value = value * 31L + surface.column();
                value = value * 31L + surface.row();
                value = value * 31L + surface.rotation();
            }
        }
        for (BlockPos computer : computers.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
                .toList()) {
            value = value * 31L + computer.asLong();
        }
        return value;
    }

    public record HostNetwork(UUID networkId, UUID rootGroupId, List<ScreenGroup> groups,
            Set<BlockPos> computers, HostSurfaceLayout layout, ScreenGroup canvas,
            Map<UUID, Surface> surfaces, long signature) {
        public HostNetwork {
            groups = List.copyOf(groups);
            computers = Set.copyOf(computers);
            surfaces = Map.copyOf(surfaces);
        }

        public ScreenGroup rootGroup() {
            return groups.stream().filter(group -> group.groupId().equals(rootGroupId))
                    .findFirst().orElse(groups.getFirst());
        }

        public boolean panoramic() {
            return layout != HostSurfaceLayout.FREE && groups.size() > 1;
        }

        @Nullable
        public Surface surface(UUID groupId) {
            return surfaces.get(groupId);
        }
    }

    public record Surface(ScreenGroup group, float left, float top, float right, float bottom,
            int column, int row, int rotation, int logicalColumns, int logicalRows) {
        public Surface {
            rotation = ScreenRotation.normalize(rotation);
        }
        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }

        public float canvasU(float physicalU, float physicalV) {
            return left + ScreenRotation.contentU(physicalU, physicalV, rotation) * width();
        }

        public float canvasV(float physicalU, float physicalV) {
            return top + ScreenRotation.contentV(physicalU, physicalV, rotation) * height();
        }

        public float physicalU(float canvasU, float canvasV) {
            float localU = (canvasU - left) / Math.max(0.000001F, width());
            float localV = (canvasV - top) / Math.max(0.000001F, height());
            return ScreenRotation.physicalU(localU, localV, rotation);
        }

        public float physicalV(float canvasU, float canvasV) {
            float localU = (canvasU - left) / Math.max(0.000001F, width());
            float localV = (canvasV - top) / Math.max(0.000001F, height());
            return ScreenRotation.physicalV(localU, localV, rotation);
        }
    }
}
