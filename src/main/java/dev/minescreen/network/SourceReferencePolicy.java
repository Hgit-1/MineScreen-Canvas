package dev.minescreen.network;

/**
 * Pure policy helpers shared by server packet validation and dependency-light release tests.
 *
 * <p>The integrated server must agree with the client-side trust override. Otherwise a local
 * single-player browser can load an HTTP page but its state/navigation payload is silently rejected
 * by the integrated server, making the address bar appear to revert. Opening the world to LAN
 * restores the normal multiplayer boundary immediately.</p>
 */
public final class SourceReferencePolicy {
    private SourceReferencePolicy() {
    }

    public static boolean permitsHttp(boolean allowHttp, boolean unrestrictedSingleplayer,
            boolean integratedSingleplayer, boolean publishedToLan) {
        return allowHttp || unrestrictedSingleplayer && integratedSingleplayer && !publishedToLan;
    }
}
