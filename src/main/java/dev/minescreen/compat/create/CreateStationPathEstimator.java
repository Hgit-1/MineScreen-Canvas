package dev.minescreen.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.station.GlobalStation;

/**
 * Calculates directed station-to-station distance on Create's real track graph.
 *
 * <p>This is used when a schedule has not travelled a segment yet, especially the closing edge of
 * a cyclic schedule. It avoids assigning every unknown closing segment the same arbitrary number
 * of ticks. Signals can still delay the train, but the base travel interval now follows the actual
 * connected rail length and the station's permitted approach side.</p>
 */
public final class CreateStationPathEstimator {
    private static final double UNREACHABLE = Double.POSITIVE_INFINITY;
    private static final int MAX_CACHE_ENTRIES = 4_096;
    private static final Map<PathKey, Double> CACHE = new ConcurrentHashMap<>();
    private static final Map<PathKey, List<String>> INTERMEDIATE_CACHE = new ConcurrentHashMap<>();

    private CreateStationPathEstimator() {
    }

    public static GlobalStation resolveNext(TrackGraph graph, String destinationFilter,
            GlobalStation previous) {
        if (graph == null || destinationFilter == null || destinationFilter.isBlank()) return null;
        GlobalStation best = null;
        double bestDistance = UNREACHABLE;
        for (GlobalStation candidate : graph.getPoints(EdgePointType.STATION)) {
            if (candidate == null || !matches(destinationFilter, candidate.name)) continue;
            if (previous == null) {
                if (best == null || exact(destinationFilter, candidate.name)) best = candidate;
                if (exact(destinationFilter, candidate.name)) break;
                continue;
            }
            double distance = distance(graph, previous, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    public static double distance(TrackGraph graph, GlobalStation from, GlobalStation to) {
        if (graph == null || from == null || to == null) return UNREACHABLE;
        if (from.id != null && from.id.equals(to.id)) return 0.0D;
        PathKey key = new PathKey(graph.id, graph.getChecksum(), from.id, to.id);
        Double cached = CACHE.get(key);
        if (cached != null) return cached;
        double calculated = calculate(graph, from, to);
        if (CACHE.size() >= MAX_CACHE_ENTRIES) CACHE.clear();
        CACHE.put(key, calculated);
        return calculated;
    }

    /**
     * Returns the ordered names of intermediate {@link GlobalStation}s physically bypassed along
     * the shortest directed rail path from {@code from} to {@code to}.
     */
    public static List<String> intermediateStations(TrackGraph graph, GlobalStation from,
            GlobalStation to) {
        if (graph == null || from == null || to == null
                || from.id == null || to.id == null
                || from.id.equals(to.id)) {
            return List.of();
        }
        PathKey key = new PathKey(graph.id, graph.getChecksum(), from.id, to.id);
        List<String> cached = INTERMEDIATE_CACHE.get(key);
        if (cached != null) return cached;
        double totalDist = distance(graph, from, to);
        if (!Double.isFinite(totalDist) || totalDist <= 1.0D) {
            INTERMEDIATE_CACHE.put(key, List.of());
            return List.of();
        }
        String fromBase = baseStationName(from.name);
        String toBase = baseStationName(to.name);
        double tolerance = Math.max(8.0D, totalDist * 0.06D);
        record Candidate(String name, String base, double distFromStart) {}
        List<Candidate> found = new ArrayList<>();
        for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
            if (station == null || station.id == null
                    || station.id.equals(from.id) || station.id.equals(to.id)) {
                continue;
            }
            String rawName = station.name == null ? "" : station.name.trim();
            if (rawName.isBlank()) continue;
            String base = baseStationName(rawName);
            if (base.isBlank() || base.equalsIgnoreCase(fromBase)
                    || base.equalsIgnoreCase(toBase)) {
                continue;
            }
            double d1 = distance(graph, from, station);
            if (!Double.isFinite(d1) || d1 <= 0.5D || d1 >= totalDist - 0.5D) continue;
            double d2 = distance(graph, station, to);
            if (!Double.isFinite(d2) || d2 <= 0.5D) continue;
            if (Math.abs((d1 + d2) - totalDist) <= tolerance) {
                found.add(new Candidate(rawName, base, d1));
            }
        }
        if (found.isEmpty()) {
            if (INTERMEDIATE_CACHE.size() >= MAX_CACHE_ENTRIES) INTERMEDIATE_CACHE.clear();
            INTERMEDIATE_CACHE.put(key, List.of());
            return List.of();
        }
        found.sort(Comparator.comparingDouble(Candidate::distFromStart));
        List<String> result = new ArrayList<>();
        List<String> seenBases = new ArrayList<>();
        for (Candidate c : found) {
            boolean duplicateBase = false;
            for (String existing : seenBases) {
                if (existing.equalsIgnoreCase(c.base())) {
                    duplicateBase = true;
                    break;
                }
            }
            if (!duplicateBase) {
                seenBases.add(c.base());
                result.add(c.name());
            }
        }
        List<String> immutable = List.copyOf(result);
        if (INTERMEDIATE_CACHE.size() >= MAX_CACHE_ENTRIES) INTERMEDIATE_CACHE.clear();
        INTERMEDIATE_CACHE.put(key, immutable);
        return immutable;
    }

    private static String baseStationName(String name) {
        if (name == null || name.isBlank()) return "";
        String stripped = name.replaceAll(
                "(?iu)\\s*(上行|下行|上り|下り|upbound|downbound)\\s*", "")
                .replace("[", "").replace("]", "")
                .replace("【", "").replace("】", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?iu)^(.+?)(?:[\\s_-]+(?:platform|plat|p|站台)\\s*([0-9]{1,2}[a-z]?)"
                        + "|[\\s]+([0-9]{1,2})\\s*站台"
                        + "|[_-]([0-9]{1,2}[a-z]?))$")
                .matcher(stripped);
        if (m.matches()) {
            String base = m.group(1).trim();
            if (!base.isBlank()) return base;
        }
        return stripped;
    }

    private static double calculate(TrackGraph graph, GlobalStation from, GlobalStation to) {
        LocatedStation source = locate(graph, from);
        LocatedStation target = locate(graph, to);
        if (source == null || target == null) return UNREACHABLE;

        double strict = search(graph, from, to, source, target, true);
        if (Double.isFinite(strict)) return strict;
        // Double-ended Create trains reverse out of terminal dead-end stations via the opposite
        // node of the station arrow. Fall back to an un-restricted exit/entry search so future
        // segments from a reversal terminus still have an accurate rail-graph distance.
        return search(graph, from, to, source, target, false);
    }

    private static double search(TrackGraph graph, GlobalStation from, GlobalStation to,
            LocatedStation source, LocatedStation target, boolean strictDirection) {
        double best = directDistance(from, to, source, target, strictDirection);
        Map<TrackNode, Double> distances = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::distance));
        seed(from, source, source.first(), distances, queue, strictDirection);
        seed(from, source, source.second(), distances, queue, strictDirection);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < 100_000) {
            NodeDistance current = queue.poll();
            if (current.distance() != distances.getOrDefault(current.node(), UNREACHABLE)) continue;
            if (current.distance() >= best) continue;
            if ((!strictDirection || to.canApproachFrom(current.node()))
                    && isEndpoint(target, current.node())) {
                best = Math.min(best, current.distance()
                        + distanceFromNode(target, current.node()));
            }
            for (Map.Entry<TrackNode, TrackEdge> connection :
                    graph.getConnectionsFrom(current.node()).entrySet()) {
                TrackEdge edge = connection.getValue();
                if (edge == null || !Double.isFinite(edge.getLength())) continue;
                double nextDistance = current.distance() + Math.max(0.0D, edge.getLength());
                if (nextDistance >= best
                        || nextDistance >= distances.getOrDefault(connection.getKey(),
                                UNREACHABLE)) continue;
                distances.put(connection.getKey(), nextDistance);
                queue.add(new NodeDistance(connection.getKey(), nextDistance));
            }
        }
        return best;
    }

    private static void seed(GlobalStation station, LocatedStation location, TrackNode node,
            Map<TrackNode, Double> distances, PriorityQueue<NodeDistance> queue,
            boolean strictDirection) {
        if (node == null || strictDirection && !station.canNavigateVia(node)) return;
        double distance = distanceFromNode(location, node);
        if (!Double.isFinite(distance)
                || distance >= distances.getOrDefault(node, UNREACHABLE)) return;
        distances.put(node, distance);
        queue.add(new NodeDistance(node, distance));
    }

    private static double directDistance(GlobalStation from, GlobalStation to,
            LocatedStation source, LocatedStation target, boolean strictDirection) {
        if (source.edge() != target.edge()) return UNREACHABLE;
        if (!strictDirection) {
            return Math.abs(source.positionFromNode1() - target.positionFromNode1());
        }
        TrackNode approach = to.canApproachFrom(target.first()) ? target.first()
                : to.canApproachFrom(target.second()) ? target.second() : null;
        if (approach == null || !from.canNavigateVia(approach)) return UNREACHABLE;
        double sourceOffset = distanceFromNode(source, approach);
        double targetOffset = distanceFromNode(target, approach);
        return sourceOffset <= targetOffset ? targetOffset - sourceOffset : UNREACHABLE;
    }

    private static LocatedStation locate(TrackGraph graph, GlobalStation station) {
        if (station.edgeLocation == null) return null;
        TrackNode first = graph.locateNode(station.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(station.edgeLocation.getSecond());
        if (first == null || second == null) return null;
        TrackEdge edge = graph.getConnectionsFrom(first).get(second);
        if (edge == null) return null;
        return new LocatedStation(first, second, edge, station.getLocationOn(edge));
    }

    private static boolean isEndpoint(LocatedStation station, TrackNode node) {
        return station.first() == node || station.second() == node;
    }

    private static double distanceFromNode(LocatedStation station, TrackNode node) {
        if (station.edge().node1 == node) return station.positionFromNode1();
        if (station.edge().node2 == node) {
            return Math.max(0.0D, station.edge().getLength() - station.positionFromNode1());
        }
        return UNREACHABLE;
    }

    private static boolean exact(String filter, String stationName) {
        return filter.trim().equalsIgnoreCase(stationName == null ? "" : stationName.trim());
    }

    private static boolean matches(String destinationFilter, String stationName) {
        String filter = destinationFilter.trim();
        String station = stationName == null ? "" : stationName.trim();
        if (filter.isBlank() || station.isBlank()) return false;
        if (filter.equalsIgnoreCase(station)) return true;
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < filter.length(); index++) {
            char character = filter.charAt(index);
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(
                    String.valueOf(Character.toLowerCase(character))));
        }
        regex.append('$');
        return station.toLowerCase(Locale.ROOT).matches(regex.toString());
    }

    private record LocatedStation(TrackNode first, TrackNode second, TrackEdge edge,
            double positionFromNode1) {
    }

    private record NodeDistance(TrackNode node, double distance) {
    }

    private record PathKey(java.util.UUID graphId, int checksum, java.util.UUID from,
            java.util.UUID to) {
    }
}
