package dev.minescreen;

/**
 * Physical role of a regular traffic display.
 *
 * <p>PLATFORM reads a nearby, explicitly bound Create station. ONBOARD reads the train owning the
 * moving contraption and must never inherit a platform binding. Persisting this distinction also
 * lets players configure a carriage while it is still parked and assembled as world blocks.</p>
 */
public enum TrafficDisplayRole {
    PLATFORM,
    ONBOARD
}
