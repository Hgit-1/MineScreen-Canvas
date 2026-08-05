package dev.minescreen.client.web;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.ClientSecurityPolicy;

/** Pure-Java URL/IP policy shared by MCEF, external browsers, video and VNC. */
public final class NetworkRequestPolicy {
    private NetworkRequestPolicy() {
    }

    public static boolean isAllowed(String value) {
        if (ClientSecurityPolicy.unrestrictedSingleplayer()) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("file")) {
                return MineScreenConfig.ALLOW_FILE_PROTOCOL.get();
            }
            if (!scheme.equals("https") && !(scheme.equals("http") && MineScreenConfig.ALLOW_HTTP.get())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            boolean localhost = normalized.equals("localhost") || normalized.endsWith(".localhost");
            if (localhost && !MineScreenConfig.ALLOW_LOCALHOST.get()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            boolean specialAddress = localhost;
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                boolean cloudMetadata = bytes.length == 4 && (bytes[0] & 0xFF) == 169
                        && (bytes[1] & 0xFF) == 254 && (bytes[2] & 0xFF) == 169
                        && (bytes[3] & 0xFF) == 254;
                if (cloudMetadata && !MineScreenConfig.ALLOW_CLOUD_METADATA.get()) {
                    return false;
                }
                specialAddress |= cloudMetadata;
                if ((address.isLoopbackAddress() || address.isAnyLocalAddress())
                        && !MineScreenConfig.ALLOW_LOCALHOST.get()) {
                    return false;
                }
                specialAddress |= address.isLoopbackAddress() || address.isAnyLocalAddress();
                if ((address.isSiteLocalAddress() || address.isLinkLocalAddress())
                        && !MineScreenConfig.ALLOW_PRIVATE_IP.get() && !cloudMetadata) {
                    return false;
                }
                specialAddress |= address.isSiteLocalAddress() || address.isLinkLocalAddress();
                boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
                if (ipv6UniqueLocal && !MineScreenConfig.ALLOW_PRIVATE_IP.get()) {
                    return false;
                }
                specialAddress |= ipv6UniqueLocal;
            }
            if (!MineScreenConfig.DISABLE_WHITELIST.get() && !whitelisted(normalized)
                    && !(specialAddress && (localhost || isIpLiteral(normalized)))) {
                return false;
            }
            return true;
        } catch (RuntimeException | java.net.UnknownHostException exception) {
            return false;
        }
    }

    public static boolean isAllowedRequest(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String scheme = URI.create(value).getScheme();
            if (scheme != null) {
                scheme = scheme.toLowerCase(Locale.ROOT);
                if (scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) {
                    return true;
                }
                if (scheme.equals("about")) {
                    return value.equalsIgnoreCase("about:blank");
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return isAllowed(value);
    }

    /**
     * Verifies the address Chromium actually connected to. This second check complements the
     * pre-navigation DNS lookup and closes the common DNS-rebinding window without exposing
     * browser control details to users.
     */
    public static boolean isAllowedResolvedAddress(String requestUrl, String remoteAddress) {
        if (ClientSecurityPolicy.unrestrictedSingleplayer()) {
            return true;
        }
        if (!isAllowedRequest(requestUrl) || remoteAddress == null || remoteAddress.isBlank()) {
            return remoteAddress == null || remoteAddress.isBlank();
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            byte[] bytes = address.getAddress();
            boolean cloudMetadata = bytes.length == 4 && (bytes[0] & 0xFF) == 169
                    && (bytes[1] & 0xFF) == 254 && (bytes[2] & 0xFF) == 169
                    && (bytes[3] & 0xFF) == 254;
            if (cloudMetadata && !MineScreenConfig.ALLOW_CLOUD_METADATA.get()) {
                return false;
            }
            if ((address.isLoopbackAddress() || address.isAnyLocalAddress())
                    && !MineScreenConfig.ALLOW_LOCALHOST.get()) {
                return false;
            }
            boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
            return !((address.isSiteLocalAddress() || address.isLinkLocalAddress() || uniqueLocal)
                    && !cloudMetadata && !MineScreenConfig.ALLOW_PRIVATE_IP.get());
        } catch (java.net.UnknownHostException invalidAddress) {
            return false;
        }
    }

    private static boolean whitelisted(String host) {
        for (String entry : MineScreenConfig.DOMAIN_WHITELIST.get()) {
            String allowed = entry.toLowerCase(Locale.ROOT).trim();
            if (!allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            if ((character < '0' || character > '9') && character != '.') {
                return false;
            }
        }
        return !host.isEmpty();
    }
}
