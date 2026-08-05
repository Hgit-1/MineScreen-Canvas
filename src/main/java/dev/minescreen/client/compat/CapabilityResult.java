package dev.minescreen.client.compat;

/** Cached probe result suitable for both backend selection and user-facing diagnostics. */
public record CapabilityResult(Capability capability, Availability availability,
        BackendKind backend, String reason) {
    public boolean available() {
        return availability != Availability.UNAVAILABLE;
    }

    public static CapabilityResult available(Capability capability, BackendKind backend) {
        return new CapabilityResult(capability, Availability.AVAILABLE, backend, "");
    }

    public static CapabilityResult compatibility(Capability capability, BackendKind backend,
            String reason) {
        return new CapabilityResult(capability, Availability.COMPATIBILITY, backend, reason);
    }

    public static CapabilityResult unavailable(Capability capability, String reason) {
        return new CapabilityResult(capability, Availability.UNAVAILABLE, BackendKind.NONE, reason);
    }
}
