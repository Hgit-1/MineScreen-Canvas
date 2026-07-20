package dev.minescreen.client.content;

import dev.minescreen.network.ScreenAccess;

/** Client-local content selection. Local file paths and URLs are never sent to a server. */
public final class ClientScreenProfile {
    public ScreenContentType contentType = ScreenContentType.IDLE;
    public String source = "";
    /** 0 means use the NeoForge default_resolution_percent value. */
    public int resolutionPercent;
    public boolean loop = true;
    public boolean paused;
    public long positionMs;
    public float volume = 1.0F;
    public boolean vncReadOnly;
    public String mediaId = "";
    public ScreenAccess access = ScreenAccess.OWNER_ONLY;
    public java.util.Set<Long> disabledTiles = new java.util.HashSet<>();
    public java.util.List<String> webTabs = new java.util.ArrayList<>();

    public ClientScreenProfile copy() {
        ClientScreenProfile copy = new ClientScreenProfile();
        copy.contentType = contentType;
        copy.source = source;
        copy.resolutionPercent = resolutionPercent;
        copy.loop = loop;
        copy.paused = paused;
        copy.positionMs = positionMs;
        copy.volume = volume;
        copy.vncReadOnly = vncReadOnly;
        copy.mediaId = mediaId;
        copy.access = access;
        copy.disabledTiles = disabledTiles == null ? new java.util.HashSet<>()
                : new java.util.HashSet<>(disabledTiles);
        copy.webTabs = webTabs == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(webTabs);
        return copy;
    }
}
