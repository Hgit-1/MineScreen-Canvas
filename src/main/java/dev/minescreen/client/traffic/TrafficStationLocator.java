package dev.minescreen.client.traffic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import dev.minescreen.client.traffic.TrafficTemplateRepository.CoordinateBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Cached, optional Create station lookup for LCD Studio coordinate bindings.
 *
 * <p>The integration intentionally uses a class-name boundary so a dedicated server or client
 * without Create never resolves Create classes. A lookup scans at most a 33x33x33 cube and is
 * cached for five seconds; it must never run from the render loop without this cache.</p>
 */
public final class TrafficStationLocator {
    private static final String CREATE_STATION =
            "com.simibubi.create.content.trains.station.StationBlockEntity";
    private static final long CACHE_TICKS = 100L;
    private static final Map<Level, Map<CoordinateBinding, CachedMatch>> CACHE =
            new WeakHashMap<>();
    private static final Map<Level, Map<NearbyKey, CachedMatch>> NEARBY_CACHE =
            new WeakHashMap<>();

    private TrafficStationLocator() {
    }

    /** Lets the editor distinguish a missing Create installation from an empty scan result. */
    public static boolean available() {
        return ModList.get().isLoaded("create");
    }

    public static StationMatch resolve(Level level, CoordinateBinding binding) {
        if (level == null || binding == null || !binding.autoCreatePlatform()
                || !ModList.get().isLoaded("create")) {
            return null;
        }
        if (!binding.dimension().isBlank()
                && !level.dimension().location().toString().equals(binding.dimension())) return null;
        long now = level.getGameTime();
        Map<CoordinateBinding, CachedMatch> byBinding = CACHE.computeIfAbsent(level,
                ignored -> new HashMap<>());
        CachedMatch cached = byBinding.get(binding);
        if (cached != null && now - cached.checkedAt() < CACHE_TICKS) return cached.match();
        StationMatch match = scan(level, binding);
        byBinding.put(binding, new CachedMatch(now, match));
        return match;
    }

    /** Finds the nearest Create station for an unbound platform board; the scan is cached. */
    public static StationMatch resolveNearby(Level level, BlockPos displayPos, String preferredName,
            int radius) {
        if (level == null || displayPos == null || !ModList.get().isLoaded("create")) return null;
        int safeRadius = Math.max(2, Math.min(32, radius));
        NearbyKey key = new NearbyKey(displayPos.immutable(), normalize(preferredName), safeRadius);
        long now = level.getGameTime();
        Map<NearbyKey, CachedMatch> cache = NEARBY_CACHE.computeIfAbsent(level,
                ignored -> new HashMap<>());
        CachedMatch cached = cache.get(key);
        if (cached != null && now - cached.checkedAt() < CACHE_TICKS) return cached.match();
        StationMatch match = scanNearby(level, displayPos, key.preferredName(), safeRadius);
        cache.put(key, new CachedMatch(now, match));
        return match;
    }

    /** Resolves only the requested Create name, without silently accepting another nearby stop. */
    public static StationMatch resolveNamedNearby(Level level, BlockPos displayPos, String name,
            int radius) {
        String wanted = normalize(name);
        if (wanted.isBlank()) return null;
        StationMatch match = resolveNearby(level, displayPos, name, radius);
        return match != null && wanted.equals(normalize(match.createStationName())) ? match : null;
    }

    /** Bounded editor-only suggestions; never called from the renderer. */
    public static List<StationMatch> nearbyStations(Level level, BlockPos displayPos, int radius) {
        if (level == null || displayPos == null || !ModList.get().isLoaded("create")) {
            return List.of();
        }
        int safeRadius = Math.max(2, Math.min(32, radius));
        Vec3 point = Vec3.atCenterOf(displayPos);
        Map<String, StationMatch> unique = new HashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(
                displayPos.offset(-safeRadius, -safeRadius, -safeRadius),
                displayPos.offset(safeRadius, safeRadius, safeRadius))) {
            if (!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getZ()))) continue;
            BlockEntity candidate = level.getBlockEntity(cursor);
            if (!isCreateStation(candidate)) continue;
            String createName = stationName(candidate);
            double distance = point.distanceTo(Vec3.atCenterOf(cursor));
            String key = normalize(createName);
            StationMatch current = unique.get(key);
            if (current == null || distance < current.distance()) {
                unique.put(key, new StationMatch(cursor.immutable(), createName, distance,
                        createName));
            }
        }
        List<StationMatch> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingDouble(StationMatch::distance)
                .thenComparing(StationMatch::createStationName));
        return List.copyOf(result);
    }

    private static StationMatch scan(Level level, CoordinateBinding binding) {
        Vec3 point = new Vec3(binding.x(), binding.y(), binding.z());
        int radius = Math.max(1, Math.min(16, (int) Math.ceil(binding.radius())));
        BlockPos center = BlockPos.containing(point);
        StationMatch nearest = null;
        for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getZ()))) continue;
            BlockEntity candidate = level.getBlockEntity(cursor);
            if (!isCreateStation(candidate)) continue;
            String createName = stationName(candidate);
            if (!matchesConfiguredName(createName, binding)) continue;
            double distance = point.distanceTo(Vec3.atCenterOf(cursor));
            if (distance <= binding.radius() && (nearest == null || distance < nearest.distance())) {
                nearest = new StationMatch(cursor.immutable(), createName, distance,
                        binding.logicalStationId());
            }
        }
        return nearest;
    }

    private static StationMatch scanNearby(Level level, BlockPos displayPos, String preferredName,
            int radius) {
        Vec3 point = Vec3.atCenterOf(displayPos);
        StationMatch nearest = null;
        StationMatch nearestPreferred = null;
        for (BlockPos cursor : BlockPos.betweenClosed(displayPos.offset(-radius, -radius, -radius),
                displayPos.offset(radius, radius, radius))) {
            if (!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(cursor.getZ()))) continue;
            BlockEntity candidate = level.getBlockEntity(cursor);
            if (!isCreateStation(candidate)) continue;
            String createName = stationName(candidate);
            double distance = point.distanceTo(Vec3.atCenterOf(cursor));
            StationMatch match = new StationMatch(cursor.immutable(), createName, distance,
                    createName);
            if (nearest == null || distance < nearest.distance()) nearest = match;
            if (!preferredName.isBlank() && preferredName.equals(normalize(createName))
                    && (nearestPreferred == null || distance < nearestPreferred.distance())) {
                nearestPreferred = match;
            }
        }
        return nearestPreferred == null ? nearest : nearestPreferred;
    }

    private static boolean isCreateStation(BlockEntity candidate) {
        if (candidate == null) return false;
        for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
            if (CREATE_STATION.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean matchesConfiguredName(String actual, CoordinateBinding binding) {
        String expected = normalize(binding.createStationName());
        String found = normalize(actual);
        if (!expected.isBlank()) return expected.equals(found);
        if (binding.aliases().isEmpty() || found.isBlank()) return true;
        return binding.aliases().stream().map(TrafficStationLocator::normalize)
                .anyMatch(found::equals);
    }

    private static String stationName(Object stationBlockEntity) {
        Object station = invoke(stationBlockEntity, "getStation", "getGlobalStation");
        if (station == null) station = stationBlockEntity;
        Object name = invoke(station, "getName", "name");
        if (name == null) name = field(station, "name");
        return name == null ? "" : String.valueOf(name);
    }

    private static Object invoke(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("station", "").replace("駅", "").replaceAll("[\\s._-]+", "").trim();
    }

    public record StationMatch(BlockPos position, String createStationName, double distance,
            String logicalStationId) {
    }

    private record CachedMatch(long checkedAt, StationMatch match) {
    }

    private record NearbyKey(BlockPos position, String preferredName, int radius) {
    }
}
