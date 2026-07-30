package dev.minescreen.network;

/** Dependency-free truth-table regression tests for common client/server trust decisions. */
public final class SecurityPolicyTestHarness {
    private SecurityPolicyTestHarness() {
    }

    public static void main(String[] args) {
        require(SourceReferencePolicy.permitsHttp(true, false, false, false),
                "Explicit allow_http must work on a dedicated server");
        require(SourceReferencePolicy.permitsHttp(false, true, true, false),
                "Default unrestricted private single-player must permit HTTP");
        require(!SourceReferencePolicy.permitsHttp(false, true, true, true),
                "Opening an integrated world to LAN must restore the HTTP restriction");
        require(!SourceReferencePolicy.permitsHttp(false, true, false, false),
                "Dedicated multiplayer must not inherit the single-player bypass");
        require(!SourceReferencePolicy.permitsHttp(false, false, true, false),
                "Disabling unrestricted_singleplayer must restore the HTTP restriction");
        System.out.println("securityPolicyTest=passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
