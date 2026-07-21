package dev.minescreen.client.content;

import java.util.ArrayList;

import com.google.gson.annotations.SerializedName;
import dev.minescreen.network.ScreenAccess;

/** Independent content settings for a non-primary physical-tile region. */
public final class ScreenRegionProfile {
    public ScreenContentType contentType = ScreenContentType.IDLE;
    public String source = "";
    @SerializedName("video_source")
    public String videoSource = "";
    @SerializedName("web_source")
    public String webSource = "";
    @SerializedName("vnc_source")
    public String vncSource = "";
    public int resolutionPercent;
    public boolean loop = true;
    public boolean paused;
    public long positionMs;
    public float volume = 1.0F;
    public boolean vncReadOnly;
    public String mediaId = "";
    public ScreenAccess access = ScreenAccess.OWNER_ONLY;
    public java.util.List<String> webTabs = new ArrayList<>();
    public WebSplitLayout webSplitLayout = WebSplitLayout.SINGLE;

    public ScreenRegionProfile copy() {
        return fromProfile(toProfile());
    }

    public ClientScreenProfile toProfile() {
        ClientScreenProfile profile = new ClientScreenProfile();
        profile.contentType = contentType == null ? ScreenContentType.IDLE : contentType;
        profile.source = source == null ? "" : source;
        profile.videoSource = videoSource == null ? "" : videoSource;
        profile.webSource = webSource == null ? "" : webSource;
        profile.vncSource = vncSource == null ? "" : vncSource;
        profile.normalizeSources();
        profile.resolutionPercent = resolutionPercent;
        profile.loop = loop;
        profile.paused = paused;
        profile.positionMs = Math.max(0L, positionMs);
        profile.volume = Math.max(0.0F, Math.min(1.0F, volume));
        profile.vncReadOnly = vncReadOnly;
        profile.mediaId = mediaId == null ? "" : mediaId;
        profile.access = access == null ? ScreenAccess.OWNER_ONLY : access;
        profile.webTabs = webTabs == null ? new ArrayList<>() : new ArrayList<>(webTabs);
        profile.webSplitLayout = webSplitLayout == null ? WebSplitLayout.SINGLE : webSplitLayout;
        return profile;
    }

    public static ScreenRegionProfile fromProfile(ClientScreenProfile profile) {
        ScreenRegionProfile region = new ScreenRegionProfile();
        region.contentType = profile.contentType == null ? ScreenContentType.IDLE : profile.contentType;
        region.source = profile.source == null ? "" : profile.source;
        region.videoSource = profile.videoSource == null ? "" : profile.videoSource;
        region.webSource = profile.webSource == null ? "" : profile.webSource;
        region.vncSource = profile.vncSource == null ? "" : profile.vncSource;
        region.resolutionPercent = profile.resolutionPercent;
        region.loop = profile.loop;
        region.paused = profile.paused;
        region.positionMs = Math.max(0L, profile.positionMs);
        region.volume = Math.max(0.0F, Math.min(1.0F, profile.volume));
        region.vncReadOnly = profile.vncReadOnly;
        region.mediaId = profile.mediaId == null ? "" : profile.mediaId;
        region.access = profile.access == null ? ScreenAccess.OWNER_ONLY : profile.access;
        region.webTabs = profile.webTabs == null ? new ArrayList<>()
                : new ArrayList<>(profile.webTabs);
        region.webSplitLayout = profile.webSplitLayout == null
                ? WebSplitLayout.SINGLE : profile.webSplitLayout;
        return region;
    }
}
