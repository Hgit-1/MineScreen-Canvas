package dev.minescreen;

/**
 * Keeps a displayed ETA on one absolute game-time axis.
 *
 * <p>Raw railway predictions are sampled rather than continuous. Acceleration, braking, route
 * recalculation and condition changes can move a new sample by several seconds. Converting every
 * sample directly into a fresh countdown makes the clock jump. This helper instead slews the
 * predicted arrival tick by a small, bounded correction while ordinary game time advances it
 * continuously.</p>
 */
public final class EtaTimeline {
    private static final long UNKNOWN_LIMIT = Long.MAX_VALUE / 8L;

    private EtaTimeline() {
    }

    public static boolean known(long ticks) {
        return ticks >= 0L && ticks < UNKNOWN_LIMIT;
    }

    public static long target(long now, long remainingTicks) {
        if (!known(remainingTicks)) return remainingTicks;
        if (remainingTicks > Long.MAX_VALUE - Math.max(0L, now)) return Long.MAX_VALUE;
        return Math.max(0L, now) + remainingTicks;
    }

    public static long remaining(long targetTick, long now) {
        if (!known(targetTick)) return targetTick;
        return Math.max(0L, targetTick - Math.max(0L, now));
    }

    /**
     * Applies sub-second corrections to an existing arrival clock. Large changes converge faster,
     * but never by more than half a second per network sample, so an mm:ss display cannot skip
     * several values in one frame.
     */
    public static long slewTarget(long previousTarget, long observedTarget,
            long ticksSincePreviousSample) {
        if (!known(previousTarget)) return observedTarget;
        if (!known(observedTarget)) return previousTarget;
        long difference = observedTarget - previousTarget;
        // One-second prediction noise is smaller than both the displayed countdown resolution and
        // one Minecraft clock minute. Keep the published arrival clock latched inside this band.
        if (Math.abs(difference) <= 20L) return previousTarget;
        long base = Math.max(1L, Math.max(0L, ticksSincePreviousSample) / 4L);
        long adaptive = Math.abs(difference) / 80L;
        long maximumCorrection = Math.min(6L, base + adaptive);
        if (Math.abs(difference) <= maximumCorrection) return observedTarget;
        return previousTarget + (difference < 0L ? -maximumCorrection : maximumCorrection);
    }
}
