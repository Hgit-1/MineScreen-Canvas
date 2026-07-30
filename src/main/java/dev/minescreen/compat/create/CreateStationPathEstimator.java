package dev.minescreen.compat.create;

import java.util.Comparator;
import java.util.HashMap;
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

    private static double calculate(TrackGraph graph, GlobalStation from, GlobalStation to) {
        LocatedStation source = locate(graph, from);
        LocatedStation target = locate(graph, to);
        if (source == null || target == null) return UNREACHABLE;

        double best = directDistance(from, to, source, target);
        Map<TrackNode, Double> distances = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::distance));
        seed(from, source, source.first(), distances, queue);
        seed(from, source, source.second(), distances, queue);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < 100_000) {
            NodeDistance current = queue.poll();
            if (current.distance() != distances.getOrDefault(current.node(), UNREACHABLE)) continue;
            if (current.distance() >= best) continue;
            if (to.canApproachFrom(current.node())
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
            Map<TrackNode, Double> distances, PriorityQueue<NodeDistance> queue) {
        if (node == null || !station.canNavigateVia(node)) return;
        double distance = distanceFromNode(location, node);
        if (!Double.isFinite(distance)
                || distance >= distances.getOrDefault(node, UNREACHABLE)) return;
        distances.put(node, distance);
        queue.add(new NodeDistance(node, distance));
    }

    private static double directDistance(GlobalStation from, GlobalStation to,
            LocatedStation source, LocatedStation target) {
        if (source.edge() != target.edge()) return UNREACHABLE;
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
