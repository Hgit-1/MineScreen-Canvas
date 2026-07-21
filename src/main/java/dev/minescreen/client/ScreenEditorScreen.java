package dev.minescreen.client;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import dev.minescreen.ScreenGroup;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.content.ScreenRegionLayout;
import dev.minescreen.client.content.WebSplitLayout;
import dev.minescreen.client.vnc.RfbEndpoint;
import dev.minescreen.client.vnc.VncCredentialStore;
import dev.minescreen.client.vnc.VncRefreshRate;
import dev.minescreen.client.video.VideoSource;
import dev.minescreen.client.web.BrowserRequestPolicy;
import dev.minescreen.client.web.BrowserSession;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import dev.minescreen.client.ui.MineScreenAssistant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Guided client-local editor opened with Shift + right-click on any tile in a joined group. */
public final class ScreenEditorScreen extends Screen {
    private static final int[] RESOLUTION_PRESETS = {100, 75, 50, 33, 25};

    private final ScreenGroup parentGroup;
    private final ScreenGroup group;
    private final int regionId;
    private final Screen parent;
    private final ClientScreenProfile profile;
    private final Button[] modeButtons = new Button[ScreenContentType.values().length];
    private final BrowserTabButton[] webTabButtons = new BrowserTabButton[5];
    private final int[] displayedWebTabs = {-1, -1, -1, -1, -1};
    private EditBox sourceBox;
    private EditBox passwordBox;
    private EditBox mediaIdBox;
    private Button chooseButton;
    private Button playButton;
    private Button loopButton;
    private Button readOnlyButton;
    private Button vncFpsButton;
    private Button vncFpsDownButton;
    private Button vncFpsUpButton;
    private Button volumeButton;
    private Button accessButton;
    private Button resolutionButton;
    private Button webSplitButton;
    private Button webBackButton;
    private Button webForwardButton;
    private Button webReloadButton;
    private Button webGoButton;
    private Component status = Component.empty();
    private int statusColor = 0xFFB8C7D9;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int progressTop;
    private int contentLeft;
    private int contentWidth;
    private int observedWebTab = -1;
    private String pendingWebAddress;
    private long pendingWebAddressUntilNanos;

    public ScreenEditorScreen(ScreenGroup group) {
        this(group, null);
    }

    public ScreenEditorScreen(ScreenGroup group, Screen parent) {
        this(group, 0, parent);
    }

    public ScreenEditorScreen(ScreenGroup parentGroup, int regionId, Screen parent) {
        super(Component.translatable("screen.minescreen.editor"));
        this.parentGroup = parentGroup;
        this.regionId = Math.max(0, Math.min(ScreenRegionLayout.MAX_REGIONS - 1, regionId));
        ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(parentGroup,
                ScreenContentManager.profile(parentGroup.groupId()), this.regionId);
        this.group = canvas == null ? parentGroup : canvas.group();
        this.parent = parent;
        this.profile = ScreenContentManager.profile(parentGroup.groupId(), this.regionId).copy();
        if (profile.contentType == null) {
            profile.contentType = ScreenContentType.IDLE;
        }
        profile.normalizeSources();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(760, width - 16);
        panelHeight = Math.min(350, height - 12);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        contentLeft = panelLeft + 14;
        contentWidth = panelWidth - 28;
        int innerLeft = contentLeft;
        int innerWidth = contentWidth;

        int resolutionWidth = Math.min(170, innerWidth / 3);
        resolutionButton = addRenderableWidget(MineScreenButton.create(resolutionLabel(),
                button -> cycleResolution(), panelLeft + panelWidth - resolutionWidth - 12,
                panelTop + 7, resolutionWidth, 20));

        int tabGap = 4;
        int tabWidth = (innerWidth - tabGap * 3) / 4;
        ScreenContentType[] modes = ScreenContentType.values();
        for (int i = 0; i < modes.length; i++) {
            ScreenContentType mode = modes[i];
            modeButtons[i] = addRenderableWidget(MineScreenButton.create(modeLabel(mode),
                    button -> setMode(mode), innerLeft + i * (tabWidth + tabGap),
                    panelTop + 36, tabWidth, 20));
        }
        int webTabGap = 3;
        int webTabWidth = (innerWidth - webTabGap * (webTabButtons.length - 1))
                / webTabButtons.length;
        for (int i = 0; i < webTabButtons.length; i++) {
            final int slot = i;
            BrowserTabButton tabButton = new BrowserTabButton(
                    innerLeft + i * (webTabWidth + webTabGap), panelTop + 59,
                    webTabWidth, 20, () -> selectOrCloseWebTab(slot),
                    () -> closeWebTab(slot));
            webTabButtons[i] = addRenderableWidget(tabButton);
        }

        sourceBox = new MineScreenEditBox(font, innerLeft, panelTop + 82, innerWidth, 20,
                Component.translatable("screen.minescreen.source"));
        // Signed CDN/video URLs can contain large query strings. This value is client-local and
        // is not serialized through the 2 KiB multiplayer screen-state payload.
        sourceBox.setMaxLength(VideoSource.MAX_INPUT_LENGTH);
        sourceBox.setValue(profile.sourceFor(profile.contentType));
        sourceBox.setResponder(value -> clearTransientError());
        addRenderableWidget(sourceBox);

        int actionTop = panelTop + 108;
        chooseButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.choose_file"), button -> chooseVideo(),
                innerLeft, actionTop, 112, 20));
        playButton = addRenderableWidget(MineScreenButton.create(playLabel(),
                button -> togglePaused(), innerLeft + 118, actionTop, 88, 20));
        loopButton = addRenderableWidget(MineScreenButton.create(loopLabel(),
                button -> toggleLoop(), innerLeft + 212, actionTop, 102, 20));
        volumeButton = addRenderableWidget(MineScreenButton.create(volumeLabel(),
                button -> cycleVolume(), innerLeft + 320, actionTop, 102, 20));

        int navWidth = Math.max(42, Math.min(58, (innerWidth - 132) / 3));
        webBackButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.back"),
                button -> browserAction(BrowserAction.BACK), innerLeft, actionTop, navWidth, 20));
        webForwardButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.forward"),
                button -> browserAction(BrowserAction.FORWARD), innerLeft + navWidth + 4,
                actionTop, navWidth, 20));
        webReloadButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.reload"),
                button -> browserAction(BrowserAction.RELOAD), innerLeft + (navWidth + 4) * 2,
                actionTop, navWidth, 20));
        webSplitButton = addRenderableWidget(MineScreenButton.create(splitLayoutLabel(),
                button -> cycleSplitLayout(), innerLeft + (navWidth + 4) * 3,
                actionTop, 116, 20));
        webGoButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.go"), button -> loadWebAddress(),
                panelLeft + panelWidth - 126, actionTop, 112, 20));
        int vncFpsTop = panelTop + 166;
        int vncFpsWidth = Math.min(270, innerWidth);
        int vncFpsStepWidth = 30;
        int vncFpsGap = 4;
        int vncFpsValueWidth = vncFpsWidth - vncFpsStepWidth * 2 - vncFpsGap * 2;
        vncFpsDownButton = addRenderableWidget(MineScreenButton.create(Component.literal("−"),
                button -> adjustVncFps(-1), innerLeft, vncFpsTop, vncFpsStepWidth, 20));
        vncFpsButton = addRenderableWidget(MineScreenButton.create(vncFpsLabel(),
                button -> resetVncFps(), innerLeft + vncFpsStepWidth + vncFpsGap, vncFpsTop,
                vncFpsValueWidth, 20));
        vncFpsUpButton = addRenderableWidget(MineScreenButton.create(Component.literal("+"),
                button -> adjustVncFps(1), innerLeft + vncFpsWidth - vncFpsStepWidth,
                vncFpsTop, vncFpsStepWidth, 20));

        passwordBox = new MineScreenEditBox(font, innerLeft, panelTop + 136,
                Math.min(210, innerWidth), 20,
                Component.translatable("screen.minescreen.vnc_password"));
        passwordBox.setMaxLength(256);
        passwordBox.setValue(VncCredentialStore.get(group.groupId()).password());
        addRenderableWidget(passwordBox);
        readOnlyButton = addRenderableWidget(MineScreenButton.create(readOnlyLabel(),
                button -> toggleReadOnly(), innerLeft + Math.min(216, innerWidth / 2),
                panelTop + 136, 118, 20));

        mediaIdBox = new MineScreenEditBox(font, innerLeft, panelTop + 136,
                Math.min(250, innerWidth), 20,
                Component.translatable("screen.minescreen.media_id"));
        mediaIdBox.setMaxLength(128);
        mediaIdBox.setValue(profile.mediaId == null ? "" : profile.mediaId);
        addRenderableWidget(mediaIdBox);

        accessButton = addRenderableWidget(MineScreenButton.create(accessLabel(),
                button -> cycleAccess(), panelLeft + panelWidth - 148, panelTop + 136, 134, 20));

        int bottom = panelTop + panelHeight - 28;
        addRenderableWidget(MineScreenButton.create(Component.translatable("screen.minescreen.save"),
                button -> saveAndClose(), panelLeft + panelWidth - 218, bottom, 98, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.cancel"),
                button -> onClose(), panelLeft + panelWidth - 114, bottom, 100, 20));

        progressTop = panelTop + 166;
        updateModeControls();
    }

    @Override
    public void tick() {
        super.tick();
        BrowserSession browser = browserSession();
        webBackButton.active = browser != null && browser.canGoBack();
        webForwardButton.active = browser != null && browser.canGoForward();
        webReloadButton.active = browser != null;
        updateWebTabs(browser);
        syncWebAddress(browser, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        MineScreenUiRegistry.render(this, graphics, mouseX, mouseY, partialTick,
                () -> renderMineScreenLayer(graphics, mouseX, mouseY, partialTick));
    }

    private void renderMineScreenLayer(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick) {
        // Panel, static text, EditBox text and widgets intentionally share the default GUI Z=0.
        // Ordering is controlled solely by call order so no translated pose can split a text box
        // background from its text/caret or place labels on a different depth plane.
        graphics.fillGradient(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
                0xFF1A202B, 0xFF0F141D);
        graphics.fill(panelLeft, panelTop, panelLeft + 4, panelTop + panelHeight, 0xFFFFD43B);
        graphics.fill(panelLeft + 4, panelTop + 30, panelLeft + panelWidth, panelTop + 31, 0xFF394657);
        graphics.drawString(font, title, panelLeft + 18, panelTop + 12, 0xFFF4F7FB, false);
        if (MineScreenConfig.UI_SHOW_MASCOT.get()
                && resolutionButton.getX() - panelLeft > 250) {
            MineScreenAssistant.drawGui(graphics, resolutionButton.getX() - 30,
                    panelTop + 1, 2, 220);
        }
        if (regionId > 0) {
            graphics.drawString(font, Component.translatable("screen.minescreen.region.editing",
                    regionId), panelLeft + 152, panelTop + 12, 0xFFFFD43B, false);
        }

        if (profile.contentType != ScreenContentType.WEB) {
            graphics.drawString(font, Component.translatable("screen.minescreen.group_summary",
                    group.columns(), group.rows(), shortId()), contentLeft, panelTop + 63,
                    0xFF93A6BB, false);
        }

        if (sourceBox.visible && profile.contentType != ScreenContentType.WEB) {
            graphics.drawString(font, sourceLabel(), contentLeft, panelTop + 72,
                    0xFFD8E2EE, false);
        }
        if (passwordBox.visible) {
            graphics.drawString(font, Component.translatable("screen.minescreen.vnc_password"),
                    contentLeft, panelTop + 128, 0xFF9EB0C4, false);
        }
        if (mediaIdBox.visible) {
            graphics.drawString(font, Component.translatable("screen.minescreen.media_id"),
                    contentLeft, panelTop + 128, 0xFF9EB0C4, false);
        }

        renderContentStatus(graphics);
        renderGuide(graphics);
        renderWidgetsDirect(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Do not call Screen.render here. ModernUI may split or reorder that method's child pass.
     * Iterating the actual children inside MineScreen's off-screen target guarantees that panel,
     * preview, widget frames and widget glyphs are flattened before the one final composite.
     */
    private void renderWidgetsDirect(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.flush();
    }

    private void renderContentStatus(GuiGraphics graphics) {
        ScreenContentSession session = ScreenContentManager.session(parentGroup.groupId(), regionId);
        int innerLeft = contentLeft;
        int innerRight = contentLeft + contentWidth;
        if (profile.contentType == ScreenContentType.VIDEO) {
            graphics.fill(innerLeft, progressTop, innerRight, progressTop + 5, 0xFF303A49);
            long duration = session == null ? 0L : session.durationMs();
            long position = session == null ? profile.positionMs : session.positionMs();
            if (duration > 0L) {
                int filled = (int) Math.round((innerRight - innerLeft)
                        * Math.min(1.0D, position / (double) duration));
                graphics.fill(innerLeft, progressTop, innerLeft + filled, progressTop + 5, 0xFFFFD43B);
                graphics.drawString(font, formatTime(position) + " / " + formatTime(duration),
                        innerLeft, progressTop + 9, 0xFFBFCADB, false);
            }
        } else if (profile.contentType == ScreenContentType.WEB) {
            BrowserSession browser = browserSession();
            if (browser != null && browser.currentUrl() != null) {
                graphics.drawString(font, Component.translatable("screen.minescreen.web.current",
                        elide(browser.currentUrl(), 78)), innerLeft, progressTop,
                        0xFF8FCBFF, false);
            }
        }
        if (session != null && session.errorMessage() != null) {
            graphics.drawString(font, Component.translatable("screen.minescreen.backend_error",
                    elide(session.errorMessage(), 82)), innerLeft, progressTop + 18, 0xFFFF7777, false);
        }
    }

    private void renderGuide(GuiGraphics graphics) {
        int x = contentLeft;
        int y = panelTop + 198;
        int width = contentWidth;
        Component guide = guideText();
        for (FormattedCharSequence line : font.split(guide, width)) {
            graphics.drawString(font, line, x, y, 0xFF9FB1C5, false);
            y += font.lineHeight + 1;
            if (y > panelTop + panelHeight - 54) {
                break;
            }
        }
        if (!status.getString().isBlank()) {
            graphics.drawString(font, status, x, panelTop + panelHeight - 43, statusColor, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (profile.contentType == ScreenContentType.VIDEO && button == 0
                && mouseY >= progressTop - 3 && mouseY <= progressTop + 10
                && mouseX >= contentLeft && mouseX <= contentLeft + contentWidth) {
            ScreenContentSession session = ScreenContentManager.session(parentGroup.groupId(), regionId);
            long duration = session == null ? 0L : session.durationMs();
            if (duration > 0L) {
                long target = Math.round((mouseX - contentLeft)
                        / (double) contentWidth * duration);
                profile.positionMs = target;
                session.seek(target);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            if (profile.contentType == ScreenContentType.WEB && sourceBox.isFocused()) {
                loadWebAddress();
            } else {
                saveAndClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void setMode(ScreenContentType mode) {
        profile.switchContentType(mode, sourceBox.getValue());
        sourceBox.setValue(profile.sourceFor(mode));
        status = Component.translatable("screen.minescreen.status.mode_changed");
        statusColor = 0xFF9FD4FF;
        updateModeControls();
    }

    private void cycleResolution() {
        int current = ScreenResolution.percent(profile);
        int next = RESOLUTION_PRESETS[0];
        for (int i = 0; i < RESOLUTION_PRESETS.length; i++) {
            if (RESOLUTION_PRESETS[i] == current) {
                next = RESOLUTION_PRESETS[(i + 1) % RESOLUTION_PRESETS.length];
                break;
            }
        }
        profile.resolutionPercent = next;
        resolutionButton.setMessage(resolutionLabel());
        status = Component.translatable("screen.minescreen.status.resolution_restart");
        statusColor = 0xFFFFD86B;
    }

    private void chooseVideo() {
        if (!ClientSecurityPolicy.localFilesAllowed()) {
            error("screen.minescreen.error.local_files_blocked");
            return;
        }
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.mp4")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.minescreen.choose_file").getString(),
                    sourceBox.getValue(), filters, "MP4 video", false);
        }
        if (selected != null && !selected.isBlank()) {
            sourceBox.setValue(Path.of(selected).toAbsolutePath().normalize().toString());
            success("screen.minescreen.status.file_selected");
        }
    }

    private void togglePaused() {
        profile.paused = !profile.paused;
        ScreenContentSession session = ScreenContentManager.session(parentGroup.groupId(), regionId);
        if (session != null) {
            session.setPaused(profile.paused);
        }
        playButton.setMessage(playLabel());
    }

    private void toggleLoop() {
        profile.loop = !profile.loop;
        loopButton.setMessage(loopLabel());
    }

    private void cycleVolume() {
        profile.volume = profile.volume >= 0.99F ? 0.0F : Math.min(1.0F, profile.volume + 0.25F);
        ScreenContentManager.updateVolume(parentGroup.groupId(), regionId, profile.volume);
        volumeButton.setMessage(volumeLabel());
    }

    private void cycleSplitLayout() {
        profile.webSplitLayout = (profile.webSplitLayout == null
                ? WebSplitLayout.SINGLE : profile.webSplitLayout).next();
        ScreenContentManager.updateWebSplitLayout(parentGroup.groupId(), regionId,
                profile.webSplitLayout);
        webSplitButton.setMessage(splitLayoutLabel());
        success("screen.minescreen.status.split_changed");
    }

    private void toggleReadOnly() {
        profile.vncReadOnly = !profile.vncReadOnly;
        readOnlyButton.setMessage(readOnlyLabel());
    }

    private void adjustVncFps(int direction) {
        profile.vncFps = direction < 0 ? VncRefreshRate.previous(profile.vncFps)
                : VncRefreshRate.next(profile.vncFps);
        vncFpsButton.setMessage(vncFpsLabel());
    }

    private void resetVncFps() {
        profile.vncFps = 0;
        vncFpsButton.setMessage(vncFpsLabel());
    }

    private void cycleAccess() {
        profile.access = switch (profile.access) {
            case OWNER_ONLY -> dev.minescreen.network.ScreenAccess.ANYONE;
            case ANYONE -> dev.minescreen.network.ScreenAccess.OPERATORS;
            case OPERATORS -> dev.minescreen.network.ScreenAccess.OWNER_ONLY;
        };
        accessButton.setMessage(accessLabel());
    }

    private void browserAction(BrowserAction action) {
        BrowserSession browser = browserSession();
        if (browser == null) {
            error("screen.minescreen.error.browser_not_running");
            return;
        }
        switch (action) {
            case BACK -> browser.goBack();
            case FORWARD -> browser.goForward();
            case RELOAD -> browser.reload();
        }
    }

    private void loadWebAddress() {
        if (profile.isVideoSource(sourceBox.getValue())) {
            error("screen.minescreen.error.web_uses_video_source");
            sourceBox.setValue(profile.sourceFor(ScreenContentType.WEB));
            return;
        }
        String url = normalizeWebUrl(sourceBox.getValue());
        sourceBox.setValue(url);
        if (!validWebUrl(url)) {
            return;
        }
        BrowserSession existing = browserSession();
        if (Screen.hasShiftDown() && existing != null) {
            if (existing.openTab(url, true)) {
                captureBrowserProfile(existing, url, true, null);
                ScreenContentManager.saveLocalProfile(parentGroup.groupId(), regionId, profile);
                pendingWebAddress = url;
                pendingWebAddressUntilNanos = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(3L);
                success("screen.minescreen.status.web_tab_opened");
            }
            return;
        }
        String previousActive = existing == null ? null : existing.restorableActiveUrl();
        if (existing != null && existing.navigate(url)) {
            captureBrowserProfile(existing, url, false, previousActive);
            ScreenContentManager.saveLocalProfile(parentGroup.groupId(), regionId, profile);
            pendingWebAddress = url;
            pendingWebAddressUntilNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(3L);
            success("screen.minescreen.status.web_loaded");
            return;
        }
        // The asynchronous wrapper may exist before Chromium is finalized. Recreate only in that
        // loading case; a live browser always navigates in place so its other tabs survive.
        profile.setSourceFor(ScreenContentType.WEB, url);
        ScreenContentManager.updateProfile(parentGroup.groupId(), regionId, profile);
        ScreenContentManager.sourceFor(parentGroup, regionId);
        success("screen.minescreen.status.web_loaded");
    }

    private void saveAndClose() {
        String source = sourceBox.getValue().trim();
        if (profile.contentType == ScreenContentType.IDLE) {
            source = "";
        } else if (profile.contentType == ScreenContentType.VIDEO) {
            if (!validateVideo(source)) {
                return;
            }
            String mediaId = mediaIdBox.getValue().trim();
            if (!mediaId.isEmpty() && !mediaId.matches("[A-Za-z0-9._-]{1,128}")) {
                error("screen.minescreen.error.media_id");
                return;
            }
            profile.mediaId = mediaId;
        } else if (profile.contentType == ScreenContentType.WEB) {
            if (profile.isVideoSource(source)) {
                error("screen.minescreen.error.web_uses_video_source");
                sourceBox.setValue(profile.sourceFor(ScreenContentType.WEB));
                return;
            }
            source = normalizeWebUrl(source);
            sourceBox.setValue(source);
            if (!validWebUrl(source)) {
                return;
            }
        } else if (profile.contentType == ScreenContentType.VNC) {
            try {
                RfbEndpoint endpoint = RfbEndpoint.parse(source);
                if (!BrowserRequestPolicy.isAllowed(endpoint.policyUrl())) {
                    error("screen.minescreen.error.vnc_blocked");
                    return;
                }
            } catch (RuntimeException invalidEndpoint) {
                error("screen.minescreen.error.vnc_format");
                return;
            }
            VncCredentialStore.put(group.groupId(),
                    new VncCredentialStore.Credential("", passwordBox.getValue()));
        }
        profile.setSourceFor(profile.contentType, source);
        ScreenContentSession current = ScreenContentManager.session(parentGroup.groupId(), regionId);
        if (current != null && profile.contentType == ScreenContentType.VIDEO) {
            profile.positionMs = current.positionMs();
        }
        ScreenContentManager.updateProfile(parentGroup.groupId(), regionId, profile);
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private boolean validateVideo(String source) {
        try {
            VideoSource.resolve(source);
            return true;
        } catch (VideoSource.ValidationException invalidSource) {
            error(videoErrorKey(invalidSource.problem()));
            return false;
        }
    }

    private static String videoErrorKey(VideoSource.Problem problem) {
        return switch (problem) {
            case BLOCKED_FILE -> "screen.minescreen.error.local_files_blocked";
            case INVALID_FILE -> "screen.minescreen.error.video_file";
            case EMPTY -> "screen.minescreen.error.video_source";
            case TOO_LONG -> "screen.minescreen.error.video_source_too_long";
            case INVALID_URL -> "screen.minescreen.error.video_url";
            case BLOCKED_URL -> "screen.minescreen.error.video_blocked";
            case UNSUPPORTED_SCHEME -> "screen.minescreen.error.video_scheme";
        };
    }

    private boolean validWebUrl(String source) {
        try {
            URI uri = URI.create(source);
            if (uri.getScheme() == null || uri.getHost() == null) {
                error("screen.minescreen.error.web_format");
                return false;
            }
        } catch (RuntimeException invalidUri) {
            error("screen.minescreen.error.web_format");
            return false;
        }
        if (!BrowserRequestPolicy.isAllowed(source)) {
            error("screen.minescreen.error.web_blocked");
            return false;
        }
        return true;
    }

    private void updateModeControls() {
        boolean contentPage = true;
        boolean idle = profile.contentType == ScreenContentType.IDLE;
        boolean video = profile.contentType == ScreenContentType.VIDEO;
        boolean web = profile.contentType == ScreenContentType.WEB;
        boolean vnc = profile.contentType == ScreenContentType.VNC;
        sourceBox.visible = contentPage && !idle;
        // Mode previews must not destructively shorten an existing video path or signed URL.
        sourceBox.setMaxLength(VideoSource.MAX_INPUT_LENGTH);
        sourceBox.setEditable(contentPage && !idle);
        chooseButton.visible = chooseButton.active = contentPage && video;
        playButton.visible = playButton.active = contentPage && video;
        loopButton.visible = loopButton.active = contentPage && video;
        volumeButton.visible = volumeButton.active = contentPage && video;
        webBackButton.visible = contentPage && web;
        webForwardButton.visible = contentPage && web;
        webReloadButton.visible = contentPage && web;
        webSplitButton.visible = webSplitButton.active = contentPage && web;
        webGoButton.visible = webGoButton.active = contentPage && web;
        for (BrowserTabButton tabButton : webTabButtons) {
            tabButton.visible = contentPage && web;
        }
        passwordBox.visible = contentPage && vnc;
        passwordBox.setEditable(contentPage && vnc);
        readOnlyButton.visible = readOnlyButton.active = contentPage && vnc;
        vncFpsButton.visible = vncFpsButton.active = contentPage && vnc;
        vncFpsDownButton.visible = vncFpsDownButton.active = contentPage && vnc;
        vncFpsUpButton.visible = vncFpsUpButton.active = contentPage && vnc;
        mediaIdBox.visible = contentPage && video;
        mediaIdBox.setEditable(contentPage && video);
        accessButton.visible = accessButton.active = contentPage && !idle && regionId == 0;
        resolutionButton.visible = resolutionButton.active = contentPage && (video || web);
        for (int i = 0; i < modeButtons.length; i++) {
            modeButtons[i].visible = contentPage;
            modeButtons[i].active = ScreenContentType.values()[i] != profile.contentType;
        }
        status = Component.translatable("screen.minescreen.guide." + profile.contentType.name()
                .toLowerCase(Locale.ROOT));
        statusColor = 0xFF8FCBFF;
    }

    private Component guideText() {
        String mode = profile.contentType.name().toLowerCase(Locale.ROOT);
        if (profile.contentType == ScreenContentType.WEB) {
            return Component.translatable(ClientSecurityPolicy.unrestrictedSingleplayer()
                    ? "screen.minescreen.guide.web_singleplayer"
                    : "screen.minescreen.guide.web_protected");
        }
        return Component.translatable("screen.minescreen.help." + mode);
    }

    private BrowserSession browserSession() {
        ScreenContentSession session = ScreenContentManager.session(parentGroup.groupId(), regionId);
        return session instanceof BrowserSession browser ? browser : null;
    }

    private void updateWebTabs(BrowserSession browser) {
        java.util.Arrays.fill(displayedWebTabs, -1);
        if (profile.contentType != ScreenContentType.WEB || browser == null) {
            for (BrowserTabButton button : webTabButtons) {
                button.visible = false;
            }
            return;
        }
        java.util.List<BrowserSession.TabInfo> tabs = browser.tabs();
        int start = Math.max(0, Math.min(browser.activeTabIndex(),
                Math.max(0, tabs.size() - webTabButtons.length)));
        for (int slot = 0; slot < webTabButtons.length; slot++) {
            int index = start + slot;
            BrowserTabButton button = webTabButtons[slot];
            if (index >= tabs.size()) {
                button.visible = false;
                continue;
            }
            BrowserSession.TabInfo tab = tabs.get(index);
            displayedWebTabs[slot] = index;
            button.visible = true;
            button.active = true;
            button.setSelected(tab.active());
            button.setMessage(Component.literal(elide(tab.title(), 18)));
        }
    }

    private void selectOrCloseWebTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedWebTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        if (Screen.hasShiftDown()) {
            browser.closeTab(index);
        } else {
            browser.activateTab(index);
        }
        pendingWebAddress = null;
        syncWebAddress(browser, true);
        captureBrowserProfile(browser, browser.currentUrl(), true, null);
        ScreenContentManager.saveBrowserState(parentGroup.groupId(), regionId, browser);
        updateWebTabs(browser);
    }

    private void closeWebTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedWebTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        browser.closeTab(index);
        pendingWebAddress = null;
        syncWebAddress(browser, true);
        captureBrowserProfile(browser, browser.currentUrl(), true, null);
        ScreenContentManager.saveBrowserState(parentGroup.groupId(), regionId, browser);
        updateWebTabs(browser);
    }

    private void syncWebAddress(BrowserSession browser, boolean force) {
        if (profile.contentType != ScreenContentType.WEB || browser == null || sourceBox == null) {
            observedWebTab = -1;
            return;
        }
        int active = browser.activeTabIndex();
        boolean tabChanged = active != observedWebTab;
        if (!force && !tabChanged && sourceBox.isFocused()) {
            return;
        }
        String url = browser.currentUrl();
        if (url == null || url.isBlank() || url.equalsIgnoreCase("about:blank")) {
            // Retry next tick until a newly-created MCEF tab exposes its real URL.
            return;
        }
        if (pendingWebAddress != null) {
            if (url.equals(pendingWebAddress)) {
                pendingWebAddress = null;
            } else if (!tabChanged && System.nanoTime() < pendingWebAddressUntilNanos) {
                return;
            } else {
                pendingWebAddress = null;
            }
        }
        observedWebTab = active;
        if (!url.equals(sourceBox.getValue())) {
            sourceBox.setValue(url);
        }
        profile.setSourceFor(ScreenContentType.WEB, url);
        if (tabChanged) {
            ScreenContentManager.saveBrowserState(parentGroup.groupId(), regionId, browser);
        }
    }

    private void captureBrowserProfile(BrowserSession browser, String requestedActiveUrl,
            boolean keepPreviousActive, String replacedActiveUrl) {
        String active = requestedActiveUrl == null || requestedActiveUrl.isBlank()
                ? browser.restorableActiveUrl() : requestedActiveUrl;
        String previousActive = replacedActiveUrl == null
                ? browser.restorableActiveUrl() : replacedActiveUrl;
        profile.setSourceFor(ScreenContentType.WEB, active);
        java.util.LinkedHashSet<String> background = new java.util.LinkedHashSet<>();
        for (String url : browser.restorableUrls()) {
            if (url != null && !url.isBlank() && !url.equalsIgnoreCase("about:blank")
                    && !url.equals(active)) {
                if (!keepPreviousActive && url.equals(previousActive)) {
                    continue;
                }
                background.add(url);
            }
        }
        profile.webTabs = new java.util.ArrayList<>(background);
    }

    private Component sourceLabel() {
        return Component.translatable(switch (profile.contentType) {
            case VIDEO -> "screen.minescreen.label.video_source";
            case WEB -> "screen.minescreen.label.web_source";
            case VNC -> "screen.minescreen.label.vnc_source";
            case IDLE -> "screen.minescreen.source";
        });
    }

    private Component modeLabel(ScreenContentType type) {
        return Component.translatable("screen.minescreen.mode." + type.name().toLowerCase(Locale.ROOT));
    }

    private Component resolutionLabel() {
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        return Component.translatable("screen.minescreen.resolution", ScreenResolution.percent(profile),
                dimensions[0], dimensions[1]);
    }

    private Component playLabel() {
        return Component.translatable(profile.paused
                ? "screen.minescreen.video.play" : "screen.minescreen.video.pause");
    }

    private Component loopLabel() {
        return Component.translatable("screen.minescreen.video.loop", onOff(profile.loop));
    }

    private Component readOnlyLabel() {
        return Component.translatable(profile.vncReadOnly
                ? "screen.minescreen.vnc.read_only" : "screen.minescreen.vnc.control");
    }

    private Component vncFpsLabel() {
        int fps = VncRefreshRate.resolve(profile);
        return Component.translatable(profile.vncFps <= 0
                ? "screen.minescreen.vnc.fps_default" : "screen.minescreen.vnc.fps", fps);
    }

    private Component volumeLabel() {
        return Component.translatable("screen.minescreen.video.volume", Math.round(profile.volume * 100.0F));
    }

    private Component splitLayoutLabel() {
        WebSplitLayout layout = profile.webSplitLayout == null
                ? WebSplitLayout.SINGLE : profile.webSplitLayout;
        return Component.translatable("screen.minescreen.web.split",
                Component.translatable("screen.minescreen.web.split."
                        + layout.name().toLowerCase(Locale.ROOT)));
    }

    private Component accessLabel() {
        return Component.translatable("screen.minescreen.access", profile.access.name());
    }

    private Component onOff(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    private void success(String key) {
        status = Component.translatable(key);
        statusColor = 0xFF7EE29A;
    }

    private void error(String key) {
        status = Component.translatable(key);
        statusColor = 0xFFFF7777;
    }

    private void clearTransientError() {
        if (statusColor == 0xFFFF7777) {
            status = Component.empty();
        }
    }

    private String shortId() {
        return group.groupId().toString().substring(0, 8);
    }

    private static String normalizeWebUrl(String value) {
        String trimmed = value.trim();
        return !trimmed.isEmpty() && !trimmed.contains("://") ? "https://" + trimmed : trimmed;
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String elide(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private enum BrowserAction {
        BACK,
        FORWARD,
        RELOAD
    }

}
