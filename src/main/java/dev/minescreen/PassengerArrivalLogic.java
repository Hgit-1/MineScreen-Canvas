package dev.minescreen;

/** Shared, dependency-free precedence rules for passenger arrival messages. */
public final class PassengerArrivalLogic {
    private PassengerArrivalLogic() {
    }

    public static Phase select(boolean atStation, boolean stoppedForSignal, long etaTicks,
            long noticeTicks, long unknownThreshold) {
        if (atStation) return Phase.ARRIVED;
        if (stoppedForSignal) return Phase.WAITING_SIGNAL;
        if (etaTicks >= 0L && etaTicks < unknownThreshold
                && etaTicks <= Math.max(0L, noticeTicks)) return Phase.APPROACHING;
        return Phase.EN_ROUTE;
    }

    public enum Phase {
        EN_ROUTE,
        WAITING_SIGNAL,
        APPROACHING,
        ARRIVED
    }
}
