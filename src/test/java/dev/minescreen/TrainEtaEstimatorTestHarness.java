package dev.minescreen;

import java.util.List;

/** Deterministic motion/display regression checks that do not require a running Minecraft client. */
public final class TrainEtaEstimatorTestHarness {
    private static final long UNKNOWN = Long.MAX_VALUE / 4L;

    private TrainEtaEstimatorTestHarness() {
    }

    public static void main(String[] args) {
        require(TrainEtaEstimator.estimateTicks(0.0D, 1.0D, 1.0D, 0.05D, 1_000L,
                UNKNOWN) == 0L, "arrival must lock ETA to zero");
        require(TrainEtaEstimator.estimateTicks(20.0D, 0.0D, 0.0D, 0.05D, 1_000L,
                UNKNOWN) == UNKNOWN, "a stopped train must not invent an ETA");

        long accelerating = TrainEtaEstimator.estimateTicks(30.0D, 0.0D, 1.0D, 0.05D,
                1_000L, UNKNOWN);
        long cruising = TrainEtaEstimator.estimateTicks(30.0D, 1.0D, 1.0D, 0.05D,
                1_000L, UNKNOWN);
        require(accelerating > cruising, "launch acceleration must increase ETA");
        require(cruising > 30L, "final braking must be included beyond naive cruise division");

        long brakingIntoPlatform = TrainEtaEstimator.estimateTicks(5.0D, 1.0D, 0.0D, 0.05D,
                1_000L, UNKNOWN);
        require(brakingIntoPlatform > 0L && brakingIntoPlatform < UNKNOWN,
                "a train already braking into the platform must retain a finite ETA");
        require(TrainEtaEstimator.estimateTicks(20.0D, 1.0D, 0.0D, 0.05D, 1_000L,
                UNKNOWN) == UNKNOWN,
                "a train braking to a signal before the platform must report waiting");
        require(PassengerArrivalLogic.select(true, true, UNKNOWN, 200L, UNKNOWN)
                        == PassengerArrivalLogic.Phase.ARRIVED,
                "arrival must take priority over a stale signal reservation");
        require(PassengerArrivalLogic.select(false, true, 80L, 200L, UNKNOWN)
                        == PassengerArrivalLogic.Phase.WAITING_SIGNAL,
                "a stopped signal hold must suppress the approaching countdown");
        require(PassengerArrivalLogic.select(false, false, 180L, 200L, UNKNOWN)
                        == PassengerArrivalLogic.Phase.APPROACHING,
                "notice must begin inside its configured ETA window");
        require(PassengerArrivalLogic.select(false, false, 240L, 200L, UNKNOWN)
                        == PassengerArrivalLogic.Phase.EN_ROUTE,
                "notice must not begin before its configured ETA window");

        long sampleAt = 10_000L;
        long arrivalAt = EtaTimeline.target(sampleAt, 1_800L);
        require(EtaTimeline.remaining(arrivalAt, sampleAt + 20L) == 1_780L,
                "an absolute arrival clock must count down exactly once per game tick");
        long delayedObservation = EtaTimeline.target(sampleAt + 20L, 2_000L);
        long corrected = EtaTimeline.slewTarget(arrivalAt, delayedObservation, 20L);
        require(corrected > arrivalAt && corrected - arrivalAt <= 10L,
                "a changed route/dwell estimate must converge without a multi-second jump");
        long earlyObservation = EtaTimeline.target(sampleAt + 20L, 1_000L);
        long correctedEarly = EtaTimeline.slewTarget(arrivalAt, earlyObservation, 20L);
        require(correctedEarly < arrivalAt && arrivalAt - correctedEarly <= 10L,
                "an optimistic estimate must also be admitted gradually");
        require(EtaTimeline.slewTarget(arrivalAt, arrivalAt + 15L, 20L) == arrivalAt,
                "sub-second prediction noise must not move the published arrival clock");
        require(SegmentRuntimeStatistics.robustEstimate(
                List.of(600L, 604L, 598L, 603L, 2_400L), 3) >= 598L
                        && SegmentRuntimeStatistics.robustEstimate(
                                List.of(600L, 604L, 598L, 603L, 2_400L), 3) <= 604L,
                "a signal/chunk outlier must not poison the learned segment runtime");
        require(SegmentRuntimeStatistics.robustEstimate(List.of(600L), 2) == -1L,
                "the configured confidence floor must be respected");
        require(SegmentRuntimeStatistics.robustEstimate(
                List.of(700L, 704L, 698L, 702L, 701L, 699L, 2_000L), 3) <= 704L,
                "trimmed aggregation must remain stable with a delayed run");

        System.out.println("trainEtaEstimatorTest=passed; accelerating=" + accelerating
                + "; cruising=" + cruising + "; braking=" + brakingIntoPlatform);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
