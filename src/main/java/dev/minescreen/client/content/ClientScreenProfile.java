package dev.minescreen.client.content;

import dev.minescreen.network.ScreenAccess;

/** Client-local content selection. Local file paths and URLs are never sent to a server. */
public final class ClientScreenProfile {
    public ScreenContentType contentType = ScreenContentType.IDLE;
    /** Compatibility/current-mode source used by rendering and multiplayer state. */
    public String source = "";
    /** Mode-specific values prevent WEB navigation from overwriting VIDEO/VNC configuration. */
    public String videoSource = "";
    public String webSource = "";
    public String vncSource = "";
    /** 0 means use the NeoForge default_resolution_percent value. */
    public int resolutionPercent;
    public boolean loop = true;
    public boolean paused;
    public long positionMs;
    public float volume = 1.0F;
    public boolean vncReadOnly;
    /** 0 uses the NeoForge vnc_max_fps default; otherwise 1..60. */
    public int vncFps;
    public String mediaId = "";
    public ScreenAccess access = ScreenAccess.OWNER_ONLY;
    public java.util.Set<Long> disabledTiles = new java.util.HashSet<>();
    public java.util.List<String> webTabs = new java.util.ArrayList<>();
    public WebSplitLayout webSplitLayout = WebSplitLayout.SINGLE;
    /** Root-group setting for a cable-connected multi-plane computer display network. */
    public HostSurfaceLayout hostSurfaceLayout = HostSurfaceLayout.FREE;
    /** Stable group UUID order for panorama slicing; unknown/removed ids are ignored. */
    public java.util.List<String> hostSurfaceOrder = new java.util.ArrayList<>();
    /** Group UUID -> top-left logical tile coordinate used by CUSTOM host layout. */
    public java.util.Map<String, HostSurfacePlacement> hostSurfacePositions =
            new java.util.HashMap<>();
    /** Group UUID -> clockwise quarter turns for that physical surface. */
    public java.util.Map<String, Integer> hostSurfaceRotations = new java.util.HashMap<>();
    /** Region ids 1..3; id 0 is represented by this root profile. */
    public java.util.Map<Integer, ScreenRegionProfile> regions = new java.util.HashMap<>();
    /** Physical BlockPos.asLong -> region id. Missing entries belong to primary region 0. */
    public java.util.Map<Long, Integer> tileRegions = new java.util.HashMap<>();

    public ClientScreenProfile copy() {
        ClientScreenProfile copy = new ClientScreenProfile();
        copy.contentType = contentType;
        copy.source = source;
        copy.videoSource = videoSource;
        copy.webSource = webSource;
        copy.vncSource = vncSource;
        copy.resolutionPercent = resolutionPercent;
        copy.loop = loop;
        copy.paused = paused;
        copy.positionMs = positionMs;
        copy.volume = volume;
        copy.vncReadOnly = vncReadOnly;
        copy.vncFps = vncFps;
        copy.mediaId = mediaId;
        copy.access = access;
        copy.disabledTiles = disabledTiles == null ? new java.util.HashSet<>()
                : new java.util.HashSet<>(disabledTiles);
        copy.webTabs = webTabs == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(webTabs);
        copy.webSplitLayout = webSplitLayout == null ? WebSplitLayout.SINGLE : webSplitLayout;
        copy.hostSurfaceLayout = hostSurfaceLayout == null
                ? HostSurfaceLayout.FREE : hostSurfaceLayout;
        copy.hostSurfaceOrder = hostSurfaceOrder == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(hostSurfaceOrder);
        copy.hostSurfacePositions = hostSurfacePositions == null ? new java.util.HashMap<>()
                : new java.util.HashMap<>(hostSurfacePositions);
        copy.hostSurfaceRotations = hostSurfaceRotations == null ? new java.util.HashMap<>()
                : new java.util.HashMap<>(hostSurfaceRotations);
        copy.regions = new java.util.HashMap<>();
        if (regions != null) {
            regions.forEach((id, region) -> {
                if (id != null && region != null) {
                    copy.regions.put(id, region.copy());
                }
            });
        }
        copy.tileRegions = tileRegions == null ? new java.util.HashMap<>()
                : new java.util.HashMap<>(tileRegions);
        return copy;
    }

    /** Migrates old one-source profiles and makes source match the selected content mode. */
    public void normalizeSources() {
        contentType = contentType == null ? ScreenContentType.IDLE : contentType;
        source = clean(source);
        videoSource = clean(videoSource);
        webSource = clean(webSource);
        vncSource = clean(vncSource);
        // Migrate the legacy one-source format only when no mode-specific value exists at all.
        // Re-running the old fallback after VIDEO had already been configured copied its MP4 path
        // into an intentionally-empty WEB field during later UI rebuilds.
        boolean legacySingleSource = videoSource.isBlank() && webSource.isBlank()
                && vncSource.isBlank();
        if (legacySingleSource && !source.isBlank()) {
            switch (contentType) {
                case VIDEO -> {
                    if (videoSource.isBlank()) videoSource = source;
                }
                case WEB -> {
                    if (webSource.isBlank()) webSource = source;
                }
                case VNC -> {
                    if (vncSource.isBlank()) vncSource = source;
                }
                case IDLE -> {
                }
            }
        }
        if (sourcesEquivalent(webSource, videoSource)) {
            webSource = "";
        }
        source = sourceFor(contentType);
    }

    public String sourceFor(ScreenContentType type) {
        return switch (type == null ? ScreenContentType.IDLE : type) {
            case VIDEO -> clean(videoSource);
            case WEB -> clean(webSource);
            case VNC -> clean(vncSource);
            case IDLE -> "";
        };
    }

    public void setSourceFor(ScreenContentType type, String value) {
        String cleaned = clean(value);
        switch (type == null ? ScreenContentType.IDLE : type) {
            case VIDEO -> videoSource = cleaned;
            case WEB -> webSource = sourcesEquivalent(cleaned, videoSource) ? "" : cleaned;
            case VNC -> vncSource = cleaned;
            case IDLE -> {
            }
        }
        if (contentType == type) {
            source = cleaned;
        }
    }

    public void switchContentType(ScreenContentType next, String currentEditorValue) {
        setSourceFor(contentType, currentEditorValue);
        contentType = next == null ? ScreenContentType.IDLE : next;
        if (contentType == ScreenContentType.WEB && sourcesEquivalent(webSource, videoSource)) {
            webSource = "";
        }
        source = sourceFor(contentType);
    }

    public boolean isVideoSource(String value) {
        return sourcesEquivalent(value, videoSource);
    }

    private static boolean sourcesEquivalent(String first, String second) {
        String left = canonicalSource(first);
        String right = canonicalSource(second);
        return !left.isBlank() && left.equalsIgnoreCase(right);
    }

    private static String canonicalSource(String value) {
        String cleaned = clean(value).trim();
        if (cleaned.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(cleaned);
            if (uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("file")) {
                return java.nio.file.Path.of(uri).toAbsolutePath().normalize().toString()
                        .replace('\\', '/');
            }
            if (uri.getScheme() != null && cleaned.indexOf(':') != 1) {
                return uri.normalize().toString();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            return java.nio.file.Path.of(cleaned).toAbsolutePath().normalize().toString()
                    .replace('\\', '/');
        } catch (RuntimeException ignored) {
            return cleaned;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
