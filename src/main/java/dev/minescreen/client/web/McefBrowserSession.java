package dev.minescreen.client.web;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenTextureManager;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.audio.SpeakerLinkResolver;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.content.WebSplitLayout;
import net.minecraft.client.Minecraft;

/** One multi-tab off-screen Chromium session per joined screen group. */
public final class McefBrowserSession implements BrowserSession {
    /** Hidden Chromium tabs keep their page/session but not a full joined-canvas framebuffer. */
    private static final int BACKGROUND_TAB_SIZE = 64;
    /** MCEF 2.1.6 dereferences this listener unconditionally; null causes an exception per cursor event. */
    private static final com.cinemamod.mcef.listeners.MCEFCursorChangeListener IGNORE_CURSOR =
            cursorType -> {
            };
    private final List<Tab> tabs = new ArrayList<>();
    private final ArrayDeque<String> pendingInitialTabs = new ArrayDeque<>();
    private final ClientScreenProfile profile;
    private final WebPeerSession peer;
    private final ScreenGroup stateGroup;
    private ScreenGroup group;
    private int activeTab = -1;
    private int splitWindowStart;
    private WebSplitLayout splitLayout;
    private int width;
    private int height;
    private boolean closed;
    private boolean focused;
    private String lastPopupUrl = "";
    private long lastPopupNanos;
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private String observedNavigationUrl = "";
    private String reportedNavigationUrl = "";
    private long navigationStableSinceNanos;
    private float volume;
    private float distanceGain = -1.0F;
    private boolean distributedFrame;
    private MCEFBrowser pointerLockBrowser;
    private double pointerLockX;
    private double pointerLockY;
    private long nextVolumeApplyNanos;

    public McefBrowserSession(ScreenGroup group, ClientScreenProfile profile) {
        this(group, profile, false, null);
    }

    /**
     * Finalizes an asynchronously prevalidated WEB profile. Chromium creation remains on the
     * client/render thread, while restored background tabs are deliberately spread across ticks.
     */
    public McefBrowserSession(ScreenGroup group, ClientScreenProfile profile,
            boolean policyValidated, ScreenGroup stateGroup) {
        this.group = group;
        this.profile = profile.copy();
        splitLayout = this.profile.webSplitLayout == null
                ? WebSplitLayout.SINGLE : this.profile.webSplitLayout;
        volume = Math.max(0.0F, Math.min(1.0F, this.profile.volume));
        if (!policyValidated && !BrowserRequestPolicy.isAllowed(profile.source)) {
            throw new IllegalStateException("URL blocked by MineScreen policy: " + profile.source);
        }
        if (!MCEF.isInitialized() && !MCEF.initialize()) {
            throw new IllegalStateException("MCEF failed to initialize");
        }
        BrowserRequestPolicy.install(MCEF.getClient());
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        width = dimensions[0];
        height = dimensions[1];
        reportedNavigationUrl = profile.source;
        this.stateGroup = stateGroup == null ? group : stateGroup;
        createTab(profile.source, true);
        if (profile.webTabs != null) {
            profile.webTabs.stream().filter(url -> !url.equals(profile.source))
                    .filter(url -> policyValidated || BrowserRequestPolicy.isAllowed(url))
                    .limit(Math.max(0, maxTabs() - 1L))
                    .forEach(pendingInitialTabs::addLast);
        }
        // Derived content-region ids are client-local in the current payload version. Do not
        // announce them as server-authoritative screen ids until the region sync protocol lands.
        peer = dev.minescreen.client.ScreenGroupManager.group(group.groupId()) == null
                ? null : WebPeerService.open(group, this);
    }

    @Override
    public ScreenContentType type() {
        return ScreenContentType.WEB;
    }

    @Override
    public ScreenRenderSource renderSource() {
        if (peer != null) {
            ScreenRenderSource distributed = peer.renderSource();
            if (distributed != null) {
                return distributed;
            }
        }
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        List<ScreenRenderSource.Pane> panes = new ArrayList<>();
        for (int paneIndex = 0; paneIndex < indices.size(); paneIndex++) {
            Tab tab = tabs.get(indices.get(paneIndex));
            int textureId = tab.browser.getRenderer().getTextureID();
            float[] area = bounds.get(paneIndex);
            if (tab.status.loading() || tab.status.failed() || textureId <= 0) {
                ScreenRenderSource status = tab.status.renderSource();
                panes.add(new ScreenRenderSource.Pane(area[0], area[1], area[2], area[3],
                        status.renderType(), status::bind, false));
                continue;
            }
            if (textureId <= 0) {
                continue;
            }
            if (tab.lastTextureId > 0 && tab.lastTextureId != textureId) {
                ScreenRenderType.release(tab.lastTextureId);
            }
            tab.lastTextureId = textureId;
            panes.add(new ScreenRenderSource.Pane(area[0], area[1], area[2], area[3],
                    ScreenRenderType.screen(textureId),
                    () -> RenderSystem.setShaderTexture(0, textureId), false));
        }
        return panes.isEmpty() ? ScreenTextureManager.idleRenderSource()
                : new ScreenRenderSource(panes);
    }

    @Override
    public void tick(ScreenGroup group) {
        this.group = group;
        boolean visible = ScreenVisibility.evaluate(group).active();
        if (visible && !pendingInitialTabs.isEmpty() && tabs.size() < maxTabs()) {
            createTab(pendingInitialTabs.removeFirst(), false);
        }
        Tab active = active();
        int activeTexture = active.browser.getRenderer().getTextureID();
        int captureWidth = width;
        int captureHeight = height;
        if (peer != null && visible
                && (active.status.loading() || active.status.failed() || activeTexture <= 0)) {
            activeTexture = active.status.textureIdForCapture();
            captureWidth = active.status.width();
            captureHeight = active.status.height();
        }
        if (peer != null) {
            peer.tick(group, activeTexture, captureWidth, captureHeight, localTabs(), visible);
        }
        boolean distributed = peer != null && peer.usesRemoteFrame();
        boolean distributionChanged = distributedFrame != distributed;
        distributedFrame = distributed;
        List<Integer> visibleTabs = visibleTabIndices();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            MCEFBrowser browser = tab.browser;
            if (tab.status.loading() && browser.getRenderer().getTextureID() > 0
                    && !browser.isLoading()
                    && System.nanoTime() - tab.loadingSinceNanos
                            >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500L)) {
                // MCEF may complete an extremely fast cached navigation before MineScreen has
                // registered its handler. isLoading() is the authoritative fallback in that race.
                tab.status.ready(browser.getURL());
            }
            // MCEF browser controls use Minecraft's OS cursor as an independent pointer. World
            // screens deliberately have exactly one pointer -- the crosshair ray -- so keep those
            // controls and cursor callbacks disabled even if another integration changed them.
            browser.useBrowserControls(false);
            browser.setCursorChangeListener(IGNORE_CURSOR);
            browser.setWindowVisibility(!distributed && visible && visibleTabs.contains(i));
        }
        if (!distributed) {
            synchronizeActiveNavigation(stateGroup);
        }
        long now = System.nanoTime();
        float nextDistanceGain = SpeakerLinkResolver.gain(stateGroup);
        boolean attenuationChanged = Math.abs(nextDistanceGain - distanceGain) >= 0.01F;
        distanceGain = nextDistanceGain;
        if (distributionChanged || attenuationChanged || now >= nextVolumeApplyNanos) {
            applyWebVolume();
            nextVolumeApplyNanos = now + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250L);
        }
    }

    @Override
    public void resize(ScreenGroup group) {
        this.group = group;
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        int nextWidth = dimensions[0];
        int nextHeight = dimensions[1];
        if (nextWidth != width || nextHeight != height) {
            width = nextWidth;
            height = nextHeight;
            applyLayoutSizes();
        }
    }

    @Override
    public String currentUrl() {
        List<TabInfo> distributed = peer == null ? List.of() : peer.remoteTabs();
        if (!distributed.isEmpty()) {
            return distributed.stream().filter(TabInfo::active).findFirst()
                    .orElse(distributed.get(0)).url();
        }
        return active().browser.getURL();
    }

    @Override
    public boolean navigate(String url) {
        if (!BrowserRequestPolicy.isAllowed(url)) {
            return false;
        }
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_NAVIGATE);
            output.writeUTF(url);
        })) {
            return true;
        }
        return navigateLocal(url);
    }

    private boolean navigateLocal(String url) {
        markLoading(active(), url);
        active().browser.loadURL(url);
        return true;
    }

    @Override
    public void goBack() {
        if (peer != null && peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_BACK))) {
            return;
        }
        goBackLocal();
    }

    private void goBackLocal() {
        if (active().browser.canGoBack()) {
            markLoading(active(), active().browser.getURL());
            active().browser.goBack();
        }
    }

    @Override
    public void goForward() {
        if (peer != null && peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_FORWARD))) {
            return;
        }
        goForwardLocal();
    }

    private void goForwardLocal() {
        if (active().browser.canGoForward()) {
            markLoading(active(), active().browser.getURL());
            active().browser.goForward();
        }
    }

    @Override
    public void reload() {
        if (peer == null || !peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_RELOAD))) {
            markLoading(active(), active().browser.getURL());
            active().browser.reload();
        }
    }

    @Override
    public boolean canGoBack() {
        return peer != null && peer.usesRemoteFrame() || active().browser.canGoBack();
    }

    @Override
    public boolean canGoForward() {
        return peer != null && peer.usesRemoteFrame() || active().browser.canGoForward();
    }

    @Override
    public List<TabInfo> tabs() {
        List<TabInfo> distributed = peer == null ? List.of() : peer.remoteTabs();
        return distributed.isEmpty() ? localTabs() : distributed;
    }

    @Override
    public List<String> restorableUrls() {
        List<TabInfo> distributed = peer == null ? List.of() : peer.remoteTabs();
        if (!distributed.isEmpty()) {
            return distributed.stream().map(TabInfo::url).toList();
        }
        List<String> result = new ArrayList<>(tabs.size() + pendingInitialTabs.size());
        tabs.stream().map(tab -> tab.browser.getURL()).filter(java.util.Objects::nonNull)
                .forEach(result::add);
        result.addAll(pendingInitialTabs);
        return List.copyOf(result);
    }

    @Override
    public String restorableActiveUrl() {
        return currentUrl();
    }

    List<TabInfo> localTabs() {
        List<TabInfo> result = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            String url = tabs.get(i).browser.getURL();
            result.add(new TabInfo(i, title(url), url == null ? "" : url, i == activeTab));
        }
        return List.copyOf(result);
    }

    @Override
    public int activeTabIndex() {
        int distributed = peer == null ? -1 : peer.remoteActiveTab();
        return distributed >= 0 ? distributed : activeTab;
    }

    @Override
    public boolean openTab(String url, boolean activate) {
        if (!BrowserRequestPolicy.isAllowed(url) || closed || tabs.size() >= maxTabs()) {
            return false;
        }
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_OPEN_TAB);
            output.writeUTF(url);
            output.writeBoolean(activate);
        })) {
            return true;
        }
        return openTabLocal(url, activate);
    }

    private boolean openTabLocal(String url, boolean activate) {
        createTab(url, activate);
        return true;
    }

    @Override
    public void activateTab(int index) {
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_ACTIVATE_TAB);
            output.writeInt(index);
        })) {
            return;
        }
        activateTabLocal(index);
    }

    private void activateTabLocal(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        if (activeTab >= 0 && activeTab < tabs.size() && activeTab != index) {
            cancelPointerLock();
            tabs.get(activeTab).browser.setFocus(false);
        }
        activeTab = index;
        ensureActiveTabVisible();
        applyLayoutSizes();
        applyActiveTabState();
    }

    @Override
    public void closeTab(int index) {
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_CLOSE_TAB);
            output.writeInt(index);
        })) {
            return;
        }
        closeTabLocal(index);
    }

    private void closeTabLocal(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) {
            return;
        }
        Tab removed = tabs.remove(index);
        destroyTab(removed);
        if (activeTab >= tabs.size()) {
            activeTab = tabs.size() - 1;
        } else if (index < activeTab) {
            activeTab--;
        }
        ensureActiveTabVisible();
        applyLayoutSizes();
        applyActiveTabState();
    }

    @Override
    public WebSplitLayout splitLayout() {
        return splitLayout;
    }

    @Override
    public void setSplitLayout(WebSplitLayout layout) {
        splitLayout = layout == null ? WebSplitLayout.SINGLE : layout;
        profile.webSplitLayout = splitLayout;
        ensureActiveTabVisible();
        applyLayoutSizes();
        applyActiveTabState();
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0.0F, Math.min(1.0F, volume));
        applyWebVolume();
        nextVolumeApplyNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
    }

    @Override
    public int inputWidth() {
        return width;
    }

    @Override
    public int inputHeight() {
        return height;
    }

    @Override
    public void focus(boolean focused) {
        this.focused = focused;
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_FOCUS);
            output.writeBoolean(focused);
        })) {
            active().browser.setFocus(focused);
        }
    }

    @Override
    public void mouseMove(int x, int y) {
        lastMouseX = x;
        lastMouseY = y;
        if (focused && pointerLockRequested()) {
            return;
        }
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_MOVE);
            output.writeInt(x);
            output.writeInt(y);
        })) {
            sendMouseMoveLocal(x, y);
        }
    }

    @Override
    public void mousePress(int x, int y, int button) {
        if (focused && pointerLockRequested()) {
            pointerLockBrowser.sendMousePress((int) Math.round(pointerLockX),
                    (int) Math.round(pointerLockY), button);
            return;
        }
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_PRESS);
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(button);
        })) {
            sendMousePressLocal(x, y, button);
        }
    }

    @Override
    public void mouseRelease(int x, int y, int button) {
        if (focused && pointerLockRequested()) {
            pointerLockBrowser.sendMouseRelease((int) Math.round(pointerLockX),
                    (int) Math.round(pointerLockY), button);
            return;
        }
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_RELEASE);
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(button);
        })) {
            sendMouseReleaseLocal(x, y, button);
        }
    }

    @Override
    public void mouseWheel(int x, int y, double amount, int modifiers) {
        if (focused && pointerLockRequested()) {
            pointerLockBrowser.sendMouseWheel((int) Math.round(pointerLockX),
                    (int) Math.round(pointerLockY), amount, modifiers);
            return;
        }
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_WHEEL);
            output.writeInt(x);
            output.writeInt(y);
            output.writeDouble(amount);
            output.writeInt(modifiers);
        })) {
            sendMouseWheelLocal(x, y, amount, modifiers);
        }
    }

    @Override
    public void keyPress(int keyCode, long scanCode, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_PRESS);
            output.writeInt(keyCode);
            output.writeLong(scanCode);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyPress(keyCode, scanCode, modifiers);
        }
    }

    @Override
    public void keyRelease(int keyCode, long scanCode, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_RELEASE);
            output.writeInt(keyCode);
            output.writeLong(scanCode);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyRelease(keyCode, scanCode, modifiers);
        }
    }

    @Override
    public void keyTyped(char character, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_TYPED);
            output.writeChar(character);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyTyped(character, modifiers);
        }
    }

    @Override
    public boolean pointerLockRequested() {
        return pointerLockBrowser != null && !closed && !tabs.isEmpty()
                && active().browser == pointerLockBrowser;
    }

    @Override
    public void relativeMouseMove(double deltaX, double deltaY) {
        if (!pointerLockRequested() || deltaX == 0.0D && deltaY == 0.0D) {
            return;
        }
        Tab active = active();
        pointerLockX = wrapPointer(pointerLockX + deltaX, active.viewportWidth);
        pointerLockY = wrapPointer(pointerLockY + deltaY, active.viewportHeight);
        pointerLockBrowser.sendMouseMove((int) Math.round(pointerLockX),
                (int) Math.round(pointerLockY));
    }

    @Override
    public void cancelPointerLock() {
        MCEFBrowser locked = pointerLockBrowser;
        pointerLockBrowser = null;
        if (locked != null) {
            BrowserRequestPolicy.exitPointerLock(locked);
        }
    }

    private static double wrapPointer(double value, int extent) {
        // Virtual OSR pointer lock still receives absolute CEF coordinates. Wrap inside the
        // viewport and let the injected movementX/Y normalizer remove the wrap discontinuity, so
        // sustained cloud-game camera turns never hit an invisible browser edge.
        if (extent <= 4) {
            return Math.max(0.0D, extent * 0.5D);
        }
        double minimum = 2.0D;
        double span = extent - 4.0D;
        double wrapped = (value - minimum) % span;
        if (wrapped < 0.0D) {
            wrapped += span;
        }
        return minimum + wrapped;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelPointerLock();
        if (peer != null) {
            WebPeerService.close(peer, group);
        }
        tabs.forEach(this::destroyTab);
        tabs.clear();
        pendingInitialTabs.clear();
    }

    private void createTab(String url, boolean activate) {
        MCEFBrowser browser = MCEF.createBrowser(url, false, width, height);
        // MCEF.createBrowser already creates the native browser. Calling createImmediately a
        // second time can leave navigation/renderer state associated with the wrong tab.
        browser.useBrowserControls(false);
        browser.setCursorChangeListener(IGNORE_CURSOR);
        browser.setWindowVisibility(false);
        Tab tab = new Tab(browser, new BrowserStatusTexture(stateGroup.groupId(), width, height));
        markLoading(tab, url);
        tabs.add(tab);
        BrowserRequestPolicy.registerNewTabHandler(browser, this::queuePopupTab);
        BrowserRequestPolicy.registerPointerLockHandler(browser,
                locked -> onPointerLockChanged(browser, locked));
        BrowserRequestPolicy.registerLoadStateHandler(browser,
                state -> onLoadStateChanged(browser, state));
        BrowserRequestPolicy.installTabHooks(browser);
        if (activate) {
            activateTab(tabs.size() - 1);
        }
        applyLayoutSizes();
    }

    /** Applies an authenticated upstream action on the elected root browser, on the client thread. */
    void applyPeerMessage(byte[] message) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int type = input.readUnsignedByte();
            int action = input.readUnsignedByte();
            if (type == WebPeerSession.MESSAGE_COMMAND) {
                switch (action) {
                    case WebPeerSession.COMMAND_NAVIGATE -> {
                        String url = input.readUTF();
                        if (BrowserRequestPolicy.isAllowed(url)) {
                            navigateLocal(url);
                        }
                    }
                    case WebPeerSession.COMMAND_BACK -> goBackLocal();
                    case WebPeerSession.COMMAND_FORWARD -> goForwardLocal();
                    case WebPeerSession.COMMAND_RELOAD -> {
                        markLoading(active(), active().browser.getURL());
                        active().browser.reload();
                    }
                    case WebPeerSession.COMMAND_OPEN_TAB -> {
                        String url = input.readUTF();
                        boolean activate = input.readBoolean();
                        if (BrowserRequestPolicy.isAllowed(url) && tabs.size() < maxTabs()) {
                            openTabLocal(url, activate);
                        }
                    }
                    case WebPeerSession.COMMAND_ACTIVATE_TAB -> activateTabLocal(input.readInt());
                    case WebPeerSession.COMMAND_CLOSE_TAB -> closeTabLocal(input.readInt());
                    default -> {
                    }
                }
            } else if (type == WebPeerSession.MESSAGE_INPUT) {
                switch (action) {
                    case WebPeerSession.INPUT_FOCUS -> active().browser.setFocus(input.readBoolean());
                    case WebPeerSession.INPUT_MOUSE_MOVE ->
                            sendMouseMoveLocal(input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_PRESS ->
                            sendMousePressLocal(input.readInt(), input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_RELEASE ->
                            sendMouseReleaseLocal(input.readInt(), input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_WHEEL -> sendMouseWheelLocal(
                            input.readInt(), input.readInt(), input.readDouble(), input.readInt());
                    case WebPeerSession.INPUT_KEY_PRESS -> active().browser.sendKeyPress(
                            input.readInt(), input.readLong(), input.readInt());
                    case WebPeerSession.INPUT_KEY_RELEASE -> active().browser.sendKeyRelease(
                            input.readInt(), input.readLong(), input.readInt());
                    case WebPeerSession.INPUT_KEY_TYPED ->
                            active().browser.sendKeyTyped(input.readChar(), input.readInt());
                    default -> {
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void destroyTab(Tab tab) {
        if (pointerLockBrowser == tab.browser) {
            cancelPointerLock();
        }
        if (!tab.status.loading() && !tab.status.failed()
                && tab.viewportWidth > BACKGROUND_TAB_SIZE
                && tab.viewportHeight > BACKGROUND_TAB_SIZE) {
            WebThumbnailCache.capture(stateGroup.groupId(), tab.browser.getURL(),
                    tab.browser.getRenderer().getTextureID(), tab.viewportWidth,
                    tab.viewportHeight);
        }
        BrowserRequestPolicy.unregisterNewTabHandler(tab.browser);
        BrowserRequestPolicy.unregisterPointerLockHandler(tab.browser);
        BrowserRequestPolicy.unregisterLoadStateHandler(tab.browser);
        tab.browser.setFocus(false);
        tab.browser.close(true);
        ScreenRenderType.release(tab.lastTextureId);
        tab.status.close();
    }

    private void onLoadStateChanged(MCEFBrowser browser,
            BrowserRequestPolicy.BrowserLoadState state) {
        Minecraft.getInstance().execute(() -> {
            if (closed) {
                return;
            }
            Tab tab = tabs.stream().filter(candidate -> candidate.browser == browser)
                    .findFirst().orElse(null);
            if (tab == null) {
                return;
            }
            if (state.loading()) {
                markLoading(tab, state.url());
            } else if (state.failed()) {
                tab.status.error(state.url(), state.statusCode(), state.message());
            } else {
                tab.status.ready(state.url());
            }
        });
    }

    private static void markLoading(Tab tab, String url) {
        tab.loadingSinceNanos = System.nanoTime();
        tab.status.loading(url);
    }

    private void onPointerLockChanged(MCEFBrowser browser, boolean locked) {
        Minecraft.getInstance().execute(() -> {
            if (closed) {
                return;
            }
            if (!locked) {
                if (pointerLockBrowser == browser) {
                    pointerLockBrowser = null;
                }
                return;
            }
            if (tabs.isEmpty() || active().browser != browser) {
                return;
            }
            pointerLockBrowser = browser;
            Tab active = active();
            pointerLockX = Math.max(1, active.viewportWidth) / 2.0D;
            pointerLockY = Math.max(1, active.viewportHeight) / 2.0D;
            browser.sendMouseMove((int) Math.round(pointerLockX),
                    (int) Math.round(pointerLockY));
            dev.minescreen.client.ClientInput.centerViewForPointerLock();
        });
    }

    private void queuePopupTab(String target) {
        Minecraft.getInstance().execute(() -> {
            if (closed || target == null || target.isBlank()
                    || !BrowserRequestPolicy.isAllowedRequest(target)) {
                return;
            }
            long now = System.nanoTime();
            if (target.equals(lastPopupUrl)
                    && now - lastPopupNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500L)) {
                return;
            }
            lastPopupUrl = target;
            lastPopupNanos = now;
            if (tabs.size() < maxTabs()) {
                createTab(target, true);
            }
        });
    }

    private static int maxTabs() {
        return MineScreenConfig.MAX_WEB_TABS_PER_SESSION.get();
    }

    private void synchronizeActiveNavigation(ScreenGroup group) {
        String url = currentUrl();
        if (url == null || url.isBlank() || url.equalsIgnoreCase("about:blank")
                || !BrowserRequestPolicy.isAllowedRequest(url)) {
            return;
        }
        long now = System.nanoTime();
        if (!url.equals(observedNavigationUrl)) {
            observedNavigationUrl = url;
            navigationStableSinceNanos = now;
            return;
        }
        // Collapse redirect chains and rapid SPA URL churn into one compact metadata packet.
        if (!url.equals(reportedNavigationUrl)
                && now - navigationStableSinceNanos
                        >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750L)) {
            reportedNavigationUrl = url;
            dev.minescreen.client.network.ClientNetworkState.sendWebNavigation(group, url);
        }
    }

    /** Applies visibility/focus immediately instead of waiting for the next content tick. */
    private void applyActiveTabState() {
        List<Integer> visible = visibleTabIndices();
        for (int i = 0; i < tabs.size(); i++) {
            MCEFBrowser browser = tabs.get(i).browser;
            boolean active = i == activeTab;
            browser.useBrowserControls(false);
            browser.setCursorChangeListener(IGNORE_CURSOR);
            browser.setWindowVisibility(visible.contains(i));
            browser.setFocus(active && focused);
        }
        if (lastMouseX >= 0 && lastMouseY >= 0) {
            sendMouseMoveLocal(lastMouseX, lastMouseY);
        }
    }

    private void sendMouseMoveLocal(int x, int y) {
        PaneTarget target = paneTarget(x, y);
        if (target != null) {
            target.tab().browser.sendMouseMove(target.x(), target.y());
        }
    }

    private void sendMousePressLocal(int x, int y, int button) {
        PaneTarget target = paneTarget(x, y);
        if (target != null) {
            activateTabLocal(target.tabIndex());
            target.tab().browser.sendMousePress(target.x(), target.y(), button);
        }
    }

    private void sendMouseReleaseLocal(int x, int y, int button) {
        PaneTarget target = paneTarget(x, y);
        if (target != null) {
            target.tab().browser.sendMouseRelease(target.x(), target.y(), button);
        }
    }

    private void sendMouseWheelLocal(int x, int y, double amount, int modifiers) {
        PaneTarget target = paneTarget(x, y);
        if (target != null) {
            target.tab().browser.sendMouseWheel(target.x(), target.y(), amount, modifiers);
        }
    }

    private PaneTarget paneTarget(int x, int y) {
        float normalizedX = Math.max(0.0F, Math.min(0.999999F,
                x / (float) Math.max(1, width)));
        float normalizedY = Math.max(0.0F, Math.min(0.999999F,
                y / (float) Math.max(1, height)));
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        for (int paneIndex = 0; paneIndex < indices.size(); paneIndex++) {
            float[] area = bounds.get(paneIndex);
            if (normalizedX >= area[0] && normalizedX < area[2]
                    && normalizedY >= area[1] && normalizedY < area[3]) {
                int tabIndex = indices.get(paneIndex);
                Tab tab = tabs.get(tabIndex);
                int paneWidth = Math.max(1, Math.round(width * (area[2] - area[0])));
                int paneHeight = Math.max(1, Math.round(height * (area[3] - area[1])));
                int localX = Math.max(0, Math.min(paneWidth - 1,
                        (int) ((normalizedX - area[0]) / (area[2] - area[0]) * paneWidth)));
                int localY = Math.max(0, Math.min(paneHeight - 1,
                        (int) ((normalizedY - area[1]) / (area[3] - area[1]) * paneHeight)));
                return new PaneTarget(tabIndex, tab, localX, localY);
            }
        }
        return null;
    }

    private void applyLayoutSizes() {
        if (tabs.isEmpty()) {
            return;
        }
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        int[] targetWidths = new int[tabs.size()];
        int[] targetHeights = new int[tabs.size()];
        java.util.Arrays.fill(targetWidths, BACKGROUND_TAB_SIZE);
        java.util.Arrays.fill(targetHeights, BACKGROUND_TAB_SIZE);
        for (int paneIndex = 0; paneIndex < indices.size(); paneIndex++) {
            float[] area = bounds.get(paneIndex);
            int tabIndex = indices.get(paneIndex);
            targetWidths[tabIndex] = Math.max(1,
                    Math.round(width * (area[2] - area[0])));
            targetHeights[tabIndex] = Math.max(1,
                    Math.round(height * (area[3] - area[1])));
        }
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            int targetWidth = targetWidths[index];
            int targetHeight = targetHeights[index];
            if (tab.viewportWidth != targetWidth || tab.viewportHeight != targetHeight) {
                tab.browser.resize(targetWidth, targetHeight);
                tab.status.resize(targetWidth, targetHeight);
                tab.viewportWidth = targetWidth;
                tab.viewportHeight = targetHeight;
            }
        }
    }

    private void applyWebVolume() {
        float effectiveVolume = distributedFrame ? 0.0F
                : volume * Math.max(0.0F, distanceGain);
        String value = String.format(java.util.Locale.ROOT, "%.4f", effectiveVolume);
        String script = "(() => { const v=" + value
                + "; window.__mineScreenVolume=v; const a=e=>{if(e instanceof HTMLMediaElement)e.volume=v;};"
                + "document.querySelectorAll('audio,video').forEach(a);"
                + "if(!window.__mineScreenVolumeObserver){window.__mineScreenVolumeObserver=new MutationObserver(ms=>ms.forEach(m=>m.addedNodes.forEach(n=>{if(n instanceof HTMLMediaElement)a(n);if(n.querySelectorAll)n.querySelectorAll('audio,video').forEach(a);})));window.__mineScreenVolumeObserver.observe(document.documentElement||document,{childList:true,subtree:true});}})();";
        for (Tab tab : tabs) {
            String url = tab.browser.getURL();
            tab.browser.executeJavaScript(script, url == null ? "about:blank" : url, 0);
        }
    }

    private List<Integer> visibleTabIndices() {
        if (tabs.isEmpty()) {
            return List.of();
        }
        if (splitLayout == WebSplitLayout.SINGLE) {
            return List.of(Math.max(0, Math.min(activeTab, tabs.size() - 1)));
        }
        int count = Math.min(splitLayout.paneCount(), tabs.size());
        ensureActiveTabVisible();
        List<Integer> indices = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            indices.add(splitWindowStart + index);
        }
        return indices;
    }

    private void ensureActiveTabVisible() {
        if (tabs.isEmpty() || splitLayout == WebSplitLayout.SINGLE) {
            return;
        }
        int count = Math.min(splitLayout.paneCount(), tabs.size());
        if (activeTab < splitWindowStart) {
            splitWindowStart = activeTab;
        } else if (activeTab >= splitWindowStart + count) {
            splitWindowStart = activeTab - count + 1;
        }
        splitWindowStart = Math.max(0, Math.min(splitWindowStart, tabs.size() - count));
    }

    private static List<float[]> paneBounds(WebSplitLayout layout, int count) {
        List<float[]> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            float[] area = switch (layout) {
                case SINGLE -> new float[] {0.0F, 0.0F, 1.0F, 1.0F};
                case VERTICAL -> index == 0
                        ? new float[] {0.0F, 0.0F, 0.5F, 1.0F}
                        : new float[] {0.5F, 0.0F, 1.0F, 1.0F};
                case HORIZONTAL -> index == 0
                        ? new float[] {0.0F, 0.0F, 1.0F, 0.5F}
                        : new float[] {0.0F, 0.5F, 1.0F, 1.0F};
                case QUAD -> switch (index) {
                    case 0 -> new float[] {0.0F, 0.0F, 0.5F, 0.5F};
                    case 1 -> new float[] {0.5F, 0.0F, 1.0F, 0.5F};
                    case 2 -> new float[] {0.0F, 0.5F, 0.5F, 1.0F};
                    default -> new float[] {0.5F, 0.5F, 1.0F, 1.0F};
                };
            };
            result.add(area);
        }
        return result;
    }

    private Tab active() {
        if (tabs.isEmpty()) {
            throw new IllegalStateException("Browser has no tabs");
        }
        return tabs.get(Math.max(0, Math.min(activeTab, tabs.size() - 1)));
    }

    private static String title(String url) {
        if (url == null || url.isBlank()) {
            return "New Tab";
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    private static final class Tab {
        private final MCEFBrowser browser;
        private final BrowserStatusTexture status;
        private int lastTextureId;
        private int viewportWidth;
        private int viewportHeight;
        private long loadingSinceNanos;

        private Tab(MCEFBrowser browser, BrowserStatusTexture status) {
            this.browser = browser;
            this.status = status;
        }
    }

    private record PaneTarget(int tabIndex, Tab tab, int x, int y) {
    }
}
