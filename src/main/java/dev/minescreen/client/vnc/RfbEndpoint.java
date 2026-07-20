package dev.minescreen.client.vnc;

import java.net.URI;

/** Parsed RFB endpoint. Credentials are intentionally not part of this value. */
public record RfbEndpoint(String host, int port) {
    public static RfbEndpoint parse(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.contains("://")) {
            normalized = "vnc://" + normalized;
        }
        URI uri = URI.create(normalized);
        if (!"vnc".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
            throw new IllegalArgumentException("Expected vnc://host[:port]");
        }
        int port = uri.getPort() < 0 ? 5900 : uri.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid VNC port");
        }
        return new RfbEndpoint(uri.getHost(), port);
    }

    public String policyUrl() {
        String bracketed = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "https://" + bracketed + "/";
    }
}
