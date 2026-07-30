package dev.minescreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Robust estimator for repeatedly observed railway segment runtimes.
 *
 * <p>The median absolute deviation removes one-off signal, chunk-loading and manual-control
 * outliers. A lightly trimmed mean of the remaining samples still follows permanent changes such
 * as a rebuilt curve or a schedule-imposed speed restriction without jumping between individual
 * runs.</p>
 */
public final class SegmentRuntimeStatistics {
    private static final long MIN_OUTLIER_BAND_TICKS = 20L;
    private static final double NORMALIZED_MAD_SCALE = 1.4826D;
    private static final double MAD_MULTIPLIER = 3.5D;

    private SegmentRuntimeStatistics() {
    }

    /**
     * @return a positive estimated tick count, or {@code -1} when too few valid samples exist.
     */
    public static long robustEstimate(List<Long> samples, int minimumSamples) {
        if (samples == null || samples.isEmpty()) return -1L;
        List<Long> sorted = new ArrayList<>(samples.size());
        for (Long sample : samples) {
            if (sample != null && sample > 0L && sample <= 24_000L) sorted.add(sample);
        }
        int required = Math.max(1, minimumSamples);
        if (sorted.size() < required) return -1L;
        Collections.sort(sorted);
        double median = median(sorted);

        List<Long> deviations = new ArrayList<>(sorted.size());
        for (long value : sorted) deviations.add(Math.round(Math.abs(value - median)));
        Collections.sort(deviations);
        double mad = median(deviations);
        double band = Math.max(MIN_OUTLIER_BAND_TICKS,
                mad * NORMALIZED_MAD_SCALE * MAD_MULTIPLIER);

        List<Long> inliers = new ArrayList<>(sorted.size());
        for (long value : sorted) {
            if (Math.abs(value - median) <= band) inliers.add(value);
        }
        // A highly split data set has no trustworthy outlier cluster. Its median is safer than
        // silently accepting fewer observations than the configured confidence floor.
        if (inliers.size() < required) return Math.max(1L, Math.round(median));

        int trim = inliers.size() >= 7 ? Math.max(1, inliers.size() / 10) : 0;
        long sum = 0L;
        for (int index = trim; index < inliers.size() - trim; index++) {
            sum += inliers.get(index);
        }
        int count = inliers.size() - trim * 2;
        return count <= 0 ? Math.max(1L, Math.round(median))
                : Math.max(1L, Math.round(sum / (double) count));
    }

    private static double median(List<Long> sorted) {
        int size = sorted.size();
        int middle = size / 2;
        if ((size & 1) != 0) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) * 0.5D;
    }
}
