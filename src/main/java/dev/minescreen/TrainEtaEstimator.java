package dev.minescreen;

/**
 * Dependency-free motion estimate for a Create train's current navigation leg.
 *
 * <p>Create passes the train speed itself to carriage track travel each server tick. This
 * integrates launch acceleration, cruising and final braking in those same graph-distance units
 * instead of scaling a historic whole-leg duration by the remaining-distance percentage. If the
 * train would stop before the platform (for example at a signal), the caller's unknown value is
 * returned rather than an increasing fake ETA.</p>
 */
public final class TrainEtaEstimator {
    private static final double MIN_SPEED = 1.0E-6D;

    private TrainEtaEstimator() {
    }

    public static long estimateTicks(double distance, double currentSpeed, double targetSpeed,
            double acceleration, long maximumTicks, long unknownValue) {
        if (!Double.isFinite(distance)) return unknownValue;
        if (distance <= 0.0D) return 0L;
        double speed = finiteMagnitude(currentSpeed);
        double target = finiteMagnitude(targetSpeed);
        double step = Double.isFinite(acceleration) ? Math.abs(acceleration) : 0.0D;
        if (step < MIN_SPEED || speed < MIN_SPEED && target < MIN_SPEED) return unknownValue;

        double remaining = distance;
        long limit = Math.max(1L, maximumTicks);
        for (long tick = 1L; tick <= limit; tick++) {
            double stoppingDistance = speed * speed / (2.0D * step);
            double goal = remaining <= stoppingDistance + Math.max(0.05D, speed)
                    ? 0.0D : target;
            double nextSpeed = approach(speed, goal, step);
            double movement = (speed + nextSpeed) * 0.5D;
            if (movement + 1.0E-7D >= remaining) return tick;
            remaining -= movement;
            speed = nextSpeed;
            if (speed < MIN_SPEED && goal < MIN_SPEED) return unknownValue;
        }
        return unknownValue;
    }

    private static double finiteMagnitude(double value) {
        return Double.isFinite(value) ? Math.abs(value) : 0.0D;
    }

    private static double approach(double value, double target, double step) {
        if (value < target) return Math.min(target, value + step);
        if (value > target) return Math.max(target, value - step);
        return value;
    }
}
